package com.khm.group.center.datatype.realtime

/**
 * 实时聚合层对外数据模型。
 * 数据来源：后端主动 pull agent(nvi-notify) 的 REST 接口，带 TTL 内存缓存。
 * 所有视图统一携带 { snapshot, snapshotTime, freshness, agentOnline } 语义。
 */

/**
 * 单个 GPU 任务的精简信息（来自 agent /gpu_task_info）
 *
 * 字段分两段：
 *  1. 前 15 个 —— 聚合层初版即透出，前端为必填；
 *  2. 后 21 个 —— 补齐的遗留字段，前端声明为可选 + VShow 保护，
 *     透出即自动亮起（详见 group-center-dashboard 的 `Doc/dev/实时数据同源聚合层迁移.md`）。
 *
 * 说明：agent 另外返回一个 `mainName`，但前端全仓零引用（与 cpuModel/osName/uptime 同属历史死字段），
 * 故**刻意不透出**。
 */
class GpuTaskBrief {
    var pid: Int = 0
    var name: String = ""
    var projectName: String = ""
    var pyFileName: String = ""
    var runTime: String = ""
    var startTimestamp: Long = 0
    var gpuMemoryUsage: Float = 0f
    var gpuMemoryUsageMax: Float = 0f
    var worldSize: Int = 0
    var localRank: Int = 0
    var condaEnv: String = ""
    var screenSessionName: String = ""
    var command: String = ""
    var cpuPercent: Float = 0f
    var gpuUtilization: Float = 0f

    // ↓↓↓ 补齐字段：来源 agent，此前因未声明 / 未映射而被丢弃 ↓↓↓

    /**
     * 任务 ID，项目订阅功能依赖它。
     * agent 侧是字符串，这里转成数字对外；解析失败/缺失时为 0，消费方按 >0 判断。
     */
    var id: Long = 0

    var debugMode: Boolean = false
    var projectDirectory: String = ""
    var multiprocessingSpawn: Boolean = false

    /** 多卡任务主进程 PID，用于给同一 DDP 任务配色。**agent 用 -1 表示"无法判定"**，消费方须按 >0 判断。 */
    var topPythonPid: Int = 0

    var pythonBinPath: String = ""
    var pythonVersion: String = ""
    var torchVersion: String = ""
    var torchCudaVersion: String = ""
    var taskMainMemoryMB: Long = 0
    var cudaRoot: String = ""
    var cudaVersion: String = ""
    var cudaVisibleDevices: String = ""
    var driverVersion: String = ""
    var userEnvEpoch: String = ""

    // 零占用率（僵尸进程）监控
    var zeroTotalGpuAlertCount: Int = 0
    var zeroTotalCpuAlertCount: Int = 0
    var zeroAlreadyAlertedGpuUsage: Boolean = false
    var zeroAlreadyAlertedCpuUsage: Boolean = false
    var zeroMaxConsecutiveCount: Int = 0
    var zeroDetectionIntervalSeconds: Int = 0
}

/**
 * 单张 GPU 的实时占用快照（来自 agent /gpu_usage_info + /gpu_task_info）
 */
class GpuSnapshot {
    var gpuId: Int = 0
    var gpuName: String = ""

    var coreUsage: Float = 0f          // 核心利用率 %
    var memoryUsage: Float = 0f        // 显存占用 %
    var memoryTotal: String = ""       // 显存总量（人类可读）
    var memoryTotalMb: Long = 0
    var powerUsage: Float = 0f         // 功耗 W
    var tdp: Float = 0f                // TDP W
    var temperature: Float = 0f        // 温度 ℃

    // 是否成功从 agent 取到该卡数据
    var hasData: Boolean = false

    var tasks: List<GpuTaskBrief> = listOf()
}

/**
 * 磁盘占用快照（来自 agent /disk_usage）
 */
class DiskSnapshot {
    var mountPoint: String = ""
    var usedPercentage: Float = 0f
    var usedStr: String = ""
    var freeStr: String = ""
    var totalStr: String = ""
    var type: String = ""
    var purpose: String = ""
    var triggerHighPercentageUsed: Boolean = false
    var triggerLowFreeBytes: Boolean = false
    var triggerSizeWarning: Boolean = false
}

/**
 * 系统内存快照（来自 agent /system_info）
 */
class SystemSnapshot {
    var memoryPhysicTotalMb: Long = 0
    var memoryPhysicUsedMb: Long = 0
    var memorySwapTotalMb: Long = 0
    var memorySwapUsedMb: Long = 0
}

/**
 * 实时视图公共字段：新鲜度与在线状态
 */
abstract class RealtimeViewBase {
    var serverNameEng: String = ""
    var serverName: String = ""

    // agent 是否在线（心跳判定，来自 MachineStatusService）
    var agentOnline: Boolean = false

    // 快照生成时间（秒级时间戳）；0 表示从无数据
    var snapshotTime: Long = 0

    // 服务器当前时间（秒级时间戳）
    var serverTime: Long = 0

    // 数据年龄（秒）= serverTime - snapshotTime；-1 表示无数据
    var freshness: Long = -1

    // 是否过期（无数据 或 freshness 超过 stale 阈值）
    var stale: Boolean = true

    // 数据来源：agent(本次实时拉取) / cache(命中缓存) / last-known-good(拉取失败回退)
    var source: String = "none"

    // 拉取失败时的错误信息
    var error: String? = null
}

/**
 * GPU 实时视图
 */
class RealtimeGpuView : RealtimeViewBase() {
    var gpuCount: Int = 0
    var snapshot: List<GpuSnapshot> = listOf()
}

/**
 * 磁盘 + 系统内存 实时视图
 */
class RealtimeDiskView : RealtimeViewBase() {
    var snapshot: List<DiskSnapshot> = listOf()
    var system: SystemSnapshot? = null
}

/**
 * 机器概览（列表接口用）
 */
class RealtimeMachineBrief {
    var serverNameEng: String = ""
    var serverName: String = ""
    var agentOnline: Boolean = false
    var hasApiUrl: Boolean = false
    var isGpu: Boolean = false
    var position: String = ""
    var snapshotTime: Long = 0
    var freshness: Long = -1
    var stale: Boolean = true
}
