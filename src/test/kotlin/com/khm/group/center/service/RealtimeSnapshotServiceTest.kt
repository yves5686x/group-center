package com.khm.group.center.service

import com.khm.group.center.datatype.agent.AgentDiskUsage
import com.khm.group.center.datatype.agent.AgentDiskUsageResponse
import com.khm.group.center.datatype.agent.AgentGpuCount
import com.khm.group.center.datatype.agent.AgentGpuTaskInfoResponse
import com.khm.group.center.datatype.agent.AgentGpuTaskItem
import com.khm.group.center.datatype.agent.AgentGpuUsageInfo
import com.khm.group.center.datatype.agent.AgentSystemInfo
import com.khm.group.center.datatype.config.MachineConfig
import com.khm.group.center.service.agent.NviNotifyAgentClient
import com.khm.group.center.utils.time.DateTimeUtils
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.mockito.Mockito
import org.springframework.test.util.ReflectionTestUtils

/**
 * RealtimeSnapshotService 的 pull-through-cache 行为单测。
 *
 * 用 Mockito 打桩 [NviNotifyAgentClient] 与 [MachineStatusService]，
 * 通过 [ReflectionTestUtils] 注入私有字段，覆盖四种数据来源：
 * agent（实时拉取）/ cache（命中缓存）/ last-known-good（拉取失败回退）/ none（无数据）。
 */
class RealtimeSnapshotServiceTest {

    private lateinit var agentClient: NviNotifyAgentClient
    private lateinit var statusService: MachineStatusService
    private lateinit var service: RealtimeSnapshotService

    /** 控制 agent 是否“在线”：false 时所有 pull 返回 null，模拟 agent 宕机。 */
    private var agentUp = true

    private val machineNameEng = "test"

    @BeforeEach
    fun setUp() {
        val m = MachineConfig()
        m.name = "Test Machine"
        m.nameEng = machineNameEng
        m.apiUrl = "/gpu/test"
        m.isGpu = true
        MachineConfig.machineList = listOf(m)

        agentUp = true
        agentClient = Mockito.mock(NviNotifyAgentClient::class.java)
        statusService = Mockito.mock(MachineStatusService::class.java)
        Mockito.`when`(statusService.isAgentOnline(Mockito.anyString())).thenReturn(false)

        Mockito.`when`(agentClient.getGpuCount(Mockito.anyString())).thenAnswer {
            if (agentUp) AgentGpuCount(result = 1) else null
        }
        Mockito.`when`(
            agentClient.getGpuUsageInfo(Mockito.anyString(), Mockito.anyInt())
        ).thenAnswer {
            if (agentUp) AgentGpuUsageInfo(
                gpuName = "RTX TEST", coreUsage = 42f, memoryUsage = 10f,
                gpuMemoryTotal = "24.00GiB", gpuMemoryTotalMB = 24576,
                gpuPowerUsage = 100f, gpuTDP = 350f, gpuTemperature = 55f
            ) else null
        }
        Mockito.`when`(
            agentClient.getGpuTaskInfo(Mockito.anyString(), Mockito.anyInt())
        ).thenAnswer {
            if (agentUp) AgentGpuTaskInfoResponse(
                taskList = listOf(
                    AgentGpuTaskItem(pid = 123, name = "tester", projectName = "proj", pyFileName = "a.py")
                )
            ) else null
        }
        Mockito.`when`(agentClient.getDiskUsage(Mockito.anyString())).thenAnswer {
            if (agentUp) AgentDiskUsageResponse(
                diskUsage = listOf(AgentDiskUsage(mountPoint = "/", usedPercentage = 50f, totalStr = "100G"))
            ) else null
        }
        Mockito.`when`(agentClient.getSystemInfo(Mockito.anyString())).thenAnswer {
            if (agentUp) AgentSystemInfo(memoryPhysicTotalMb = 1000, memoryPhysicUsedMb = 200) else null
        }

        service = RealtimeSnapshotService()
        ReflectionTestUtils.setField(service, "agentClient", agentClient)
        ReflectionTestUtils.setField(service, "machineStatusService", statusService)
        ReflectionTestUtils.setField(service, "cacheTtlSeconds", 5L)
        ReflectionTestUtils.setField(service, "staleThresholdSeconds", 60L)
        ReflectionTestUtils.setField(service, "maxStaleSeconds", 300L)
        ReflectionTestUtils.setField(service, "maxGpuCount", 32)
    }

    @Test
    fun `first pull hits agent then second call within TTL hits cache`() {
        val v1 = service.buildGpuView(machineNameEng)
        assertEquals("agent", v1.source)
        assertFalse(v1.stale)
        assertEquals(1, v1.gpuCount)
        assertEquals(1, v1.snapshot.size)
        assertEquals("RTX TEST", v1.snapshot[0].gpuName)
        assertTrue(v1.snapshot[0].hasData)
        assertEquals(1, v1.snapshot[0].tasks.size)
        assertEquals("tester", v1.snapshot[0].tasks[0].name)

        val v2 = service.buildGpuView(machineNameEng)
        assertEquals("cache", v2.source)
        assertEquals(1, v2.gpuCount)

        // TTL 命中期内 agent 只被拉取一次
        Mockito.verify(agentClient, Mockito.times(1)).getGpuCount(Mockito.anyString())
    }

    @Test
    fun `agent down after cache serves last-known-good`() {
        // TTL = -1 强制每次都尝试重新拉取
        ReflectionTestUtils.setField(service, "cacheTtlSeconds", -1L)

        val v1 = service.buildGpuView(machineNameEng)
        assertEquals("agent", v1.source)

        agentUp = false
        val v2 = service.buildGpuView(machineNameEng)
        assertEquals("last-known-good", v2.source)
        assertNotNull(v2.error)
        // 回退仍返回上一次成功的快照
        assertEquals(1, v2.gpuCount)
        assertEquals("RTX TEST", v2.snapshot[0].gpuName)
    }

    @Test
    fun `agent down without cache returns none and stale`() {
        agentUp = false
        val v = service.buildGpuView(machineNameEng)
        assertEquals("none", v.source)
        assertTrue(v.stale)
        assertEquals(0, v.gpuCount)
        assertTrue(v.snapshot.isEmpty())
        assertEquals(0L, v.snapshotTime)
        assertEquals(-1L, v.freshness)
    }

    @Test
    fun `unknown machine returns none gracefully`() {
        val v = service.buildGpuView("does-not-exist")
        assertEquals("none", v.source)
        assertTrue(v.stale)
        assertNotNull(v.error)
        assertTrue(v.error!!.contains("unknown machine"))
    }

    @Test
    fun `disk view maps disk and system memory`() {
        val d = service.buildDiskView(machineNameEng)
        assertEquals("agent", d.source)
        assertEquals(1, d.snapshot.size)
        assertEquals("/", d.snapshot[0].mountPoint)
        assertNotNull(d.system)
        assertEquals(1000L, d.system!!.memoryPhysicTotalMb)
        assertEquals(200L, d.system!!.memoryPhysicUsedMb)
    }

    @Test
    fun `machine list does not trigger a pull`() {
        val list = service.buildMachineList()
        assertEquals(1, list.size)
        assertEquals(machineNameEng, list[0].serverNameEng)
        assertFalse(list[0].agentOnline)
        assertTrue(list[0].hasApiUrl)
        assertTrue(list[0].isGpu)
        // 列表接口不应触发对 agent 的实时拉取
        Mockito.verify(agentClient, Mockito.never()).getGpuCount(Mockito.anyString())
    }

    // ==================== last-known-good 硬上限 ====================

    @Test
    fun `agent down serves last-known-good within max stale window`() {
        ReflectionTestUtils.setField(service, "cacheTtlSeconds", -1L)

        val v1 = service.buildGpuView(machineNameEng)
        assertEquals("agent", v1.source)

        agentUp = false
        val v2 = service.buildGpuView(machineNameEng)
        assertEquals("last-known-good", v2.source)
        assertEquals(1, v2.gpuCount)
    }

    @Test
    fun `agent down with over-age cache returns none instead of stale snapshot`() {
        // TTL = -1 强制每次都重新拉取；max-stale = -1 令回退窗口必然关闭
        ReflectionTestUtils.setField(service, "cacheTtlSeconds", -1L)
        ReflectionTestUtils.setField(service, "maxStaleSeconds", -1L)

        val v1 = service.buildGpuView(machineNameEng)
        assertEquals("agent", v1.source)

        agentUp = false
        val v2 = service.buildGpuView(machineNameEng)
        // 缓存存在但已超过硬上限：不得回退到旧快照
        assertEquals("none", v2.source)
        assertTrue(v2.snapshot.isEmpty())
        assertTrue(v2.error!!.contains("too old"))
    }

    @Test
    fun `no apiUrl with over-age cache returns none`() {
        ReflectionTestUtils.setField(service, "maxStaleSeconds", -1L)
        val m = MachineConfig.machineList[0]
        m.apiUrl = ""
        try {
            val v = service.buildGpuView(machineNameEng)
            assertEquals("none", v.source)
            assertEquals(0, v.gpuCount)
        } finally {
            m.apiUrl = "/gpu/test"
        }
    }

    // ==================== 半挂 agent 不得被当成成功 ====================

    @Test
    fun `only gpu_count succeeding is not treated as a successful pull`() {
        // gpu_count 通、其余全挂：旧实现会判 anySuccess=true，
        // 输出一组 hasData=false 的空卡并标 source=agent / stale=false
        ReflectionTestUtils.setField(service, "cacheTtlSeconds", -1L)

        Mockito.`when`(agentClient.getGpuUsageInfo(Mockito.anyString(), Mockito.anyInt()))
            .thenReturn(null)
        Mockito.`when`(agentClient.getGpuTaskInfo(Mockito.anyString(), Mockito.anyInt()))
            .thenReturn(null)
        Mockito.`when`(agentClient.getDiskUsage(Mockito.anyString())).thenReturn(null)
        Mockito.`when`(agentClient.getSystemInfo(Mockito.anyString())).thenReturn(null)

        val v = service.buildGpuView(machineNameEng)
        assertEquals("none", v.source)
        assertTrue(v.snapshot.isEmpty())
    }

    @Test
    fun `system info alone counts as a successful pull`() {
        ReflectionTestUtils.setField(service, "cacheTtlSeconds", -1L)

        Mockito.`when`(agentClient.getGpuUsageInfo(Mockito.anyString(), Mockito.anyInt()))
            .thenReturn(null)
        Mockito.`when`(agentClient.getGpuTaskInfo(Mockito.anyString(), Mockito.anyInt()))
            .thenReturn(null)
        Mockito.`when`(agentClient.getDiskUsage(Mockito.anyString())).thenReturn(null)
        Mockito.`when`(agentClient.getSystemInfo(Mockito.anyString()))
            .thenReturn(AgentSystemInfo(memoryPhysicTotalMb = 2048, memoryPhysicUsedMb = 512))

        val d = service.buildDiskView(machineNameEng)
        assertEquals("agent", d.source)
        assertNotNull(d.system)
        assertEquals(2048L, d.system!!.memoryPhysicTotalMb)
    }

    // ==================== gpuCount 放大防护 ====================

    @Test
    fun `implausible gpu count from agent is clamped`() {
        ReflectionTestUtils.setField(service, "maxGpuCount", 4)
        Mockito.`when`(agentClient.getGpuCount(Mockito.anyString()))
            .thenReturn(AgentGpuCount(result = 8000))

        val v = service.buildGpuView(machineNameEng)
        // 被截断到 4，因此只会为 4 张卡发起拉取
        assertEquals(4, v.gpuCount)
        assertEquals(4, v.snapshot.size)
        Mockito.verify(agentClient, Mockito.times(4))
            .getGpuUsageInfo(Mockito.anyString(), Mockito.anyInt())
    }

    // ==================== single-flight ====================

    @Test
    fun `concurrent misses on same machine collapse into a single pull`() {
        // 保持默认 TTL=5s（生产值），并让拉取变慢，使 8 个线程真正重叠。
        // 场景：一次拉取耗时超过 TTL 时，原本每个请求都会各自全量拉一遍；
        // 引入 per-key 锁 + 双检后，后来者拿到锁时缓存已新鲜，直接命中。
        Mockito.`when`(agentClient.getGpuCount(Mockito.anyString())).thenAnswer {
            Thread.sleep(80)
            AgentGpuCount(result = 1)
        }

        val threads = (1..8).map { Thread { service.buildGpuView(machineNameEng) } }
        val start = System.nanoTime()
        threads.forEach { it.start() }
        threads.forEach { it.join() }
        val elapsedMs = (System.nanoTime() - start) / 1_000_000

        Mockito.verify(agentClient, Mockito.times(1)).getGpuCount(Mockito.anyString())
        // 若没有单飞，8 次串行拉取至少需要 8×80ms
        assertTrue(elapsedMs < 8 * 80, "8 个并发请求耗时 ${elapsedMs}ms，疑似未合并为单次拉取")
    }

    @Test
    fun `cached snapshot timestamp reflects pull completion not start`() {
        val v1 = service.buildGpuView(machineNameEng)
        assertEquals("agent", v1.source)
        // pulledAt 取的是拉取完成时刻，因此必然不早于调用开始的那一刻
        assertTrue(
            v1.snapshotTime >= DateTimeUtils.getCurrentTimestamp() - 5,
            "pulledAt 应为拉取完成时刻，实际=${v1.snapshotTime}"
        )
        assertTrue(v1.freshness >= 0)
    }
}
