package com.khm.group.center.controller.api.web.public

import com.khm.group.center.datatype.response.ClientResponse
import com.khm.group.center.service.RealtimeSnapshotService
import com.khm.group.center.utils.program.Slf4jKt
import io.swagger.v3.oas.annotations.Operation
import io.swagger.v3.oas.annotations.Parameter
import io.swagger.v3.oas.annotations.tags.Tag
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.PathVariable
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RestController

/**
 * 实时聚合层对外读取接口（同源，公开侧，与现有 /web/open 一致，无需鉴权）。
 *
 * 前端改为访问这里，不再直连内网 agent；数据由后端 pull agent 并做 TTL 缓存。
 * 统一返回 { snapshot, snapshotTime, freshness, agentOnline, stale, source }。
 */
@RestController
@RequestMapping("/web/open/realtime")
@Tag(name = "Realtime", description = "Same-origin realtime GPU/disk aggregation proxied from agents")
@Slf4jKt
class RealtimeController {

    @Autowired
    private lateinit var realtimeSnapshotService: RealtimeSnapshotService

    @Operation(
        summary = "机器实时概览列表",
        description = "返回所有已配置机器的在线状态与缓存快照新鲜度，不触发对 agent 的实时拉取"
    )
    @GetMapping("/machines")
    fun listMachines(): ClientResponse {
        val response = ClientResponse()
        response.result = realtimeSnapshotService.buildMachineList()
        response.isSucceed = true
        return response
    }

    @Operation(
        summary = "指定机器的实时 GPU 快照",
        description = "后端 pull agent 的 gpu_count/gpu_usage_info/gpu_task_info，带 TTL 缓存与 last-known-good 回退"
    )
    @GetMapping("/machines/{name}/gpu")
    fun getMachineGpu(
        @Parameter(description = "机器英文名 nameEng") @PathVariable name: String
    ): ClientResponse {
        val response = ClientResponse()
        response.result = realtimeSnapshotService.buildGpuView(name)
        response.isSucceed = true
        return response
    }

    @Operation(
        summary = "指定机器的实时磁盘与系统内存快照",
        description = "后端 pull agent 的 disk_usage/system_info，带 TTL 缓存与 last-known-good 回退"
    )
    @GetMapping("/machines/{name}/disk")
    fun getMachineDisk(
        @Parameter(description = "机器英文名 nameEng") @PathVariable name: String
    ): ClientResponse {
        val response = ClientResponse()
        response.result = realtimeSnapshotService.buildDiskView(name)
        response.isSucceed = true
        return response
    }
}
