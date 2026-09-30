package com.khm.group.center.datatype.response

/**
 * 查询类接口的可预期失败。
 *
 * 之前这类失败被 service 层 `catch (e: Exception) { return empty() }` 吞掉，
 * controller 再无条件写 `isSucceed = true`，于是参数写错、SQL 报错和
 * 「确实没有数据」在响应上完全同形：都是 200 + isSucceed=true + 空数组。
 * 运维只能靠翻服务端日志区分，前端连「请求有问题」都不知道。
 *
 * 抛出这个异常让失败能走到响应边界，由 [com.khm.group.center.controller.GlobalQueryExceptionHandler]
 * 转换成明确标记失败的响应。HTTP 状态码保持 200，避免影响现有前端对非 2xx 的处理。
 */
sealed class QueryFailureException(
    message: String,
    cause: Throwable? = null
) : RuntimeException(message, cause) {

    /** 面向调用方的简短说明，不含内部细节。 */
    abstract val userMessage: String

    /**
     * 请求参数不合法：字段/操作符不支持、值类型不匹配、分页越界、时间范围颠倒等。
     * 这类错误是调用方能自己修正的，提示应当足够具体。
     */
    class InvalidRequest(detail: String) : QueryFailureException(detail) {
        override val userMessage: String = "查询参数不合法: $detail"
    }

    /** 查询执行期失败，底层原因通常是数据库或排序字段问题。 */
    class Execution(override val userMessage: String, cause: Throwable? = null) :
        QueryFailureException(userMessage, cause)
}
