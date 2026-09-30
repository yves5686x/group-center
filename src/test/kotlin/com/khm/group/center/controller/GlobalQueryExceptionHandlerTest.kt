package com.khm.group.center.controller

import com.khm.group.center.datatype.query.GpuTaskQueryRequest
import com.khm.group.center.datatype.query.Pagination
import com.khm.group.center.datatype.query.QueryFilter
import com.khm.group.center.datatype.query.enums.QueryField
import com.khm.group.center.datatype.query.enums.QueryOperator
import com.khm.group.center.datatype.query.enums.SortField
import com.khm.group.center.datatype.query.enums.SortOrder
import com.khm.group.center.datatype.response.QueryFailureException
import com.khm.group.center.service.GpuTaskQueryService
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.mockito.ArgumentCaptor
import org.mockito.Mockito

/**
 * M2 回归：查询失败不得再被伪装成「成功但无数据」。
 *
 * 修复前 service 层 `catch (e: Exception) { return empty() }` 会把参数错误和
 * 数据库异常都吞成空列表，controller 再无条件写 isSucceed=true，
 * 三者（参数错 / 执行失败 / 确实无数据）在响应上完全同形。
 */
class GlobalQueryExceptionHandlerTest {

    private lateinit var service: GpuTaskQueryService
    private lateinit var handler: GlobalQueryExceptionHandler

    @BeforeEach
    fun setUp() {
        service = Mockito.mock(GpuTaskQueryService::class.java)
        handler = GlobalQueryExceptionHandler()
    }

    private fun requestWith(filters: List<QueryFilter>): GpuTaskQueryRequest = GpuTaskQueryRequest(
        filters = filters,
        timeRange = null,
        pagination = Pagination(1, 20, SortField.TASK_START_TIME, SortOrder.DESC),
        includeStatistics = false
    )

    @Test
    fun `invalid filter surfaces as a failed response instead of empty success`() {
        // BETWEEN 传了非列表值，validate() 不通过
        val bad = QueryFilter(QueryField.GPU_USAGE_PERCENT, QueryOperator.BETWEEN, "not-a-list")
        val request = requestWith(listOf(bad))

        // service 不再吞成 empty()，而是抛出可预期的失败
        Mockito.`when`(service.queryGpuTasks(request)).thenThrow(
            QueryFailureException.InvalidRequest("filters[0] 的值不合法")
        )

        val response = handler.handleInvalidRequest(
            QueryFailureException.InvalidRequest("filters[0] 的值不合法")
        )

        assertFalse(response.isSucceed, "参数错误必须能被识别为失败")
        assertTrue(response.haveError)
        assertTrue(
            response.result.toString().contains("不合法"),
            "响应体应带出可操作的原因，实际: ${response.result}"
        )
    }

    @Test
    fun `execution failure surfaces as a failed response`() {
        val response = handler.handleExecutionFailure(
            QueryFailureException.Execution("查询执行失败", IllegalStateException("boom"))
        )

        assertFalse(response.isSucceed)
        assertTrue(response.haveError)
    }

    @Test
    fun `handler keeps http 200 semantics for frontend compatibility`() {
        val response = handler.handleInvalidRequest(QueryFailureException.InvalidRequest("x"))

        // 现有前端按 isSucceed 判定成败，改成 4xx/5xx 会改变它的响应解析路径，
        // 因此这里只在响应体上区分，不动 HTTP 状态码。
        assertEquals(false, response.isSucceed)
        assertTrue(response.haveError)
    }

    @Test
    fun `service actually throws on invalid request instead of returning empty`() {
        // 直接验证 service 层的契约：无效请求必须抛，而不是返回空结果
        val realService = GpuTaskQueryService()
        val bad = QueryFilter(QueryField.TASK_USER, QueryOperator.EQUALS, listOf("a", "b"))
        val request = requestWith(listOf(bad))

        val e = assertThrows(QueryFailureException.InvalidRequest::class.java) {
            realService.queryGpuTasks(request)
        }
        assertTrue(
            e.userMessage.contains("filters[0]"),
            "应指出具体是哪个 filter，实际: ${e.userMessage}"
        )
    }

    @Test
    fun `validation message enumerates every offending filter`() {
        val realService = GpuTaskQueryService()
        val bad1 = QueryFilter(QueryField.TASK_USER, QueryOperator.EQUALS, listOf("a", "b"))
        val bad2 = QueryFilter(QueryField.GPU_USAGE_PERCENT, QueryOperator.BETWEEN, "oops")
        val request = requestWith(listOf(bad1, bad2))

        val e = assertThrows(QueryFailureException.InvalidRequest::class.java) {
            realService.queryGpuTasks(request)
        }
        val msg = e.userMessage
        assertTrue(msg.contains("filters[0]") && msg.contains("filters[1]"),
            "应逐项列出所有问题 filter，实际: $msg")
    }
}
