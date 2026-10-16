package com.khm.group.center.service

import com.baomidou.mybatisplus.core.conditions.query.QueryWrapper
import com.alibaba.fastjson2.JSON
import com.khm.group.center.controller.api.web.public.GpuTaskQueryController
import com.khm.group.center.datatype.config.GroupUserConfig
import com.khm.group.center.datatype.config.MachineConfig
import com.khm.group.center.datatype.query.GpuTaskQueryResponse
import com.khm.group.center.datatype.query.enums.SortField
import com.khm.group.center.datatype.query.enums.SortOrder
import com.khm.group.center.datatype.statistics.UserStatistics
import com.khm.group.center.db.mapper.client.GpuTaskInfoMapper
import com.khm.group.center.db.model.client.GpuTaskInfoModel
import com.khm.group.center.test.H2DatabaseTest
import com.khm.group.center.service.cache.ReportCacheManager
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.test.util.ReflectionTestUtils
import java.util.UUID

@H2DatabaseTest
class TaskQueryPresentationTest {
    @Autowired private lateinit var controller: GpuTaskQueryController
    @Autowired private lateinit var service: GpuTaskQueryService
    @Autowired private lateinit var mapper: GpuTaskInfoMapper

    private val prefix = "presentation-${UUID.randomUUID()}"

    private fun task(user: String, project: String, server: String, gpu: String = "RTX 4090", runtime: Int = 100) =
        GpuTaskInfoModel().apply {
            taskId = "$prefix-${UUID.randomUUID()}"
            taskUser = user
            projectName = project
            serverNameEng = server
            serverName = "Old display label"
            taskGpuName = gpu
            taskRunningTimeInSeconds = runtime
            taskStartTime = 1000
            taskFinishTime = 1000L + runtime
        }

    @AfterEach fun cleanup() {
        mapper.delete(QueryWrapper<GpuTaskInfoModel>().likeRight("task_id", prefix))
    }

    @Test fun `partial user and project match while selected server is exact`() {
        val wanted = task("$prefix-alice", "train-experiment", "4098a")
        mapper.insert(wanted)
        mapper.insert(task("$prefix-alice", "train-experiment", "4098ab"))
        mapper.insert(task("$prefix-bob", "train-experiment", "4098a"))
        val response = controller.queryGpuTasksSimple(
            userName = "$prefix-ali", projectName = "experiment", deviceName = null,
            serverNameEng = "4098a", taskType = null, isMultiGpu = null,
            startTime = null, endTime = null, page = 1, pageSize = 20,
            sortBy = SortField.TASK_START_TIME, sortOrder = SortOrder.DESC,
            includeStatistics = false
        ).result as GpuTaskQueryResponse
        assertEquals(listOf(wanted.taskId), response.data.map { it.taskId })
    }

    @Test fun `literal underscores and percent do not widen fuzzy matches`() {
        mapper.insert(task("${prefix}_ali%", "project", "4098a"))
        mapper.insert(task("${prefix}XaliSuffix", "project", "4098a"))
        val response = controller.queryGpuTasksSimple(
            userName = "${prefix}_ali%", projectName = null, deviceName = null,
            serverNameEng = null, taskType = null, isMultiGpu = null,
            startTime = null, endTime = null, page = 1, pageSize = 20,
            sortBy = SortField.TASK_START_TIME, sortOrder = SortOrder.DESC,
            includeStatistics = false
        ).result as GpuTaskQueryResponse
        assertEquals(1, response.data.size)
        assertEquals("${prefix}_ali%", response.data.single().taskUser)
    }

    @Test fun `options include historical and configured users and servers without recent tasks`() {
        val oldUsers = GroupUserConfig.userList
        val oldMachines = MachineConfig.machineList
        try {
            GroupUserConfig.userList = oldUsers + GroupUserConfig().apply { name = "$prefix-no-tasks" }
            MachineConfig.machineList = oldMachines + MachineConfig().apply { nameEng = "$prefix-idle-server" }
            repeat(25) { mapper.insert(task("$prefix-user-$it", "old-project", "$prefix-old-server")) }
            val options = service.getQueryOptions()
            val users = options["users"] as List<*>
            assertTrue(users.contains("$prefix-no-tasks"))
            assertEquals(25, users.count { it.toString().startsWith("$prefix-user-") })
            val servers = (options["servers"] as List<*>).map { (it as Map<*, *>)["serverNameEng"] }
            assertTrue(servers.contains("$prefix-idle-server"))
            assertTrue(servers.contains("$prefix-old-server"))
        } finally {
            GroupUserConfig.userList = oldUsers
            MachineConfig.machineList = oldMachines
        }
    }

    @Test fun `favorite GPU and server use summed runtime independently of task order`() {
        val tasks = listOf(
            task("alice", "project-a", "4090a", "RTX 4090", 60),
            task("alice", "project-a", "4090a", "RTX 4090", 60),
            task("alice", "project-b", "2084", "RTX 2080 Ti", 100)
        )
        val analyzer = StatisticsAnalyzer()
        val result = analyzer.analyzeUserStatistics(tasks).single()
        assertEquals("RTX 4090", result.favoriteGpu)
        assertEquals("4090a", result.favoriteServer)
        assertEquals("project-a", result.favoriteProject)
        assertEquals(listOf("RTX 2080 Ti", "RTX 4090"), result.gpuModels)
        assertEquals(result, analyzer.analyzeUserStatistics(tasks.reversed()).single())
        assertEquals(setOf("4090a", "2084"), analyzer.analyzeGpuStatistics(tasks).map { it.serverName }.toSet())
    }

    @Test fun `favorites consider only runtime inside the selected period`() {
        val tasks = listOf(
            task("alice", "old", "2084", "RTX 2080 Ti", 5000),
            task("alice", "new", "4090a", "RTX 4090", 100).apply {
                taskStartTime = 5990
                taskFinishTime = 6090
            }
        )
        val result = StatisticsAnalyzer().analyzeUserStatistics(tasks, 5990, 6100).single()
        assertEquals("4090a", result.favoriteServer)
        assertEquals("RTX 4090", result.favoriteGpu)
    }

    @Test fun `disk cache retains new usage fields and accepts old reports`() {
        val cache = ReportCacheManager()
        val newJson = JSON.parseArray("""[{"userName":"alice","favoriteGpu":"RTX 4090","favoriteProject":"train","favoriteServer":"4098a","gpuModels":["RTX 4090","RTX 2080 Ti"]}]""")
        val oldJson = JSON.parseArray("""[{"userName":"alice","favoriteGpu":"RTX 4090","favoriteProject":"train"}]""")
        for (method in listOf("parseUserStatisticsList", "convertJsonArrayToTypedList")) {
            fun parse(json: com.alibaba.fastjson2.JSONArray): UserStatistics {
                val args: Array<Any> = if (method == "parseUserStatisticsList") arrayOf(json)
                    else arrayOf(json, "user_stats_v2_ONE_WEEK")
                return ReflectionTestUtils.invokeMethod<List<UserStatistics>>(cache, method, *args)!!.single()
            }
            assertEquals("4098a", parse(newJson).favoriteServer)
            assertEquals(listOf("RTX 4090", "RTX 2080 Ti"), parse(newJson).gpuModels)
            assertEquals("", parse(oldJson).favoriteServer)
            assertEquals(emptyList<String>(), parse(oldJson).gpuModels)
        }
    }
}
