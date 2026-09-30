package com.khm.group.center.service

import com.baomidou.mybatisplus.core.conditions.query.QueryWrapper
import com.khm.group.center.datatype.query.GpuTaskQueryRequest
import com.khm.group.center.datatype.query.Pagination
import com.khm.group.center.datatype.query.QueryFilter
import com.khm.group.center.datatype.query.enums.QueryField
import com.khm.group.center.datatype.query.enums.QueryOperator
import com.khm.group.center.datatype.query.enums.SortField
import com.khm.group.center.datatype.query.enums.SortOrder
import com.khm.group.center.db.mapper.client.GpuTaskInfoMapper
import com.khm.group.center.db.model.client.GpuTaskInfoModel
import com.khm.group.center.test.H2DatabaseTest
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.test.util.ReflectionTestUtils
import java.time.LocalDateTime
import java.time.ZoneId
import java.util.Date

/**
 * [GpuTaskQueryService] 的两个安全边界：
 * 1. `/recent` 必须有行数上限与时间窗口上限（此前 hours=876000 等于拉全表）；
 * 2. 所有返回给前端的命令行都要脱敏，且**不能**回写数据库。
 *
 * 走真实的 H2 + MyBatis-Plus，而不是 mock：LIMIT 是拼在 SQL 尾部的，
 * mock 只能断言「调用了 last()」，断言不了「真的只返回 N 行」。
 */
@H2DatabaseTest
class GpuTaskQueryServiceTest {

    @Autowired
    private lateinit var service: GpuTaskQueryService

    @Autowired
    private lateinit var mapper: GpuTaskInfoMapper

    @Autowired
    private lateinit var statisticsService: StatisticsServiceImpl

    private var originalMaxRows = 0
    private var originalMaxHours = 0
    private var seq = 0

    private val secretCommand = "python train.py --num_workers 8 --wandb_key=abc123 --HF_TOKEN hf_xxx"

    /** 专用项目名：高级查询按它过滤，避免命中其它测试遗留的数据。 */
    private val TEST_PROJECT = "sanitize-test-proj"

    /** 专用 taskId 前缀：H2 库在测试类之间是共享的，断言只针对自己的数据。 */
    private val TEST_TASK_PREFIX = "sanitize-test-"

    @BeforeEach
    fun setUp() {
        // Spring 上下文在测试类之间是复用的，改完必须还原，否则会污染其它测试
        originalMaxRows = ReflectionTestUtils.getField(service, "recentMaxRows") as Int
        originalMaxHours = ReflectionTestUtils.getField(service, "recentMaxHours") as Int
    }

    @AfterEach
    fun tearDown() {
        ReflectionTestUtils.setField(service, "recentMaxRows", originalMaxRows)
        ReflectionTestUtils.setField(service, "recentMaxHours", originalMaxHours)
        mapper.delete(QueryWrapper<GpuTaskInfoModel>().likeRight("task_id", TEST_TASK_PREFIX))
    }

    /** 插入一条测试任务；[hoursAgo] 决定它落在 `/recent` 时间窗口的哪一侧。 */
    private fun insertTask(hoursAgo: Long, commandLine: String = secretCommand): String {
        val taskId = "$TEST_TASK_PREFIX${seq++}"
        val start = System.currentTimeMillis() / 1000 - hoursAgo * 3600
        mapper.insert(
            GpuTaskInfoModel().apply {
                this.taskId = taskId
                this.serverNameEng = "test-machine"
                this.serverName = "测试机"
                this.taskUser = "tester"
                this.messageType = "finish"
                this.taskStatus = "finished"
                this.projectName = TEST_PROJECT
                this.projectDirectory = "/data/exp1"
                this.taskStartTime = start
                this.taskFinishTime = start + 60
                this.taskStartTimeObj = Date(start * 1000)
                this.taskFinishTimeObj = Date((start + 60) * 1000)
                this.commandLine = commandLine
            }
        )
        return taskId
    }

    // ==================== /recent 的行数上限 ====================

    @Test
    fun `recent tasks are capped by the configured row limit`() {
        repeat(5) { insertTask(hoursAgo = it.toLong()) }
        ReflectionTestUtils.setField(service, "recentMaxRows", 2)

        val tasks = service.getRecentTasks(24)

        assertEquals(2, tasks.size, "recent-max-rows=2 时不得返回 5 条")
        // 按 task_start_time 倒序：留下的应该是最近的两条
        assertTrue(
            tasks[0].taskStartTime >= tasks[1].taskStartTime,
            "应按启动时间倒序返回，实际: ${tasks.map { it.taskStartTime }}"
        )
    }

    @Test
    fun `recent tasks below the row limit are all returned`() {
        repeat(3) { insertTask(hoursAgo = it.toLong()) }
        ReflectionTestUtils.setField(service, "recentMaxRows", 100)

        val mine = service.getRecentTasks(24).count { it.taskId.startsWith(TEST_TASK_PREFIX) }
        assertEquals(3, mine, "未触及上限时不得截断")
    }

    @Test
    fun `row limit falls back to one when misconfigured as zero`() {
        // LIMIT 0 会静默返回空、负数更是 SQL 语法错误；配置写错时兜一个下限
        repeat(3) { insertTask(hoursAgo = it.toLong()) }
        ReflectionTestUtils.setField(service, "recentMaxRows", 0)

        assertEquals(1, service.getRecentTasks(24).size, "recent-max-rows=0 时应退化为 LIMIT 1")
    }

    // ==================== /recent 的时间窗口上限 ====================

    @Test
    fun `oversized hours window is clamped to the configured maximum`() {
        val recent = insertTask(hoursAgo = 1)
        val old = insertTask(hoursAgo = 500)   // 远在一年之外
        ReflectionTestUtils.setField(service, "recentMaxHours", 24)

        // 不钳制的话 876000 小时会把 500 小时前那条也捞出来
        val ids = service.getRecentTasks(876000).map { it.taskId }

        assertTrue(recent in ids, "窗口内的任务应保留")
        assertFalse(old in ids, "超出 recent-max-hours 的任务应被挡在窗口外")
    }

    @Test
    fun `hours within the limit are not clamped`() {
        val taskId = insertTask(hoursAgo = 20)
        ReflectionTestUtils.setField(service, "recentMaxHours", 24)

        val ids = service.getRecentTasks(24).map { it.taskId }
        assertTrue(taskId in ids, "24h <= 上限，不应被截断")
    }

    // ==================== 命令行脱敏 ====================

    @Test
    fun `recent tasks mask credentials before returning`() {
        val taskId = insertTask(hoursAgo = 1)

        val task = service.getRecentTasks(24).first { it.taskId == taskId }

        assertEquals(
            "python train.py --num_workers 8 --wandb_key=*** --HF_TOKEN ***",
            task.commandLine
        )
        assertFalse(task.commandLine.contains("abc123"), "凭据不得出现在返回值中")
        assertFalse(task.commandLine.contains("hf_xxx"), "凭据不得出现在返回值中")
    }

    @Test
    fun `task by taskId masks credentials before returning`() {
        val taskId = insertTask(hoursAgo = 1)

        val task = service.getTaskByTaskId(taskId)!!

        assertEquals(
            "python train.py --num_workers 8 --wandb_key=*** --HF_TOKEN ***",
            task.commandLine
        )
    }

    @Test
    fun `advanced query masks credentials before returning`() {
        insertTask(hoursAgo = 1)
        val request = GpuTaskQueryRequest(
            filters = listOf(QueryFilter(QueryField.PROJECT_NAME, QueryOperator.EQUALS, TEST_PROJECT)),
            pagination = Pagination(1, 20, SortField.TASK_START_TIME, SortOrder.DESC)
        )

        val response = service.queryGpuTasks(request)

        assertTrue(response.data.isNotEmpty(), "测试数据应出现在查询结果中")
        assertTrue(
            response.data.all { !it.commandLine.contains("abc123") && it.commandLine.contains("--wandb_key=***") },
            "高级查询返回前必须脱敏，实际: ${response.data.map { it.commandLine }}"
        )
    }

    @Test
    fun `sleep analysis masks credentials in late night tasks`() {
        // 熬夜窗口是本地时间 00:00-04:00
        val lateNight = LocalDateTime.now()
            .withHour(2).withMinute(30).withSecond(0).withNano(0)
            .atZone(ZoneId.systemDefault()).toEpochSecond()
        val task = GpuTaskInfoModel().apply {
            taskId = "sanitize-test-late"
            taskUser = "tester"
            taskStartTime = lateNight
            commandLine = secretCommand
        }

        val analysis = statisticsService.getSleepAnalysis(listOf(task), 0L, System.currentTimeMillis() / 1000)

        assertEquals(1, analysis.lateNightTasks.size, "构造的凌晨任务应被判为熬夜任务")
        assertEquals(
            "python train.py --num_workers 8 --wandb_key=*** --HF_TOKEN ***",
            analysis.lateNightTasks.first().commandLine
        )
    }

    @Test
    fun `masking never writes back to the database`() {
        // 脱敏只发生在返回边界：库里必须仍是原始命令，否则运维会永远看不到真实凭据，
        // 也就无法排查「key 失效」这类问题
        val taskId = insertTask(hoursAgo = 1)
        service.getRecentTasks(24)
        service.getTaskByTaskId(taskId)

        val stored = mapper.selectOne(QueryWrapper<GpuTaskInfoModel>().eq("task_id", taskId))
        assertEquals(secretCommand, stored!!.commandLine, "脱敏不得回写数据库")
    }
}
