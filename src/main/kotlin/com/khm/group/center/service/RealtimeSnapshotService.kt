package com.khm.group.center.service

import com.khm.group.center.datatype.agent.AgentDiskUsage
import com.khm.group.center.datatype.agent.AgentGpuTaskInfoResponse
import com.khm.group.center.datatype.agent.AgentGpuTaskItem
import com.khm.group.center.datatype.agent.AgentGpuUsageInfo
import com.khm.group.center.datatype.agent.AgentSystemInfo
import com.khm.group.center.datatype.config.MachineConfig
import com.khm.group.center.datatype.realtime.DiskSnapshot
import com.khm.group.center.datatype.realtime.GpuSnapshot
import com.khm.group.center.datatype.realtime.GpuTaskBrief
import com.khm.group.center.datatype.realtime.RealtimeDiskView
import com.khm.group.center.datatype.realtime.RealtimeGpuView
import com.khm.group.center.datatype.realtime.RealtimeMachineBrief
import com.khm.group.center.datatype.realtime.SystemSnapshot
import com.khm.group.center.service.agent.NviNotifyAgentClient
import com.khm.group.center.utils.program.Slf4jKt
import com.khm.group.center.utils.program.Slf4jKt.Companion.logger
import com.khm.group.center.utils.time.DateTimeUtils
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.runBlocking
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.beans.factory.annotation.Value
import org.springframework.stereotype.Service
import java.util.concurrent.ConcurrentHashMap

/**
 * 实时聚合服务（pull-through-cache）。
 *
 * 数据流：前端 → group-center(/web/open/realtime) → 后端主动 pull agent(nvi-notify) → 内存 TTL 缓存。
 * 收益：浏览器不再直连内网 agent（去 CORS / 去拓扑暴露）；N 个观看者共享一份缓存，
 * agent 负载从 O(N×频率) 降到 O(1×频率)；agent 挂了保留 last-known-good 并标 stale。
 *
 * 纯内存，不落库、不依赖 Redis；agentOnline 复用 [MachineStatusService] 的心跳判定。
 */
@Service
@Slf4jKt
class RealtimeSnapshotService {

    @Autowired
    private lateinit var agentClient: NviNotifyAgentClient

    @Autowired
    private lateinit var machineStatusService: MachineStatusService

    /** 缓存 TTL（秒）：命中期内直接返回缓存，不再拉取 agent。（权威来源：application.yml） */
    @Value("\${realtime.cache-ttl-seconds}")
    private var cacheTtlSeconds: Long = 5

    /** 过期阈值（秒）：freshness 超过该值标记 stale（用于 last-known-good）。（权威来源：application.yml） */
    @Value("\${realtime.stale-threshold}")
    private var staleThresholdSeconds: Long = 60

    /** 单台机器一次完整拉取的结果（GPU + 磁盘 + 系统内存）。 */
    private class MachinePullResult(
        val gpuCount: Int,
        val gpus: List<GpuSnapshot>,
        val disks: List<DiskSnapshot>,
        val system: SystemSnapshot?,
        val pulledAt: Long
    )

    // serverNameEng -> 最近一次成功拉取的结果
    private val cache = ConcurrentHashMap<String, MachinePullResult>()

    /** 一次读取的数据来源结果。 */
    private class PullOutcome(
        val machine: MachineConfig?,
        val data: MachinePullResult?,
        val source: String,   // agent | cache | last-known-good | none
        val error: String?
    )

    // ==================== 对外视图构建 ====================

    fun buildGpuView(nameEng: String): RealtimeGpuView {
        val outcome = getOrPull(nameEng)
        val now = DateTimeUtils.getCurrentTimestamp()

        val view = RealtimeGpuView()
        fillBase(view, outcome, now)
        view.gpuCount = outcome.data?.gpuCount ?: 0
        view.snapshot = outcome.data?.gpus ?: listOf()
        return view
    }

    fun buildDiskView(nameEng: String): RealtimeDiskView {
        val outcome = getOrPull(nameEng)
        val now = DateTimeUtils.getCurrentTimestamp()

        val view = RealtimeDiskView()
        fillBase(view, outcome, now)
        view.snapshot = outcome.data?.disks ?: listOf()
        view.system = outcome.data?.system
        return view
    }

    /**
     * 机器概览列表：不触发 pull，只汇报配置、在线状态与已缓存快照的新鲜度。
     */
    fun buildMachineList(): List<RealtimeMachineBrief> {
        val now = DateTimeUtils.getCurrentTimestamp()
        val result = mutableListOf<RealtimeMachineBrief>()

        for (machine in MachineConfig.machineList) {
            val brief = RealtimeMachineBrief()
            brief.serverNameEng = machine.nameEng
            brief.serverName = machine.name
            brief.agentOnline = machineStatusService.isAgentOnline(machine.nameEng)
            brief.hasApiUrl = machine.apiUrl.isNotBlank()
            brief.isGpu = machine.isGpu
            brief.position = machine.position

            val cached = cache[machine.nameEng]
            if (cached != null) {
                brief.snapshotTime = cached.pulledAt
                brief.freshness = now - cached.pulledAt
                brief.stale = brief.freshness > staleThresholdSeconds
            } else {
                brief.snapshotTime = 0
                brief.freshness = -1
                brief.stale = true
            }
            result.add(brief)
        }

        return result
    }

    // ==================== 内部：缓存 + 拉取 ====================

    private fun fillBase(
        view: com.khm.group.center.datatype.realtime.RealtimeViewBase,
        outcome: PullOutcome,
        now: Long
    ) {
        val machine = outcome.machine
        view.serverNameEng = machine?.nameEng ?: ""
        view.serverName = machine?.name ?: ""
        view.agentOnline = machine != null && machineStatusService.isAgentOnline(machine.nameEng)
        view.serverTime = now
        view.source = outcome.source
        view.error = outcome.error

        val data = outcome.data
        view.snapshotTime = data?.pulledAt ?: 0
        view.freshness = if (data != null) now - data.pulledAt else -1
        view.stale = data == null || view.freshness > staleThresholdSeconds
    }

    private fun getOrPull(nameEng: String): PullOutcome {
        val key = nameEng.trim()
        val machine = MachineConfig.getMachineByNameEng(key)
            ?: return PullOutcome(null, null, "none", "unknown machine: $key")

        val cached = cache[key]

        if (machine.apiUrl.isBlank()) {
            return if (cached != null) {
                PullOutcome(machine, cached, "last-known-good", "machine has no apiUrl, serving cache")
            } else {
                PullOutcome(machine, null, "none", "machine has no apiUrl configured")
            }
        }

        val now = DateTimeUtils.getCurrentTimestamp()
        if (cached != null && now - cached.pulledAt <= cacheTtlSeconds) {
            return PullOutcome(machine, cached, "cache", null)
        }

        val fresh = pullMachine(machine)
        if (fresh != null) {
            cache[key] = fresh
            return PullOutcome(machine, fresh, "agent", null)
        }

        // 拉取失败：回退到过期缓存（last-known-good）
        return if (cached != null) {
            PullOutcome(machine, cached, "last-known-good", "agent pull failed, serving stale cache")
        } else {
            PullOutcome(machine, null, "none", "agent unreachable and no cached snapshot")
        }
    }

    /**
     * 从 agent 完整拉取一台机器（GPU 并行拉取）。全部子请求失败时返回 null。
     */
    private fun pullMachine(machine: MachineConfig): MachinePullResult? {
        val apiUrl = machine.apiUrl
        val now = DateTimeUtils.getCurrentTimestamp()

        val gpuCountResp = agentClient.getGpuCount(apiUrl)
        val gpuCount = gpuCountResp?.result ?: 0

        val gpus: List<GpuSnapshot> = if (gpuCount > 0) {
            runBlocking {
                (0 until gpuCount).map { idx ->
                    async(Dispatchers.IO) {
                        val usage = agentClient.getGpuUsageInfo(apiUrl, idx)
                        val tasks = agentClient.getGpuTaskInfo(apiUrl, idx)
                        toGpuSnapshot(idx, usage, tasks)
                    }
                }.awaitAll()
            }
        } else {
            listOf()
        }

        val diskResp = agentClient.getDiskUsage(apiUrl)
        val sysResp = agentClient.getSystemInfo(apiUrl)

        val anySuccess = gpuCountResp != null ||
                gpus.any { it.hasData } ||
                diskResp != null ||
                sysResp != null

        if (!anySuccess) {
            logger.warn("Realtime pull got nothing from agent: ${machine.nameEng} ($apiUrl)")
            return null
        }

        return MachinePullResult(
            gpuCount = gpuCount,
            gpus = gpus,
            disks = diskResp?.diskUsage?.map { toDiskSnapshot(it) } ?: listOf(),
            system = sysResp?.let { toSystemSnapshot(it) },
            pulledAt = now
        )
    }

    // ==================== 内部：映射 ====================

    private fun toGpuSnapshot(
        idx: Int,
        usage: AgentGpuUsageInfo?,
        taskResp: AgentGpuTaskInfoResponse?
    ): GpuSnapshot {
        val s = GpuSnapshot()
        s.gpuId = idx
        if (usage != null) {
            s.hasData = true
            s.gpuName = usage.gpuName
            s.coreUsage = usage.coreUsage
            s.memoryUsage = usage.memoryUsage
            s.memoryTotal = usage.gpuMemoryTotal
            s.memoryTotalMb = usage.gpuMemoryTotalMB
            s.powerUsage = usage.gpuPowerUsage
            s.tdp = usage.gpuTDP
            s.temperature = usage.gpuTemperature
        }
        s.tasks = (taskResp?.taskList ?: listOf()).map { toTaskBrief(it) }
        return s
    }

    private fun toTaskBrief(item: AgentGpuTaskItem): GpuTaskBrief {
        val b = GpuTaskBrief()
        b.pid = item.pid
        b.name = item.name
        b.projectName = item.projectName
        b.pyFileName = item.pyFileName
        b.runTime = item.runTime
        b.startTimestamp = item.startTimestamp
        b.gpuMemoryUsage = item.gpuMemoryUsage
        b.gpuMemoryUsageMax = item.gpuMemoryUsageMax
        b.worldSize = item.worldSize
        b.localRank = item.localRank
        b.condaEnv = item.condaEnv
        b.screenSessionName = item.screenSessionName
        b.command = item.command
        b.cpuPercent = item.cpuPercent
        b.gpuUtilization = item.gpuUtilization

        // 补齐字段：agent 侧 id 是字符串，转数字对外；非数字/缺失时退化为 0
        // （前端据此把订阅入口置灰，而不是把 0 当成合法 projectId 传出去）。
        b.id = item.id.trim().toLongOrNull() ?: 0L
        b.debugMode = item.debugMode
        b.projectDirectory = item.projectDirectory
        b.multiprocessingSpawn = item.multiprocessingSpawn
        b.topPythonPid = item.topPythonPid
        b.pythonBinPath = item.pythonBinPath
        b.pythonVersion = item.pythonVersion
        b.torchVersion = item.torchVersion
        b.torchCudaVersion = item.torchCudaVersion
        b.taskMainMemoryMB = item.taskMainMemoryMB
        b.cudaRoot = item.cudaRoot
        b.cudaVersion = item.cudaVersion
        b.cudaVisibleDevices = item.cudaVisibleDevices
        b.driverVersion = item.driverVersion
        b.userEnvEpoch = item.userEnvEpoch
        b.zeroTotalGpuAlertCount = item.zeroTotalGpuAlertCount
        b.zeroTotalCpuAlertCount = item.zeroTotalCpuAlertCount
        b.zeroAlreadyAlertedGpuUsage = item.zeroAlreadyAlertedGpuUsage
        b.zeroAlreadyAlertedCpuUsage = item.zeroAlreadyAlertedCpuUsage
        b.zeroMaxConsecutiveCount = item.zeroMaxConsecutiveCount
        b.zeroDetectionIntervalSeconds = item.zeroDetectionIntervalSeconds
        return b
    }

    private fun toDiskSnapshot(d: AgentDiskUsage): DiskSnapshot {
        val s = DiskSnapshot()
        s.mountPoint = d.mountPoint
        s.usedPercentage = d.usedPercentage
        s.usedStr = d.usedStr
        s.freeStr = d.freeStr
        s.totalStr = d.totalStr
        s.type = d.type
        s.purpose = d.purpose
        s.triggerHighPercentageUsed = d.triggerHighPercentageUsed
        s.triggerLowFreeBytes = d.triggerLowFreeBytes
        s.triggerSizeWarning = d.triggerSizeWarning
        return s
    }

    private fun toSystemSnapshot(s: AgentSystemInfo): SystemSnapshot {
        val v = SystemSnapshot()
        v.memoryPhysicTotalMb = s.memoryPhysicTotalMb
        v.memoryPhysicUsedMb = s.memoryPhysicUsedMb
        v.memorySwapTotalMb = s.memorySwapTotalMb
        v.memorySwapUsedMb = s.memorySwapUsedMb
        return v
    }
}
