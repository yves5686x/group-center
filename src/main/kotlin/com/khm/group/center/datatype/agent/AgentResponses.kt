package com.khm.group.center.datatype.agent

/**
 * nvi-notify(agent)对外 REST 接口的响应 DTO。
 * 字段与前端 group-center-dashboard 的 API.* 类型对齐。
 * 解析时对未知字段宽容（Jackson FAIL_ON_UNKNOWN_PROPERTIES=false）。
 */

// GET /gpu_count
data class AgentGpuCount(
    var result: Int = 0
)

// GET /gpu_usage_info?gpuIndex=N
data class AgentGpuUsageInfo(
    // 实测发现该字段可能是整数或字符串，用宽松类型避免解析失败（业务不使用它）
    var result: Any? = null,
    var gpuName: String = "",
    var coreUsage: Float = 0f,
    var memoryUsage: Float = 0f,
    var gpuMemoryTotalMB: Long = 0,
    var gpuMemoryTotal: String = "",
    var gpuPowerUsage: Float = 0f,
    var gpuTDP: Float = 0f,
    var gpuTemperature: Float = 0f
)

/**
 * GET /gpu_task_info?gpuIndex=N —— taskList 元素。
 *
 * 字段顺序与 agent 实际返回的 JSON 对齐（实测共 36 个字段）。
 * 注意：解析侧配置了 FAIL_ON_UNKNOWN_PROPERTIES=false，**未在此声明字段会被静默丢弃**，
 * 所以 agent 返回的字段必须在这里逐个声明，否则下游拿不到。
 */
data class AgentGpuTaskItem(
    // 任务 ID。agent 实际返回的是**字符串**（形如 "20260902044750"），
    // 这里按原样用 String 接收，避免 Jackson 强转失败导致整个 taskList 解析中断。
    var id: String = "",
    var pid: Int = 0,
    var name: String = "",
    var debugMode: Boolean = false,
    var projectDirectory: String = "",
    var projectName: String = "",
    var pyFileName: String = "",
    var runTime: String = "",
    var startTimestamp: Long = 0,
    var gpuMemoryUsage: Float = 0f,
    var gpuMemoryUsageMax: Float = 0f,
    var multiprocessingSpawn: Boolean = false,
    var worldSize: Int = 0,
    var localRank: Int = 0,
    /** 主进程 PID。**agent 用 -1 表示"无法判定"**，不是 0 也不是缺失，消费方需按 >0 判断。 */
    var topPythonPid: Int = 0,
    var condaEnv: String = "",
    var screenSessionName: String = "",
    var pythonBinPath: String = "",
    var pythonVersion: String = "",
    var torchVersion: String = "",
    var torchCudaVersion: String = "",
    var command: String = "",
    var taskMainMemoryMB: Long = 0,
    var cudaRoot: String = "",
    var cudaVersion: String = "",
    var cudaVisibleDevices: String = "",
    var driverVersion: String = "",
    var userEnvEpoch: String = "",
    var cpuPercent: Float = 0f,
    var gpuUtilization: Float = 0f,
    var zeroTotalGpuAlertCount: Int = 0,
    var zeroTotalCpuAlertCount: Int = 0,
    var zeroAlreadyAlertedGpuUsage: Boolean = false,
    var zeroAlreadyAlertedCpuUsage: Boolean = false,
    var zeroMaxConsecutiveCount: Int = 0,
    var zeroDetectionIntervalSeconds: Int = 0
)

// GET /gpu_task_info?gpuIndex=N
data class AgentGpuTaskInfoResponse(
    var result: Any? = null,
    var taskList: List<AgentGpuTaskItem> = listOf()
)

// GET /system_info
data class AgentSystemInfo(
    var memoryPhysicTotalMb: Long = 0,
    var memoryPhysicUsedMb: Long = 0,
    var memorySwapTotalMb: Long = 0,
    var memorySwapUsedMb: Long = 0
)

// GET /disk_usage —— diskUsage 元素
data class AgentDiskUsage(
    var mountPoint: String = "",
    var usedPercentage: Float = 0f,
    var usedStr: String = "",
    var freeStr: String = "",
    var totalStr: String = "",
    var triggerHighPercentageUsed: Boolean = false,
    var triggerLowFreeBytes: Boolean = false,
    var triggerSizeWarning: Boolean = false,
    var type: String = "",
    var purpose: String = ""
)

// GET /disk_usage
data class AgentDiskUsageResponse(
    var result: Any? = null,
    var diskUsage: List<AgentDiskUsage> = listOf()
)
