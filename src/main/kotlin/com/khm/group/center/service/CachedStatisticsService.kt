package com.khm.group.center.service

import com.khm.group.center.datatype.statistics.*
import com.khm.group.center.db.model.client.GpuTaskInfoModel
import com.khm.group.center.db.query.GpuTaskQuery
import com.khm.group.center.service.cache.ReportCacheManager
import com.khm.group.center.utils.time.RoundedHourUtils
import com.khm.group.center.utils.time.TimePeriod
import org.slf4j.LoggerFactory
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.stereotype.Service
import java.time.LocalDate
import java.time.ZoneId

/**
 * 缓存统计服务 - 包装基础服务并添加缓存逻辑
 * 现有统计API使用此服务
 */
@Service
class CachedStatisticsService {

    @Autowired
    private lateinit var baseStatisticsService: BaseStatisticsService

    @Autowired
    private lateinit var gpuTaskQuery: GpuTaskQuery

    @Autowired
    private lateinit var reportCacheManager: ReportCacheManager

    private val logger = LoggerFactory.getLogger(CachedStatisticsService::class.java)

    /**
     * 获取用户统计信息（带缓存）
     */
    fun getUserStatistics(timePeriod: TimePeriod): List<UserStatistics> {
        val cacheKey = "user_stats_v2_${timePeriod.name}"
        
        // 尝试从缓存获取
        val cached: List<UserStatistics>? = reportCacheManager.getCachedData(cacheKey)
        if (cached != null) {
            logger.debug("从缓存获取用户统计：$timePeriod")
            return cached
        }

        logger.info("缓存未命中，重新计算用户统计：$timePeriod")
        val tasks = gpuTaskQuery.queryTasks(timePeriod)
        val stats = baseStatisticsService.getUserStatistics(tasks)

        // 存储到缓存
        reportCacheManager.putCachedData(cacheKey, stats)
        return stats
    }

    /**
     * 获取GPU统计信息（带缓存）
     */
    fun getGpuStatistics(timePeriod: TimePeriod): List<GpuStatistics> {
        val cacheKey = "gpu_stats_v2_${timePeriod.name}"
        
        // 尝试从缓存获取
        val cached: List<GpuStatistics>? = reportCacheManager.getCachedData(cacheKey)
        if (cached != null) {
            logger.debug("从缓存获取GPU统计：$timePeriod")
            return cached
        }

        logger.info("缓存未命中，重新计算GPU统计：$timePeriod")
        val tasks = gpuTaskQuery.queryTasks(timePeriod)
        val stats = baseStatisticsService.getGpuStatistics(tasks)

        // 存储到缓存
        reportCacheManager.putCachedData(cacheKey, stats)
        return stats
    }

    /**
     * 获取服务器统计信息（带缓存）
     */
    fun getServerStatistics(timePeriod: TimePeriod): List<ServerStatistics> {
        val cacheKey = "server_stats_v2_${timePeriod.name}"
        
        // 尝试从缓存获取
        val cached: List<ServerStatistics>? = reportCacheManager.getCachedData(cacheKey)
        if (cached != null) {
            logger.debug("从缓存获取服务器统计：$timePeriod")
            return cached
        }

        logger.info("缓存未命中，重新计算服务器统计：$timePeriod")
        val tasks = gpuTaskQuery.queryTasks(timePeriod)
        val stats = baseStatisticsService.getServerStatistics(tasks)

        // 存储到缓存
        reportCacheManager.putCachedData(cacheKey, stats)
        return stats
    }

    /**
     * 获取项目统计信息（带缓存）
     */
    fun getProjectStatistics(timePeriod: TimePeriod): List<ProjectStatistics> {
        val cacheKey = "project_stats_${timePeriod.name}"
        
        // 尝试从缓存获取
        val cached: List<ProjectStatistics>? = reportCacheManager.getCachedData(cacheKey)
        if (cached != null) {
            logger.debug("从缓存获取项目统计：$timePeriod")
            return cached
        }

        logger.info("缓存未命中，重新计算项目统计：$timePeriod")
        val tasks = gpuTaskQuery.queryTasks(timePeriod)
        val stats = baseStatisticsService.getProjectStatistics(tasks)

        // 存储到缓存
        reportCacheManager.putCachedData(cacheKey, stats)
        return stats
    }

    /**
     * 获取时间趋势统计信息（带缓存）
     */
    fun getTimeTrendStatistics(timePeriod: TimePeriod): TimeTrendStatistics {
        val cacheKey = "time_trend_${timePeriod.name}"
        
        // 尝试从缓存获取
        val cached: TimeTrendStatistics? = reportCacheManager.getCachedData(cacheKey)
        if (cached != null) {
            logger.debug("从缓存获取时间趋势统计：$timePeriod")
            return cached
        }

        logger.info("缓存未命中，重新计算时间趋势统计：$timePeriod")
        val tasks = gpuTaskQuery.queryTasks(timePeriod)
        val stats = baseStatisticsService.getTimeTrendStatistics(tasks, timePeriod)

        // 存储到缓存
        reportCacheManager.putCachedData(cacheKey, stats)
        return stats
    }

    /**
     * 获取24小时报告数据（带缓存，向后取整整小时）
     * 例如：现在是14:10，统计昨天15:00到今天15:00
     */
    fun get24HourReport(): Report {
        val (startTime, endTime) = RoundedHourUtils.getRoundedHourRange(24)
        val cacheKey = "24hour_report_${startTime}_${endTime}"
        
        // 尝试从缓存获取
        val cached: Any? = reportCacheManager.getCachedData(cacheKey)
        if (cached != null) {
            if (cached is Report) {
                logger.info("✅ 缓存命中，从缓存获取24小时报告（时间范围：${startTime} - ${endTime}）")
                return cached
            } else {
                logger.warn("⚠️ 缓存数据类型错误，期望Report但获取到: ${cached.javaClass.name}，将重新计算")
                // 清除错误的缓存
                reportCacheManager.clearCache(cacheKey)
            }
        }

        logger.info("🔄 缓存未命中，重新计算24小时报告（时间范围：${startTime} - ${endTime}）")

        val tasks = gpuTaskQuery.queryTasks(
            timePeriod = TimePeriod.ONE_DAY,
            startTime = startTime,
            endTime = endTime
        )

        val report = baseStatisticsService.generate24HourReport(tasks, startTime, endTime)

        // 存储到缓存
        reportCacheManager.putCachedData(cacheKey, report)
        logger.info("💾 新24小时报告已缓存（时间范围：${startTime} - ${endTime}）")
        
        return report
    }

    /**
     * 获取48小时报告数据（带缓存，向后取整整小时）
     * 例如：现在是14:10，统计前天15:00到今天15:00
     */
    fun get48HourReport(): Report {
        val (startTime, endTime) = RoundedHourUtils.getRoundedHourRange(48)
        val cacheKey = "48hour_report_${startTime}_${endTime}"
        
        // 尝试从缓存获取
        val cached: Any? = reportCacheManager.getCachedData(cacheKey)
        if (cached != null) {
            if (cached is Report) {
                logger.info("✅ 缓存命中，从缓存获取48小时报告（时间范围：${startTime} - ${endTime}）")
                return cached
            } else {
                logger.warn("⚠️ 缓存数据类型错误，期望Report但获取到: ${cached.javaClass.name}，将重新计算")
                // 清除错误的缓存
                reportCacheManager.clearCache(cacheKey)
            }
        }

        logger.info("🔄 缓存未命中，重新计算48小时报告（时间范围：${startTime} - ${endTime}）")

        val tasks = gpuTaskQuery.queryTasks(
            timePeriod = TimePeriod.ONE_DAY,
            startTime = startTime,
            endTime = endTime
        )

        val report = baseStatisticsService.generate24HourReport(tasks, startTime, endTime)

        // 存储到缓存
        reportCacheManager.putCachedData(cacheKey, report)
        logger.info("💾 新48小时报告已缓存（时间范围：${startTime} - ${endTime}）")
        
        return report
    }

    /**
     * 获取72小时报告数据（带缓存，向后取整整小时）
     * 例如：现在是14:10，统计大前天15:00到今天15:00
     */
    fun get72HourReport(): Report {
        val (startTime, endTime) = RoundedHourUtils.getRoundedHourRange(72)
        val cacheKey = "72hour_report_${startTime}_${endTime}"
        
        // 尝试从缓存获取
        val cached: Any? = reportCacheManager.getCachedData(cacheKey)
        if (cached != null) {
            if (cached is Report) {
                logger.info("✅ 缓存命中，从缓存获取72小时报告（时间范围：${startTime} - ${endTime}）")
                return cached
            } else {
                logger.warn("⚠️ 缓存数据类型错误，期望Report但获取到: ${cached.javaClass.name}，将重新计算")
                // 清除错误的缓存
                reportCacheManager.clearCache(cacheKey)
            }
        }

        logger.info("🔄 缓存未命中，重新计算72小时报告（时间范围：${startTime} - ${endTime}）")

        val tasks = gpuTaskQuery.queryTasks(
            timePeriod = TimePeriod.ONE_DAY,
            startTime = startTime,
            endTime = endTime
        )

        val report = baseStatisticsService.generate24HourReport(tasks, startTime, endTime)

        // 存储到缓存
        reportCacheManager.putCachedData(cacheKey, report)
        logger.info("💾 新72小时报告已缓存（时间范围：${startTime} - ${endTime}）")
        
        return report
    }

    /**
     * 获取今日日报数据（带缓存，整点时间范围：今天0:00到明天0:00）
     */
     fun getTodayReport(): Report {
         return getDailyReport(LocalDate.now())
     }
  
     /**
      * 获取昨日日报数据（带缓存，整点时间范围：昨天0:00到今天0:00）
      */
     fun getYesterdayReport(): Report {
         return getDailyReport(LocalDate.now().minusDays(1))
     }

    /**
     * 获取日报数据（带缓存）
     * @param date 日期（可选，默认昨天）
     */
    fun getDailyReport(date: LocalDate? = null): Report {
        val targetDate = date ?: LocalDate.now().minusDays(1)
        
        // 检查未来日期，如果出现未来日期就回退到今日
        val validatedDate = validateDate(targetDate)
        val cacheKey = "daily_report_${validatedDate}"
          
        // 尝试从缓存获取
        val cached: Any? = reportCacheManager.getCachedData(cacheKey)
        if (cached != null) {
            if (cached is Report) {
                logger.info("✅ 缓存命中，从缓存获取日报（${validatedDate}）")
                return cached
            } else {
                logger.warn("⚠️ 缓存数据类型错误，期望Report但获取到: ${cached.javaClass.name}，将重新计算")
                // 清除错误的缓存
                reportCacheManager.clearCache(cacheKey)
            }
        }
  
        logger.info("🔄 缓存未命中，重新计算日报（${validatedDate}）")
          
        val tasks = gpuTaskQuery.queryTasks(
            timePeriod = TimePeriod.ONE_DAY,
            startTime = validatedDate.atStartOfDay(ZoneId.systemDefault()).toEpochSecond(),
            endTime = validatedDate.atTime(23, 59, 59).atZone(ZoneId.systemDefault()).toEpochSecond()
        )
        val report = baseStatisticsService.generateDailyReport(tasks, validatedDate)
  
        // 存储到缓存
        reportCacheManager.putCachedData(cacheKey, report)
        logger.info("💾 新日报已缓存（${validatedDate}）")
        return report
    }

    /**
     * 获取指定日期范围的日报数据（带缓存）
     * @param startDate 开始日期
     * @param endDate 结束日期
     */
    fun getDailyReport(startDate: LocalDate, endDate: LocalDate): Report {
        val cacheKey = "daily_report_${startDate}_${endDate}"
          
        // 尝试从缓存获取
        val cached: Any? = reportCacheManager.getCachedData(cacheKey)
        if (cached != null) {
            if (cached is Report) {
                logger.info("✅ 缓存命中，从缓存获取日报（${startDate} - ${endDate}）")
                return cached
            } else {
                logger.warn("⚠️ 缓存数据类型错误，期望Report但获取到: ${cached.javaClass.name}，将重新计算")
                // 清除错误的缓存
                reportCacheManager.clearCache(cacheKey)
            }
        }
  
        logger.info("🔄 缓存未命中，重新计算日报（${startDate} - ${endDate}）")
          
        val tasks = gpuTaskQuery.queryTasks(
            timePeriod = TimePeriod.ONE_DAY,
            startTime = startDate.atStartOfDay(ZoneId.systemDefault()).toEpochSecond(),
            endTime = endDate.atTime(23, 59, 59).atZone(ZoneId.systemDefault()).toEpochSecond()
        )
        val report = baseStatisticsService.generateDailyReport(tasks, startDate, endDate)
  
        // 存储到缓存（历史数据永不过期）
        reportCacheManager.putCachedData(cacheKey, report)
        logger.info("💾 新日报已缓存（${startDate} - ${endDate}）")
        return report
    }

    /**
     * 获取周报数据（带缓存）
     * @param year 年份（可选，默认当前年）
     * @param week 周数（可选，默认上周）
     */
     fun getWeeklyReport(year: Int? = null, week: Int? = null): Report {
         return if (year != null || week != null) {
             // 如果指定了年份或周数，使用指定周的报告
             val targetYear = year ?: LocalDate.now().year
             val targetWeek = week ?: LocalDate.now().get(java.time.temporal.WeekFields.ISO.weekOfYear())
             
             // 检查未来周，如果出现未来周就回退到本周
             val (validatedYear, validatedWeek) = validateWeek(targetYear, targetWeek)
             val cacheKey = "weekly_report_${validatedYear}_${validatedWeek}"
             
             // 尝试从缓存获取
             val cached: Report? = reportCacheManager.getCachedData(cacheKey)
             if (cached != null) {
                 logger.info("✅ 缓存命中，从缓存获取周报（${validatedYear}年第${validatedWeek}周）")
                 return cached
             }
 
             logger.info("🔄 缓存未命中，重新计算周报（${validatedYear}年第${validatedWeek}周）")
             
             val tasks = gpuTaskQuery.queryTasks(
                 timePeriod = TimePeriod.ONE_WEEK,
                 startTime = getWeekStartTime(validatedYear, validatedWeek),
                 endTime = getWeekEndTime(validatedYear, validatedWeek)
             )
             val report = baseStatisticsService.generateWeeklyReport(tasks, validatedYear, validatedWeek)
 
             // 存储到缓存（历史数据永不过期）
             reportCacheManager.putCachedData(cacheKey, report)
             logger.info("💾 新周报已缓存（${validatedYear}年第${validatedWeek}周）")
             report
         } else {
             // 如果没有指定年份和周数，默认生成上周的报告
             val lastWeek = LocalDate.now().minusWeeks(1)
             val targetYear = lastWeek.year
             val targetWeek = lastWeek.get(java.time.temporal.WeekFields.ISO.weekOfYear())
             val cacheKey = "weekly_report_${targetYear}_${targetWeek}"
             
             // 尝试从缓存获取
             val cached: Report? = reportCacheManager.getCachedData(cacheKey)
             if (cached != null) {
                 logger.info("✅ 缓存命中，从缓存获取周报（${targetYear}年第${targetWeek}周）")
                 return cached
             }
 
             logger.info("🔄 缓存未命中，重新计算周报（${targetYear}年第${targetWeek}周）")
             
             val tasks = gpuTaskQuery.queryTasks(
                 timePeriod = TimePeriod.ONE_WEEK,
                 startTime = getWeekStartTime(targetYear, targetWeek),
                 endTime = getWeekEndTime(targetYear, targetWeek)
             )
             val report = baseStatisticsService.generateWeeklyReport(tasks, targetYear, targetWeek)
 
             // 存储到缓存（历史数据永不过期）
             reportCacheManager.putCachedData(cacheKey, report)
             logger.info("💾 新周报已缓存（${targetYear}年第${targetWeek}周）")
             report
         }
     }
    /**
     * 获取月报数据（带缓存）
     * @param year 年份（可选，默认当前年）
     * @param month 月份（可选，默认上个月）
     */
    fun getMonthlyReport(year: Int? = null, month: Int? = null): Report {
        return if (year != null || month != null) {
            // 如果指定了年份或月份，使用指定月份的报告
            val targetYear = year ?: LocalDate.now().year
            val targetMonth = month ?: LocalDate.now().monthValue
            
            // 检查未来月份，如果出现未来月份就回退到本月
            val (validatedYear, validatedMonth) = validateMonth(targetYear, targetMonth)
            val cacheKey = "monthly_report_${validatedYear}_${validatedMonth}"
            
            // 尝试从缓存获取
            val cached: Report? = reportCacheManager.getCachedData(cacheKey)
            if (cached != null) {
                logger.info("✅ 缓存命中，从缓存获取月报（${validatedYear}年${validatedMonth}月）")
                return cached
            }

            logger.info("🔄 缓存未命中，重新计算月报（${validatedYear}年${validatedMonth}月）")
            
            val tasks = gpuTaskQuery.queryTasks(
                timePeriod = TimePeriod.ONE_MONTH,
                startTime = getMonthStartTime(validatedYear, validatedMonth),
                endTime = getMonthEndTime(validatedYear, validatedMonth)
            )
            val report = baseStatisticsService.generateMonthlyReport(tasks, validatedYear, validatedMonth)

            // 存储到缓存（历史数据永不过期）
            reportCacheManager.putCachedData(cacheKey, report)
            logger.info("💾 新月报已缓存（${validatedYear}年${validatedMonth}月）")
            report
        } else {
            // 如果没有指定年份和月份，默认生成上个月的报告
            val lastMonth = LocalDate.now().minusMonths(1)
            val targetYear = lastMonth.year
            val targetMonth = lastMonth.monthValue
            val cacheKey = "monthly_report_${targetYear}_${targetMonth}"
            
            // 尝试从缓存获取
            val cached: Report? = reportCacheManager.getCachedData(cacheKey)
            if (cached != null) {
                logger.info("✅ 缓存命中，从缓存获取月报（${targetYear}年${targetMonth}月）")
                return cached
            }

            logger.info("🔄 缓存未命中，重新计算月报（${targetYear}年${targetMonth}月）")
            
            val tasks = gpuTaskQuery.queryTasks(
                timePeriod = TimePeriod.ONE_MONTH,
                startTime = getMonthStartTime(targetYear, targetMonth),
                endTime = getMonthEndTime(targetYear, targetMonth)
            )
            val report = baseStatisticsService.generateMonthlyReport(tasks, targetYear, targetMonth)

            // 存储到缓存（历史数据永不过期）
            reportCacheManager.putCachedData(cacheKey, report)
            logger.info("💾 新月报已缓存（${targetYear}年${targetMonth}月）")
            report
        }
    }
    
    /**
     * 获取年报数据（带缓存）
     * @param year 年份（可选，默认去年）
     */
    fun getYearlyReport(year: Int? = null): Report {
        return if (year != null) {
            // 如果指定了年份，使用指定年份的报告
            val targetYear = year
            
            // 检查未来年份，如果出现未来年份就回退到今年
            val validatedYear = validateYear(targetYear)
            val cacheKey = "yearly_report_${validatedYear}"
            
            // 尝试从缓存获取
            val cached: Any? = reportCacheManager.getCachedData(cacheKey)
            if (cached != null) {
                if (cached is Report) {
                    logger.info("✅ 缓存命中，从缓存获取年报（${validatedYear}年）")
                    return cached
                } else {
                    logger.warn("⚠️ 缓存数据类型错误，期望Report但获取到: ${cached.javaClass.name}，将重新计算")
                    // 清除错误的缓存
                    reportCacheManager.clearCache(cacheKey)
                }
            }

            logger.info("🔄 缓存未命中，重新计算年报（${validatedYear}年）")
            
            val tasks = gpuTaskQuery.queryTasks(
                timePeriod = TimePeriod.ONE_YEAR,
                startTime = getYearStartTime(validatedYear),
                endTime = getYearEndTime(validatedYear)
            )
            val report = baseStatisticsService.generateYearlyReport(tasks, validatedYear)

            // 存储到缓存（历史数据永不过期）
            reportCacheManager.putCachedData(cacheKey, report)
            logger.info("💾 新年报已缓存（${validatedYear}年）")
            report
        } else {
            // 如果没有指定年份，默认生成去年的报告
            val lastYear = LocalDate.now().minusYears(1)
            val targetYear = lastYear.year
            val cacheKey = "yearly_report_${targetYear}"
            
            // 尝试从缓存获取
            val cached: Any? = reportCacheManager.getCachedData(cacheKey)
            if (cached != null) {
                if (cached is Report) {
                    logger.info("✅ 缓存命中，从缓存获取年报（${targetYear}年）")
                    return cached
                } else {
                    logger.warn("⚠️ 缓存数据类型错误，期望Report但获取到: ${cached.javaClass.name}，将重新计算")
                    // 清除错误的缓存
                    reportCacheManager.clearCache(cacheKey)
                }
            }

            logger.info("🔄 缓存未命中，重新计算年报（${targetYear}年）")
            
            val tasks = gpuTaskQuery.queryTasks(
                timePeriod = TimePeriod.ONE_YEAR,
                startTime = getYearStartTime(targetYear),
                endTime = getYearEndTime(targetYear)
            )
            val report = baseStatisticsService.generateYearlyReport(tasks, targetYear)

            // 存储到缓存（历史数据永不过期）
            reportCacheManager.putCachedData(cacheKey, report)
            logger.info("💾 新年报已缓存（${targetYear}年）")
            report
        }
    }

    /**
     * 获取用户活动时间分布（带缓存）
     * @param timePeriod 时间周期
     * @return 用户活动时间分布
     */
    fun getUserActivityTimeDistribution(timePeriod: TimePeriod): UserActivityTimeDistribution {
        val cacheKey = "user_activity_time_${timePeriod.name}"
        
        // 尝试从缓存获取
        val cached: UserActivityTimeDistribution? = reportCacheManager.getCachedData(cacheKey)
        if (cached != null) {
            logger.debug("✅ 缓存命中，从缓存获取用户活动时间分布：$timePeriod")
            return cached
        }

        logger.info("🔄 缓存未命中，重新计算用户活动时间分布：$timePeriod")
        val tasks = gpuTaskQuery.queryTasks(timePeriod)
        val stats = baseStatisticsService.getUserActivityTimeDistribution(tasks)

        // 存储到缓存
        reportCacheManager.putCachedData(cacheKey, stats)
        logger.info("💾 新用户活动时间分布已缓存：$timePeriod")
        return stats
    }

    /**
     * 获取自定义时间段的用户活动时间分布（带缓存）
     * @param startTime 开始时间（秒）
     * @param endTime 结束时间（秒）
     * @return 用户活动时间分布
     */
    fun getUserActivityTimeDistribution(startTime: Long, endTime: Long): UserActivityTimeDistribution {
        val cacheKey = "user_activity_time_custom_${startTime}_${endTime}"
        
        // 尝试从缓存获取
        val cached: UserActivityTimeDistribution? = reportCacheManager.getCachedData(cacheKey)
        if (cached != null) {
            logger.debug("✅ 缓存命中，从缓存获取用户活动时间分布（自定义时间段）")
            return cached
        }

        logger.info("🔄 缓存未命中，重新计算用户活动时间分布（自定义时间段）")
        val tasks = gpuTaskQuery.queryTasks(
            timePeriod = TimePeriod.ONE_DAY, // 使用任意周期，实际使用自定义时间
            startTime = startTime,
            endTime = endTime
        )
        val stats = baseStatisticsService.getUserActivityTimeDistribution(tasks, startTime, endTime)

        // 存储到缓存
        reportCacheManager.putCachedData(cacheKey, stats)
        logger.info("💾 新用户活动时间分布已缓存（自定义时间段）")
        return stats
    }

    /**
     * 获取周的开始时间
     */
    private fun getWeekStartTime(year: Int, week: Int): Long {
        val weekStart = LocalDate.of(year, 1, 1)
            .with(java.time.temporal.WeekFields.ISO.weekOfYear(), week.toLong())
            .with(java.time.DayOfWeek.MONDAY)
        return weekStart.atStartOfDay(ZoneId.systemDefault()).toEpochSecond()
    }

    /**
     * 获取周的结束时间
     */
    private fun getWeekEndTime(year: Int, week: Int): Long {
        val weekEnd = LocalDate.of(year, 1, 1)
            .with(java.time.temporal.WeekFields.ISO.weekOfYear(), week.toLong())
            .with(java.time.DayOfWeek.MONDAY)
            .plusDays(6)
        return weekEnd.atTime(23, 59, 59).atZone(ZoneId.systemDefault()).toEpochSecond()
    }

    /**
     * 获取月的开始时间
     */
    private fun getMonthStartTime(year: Int, month: Int): Long {
        val monthStart = LocalDate.of(year, month, 1)
        return monthStart.atStartOfDay(ZoneId.systemDefault()).toEpochSecond()
    }

    /**
     * 获取月的结束时间
     */
    private fun getMonthEndTime(year: Int, month: Int): Long {
        val monthEnd = LocalDate.of(year, month, 1).withDayOfMonth(
            LocalDate.of(year, month, 1).lengthOfMonth()
        )
        return monthEnd.atTime(23, 59, 59).atZone(ZoneId.systemDefault()).toEpochSecond()
    }

    /**
     * 获取年的开始时间
     */
    private fun getYearStartTime(year: Int): Long {
        val yearStart = LocalDate.of(year, 1, 1)
        return yearStart.atStartOfDay(ZoneId.systemDefault()).toEpochSecond()
    }

    /**
     * 获取年的结束时间
     */
    private fun getYearEndTime(year: Int): Long {
        val yearEnd = LocalDate.of(year, 12, 31)
        return yearEnd.atTime(23, 59, 59).atZone(ZoneId.systemDefault()).toEpochSecond()
    }

    /**
     * 清除所有缓存
     */
    fun clearCache() {
        logger.info("清除统计缓存")
        reportCacheManager.clearAllCache()
    }

    /**
     * 强制更新缓存
     */
    fun forceUpdateCache() {
        logger.info("强制更新统计缓存")
        clearCache()
        // 预加载常用统计数据
        getUserStatistics(TimePeriod.ONE_WEEK)
        getGpuStatistics(TimePeriod.ONE_WEEK)
        getUserActivityTimeDistribution(TimePeriod.ONE_WEEK)
        getTodayReport()
        getYesterdayReport()
        get24HourReport()
        get48HourReport()
        get72HourReport()
    }

    /**
     * 验证日期，如果出现未来日期就回退到今日
     * @param date 要验证的日期
     * @return 验证后的日期
     */
    private fun validateDate(date: LocalDate): LocalDate {
        val today = LocalDate.now()
        return if (date.isAfter(today)) {
            logger.warn("检测到未来日期 $date，已回退到今日 $today")
            today
        } else {
            date
        }
    }

    /**
     * 验证月份，如果出现未来月份就回退到本月
     * @param year 年份
     * @param month 月份
     * @return 验证后的年份和月份
     */
    private fun validateMonth(year: Int, month: Int): Pair<Int, Int> {
        val now = LocalDate.now()
        val currentYear = now.year
        val currentMonth = now.monthValue
        
        return if (year > currentYear || (year == currentYear && month > currentMonth)) {
            logger.warn("检测到未来月份 ${year}年${month}月，已回退到本月 ${currentYear}年${currentMonth}月")
            Pair(currentYear, currentMonth)
        } else {
            Pair(year, month)
        }
    }

    /**
     * 验证年份，如果出现未来年份就回退到今年
     * @param year 年份
     * @return 验证后的年份
     */
    private fun validateYear(year: Int): Int {
        val currentYear = LocalDate.now().year
        return if (year > currentYear) {
            logger.warn("检测到未来年份 ${year}，已回退到今年 ${currentYear}")
            currentYear
        } else {
            year
        }
    }

    /**
     * 验证周，如果出现未来周就回退到本周
     * @param year 年份
     * @param week 周数
     * @return 验证后的年份和周数
     */
    private fun validateWeek(year: Int, week: Int): Pair<Int, Int> {
        val now = LocalDate.now()
        val currentYear = now.year
        val currentWeek = now.get(java.time.temporal.WeekFields.ISO.weekOfYear())
        
        return if (year > currentYear || (year == currentYear && week > currentWeek)) {
            logger.warn("检测到未来周 ${year}年第${week}周，已回退到本周 ${currentYear}年第${currentWeek}周")
            Pair(currentYear, currentWeek)
        } else {
            Pair(year, week)
        }
    }
}
