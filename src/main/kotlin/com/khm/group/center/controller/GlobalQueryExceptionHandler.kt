package com.khm.group.center.controller

import com.khm.group.center.datatype.response.ClientResponse
import com.khm.group.center.datatype.response.QueryFailureException
import com.khm.group.center.utils.program.Slf4jKt.Companion.logger
import org.springframework.web.bind.annotation.ExceptionHandler
import org.springframework.web.bind.annotation.RestControllerAdvice

/**
 * 把查询类接口的可预期失败转成明确标记失败的响应。
 *
 * 这里刻意保持 HTTP 200：现有前端按 `isSucceed` 判定成败，直接改成 4xx/5xx
 * 会改变它的响应解析路径。改进点在于响应体本身——
 * 之前这些失败被 service 吞成 `isSucceed=true` + 空数组，现在至少能区分
 * 「没有数据」和「请求/执行有问题」，且服务端留下 WARN 日志。
 */
@RestControllerAdvice
class GlobalQueryExceptionHandler {

    /**
     * 参数不合法：记 WARN 而非 ERROR。调用方能自行修正，属于正常业务路径，
     * 但因为此前被伪装成成功，运营需要能一眼看见。
     */
    @ExceptionHandler(QueryFailureException.InvalidRequest::class)
    fun handleInvalidRequest(e: QueryFailureException.InvalidRequest): ClientResponse {
        logger.warn("Rejected invalid query request: {}", e.message)
        return failed(e.userMessage)
    }

    /**
     * 查询执行失败：记 ERROR 并带上堆栈，这是需要有人看的真问题。
     */
    @ExceptionHandler(QueryFailureException.Execution::class)
    fun handleExecutionFailure(e: QueryFailureException.Execution): ClientResponse {
        logger.error("Query execution failed: ${e.userMessage}", e)
        return failed(e.userMessage)
    }

    private fun failed(message: String): ClientResponse {
        val response = ClientResponse()
        response.isSucceed = false
        response.haveError = true
        response.result = mapOf("error" to message)
        return response
    }
}
