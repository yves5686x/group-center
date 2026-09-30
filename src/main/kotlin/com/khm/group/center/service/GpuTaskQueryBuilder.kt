package com.khm.group.center.service

import com.baomidou.mybatisplus.core.conditions.query.QueryWrapper
import com.khm.group.center.datatype.query.QueryFilter
import com.khm.group.center.datatype.query.TimeRange
import com.khm.group.center.datatype.query.enums.LogicOperator
import com.khm.group.center.datatype.query.enums.QueryOperator
import com.khm.group.center.db.model.client.GpuTaskInfoModel
import com.khm.group.center.utils.program.Slf4jKt
import com.khm.group.center.utils.program.Slf4jKt.Companion.logger

/**
 * GPU任务查询构建器
 */
@Slf4jKt
class GpuTaskQueryBuilder {

    /**
     * 构建查询条件
     *
     * 时间范围与过滤器各自收进独立的括号组，组内再按 [LogicOperator] 组合。
     * 若不加这层括号，SQL 运算符优先级会把它解析成
     * `(timeRange AND f1) OR f2` —— 时间范围这个最重要的隔离边界会被 OR 顶掉，
     * 查询范围放大到全历史数据。
     */
    fun buildQueryWrapper(
        filters: List<QueryFilter>,
        timeRange: TimeRange?
    ): QueryWrapper<GpuTaskInfoModel> {
        val queryWrapper = QueryWrapper<GpuTaskInfoModel>()

        // 处理时间范围
        timeRange?.let { range ->
            queryWrapper.and { group ->
                range.getStartTimestamp()?.let { group.ge("task_start_time", it) }
                range.getEndTimestamp()?.let { group.le("task_start_time", it) }
            }
        }

        // 处理过滤器：先归一化成条件列表，非法条件直接剔除，
        // 只有确实存在条件时才追加括号组，避免生成空的条件块
        if (filters.isNotEmpty()) {
            val conditions = filters.mapNotNull { it.toCondition() }
            if (conditions.isNotEmpty()) {
                val (expression, values) = compileConditions(conditions)
                queryWrapper.and { group -> group.apply(expression, *values.toTypedArray()) }
            }
        }

        logger.debug("Build query conditions: ${queryWrapper.targetSql}")
        return queryWrapper
    }

    /**
     * 归一化后的单个查询条件。
     *
     * @param logic 本条件与「前一个条件」的连接方式；首个条件的 logic 会被忽略
     */
    private class Condition(
        val logic: LogicOperator,
        val column: String,
        val operator: String,
        val value: Any
    )

    /**
     * 把过滤器翻译成条件；无法翻译的返回 null（由调用方剔除）。
     *
     * 此前非法条件会被静默丢弃却仍推进 AND/OR 游标，导致相邻条件以错误的
     * 逻辑相连；若所有条件都被丢弃，查询会退化成无 WHERE 的全表扫描。
     */
    private fun QueryFilter.toCondition(): Condition? {
        val column = field.getColumnName()
        return when (operator) {
            QueryOperator.EQUALS -> Condition(logic, column, "=", value)
            QueryOperator.NOT_EQUALS -> Condition(logic, column, "<>", value)
            QueryOperator.GREATER_THAN -> Condition(logic, column, ">", value)
            QueryOperator.LESS_THAN -> Condition(logic, column, "<", value)
            QueryOperator.GREATER_EQUAL -> Condition(logic, column, ">=", value)
            QueryOperator.LESS_EQUAL -> Condition(logic, column, "<=", value)
            QueryOperator.LIKE -> Condition(logic, column, "LIKE", value)
            QueryOperator.BETWEEN -> {
                val range = value as? List<*> ?: return null
                if (range.size != 2) return null
                val low = range[0] ?: return null
                val high = range[1] ?: return null
                Condition(logic, column, "BETWEEN", low to high)
            }
        }
    }

    /**
     * 把条件列表编译成一段显式加括号的 SQL 表达式。
     *
     * 语义：filter[i].logic 表示 filter[i] 与 filter[i-1] 的连接方式，
     * 首个 filter 的 logic 忽略。此前实现把 logic 当成「与下一个条件的连接方式」，
     * 导致整体错位一格：filters[0].logic 被丢弃，filters[n].logic 作用在 filters[n+1] 上。
     *
     * 必须自己控制括号，原因是 SQL 里 AND 优先级高于 OR，而逐条调用
     * QueryWrapper.and{}/or{} 只会把「新增的那一个条件」括起来，累积部分不受影响：
     * `[A, B(OR), C(AND)]` 会得到 `A OR (B) AND (C)`，解析成 `A OR (B AND C)`，
     * 而从左到右应当是 `(A OR B) AND C`。因此这里每步都把已累积的表达式整体重新括起来。
     *
     * 安全性：列名来自 [QueryField] 闭集枚举、运算符来自闭集字面量，
     * 值一律通过 `{n}` 占位符交给 MyBatis-Plus 绑定，不参与字符串拼接。
     *
     * @return 表达式文本与对应的参数值列表
     */
    private fun compileConditions(conditions: List<Condition>): Pair<String, List<Any>> {
        val values = mutableListOf<Any>()

        fun placeholder(value: Any): String {
            values.add(value)
            return "{${values.size - 1}}"
        }

        fun fragment(condition: Condition): String {
            val column = condition.column
            return when (condition.operator) {
                "LIKE" -> "$column LIKE ${placeholder(escapeLikeWildcards(condition.value.toString()))}"
                "BETWEEN" -> {
                    @Suppress("UNCHECKED_CAST")
                    val range = condition.value as Pair<Any, Any>
                    "$column BETWEEN ${placeholder(range.first)} AND ${placeholder(range.second)}"
                }
                else -> "$column ${condition.operator} ${placeholder(condition.value)}"
            }
        }

        var expression = fragment(conditions.first())
        for (index in 1 until conditions.size) {
            val condition = conditions[index]
            val connector = if (condition.logic == LogicOperator.OR) "OR" else "AND"
            expression = "($expression $connector ${fragment(condition)})"
        }
        return expression to values
    }

    /**
     * 转义 LIKE 通配符，避免用户输入的 % 和 _ 改变匹配范围
     * （输入一个 % 原本会匹配全表，输入 _ 会变成单字符通配）。
     * MySQL 与 H2 的 LIKE 默认转义字符均为反斜杠。
     */
    private fun escapeLikeWildcards(value: String): String =
        value.replace("\\", "\\\\").replace("%", "\\%").replace("_", "\\_")

    /**
     * QueryWrapper的扩展函数，用于应用条件
     */
    private fun QueryWrapper<GpuTaskInfoModel>.applyCondition(column: String, operator: String, value: Any) {
        when (operator) {
            "=" -> this.eq(column, value)
            "<>" -> this.ne(column, value)
            ">" -> this.gt(column, value)
            "<" -> this.lt(column, value)
            ">=" -> this.ge(column, value)
            "<=" -> this.le(column, value)
        }
    }
}