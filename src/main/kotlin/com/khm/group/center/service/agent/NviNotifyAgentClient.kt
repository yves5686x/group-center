package com.khm.group.center.service.agent

import com.fasterxml.jackson.databind.DeserializationFeature
import com.fasterxml.jackson.module.kotlin.jacksonObjectMapper
import com.khm.group.center.datatype.agent.AgentDiskUsageResponse
import com.khm.group.center.datatype.agent.AgentGpuCount
import com.khm.group.center.datatype.agent.AgentGpuTaskInfoResponse
import com.khm.group.center.datatype.agent.AgentGpuUsageInfo
import com.khm.group.center.datatype.agent.AgentSystemInfo
import com.khm.group.center.utils.program.Slf4jKt
import com.khm.group.center.utils.program.Slf4jKt.Companion.logger
import okhttp3.OkHttpClient
import okhttp3.Request
import org.springframework.beans.factory.annotation.Value
import org.springframework.stereotype.Component
import java.util.concurrent.TimeUnit

/**
 * nvi-notify(agent)REST 接口客户端。
 *
 * 后端主动 pull agent 数据（前端原先直连 agent 的链路改为经服务器中转）。
 * 所有方法在网络/解析失败时返回 null，由上层做 last-known-good 回退。
 */
@Component
@Slf4jKt
class NviNotifyAgentClient {

    private val client = OkHttpClient.Builder()
        .connectTimeout(3, TimeUnit.SECONDS)
        .readTimeout(5, TimeUnit.SECONDS)
        .callTimeout(8, TimeUnit.SECONDS)
        .build()

    private val objectMapper = jacksonObjectMapper()
        .configure(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES, false)

    /**
     * agent 基地址前缀。
     * 生产配置中机器的 apiUrl 是相对路径（如 /gpu/3090），
     * 由公网入口（反向代理）转发到内网 agent；后端 pull 时需拼接该 origin。
     * 若 apiUrl 本身已是 http(s) 绝对地址，则忽略此前缀。
     */
    // 权威来源：application.yml 的 realtime.agent-base-url（默认空串，由 yml 的
    // ${REALTIME_AGENT_BASE_URL:} 占位符承接环境变量）
    @Value("\${realtime.agent-base-url}")
    private var agentBaseUrl: String = ""

    fun getGpuCount(baseUrl: String): AgentGpuCount? =
        getJson(joinUrl(baseUrl, "gpu_count"), AgentGpuCount::class.java)

    fun getGpuUsageInfo(baseUrl: String, gpuIndex: Int): AgentGpuUsageInfo? =
        getJson(
            joinUrl(baseUrl, "gpu_usage_info") + "?gpuIndex=$gpuIndex",
            AgentGpuUsageInfo::class.java
        )

    fun getGpuTaskInfo(baseUrl: String, gpuIndex: Int): AgentGpuTaskInfoResponse? =
        getJson(
            joinUrl(baseUrl, "gpu_task_info") + "?gpuIndex=$gpuIndex",
            AgentGpuTaskInfoResponse::class.java
        )

    fun getSystemInfo(baseUrl: String): AgentSystemInfo? =
        getJson(joinUrl(baseUrl, "system_info"), AgentSystemInfo::class.java)

    fun getDiskUsage(baseUrl: String): AgentDiskUsageResponse? =
        getJson(joinUrl(baseUrl, "disk_usage"), AgentDiskUsageResponse::class.java)

    /**
     * 拼接 baseUrl 与路径，保留 scheme（不把 http:// 压成 http:/）。
     * 若 baseUrl 为相对路径，则用 [agentBaseUrl] 补全为绝对地址。
     */
    private fun joinUrl(baseUrl: String, path: String): String {
        var base = baseUrl.trim()
        if (!base.startsWith("http://") && !base.startsWith("https://")) {
            base = agentBaseUrl.trim().trimEnd('/') + "/" + base.trimStart('/')
        }
        base = base.trimEnd('/')
        val p = path.trim().trimStart('/')
        return "$base/$p"
    }

    private fun <T> getJson(url: String, clazz: Class<T>): T? {
        return try {
            val request = Request.Builder().url(url).get().build()
            client.newCall(request).execute().use { response ->
                if (!response.isSuccessful) {
                    logger.warn("Agent request failed: $url -> HTTP ${response.code}")
                    return null
                }
                val body = response.body.string()
                if (body.isBlank()) {
                    logger.warn("Agent request empty body: $url")
                    return null
                }
                objectMapper.readValue(body, clazz)
            }
        } catch (e: Exception) {
            logger.warn("Agent request error: $url - ${e.message}")
            null
        }
    }
}
