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
import com.khm.group.center.utils.format.CommandLineSanitizer
import com.khm.group.center.utils.time.DateTimeUtils
import jakarta.annotation.PostConstruct
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.withTimeoutOrNull
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.beans.factory.annotation.Value
import org.springframework.stereotype.Service
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.Semaphore
import java.util.concurrent.TimeUnit

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

    /**
     * last-known-good 硬上限（秒）：拉取失败时，只有缓存年龄不超过该值才回退。
     * 超限则返回 source=none，避免把很久以前的快照当现状展示。
     * （权威来源：application.yml）
     */
    @Value("\${realtime.max-stale-seconds}")
    private var maxStaleSeconds: Long = 300

    /**
     * 单台机器允许的最大 GPU 张数：一次拉取按张数放大请求（每卡 usage + task），
     * agent 报异常值时按此上限截断。（权威来源：application.yml）
     */
    @Value("\${realtime.max-gpu-count}")
    private var maxGpuCount: Int = 32

    /**
     * 单台机器一次完整拉取的总预算（秒）。
     *
     * 一次拉取包含按 GPU 张数放大的 HTTP 调用（8 卡机 19 个请求），逐个超时相加
     * 最坏可达 40 秒以上。单个请求的超时只约束那一个请求，约束不了整条链路；
     * 而这段逻辑跑在 Tomcat 工作线程上，一旦 agent 处于「丢包」状态（请求挂起直到超时），
     * 数十个并发看板请求就能占满线程池，把心跳接收、机器人推送等无关接口一起拖垮。
     * 这里给整次拉取一个总闸门，超时即放弃本轮。（权威来源：application.yml）
     */
    @Value("\${realtime.pull-timeout-seconds}")
    private var pullTimeoutSeconds: Long = 15

    /**
     * 全局并发拉取上限：同一时刻最多有多少台机器在被拉取。
     *
     * 单飞锁只保证「同一台机器」不重复拉取，不限制「不同机器」同时拉。
     * 打开看板概览会遍历所有机器，若每台都要 40 秒，则请求数与机器数同阶增长。
     * 超出上限时直接快速失败并回退到缓存，而不是排队——排队等于继续占住线程，
     * 那正是要避免的情况。（权威来源：application.yml）
     */
    @Value("\${realtime.max-concurrent-pulls}")
    private var maxConcurrentPulls: Int = 4

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

    // serverNameEng -> 拉取锁。仅用于把同一台机器的并发 miss 合并成一次拉取，
    // 键只能来自 MachineConfig.machineList（启动期一次性赋值），因此不会无界增长。
    private val pullLocks = ConcurrentHashMap<String, java.util.concurrent.locks.ReentrantLock>()

    /**
     * 失败冷却（秒）：某台机器拉取失败后，在这段时间内不再重试，直接回退缓存。（权威来源：application.yml）
     */
    @Value("\${realtime.pull-failure-cooldown-seconds}")
    private var pullFailureCooldownSeconds: Long = 30

    /** serverNameEng -> 最近一次拉取失败的时间戳，用于失败冷却判定。 */
    private val lastPullFailures = ConcurrentHashMap<String, Long>()

    /** 全局拉取闸门：限制同时进行的拉取数，上限由 maxConcurrentPulls 控制。 */
    private val pullGate = Semaphore(DEFAULT_MAX_CONCURRENT_PULLS)

    @PostConstruct
    fun initPullGate() {
        // 字段注入发生在构造之后，闸门容量只能在注入完成后再定容
        if (maxConcurrentPulls > 0) {
            pullGate.release(maxConcurrentPulls - DEFAULT_MAX_CONCURRENT_PULLS)
        } else {
            logger.warn(
                "realtime.max-concurrent-pulls={} is invalid, keeping default {}",
                maxConcurrentPulls, DEFAULT_MAX_CONCURRENT_PULLS
            )
        }
    }

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

        if (machine.apiUrl.isBlank()) {
            val cached = cache[key]
            return if (cached != null && isWithinMaxStale(cached)) {
                PullOutcome(machine, cached, "last-known-good", "machine has no apiUrl, serving cache")
            } else {
                PullOutcome(machine, null, "none", "machine has no apiUrl configured")
            }
        }

        if (isFresh(cache[key])) {
            return PullOutcome(machine, cache[key]!!, "cache", null)
        }

        val cached = cache[key]

        // 失败冷却：拉取失败不写缓存，因此若无冷却，每个后续请求都会重跑一遍完整拉取。
        // agent 挂掉时这就是放大器：N 个并发看板请求 = N 次全量拉取，全部占着 Tomcat 线程。
        // 单飞锁只在成功时有意义（它依赖「等锁期间别人已把结果写进缓存」），
        // 失败路径上缓存始终为空，锁只会把并发请求串行化——正是要避免的排队。
        val lastFailure = lastPullFailures[key]
        if (lastFailure != null &&
            DateTimeUtils.getCurrentTimestamp() - lastFailure < pullFailureCooldownSeconds
        ) {
            return fallbackOutcome(machine, cached, "agent pull recently failed, serving cache without retrying")
        }

        // 闸门必须在 per-machine 锁【之外】：放在锁内的话，并发请求会先在锁上排队，
        // 永远走不到闸门，闸门形同虚设，线程照样被占住。
        if (!pullGate.tryAcquire()) {
            logger.warn("Realtime pull gate is full (max={}), skipping pull: {}", maxConcurrentPulls, key)
            return fallbackOutcome(machine, cached, "too many concurrent realtime pulls, skipped this round")
        }

        try {
            val lock = pullLocks.computeIfAbsent(key) { java.util.concurrent.locks.ReentrantLock() }
            // 必须 tryLock 而非 synchronized：单飞锁在 agent 健康时只需等几百毫秒（无害），
            // 但 agent 挂掉时锁会持有整整一个拉取预算，后续请求全堵在监视器上——
            // 那正是要避免的占住工作线程。拿不到锁就直接回退，让在途那次去填缓存。
            val acquired = (lock as java.util.concurrent.locks.ReentrantLock)
                .tryLock(PULL_LOCK_WAIT_MILLIS, java.util.concurrent.TimeUnit.MILLISECONDS)
            if (!acquired) {
                logger.warn("Realtime pull already in progress for {}, skipping this request", key)
                return fallbackOutcome(machine, cached, "a pull for this machine is already in progress")
            }

            try {
                // 双检：等锁期间可能已被其它线程刷新
                val latest = cache[key]
                if (isFresh(latest)) {
                    return PullOutcome(machine, latest!!, "cache", null)
                }

                val fresh = withPullTimeout { pullMachine(machine) }
                if (fresh != null) {
                    lastPullFailures.remove(key)
                    // 并发写回时只接受更新的快照，避免慢请求用旧数据覆盖快请求的结果
                    val winner = cache.merge(key, fresh) { old, new ->
                        if (new.pulledAt >= old.pulledAt) new else old
                    }
                    return PullOutcome(machine, winner, "agent", null)
                }

                lastPullFailures[key] = DateTimeUtils.getCurrentTimestamp()
                return fallbackOutcome(machine, latest, "agent pull failed, serving stale cache")
            } finally {
                (lock as java.util.concurrent.locks.ReentrantLock).unlock()
            }
        } finally {
            pullGate.release()
        }
    }

    /** 缓存是否仍在 TTL 内。 */
    private fun isFresh(data: MachinePullResult?): Boolean {
        if (data == null) return false
        return DateTimeUtils.getCurrentTimestamp() - data.pulledAt <= cacheTtlSeconds
    }

    /**
     * 拉取失败时的统一回退：受 last-known-good 硬上限约束。
     *
     * 抽出来是因为「闸门拒绝」和「拉取失败」两条路径的降级规则必须一致，
     * 否则会出现某条路径漏掉年龄上限、又返回陈旧快照的情况。
     */
    private fun fallbackOutcome(
        machine: MachineConfig,
        cached: MachinePullResult?,
        reason: String
    ): PullOutcome {
        return when {
            cached != null && isWithinMaxStale(cached) ->
                PullOutcome(machine, cached, "last-known-good", reason)
            cached != null ->
                PullOutcome(machine, null, "none", "$reason and cached snapshot is too old")
            else ->
                PullOutcome(machine, null, "none", "$reason and no cached snapshot")
        }
    }

    /**
     * 在总预算内执行一次拉取，超时即放弃本轮。
     *
     * 关键点：把拉取丢到 [Dispatchers.IO] 独立执行，本线程只「等到预算耗尽为止」，
     * 而不是等拉取本身完成。原先用 `withTimeout { pullMachine() }` 是无效的——
     * [pullMachine] 内部是 OkHttp 阻塞调用，不存在能响应协程取消的挂起点，
     * 超时信号要等下一个挂起点才被检查，而那时线程早已陪着等完了。
     *
     * 因此预算只约束「调用方等多久」，而非「工作跑多久」：到点立即返回、
     * 释放调用方线程，这正是要达到的效果。被放弃的工作仍在后台跑完，
     * 由 OkHttp 自身的 callTimeout 收尾，不会无限悬挂。
     */
    private fun <T> withPullTimeout(block: suspend CoroutineScope.() -> T): T? {
        val budgetMillis = TimeUnit.SECONDS.toMillis(pullTimeoutSeconds)
        // 独立作用域很关键：若用 runBlocking 自身的 scope 启 async，那个 deferred 就是
        // runBlocking 的子协程，而 runBlocking 必须等所有子协程结束才返回——
        // 于是超时虽然准点返回了 null，调用方线程仍会陪着等到活干完，等于没超时。
        // 放到独立作用域里，被放弃的工作就在后台自行收尾，不再占用调用方线程。
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
        return runBlocking {
            val deferred = scope.async { block() }
            val result = withTimeoutOrNull(budgetMillis) { deferred.await() }
            if (result == null && !deferred.isCompleted) {
                logger.warn("Realtime pull exceeded budget of {}s, abandoning this round", pullTimeoutSeconds)
            }
            result
        }
    }

    /** 缓存年龄是否仍在 last-known-good 硬上限内。 */
    private fun isWithinMaxStale(data: MachinePullResult): Boolean {
        return DateTimeUtils.getCurrentTimestamp() - data.pulledAt <= maxStaleSeconds
    }

    /**
     * 从 agent 完整拉取一台机器（GPU 并行拉取）。全部子请求失败时返回 null。
     */
    private fun pullMachine(machine: MachineConfig): MachinePullResult? {
        val apiUrl = machine.apiUrl

        val gpuCountResp = agentClient.getGpuCount(apiUrl)
        val reportedGpuCount = gpuCountResp?.result ?: 0
        val gpuCount = if (reportedGpuCount > maxGpuCount) {
            logger.warn(
                "Agent reported an implausible gpu count, clamping: {} -> {} (machine={})",
                reportedGpuCount, maxGpuCount, machine.nameEng
            )
            maxGpuCount
        } else {
            reportedGpuCount
        }

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

        // 只有拿到实质内容才算成功。仅 gpu_count 单独成功时，gpus 会是一组
        // hasData=false 的空卡，若据此判成功，会把「agent 半挂」显示成
        // 「N 张卡、无数据、来源 agent、数据很新」，比退回旧数据更具欺骗性。
        val anySuccess = gpus.any { it.hasData } ||
                diskResp?.diskUsage?.isNotEmpty() == true ||
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
            // 取拉取完成时刻：一次拉取包含按 GPU 张数放大的 HTTP 调用，
            // 若取开始时刻，写入缓存时可能已超过 TTL，导致缓存永不命中。
            pulledAt = DateTimeUtils.getCurrentTimestamp()
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
        // agent 上报的命令行同样可能内嵌 wandb key / HF token，与 command_line 同源同样要脱敏
        b.command = CommandLineSanitizer.maskCredentials(item.command)
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

    private companion object {
        /** 闸门默认容量。真正的取值来自 realtime.max-concurrent-pulls，见 initPullGate()。 */
        const val DEFAULT_MAX_CONCURRENT_PULLS = 4

        /** 单飞锁最多等待的毫秒数：agent 健康时拉取仅需几百毫秒，等一下是划算的。 */
        const val PULL_LOCK_WAIT_MILLIS = 300L
    }
}
