package com.khm.group.center.datatype.realtime

/**
 * 实时聚合层对外数据模型。
 * 数据来源：后端主动 pull agent(nvi-notify) 的 REST 接口，带 TTL 内存缓存。
 * 所有视图统一携带 { snapshot, snapshotTime, freshness, agentOnline } 语义。
 */

/**
 * 单个 GPU 任务的精简信息（来自 agent /gpu_task_info）
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
