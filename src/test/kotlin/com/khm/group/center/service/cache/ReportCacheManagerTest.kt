package com.khm.group.center.service.cache

import com.khm.group.center.datatype.statistics.Report
import com.khm.group.center.datatype.statistics.ReportType
import com.khm.group.center.test.H2DatabaseTest
import com.khm.group.center.utils.program.Slf4jKt
import com.khm.group.center.utils.program.Slf4jKt.Companion.logger
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertInstanceOf
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertSame
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import java.nio.file.Files
import java.time.Duration
import java.time.LocalDate
import java.time.LocalDateTime
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.TimeUnit

/**
 * 报告缓存管理器测试
 */
@H2DatabaseTest
class ReportCacheManagerTest {

    @Autowired
    private lateinit var reportCacheManager: ReportCacheManager

    @Test
    fun testMemoryCache() {
        logger.info("测试内存缓存功能")
        
        val testKey = "test_memory_cache"
        val testData = "测试数据"
        
        // 存储数据到缓存
        reportCacheManager.putCachedData(testKey, testData)
        
        // 从缓存获取数据
        val cachedData: String? = reportCacheManager.getCachedData(testKey)
        assert(cachedData == testData) { "内存缓存数据不匹配" }
        
        logger.info("✅ 内存缓存测试通过")
    }

    @Test
    fun testDiskCache() {
        logger.info("测试磁盘缓存功能")
        
        val testKey = "test_disk_cache"
        val now = LocalDateTime.now()
        val today = LocalDate.now()
        val yesterday = today.minusDays(1)
        
        val testData = Report(
            reportType = ReportType.TODAY,
            title = "测试报告",
            periodStartDate = yesterday,
            periodEndDate = today,
            startTime = now.minusDays(1),
            endTime = now,
            actualTaskStartTime = now.minusDays(1),
            actualTaskEndTime = now,
            totalTasks = 100,
            totalRuntime = 3600,
            activeUsers = 5,
            topUsers = emptyList(),
            topGpus = emptyList(),
            topProjects = emptyList(),
            sleepAnalysis = null
        )
        
        // 存储数据到缓存（应该会持久化到磁盘）
        reportCacheManager.putCachedData(testKey, testData)
        
        // 从缓存获取数据
        val cachedData: Report? = reportCacheManager.getCachedData(testKey)
        assert(cachedData != null) { "磁盘缓存数据为空" }
        assert(cachedData?.title == testData.title) { "磁盘缓存数据不匹配" }
        
        logger.info("✅ 磁盘缓存测试通过")
    }

    @Test
    fun testCacheExpiry() {
        logger.info("测试缓存过期功能")
        
        val testKey = "test_expiry_cache"
        val testData = "过期测试数据"
        
        // 存储数据到缓存
        reportCacheManager.putCachedData(testKey, testData)
        
        // 立即获取应该能命中
        val cachedData1: String? = reportCacheManager.getCachedData(testKey)
        assert(cachedData1 == testData) { "缓存过期测试失败 - 第一次获取" }
        
        // 模拟等待（这里只是测试逻辑，实际过期时间由配置决定）
        Thread.sleep(100)
        
        // 清理过期缓存（这里应该不会清理，因为还没过期）
        val cleanedCount = reportCacheManager.cleanupExpiredCache()
        logger.info("清理过期缓存数量: $cleanedCount")
        
        logger.info("✅ 缓存过期测试通过")
    }

    @Test
    fun testCacheClear() {
        logger.info("测试缓存清理功能")
        
        val testKey = "test_clear_cache"
        val testData = "清理测试数据"
        
        // 存储数据到缓存
        reportCacheManager.putCachedData(testKey, testData)
        
        // 验证数据存在
        val cachedData1: String? = reportCacheManager.getCachedData(testKey)
        assert(cachedData1 == testData) { "缓存清理测试失败 - 清理前获取" }
        
        // 清理指定缓存
        reportCacheManager.clearCache(testKey)
        
        // 验证数据已被清理
        val cachedData2: String? = reportCacheManager.getCachedData(testKey)
        assert(cachedData2 == null) { "缓存清理测试失败 - 清理后获取" }
        
        logger.info("✅ 缓存清理测试通过")
    }

    @Test
    fun testCacheStats() {
        logger.info("测试缓存统计功能")
        
        // 获取缓存统计信息
        val stats = reportCacheManager.getCacheStats()
        
        logger.info("缓存统计信息:")
        logger.info("- 内存缓存条目数: ${stats.memoryEntryCount}")
        logger.info("- 磁盘缓存文件数: ${stats.diskFileCount}")
        logger.info("- 磁盘缓存大小: ${stats.diskSizeBytes} 字节")
        
        assert(stats.memoryEntryCount >= 0) { "内存缓存条目数异常" }
        assert(stats.diskFileCount >= 0) { "磁盘缓存文件数异常" }
        assert(stats.diskSizeBytes >= 0) { "磁盘缓存大小异常" }
        
        logger.info("✅ 缓存统计测试通过")
    }

    @Test
    fun testPathManager() {
        logger.info("测试路径管理器功能")
        
        // 测试缓存目录路径
        val cacheRoot = ReportCachePathManager.getCacheRootPath()
        logger.info("缓存根目录: $cacheRoot")
        
        // 测试确保目录存在（使用正确的方法名）
        val dirCreated = ReportCachePathManager.ensureCacheDirectories()
        assert(dirCreated) { "Cache directory creation failed" }
        
        // 测试各种报告路径生成
        val todayPath = ReportCachePathManager.getTodayReportPath()
        logger.info("今日报告路径: $todayPath")
        
        val yesterdayPath = ReportCachePathManager.getYesterdayReportPath()
        logger.info("昨日报告路径: $yesterdayPath")
        
        // 测试周报路径（需要提供参数）
        val weeklyPath = ReportCachePathManager.getWeeklyReportPath(2025, 39)
        logger.info("周报路径: $weeklyPath")
        
        // 测试月报路径（需要提供参数）
        val monthlyPath = ReportCachePathManager.getMonthlyReportPath(2025, 9)
        logger.info("月报路径: $monthlyPath")
        
        // 测试年报路径（需要提供参数）
        val yearlyPath = ReportCachePathManager.getYearlyReportPath(2025)
        logger.info("年报路径: $yearlyPath")
        
        val hourlyPath = ReportCachePathManager.getHourlyReportPath(24, "2025-09-25-15-00", "2025-09-26-15-00")
        logger.info("24小时报告路径: $hourlyPath")
        
        logger.info("✅ 路径管理器测试通过")
    }

    /**
     * 缺陷1回归：daily_report_* 过去没被 loadFromDisk 的 when 分支接住，落到 else 分支
     * 反序列化出 JSONObject 而不是 Report，调用方类型判否后每次都清缓存重算。
     */
    @Test
    fun testDailyReportLoadedFromDiskIsReport() {
        logger.info("测试日报从磁盘加载后的类型")
        
        val date = LocalDate.now().minusDays(7)
        val cacheKey = "daily_report_$date"
        val report = buildTestReport("历史日报")
        
        // ReportCacheManager 无外部依赖，直接 new 一个即可保证内存缓存为空，只能走磁盘分支
        val writer = ReportCacheManager()
        writer.putCachedData(cacheKey, report)
        val cacheFile = ReportCachePathManager.getStatisticsPath(cacheKey)
        assert(Files.exists(cacheFile)) { "历史日报应当持久化到磁盘: $cacheFile" }
        
        val reader = ReportCacheManager()
        val cached = reader.getCachedData<Any>(cacheKey)
        
        val restored = assertInstanceOf(Report::class.java, cached, "从磁盘加载的日报应当是 Report，而不是 JSONObject")
        assert(restored.title == report.title) { "日报标题不匹配: ${restored.title}" }
        assert(restored.totalTasks == report.totalTasks) { "日报任务数不匹配: ${restored.totalTasks}" }
        assert(restored.periodStartDate == report.periodStartDate) { "日报统计开始日期不匹配: ${restored.periodStartDate}" }
        
        logger.info("✅ 日报磁盘缓存类型测试通过")
        writer.clearCache(cacheKey)
    }

    /**
     * 缺陷1回归：日期范围形式的日报 key（daily_report_开始日_结束日）同样必须走 Report 反序列化
     */
    @Test
    fun testDailyReportRangeLoadedFromDiskIsReport() {
        logger.info("测试日期范围日报从磁盘加载后的类型")
        
        val startDate = LocalDate.now().minusDays(14)
        val endDate = LocalDate.now().minusDays(8)
        val cacheKey = "daily_report_${startDate}_${endDate}"
        val report = buildTestReport("范围日报")
        
        val writer = ReportCacheManager()
        writer.putCachedData(cacheKey, report)
        assert(Files.exists(ReportCachePathManager.getStatisticsPath(cacheKey))) { "范围日报应当持久化到磁盘" }
        
        val reader = ReportCacheManager()
        val restored = assertInstanceOf(
            Report::class.java, reader.getCachedData<Any>(cacheKey),
            "从磁盘加载的范围日报应当是 Report，而不是 JSONObject"
        )
        assert(restored.title == report.title) { "范围日报标题不匹配: ${restored.title}" }
        
        logger.info("✅ 范围日报磁盘缓存类型测试通过")
        writer.clearCache(cacheKey)
    }

    /**
     * 缺陷2回归：当日日报过去配的是 Long.MAX_VALUE，当天第一份快照全天不刷新。
     * 这里把内存条目的写入时间往前挪 11 分钟，验证当日日报会过期。
     */
    @Test
    fun testTodayDailyReportExpires() {
        logger.info("测试当日日报的短 TTL")
        
        val cacheKey = "daily_report_${LocalDate.now()}"
        val report = buildTestReport("当日日报")
        val manager = ReportCacheManager()
        
        manager.putCachedData(cacheKey, report)
        // 刚写入应当立即命中
        assertSame(report, manager.getCachedData<Any>(cacheKey), "当日日报写入后应当立即命中内存缓存")
        // 当日报告只有内存缓存，不落盘
        assertFalse(Files.exists(ReportCachePathManager.getStatisticsPath(cacheKey)), "当日日报不应当落盘")
        
        // 把写入时间往前挪 11 分钟（超过 10 分钟的短 TTL）
        backdateMemoryEntry(manager, cacheKey, Duration.ofMinutes(11))
        
        assertNull(manager.getCachedData<Any>(cacheKey), "当日日报超过短 TTL 后应当过期并重新统计")
        
        logger.info("✅ 当日日报过期测试通过")
    }

    /**
     * 缺陷2回归：历史日报必须永不过期（往前挪一年仍然命中）
     */
    @Test
    fun testHistoricalDailyReportNeverExpires() {
        logger.info("测试历史日报永不过期")
        
        val cacheKey = "daily_report_${LocalDate.now().minusDays(30)}"
        val report = buildTestReport("历史日报")
        val manager = ReportCacheManager()
        
        manager.putCachedData(cacheKey, report)
        backdateMemoryEntry(manager, cacheKey, Duration.ofDays(365))
        
        // 命中的必须是内存里那同一个实例（从磁盘反序列化会得到新对象）
        assertSame(report, manager.getCachedData<Any>(cacheKey), "历史日报永不过期，一年后仍应当命中内存缓存")
        
        logger.info("✅ 历史日报不过期测试通过")
        manager.clearCache(cacheKey)
    }

    /**
     * 缺陷2的同源回归：当周/当月/当年报告此前和日报一样被配成 NEVER_EXPIRE，
     * 导致当周周报在周一算出后整周不变、当月月报整月不变。
     */
    @Test
    fun testCurrentPeriodWeeklyMonthlyYearlyExpire() {
        logger.info("测试当周/当月/当年报告的短 TTL")

        val now = LocalDate.now()
        val currentWeek = now.get(java.time.temporal.WeekFields.ISO.weekOfYear())

        val keys = listOf(
            "weekly_report_${now.year}_$currentWeek",
            "monthly_report_${now.year}_${now.monthValue}",
            "yearly_report_${now.year}"
        )

        for (key in keys) {
            val report = buildTestReport("当前周期报告-$key")
            val manager = ReportCacheManager()

            manager.putCachedData(key, report)
            assertSame(
                report, manager.getCachedData<Any>(key),
                "当前周期的 $key 写入后应当立即命中"
            )

            backdateMemoryEntry(manager, key, Duration.ofMinutes(11))

            assertNull(
                manager.getCachedData<Any>(key),
                "当前周期的 $key 超过短 TTL 后应当过期并重新统计"
            )
            logger.info("  ✅ $key 会过期")
        }
    }

    /**
     * 缺陷2的反向回归：历史周期的周/月/年报必须仍然永不过期。
     */
    @Test
    fun testHistoricalPeriodReportsNeverExpire() {
        logger.info("测试历史周期的周/月/年报永不过期")

        val past = LocalDate.now().minusYears(1)
        val keys = listOf(
            "weekly_report_${past.year}_1",
            "monthly_report_${past.year}_1",
            "yearly_report_${past.year}"
        )

        for (key in keys) {
            val report = buildTestReport("历史周期报告-$key")
            val manager = ReportCacheManager()

            manager.putCachedData(key, report)
            backdateMemoryEntry(manager, key, Duration.ofDays(400))

            assertSame(
                report, manager.getCachedData<Any>(key),
                "历史周期的 $key 永不过期，一年前仍应当命中内存缓存"
            )
            logger.info("  ✅ $key 不过期")
            manager.clearCache(key)
        }
    }

    /**
     * 构造测试用的最小 Report
     */
    private fun buildTestReport(title: String): Report {
        val now = LocalDateTime.now()
        val today = LocalDate.now()
        return Report(
            reportType = ReportType.CUSTOM,
            title = title,
            periodStartDate = today.minusDays(1),
            periodEndDate = today,
            startTime = now.minusDays(1),
            endTime = now,
            actualTaskStartTime = now.minusDays(1),
            actualTaskEndTime = now,
            totalTasks = 7,
            totalRuntime = 3600,
            activeUsers = 2,
            topUsers = emptyList(),
            topGpus = emptyList(),
            topProjects = emptyList(),
            sleepAnalysis = null
        )
    }

    /**
     * 把内存缓存中某个 key 的写入时间往前挪，用于验证过期逻辑（不用真等 10 分钟）。
     * 内存缓存是 ReportCacheManager 的私有字段，这里反射出一个新的 CacheEntry 替换掉。
     */
    private fun backdateMemoryEntry(manager: ReportCacheManager, cacheKey: String, ago: Duration) {
        val memoryCacheField = ReportCacheManager::class.java.getDeclaredField("memoryCache")
        memoryCacheField.isAccessible = true
        @Suppress("UNCHECKED_CAST")
        val memoryCache = memoryCacheField.get(manager) as ConcurrentHashMap<String, Any>
        
        val oldEntry = memoryCache[cacheKey] ?: error("内存缓存中不存在 key: $cacheKey")
        val entryClass = oldEntry.javaClass
        val data = entryClass.getDeclaredMethod("getData").apply { isAccessible = true }.invoke(oldEntry)
        val expiry = entryClass.getDeclaredMethod("getExpiryTime").apply { isAccessible = true }.invoke(oldEntry)
        val newEntry = entryClass
            .getDeclaredConstructor(Any::class.java, Long::class.javaPrimitiveType, Long::class.javaPrimitiveType)
            .apply { isAccessible = true }
            .newInstance(data, System.currentTimeMillis() - ago.toMillis(), expiry)
        memoryCache[cacheKey] = newEntry
    }
}