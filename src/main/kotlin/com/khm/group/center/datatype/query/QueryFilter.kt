package com.khm.group.center.datatype.query

import com.khm.group.center.datatype.query.enums.LogicOperator
import com.khm.group.center.datatype.query.enums.QueryField
import com.khm.group.center.datatype.query.enums.QueryOperator

/**
 * 查询过滤器
 *
 * @param logic 本过滤器与**前一个**过滤器的连接方式；列表中首个过滤器的 logic 会被忽略。
 *   例如 `[A, B, C]` 中若 B.logic 为 OR，则表示 `A OR B`；若 C.logic 为 AND，则整体是
 *   `(A OR B) AND C`。所有过滤器整体被收进一对括号，与时间范围条件并列而非混入其中。
 */
data class QueryFilter(
    val field: QueryField,
    val operator: QueryOperator,
    val value: Any,
    val logic: LogicOperator = LogicOperator.AND
) {
    /**
     * 验证过滤器的有效性
     */
    fun validate(): Boolean {
        // 检查操作符是否支持该字段
        if (!operator.supportsField(field)) {
            return false
        }

        // BETWEEN 的值必须是长度为 2 的非空列表。此前所有分支都只接受标量类型，
        // 导致 BETWEEN 永远在此处被判不合法、请求被静默降级为空结果。
        if (operator == QueryOperator.BETWEEN) {
            val range = value as? List<*> ?: return false
            if (range.size != 2) return false
            val low = range[0] ?: return false
            val high = range[1] ?: return false
            return isValueTypeValid(low) && isValueTypeValid(high)
        }

        // 检查值的类型是否匹配字段类型
        return isValueTypeValid(value)
    }

    /**
     * 判断单个值是否与字段类型匹配
     */
    private fun isValueTypeValid(v: Any): Boolean {
        return when (field) {
            QueryField.ID,
            QueryField.TASK_GPU_ID,
            QueryField.MULTI_DEVICE_WORLD_SIZE,
            QueryField.MULTI_DEVICE_LOCAL_RANK,
            QueryField.TASK_RUNNING_TIME_IN_SECONDS -> v is Int || v is Long
            
            QueryField.GPU_USAGE_PERCENT,
            QueryField.GPU_MEMORY_PERCENT,
            QueryField.TASK_GPU_MEMORY_GB -> v is Float || v is Double || v is Int
            
            QueryField.IS_DEBUG_MODE,
            QueryField.IS_MULTI_GPU -> v is Boolean
            
            QueryField.TASK_START_TIME,
            QueryField.TASK_FINISH_TIME -> v is Long || v is String
            
            else -> v is String
        }
    }

    /**
     * 获取过滤器的描述
     */
    fun getDescription(): String {
        return "${field.name} ${operator.getDescription()} $value"
    }
}