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

// GET /gpu_task_info?gpuIndex=N —— taskList 元素（仅取需要的字段）
data class AgentGpuTaskItem(
    var id: Long = 0,
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
    var worldSize: Int = 0,
    var localRank: Int = 0,
    var topPythonPid: Int = 0,
    var condaEnv: String = "",
    var screenSessionName: String = "",
    var pythonVersion: String = "",
    var command: String = "",
    var taskMainMemoryMB: Long = 0,
    var cudaVersion: String = "",
    var cpuPercent: Float = 0f,
    var gpuUtilization: Float = 0f
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
