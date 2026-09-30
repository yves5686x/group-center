package com.khm.group.center.service

import com.khm.group.center.datatype.query.QueryFilter
import com.khm.group.center.datatype.query.TimeRange
import com.khm.group.center.datatype.query.enums.LogicOperator
import com.khm.group.center.datatype.query.enums.QueryField
import com.khm.group.center.datatype.query.enums.QueryOperator
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.time.Instant

/**
 * GpuTaskQueryBuilder 的 DSL → SQL 翻译单测。
 *
 * 这里不连数据库，直接断言生成的条件片段（[com.baomidou.mybatisplus.core.conditions.query.QueryWrapper]
 * 的 customSqlSegment），因为这几类缺陷恰恰是「SQL 文本对不对」的问题——
 * 只有把括号与 AND/OR 的相对位置断言出来，才能锁住它们。
 */
class GpuTaskQueryBuilderTest {

    private val builder = GpuTaskQueryBuilder()

    private fun QueryFilter.and() = copy(logic = LogicOperator.AND)
    private fun QueryFilter.or() = copy(logic = LogicOperator.OR)

    private fun sqlOf(filters: List<QueryFilter>, timeRange: TimeRange? = null): String {
        val wrapper = builder.buildQueryWrapper(filters, timeRange)
        return normalize(wrapper.customSqlSegment)
    }

    /**
     * 归一化：去掉 WHERE 前缀与参数编号，并把「单条件」外面多余的一层括号剥掉。
     *
     * MyBatis-Plus 会给每个 AND/OR 目标都套一层括号，包括只含单个条件的
     * `(project_name LIKE ?)`——这层括号不携带语义。剥掉之后，
     * 真正决定语义的「分组括号」在 SQL 里一目了然，可以直接整体比对。
     */
    private fun normalize(sql: String): String = sql
        .replace(Regex("\\bWHERE\\s+"), "")
        .replace(Regex("#\\{[^}]*}"), "?")
        .replace(Regex("\\(\\s*([a-z_]+ (?:LIKE|BETWEEN|=|>|<|>=|<=|<>) \\?)\\s*\\)"), "$1")
        .replace(Regex("\\s+"), " ")
        .trim()

    // ==================== S1：OR 不得击穿时间范围 ====================

    @Test
    fun `time range and filters are wrapped in separate groups`() {
        val range = TimeRange(Instant.ofEpochSecond(1000L), Instant.ofEpochSecond(2000L))
        val filters = listOf(
            QueryFilter(QueryField.TASK_USER, QueryOperator.EQUALS, "alice").and(),
            QueryFilter(QueryField.PROJECT_NAME, QueryOperator.LIKE, "proj").or()
        )

        val sql = sqlOf(filters, range)

        // 时间组先闭合，再以 AND 连接过滤器组，OR 只发生在过滤器组内部。
        // 若时间范围被 OR 顶掉，这里会变成
        //   (task_start_time >= ? AND task_start_time <= ? AND task_user = ?) OR (project_name LIKE ?)
        // 即 `task_start_time <= ?` 后面跟的是 `AND`/`OR` 而非 `) AND (`。
        assertTrue(sql.contains("(task_start_time >= ? AND task_start_time <= ?)"), "时间组应完整，实际: $sql")
        assertTrue(
            sql.contains("task_start_time <= ?) AND ((task_user = ? OR project_name LIKE ?"),
            "OR 必须被关在时间组之后的过滤器组内，实际: $sql"
        )
    }

    @Test
    fun `logic connects a filter to the previous one`() {
        val filters = listOf(
            QueryFilter(QueryField.TASK_USER, QueryOperator.EQUALS, "alice").and(),
            QueryFilter(QueryField.PROJECT_NAME, QueryOperator.LIKE, "p1").or(),
            QueryFilter(QueryField.SERVER_NAME_ENG, QueryOperator.EQUALS, "gpu-a").and()
        )

        // [A, B(OR), C(AND)] => (A OR B) AND C
        // 关键在 A、B 之间的那对括号：没有它就会退化成 A OR (B AND C)
        assertTrue(
            sqlOf(filters).contains("((task_user = ? OR project_name LIKE ?) AND server_name_eng = ?)"),
            "logic 应表示与前一个条件的连接，且 OR 段需先括起来，实际: ${sqlOf(filters)}"
        )
    }

    @Test
    fun `mixed logic is evaluated strictly left to right`() {
        val filters = listOf(
            QueryFilter(QueryField.TASK_USER, QueryOperator.EQUALS, "A").and(),
            QueryFilter(QueryField.SERVER_NAME_ENG, QueryOperator.EQUALS, "B").and(),
            QueryFilter(QueryField.TASK_TYPE, QueryOperator.EQUALS, "D").or()
        )

        // [A, B(AND), C(OR)] => ((A AND B) OR C)
        assertTrue(
            sqlOf(filters).contains("((task_user = ? AND server_name_eng = ?) OR task_type = ?)"),
            "混合 AND/OR 应严格从左到右结合，实际: ${sqlOf(filters)}"
        )
    }

    @Test
    fun `first filter logic is ignored`() {
        // 首个 filter 即使写了 OR，也不应产生顶层 OR
        val filters = listOf(
            QueryFilter(QueryField.TASK_USER, QueryOperator.EQUALS, "alice").or(),
            QueryFilter(QueryField.PROJECT_NAME, QueryOperator.LIKE, "p1").and()
        )
        assertTrue(sqlOf(filters).contains("(task_user = ? AND project_name LIKE ?)"), "实际: ${sqlOf(filters)}")
    }

    @Test
    fun `all OR filters combine inside one group`() {
        val filters = listOf(
            QueryFilter(QueryField.TASK_USER, QueryOperator.EQUALS, "a").or(),
            QueryFilter(QueryField.TASK_USER, QueryOperator.EQUALS, "b").or()
        )
        assertTrue(sqlOf(filters).contains("(task_user = ? OR task_user = ?)"), "实际: ${sqlOf(filters)}")
    }

    // ==================== S2：BETWEEN 必须可用 ====================

    @Test
    fun `between filter passes validation`() {
        val f = QueryFilter(QueryField.GPU_USAGE_PERCENT, QueryOperator.BETWEEN, listOf(10, 80))
        assertTrue(f.validate(), "BETWEEN 的 2 元素列表必须通过校验")
    }

    @Test
    fun `between filter is rejected when malformed`() {
        assertFalse(
            QueryFilter(QueryField.GPU_USAGE_PERCENT, QueryOperator.BETWEEN, listOf(10)).validate(),
            "单元素列表应被拒绝"
        )
        assertFalse(
            QueryFilter(QueryField.GPU_USAGE_PERCENT, QueryOperator.BETWEEN, "abc").validate(),
            "非列表值应被拒绝"
        )
        assertFalse(
            QueryFilter(QueryField.GPU_USAGE_PERCENT, QueryOperator.BETWEEN, listOf(10, null)).validate(),
            "含 null 的列表应被拒绝"
        )
    }

    @Test
    fun `between produces a between condition`() {
        val filters = listOf(
            QueryFilter(QueryField.GPU_USAGE_PERCENT, QueryOperator.BETWEEN, listOf(10, 80)).and()
        )
        val sql = sqlOf(filters)
        assertTrue(sql.contains("gpu_usage_percent BETWEEN ? AND ?"), "实际: $sql")
    }

    @Test
    fun `non-between filter still rejects list value`() {
        val f = QueryFilter(QueryField.TASK_USER, QueryOperator.EQUALS, listOf("a", "b"))
        assertFalse(f.validate(), "EQUALS 传列表应被拒绝")
    }

    // ==================== S3：非法条件不得静默放大查询 ====================

    @Test
    fun `malformed between is dropped without corrupting neighbouring logic`() {
        val filters = listOf(
            QueryFilter(QueryField.TASK_USER, QueryOperator.EQUALS, "bob").and(),
            // 非法：不是列表
            QueryFilter(QueryField.GPU_USAGE_PERCENT, QueryOperator.BETWEEN, "abc").or()
        )
        val sql = sqlOf(filters)
        // 非法条件消失后，只剩 bob，且不应残留 OR
        assertTrue(sql.contains("(task_user = ?)"), "实际: $sql")
        assertFalse(sql.contains("OR"), "被丢弃的条件不应推进逻辑游标，实际: $sql")
    }

    @Test
    fun `all conditions malformed yields no where clause at all`() {
        val filters = listOf(
            QueryFilter(QueryField.GPU_USAGE_PERCENT, QueryOperator.BETWEEN, "abc").or()
        )
        val sql = sqlOf(filters)
        // 全部被丢弃时不能生成空的括号组，也不能退化成全表查询
        assertFalse(sql.contains("BETWEEN"), "实际: $sql")
        assertTrue(sql.isEmpty() || !sql.contains("()"), "不应生成空条件组，实际: $sql")
    }

    // ==================== M1：LIKE 通配符 ====================

    @Test
    fun `like does not double wrap percent signs`() {
        val filters = listOf(
            QueryFilter(QueryField.PROJECT_NAME, QueryOperator.LIKE, "proj").and()
        )
        // like() 自带两侧 %，这里不应再手写一层变成 %%%proj%%%
        val sql = sqlOf(filters)
        assertTrue(sql.contains("project_name LIKE ?"), "实际: $sql")
    }

    @Test
    fun `like wildcards in user input are escaped`() {
        val builder2 = GpuTaskQueryBuilder()
        val method = builder2.javaClass.getDeclaredMethod("escapeLikeWildcards", String::class.java)
        method.isAccessible = true

        assertEquals("100\\%完成", method.invoke(builder2, "100%完成"))
        assertEquals("a\\_b", method.invoke(builder2, "a_b"))
        assertEquals("c:\\\\tmp", method.invoke(builder2, "c:\\tmp"))
    }

    // ==================== 基本回归 ====================

    @Test
    fun `no filters and no time range produces empty segment`() {
        assertTrue(sqlOf(emptyList()).isEmpty())
    }

    @Test
    fun `time range only still works`() {
        val sql = sqlOf(emptyList(), TimeRange(Instant.ofEpochSecond(1000L), Instant.ofEpochSecond(2000L)))
        assertTrue(sql.contains("task_start_time >= ?"), "实际: $sql")
        assertTrue(sql.contains("task_start_time <= ?"), "实际: $sql")
    }

    @Test
    fun `all comparison operators map correctly`() {
        val cases = mapOf(
            QueryOperator.EQUALS to "=",
            QueryOperator.NOT_EQUALS to "<>",
            QueryOperator.GREATER_THAN to ">",
            QueryOperator.LESS_THAN to "<",
            QueryOperator.GREATER_EQUAL to ">=",
            QueryOperator.LESS_EQUAL to "<="
        )
        for ((op, symbol) in cases) {
            val sql = sqlOf(listOf(QueryFilter(QueryField.TASK_RUNNING_TIME_IN_SECONDS, op, 100).and()))
            assertTrue(
                sql.contains("task_running_time_in_seconds $symbol ?"),
                "操作符 $op 应翻译为 $symbol，实际: $sql"
            )
        }
    }
}
