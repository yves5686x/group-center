package com.khm.group.center.service

import com.baomidou.mybatisplus.core.conditions.query.QueryWrapper
import com.baomidou.mybatisplus.extension.plugins.pagination.Page
import com.khm.group.center.datatype.query.*
import com.khm.group.center.datatype.query.enums.SortOrder
import com.khm.group.center.datatype.response.QueryFailureException
import com.khm.group.center.db.mapper.client.GpuTaskInfoMapper
import com.khm.group.center.db.model.client.GpuTaskInfoModel
import com.khm.group.center.utils.format.CommandLineSanitizer
import com.khm.group.center.utils.program.Slf4jKt
import com.khm.group.center.utils.program.Slf4jKt.Companion.logger
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.beans.factory.annotation.Value
import org.springframework.stereotype.Service

/**
 * GPU任务查询服务
 */
@Service
@Slf4jKt
class GpuTaskQueryService {

    @Autowired
    private lateinit var gpuTaskInfoMapper: GpuTaskInfoMapper

    /**
     * `/recent` 单次返回的最大记录数。
     * 该端点无鉴权，没有行数上限等于开放全表读取。（权威来源：application.yml）
     */
    @Value("\${gpu-task.recent-max-rows}")
    private var recentMaxRows: Int = 500

    /**
     * `/recent` 时间窗口（小时）上限：超出则钳制到该值并打 WARN。
     * 默认一年（8760h），挡住 hours=876000 这类「变相拉全表」的用法。
     * （权威来源：application.yml）
     */
    @Value("\${gpu-task.recent-max-hours}")
    private var recentMaxHours: Int = 8760

    private val queryBuilder = GpuTaskQueryBuilder()
    private val statisticsCalculator = QueryStatisticsCalculator()

    /**
     * 执行GPU任务查询
     */
    fun queryGpuTasks(request: GpuTaskQueryRequest): GpuTaskQueryResponse {
        logger.info("Start GPU task query: ${request.getQueryDescription()}")

        // 验证请求参数。校验失败必须显式失败：此前返回 empty()，
        // controller 再无条件写 isSucceed=true，于是「参数写错」和
        // 「确实没有数据」在响应上完全同形，调用方无从分辨。
        if (!request.validate()) {
            throw QueryFailureException.InvalidRequest(describeInvalidRequest(request))
        }

        try {
            // 构建查询条件
            val queryWrapper = queryBuilder.buildQueryWrapper(request.filters, request.timeRange)

            // 设置排序
            applySorting(queryWrapper, request.pagination)

            // 执行分页查询（手动分页方式）
            val pageResult = executePagedQuery(queryWrapper, request.pagination)

            // 计算统计信息（如果需要）
            val statistics = if (request.includeStatistics) {
                statisticsCalculator.calculateStatistics(pageResult.records)
            } else {
                null
            }

            // 返回给前端前统一脱敏命令行里的凭据（wandb key / HF token / api key ...）
            CommandLineSanitizer.sanitizeAll(pageResult.records)

            // 构建响应
            val paginationInfo = PaginationInfo.fromPagination(request.pagination, pageResult.total)

            logger.info("Query completed: found ${pageResult.total} records, returned ${pageResult.records.size} records")

            return GpuTaskQueryResponse.fromDataWithStats(
                data = pageResult.records,
                pagination = paginationInfo,
                statistics = statistics ?: QueryStatistics.empty()
            )

        } catch (e: QueryFailureException) {
            throw e
        } catch (e: Exception) {
            // 同样不再吞成空结果：数据库/排序出错时返回空数组，会让运维误判为「无数据」
            throw QueryFailureException.Execution("查询执行失败，请查看服务端日志", e)
        }
    }

    /**
     * 生成参数校验失败的具体原因，便于调用方自行修正。
     * 逐项定位比只说「参数不合法」有用得多。
     */
    private fun describeInvalidRequest(request: GpuTaskQueryRequest): String {
        val reasons = mutableListOf<String>()
        request.filters.forEachIndexed { index, filter ->
            if (!filter.validate()) {
                reasons += "filters[$index](field=${filter.field}, operator=${filter.operator}) 的值不合法"
            }
        }
        request.timeRange?.let {
            if (!it.validate()) reasons += "timeRange 的 startTime 晚于 endTime"
        }
        if (!request.pagination.validate()) {
            reasons += "pagination 越界（page>=1 且 1<=pageSize<=1000）"
        }
        return if (reasons.isEmpty()) "请求参数未通过校验" else reasons.joinToString("; ")
    }

    /**
     * 应用排序
     */
    private fun applySorting(
        queryWrapper: QueryWrapper<GpuTaskInfoModel>,
        pagination: Pagination
    ) {
        val sortColumn = pagination.getSortColumn()
        pagination.getSortDirection()

        when (pagination.sortOrder) {
            SortOrder.ASC -> queryWrapper.orderByAsc(sortColumn)
            SortOrder.DESC -> queryWrapper.orderByDesc(sortColumn)
        }
    }

    /**
     * 执行分页查询（手动分页方式）
     */
    private fun executePagedQuery(
        queryWrapper: QueryWrapper<GpuTaskInfoModel>,
        pagination: Pagination
    ): Page<GpuTaskInfoModel> {
        // 先查询总数
        val totalCount = gpuTaskInfoMapper.selectCount(queryWrapper).toLong()
        
        // 计算总页数
        val totalPages = if (totalCount == 0L) 1 else 
            ((totalCount - 1) / pagination.pageSize + 1).toInt()
        
        // 修正页码，确保在有效范围内
        val correctedPage = if (pagination.page > totalPages) {
            logger.warn("Page number ${pagination.page} out of range (total pages: $totalPages), automatically corrected to last page")
            totalPages
        } else if (pagination.page < 1) {
            logger.warn("Page number ${pagination.page} is invalid, automatically corrected to first page")
            1
        } else {
            pagination.page
        }
        
        // 计算修正后的偏移量
        val correctedOffset = (correctedPage - 1) * pagination.pageSize
        
        // 手动设置分页参数
        queryWrapper.last("LIMIT $correctedOffset, ${pagination.pageSize}")
        
        // 查询当前页数据
        val records = gpuTaskInfoMapper.selectList(queryWrapper)
        
        // 创建分页结果
        val page = Page<GpuTaskInfoModel>(
            correctedPage.toLong(),
            pagination.pageSize.toLong(),
            totalCount
        )
        page.records = records
        
        logger.debug("Pagination query result: total=$totalCount, totalPages=$totalPages, currentPage=$correctedPage, returnedRecords=${records.size}")
        
        return page
    }

    /**
     * 获取所有任务数量（用于统计）
     */
    fun getTotalTaskCount(): Long {
        return gpuTaskInfoMapper.selectCount(null).toLong()
    }

    /**
     * 获取最近N小时的任务
     *
     * 该端点无鉴权且历史上没有任何 LIMIT：hours 传 876000 就等于拉全表。
     * 这里从两个方向收口——时间窗口按 [recentMaxHours] 钳制，返回行数按 [recentMaxRows] 截断。
     */
    fun getRecentTasks(hours: Int): List<GpuTaskInfoModel> {
        val effectiveHours = if (hours > recentMaxHours) {
            logger.warn("Recent task window too large, clamping: {} -> {}", hours, recentMaxHours)
            recentMaxHours
        } else {
            hours
        }
        // 配置被写成 0/负数时 LIMIT 0 会返回空、负数更是 SQL 语法错误，这里兜一个下限
        val limit = if (recentMaxRows < 1) 1 else recentMaxRows

        val timeRange = TimeRange.lastNHours(effectiveHours)
        val queryWrapper = queryBuilder.buildQueryWrapper(emptyList(), timeRange)
        queryWrapper.orderByDesc("task_start_time")
        queryWrapper.last("LIMIT $limit")

        return CommandLineSanitizer.sanitizeAll(gpuTaskInfoMapper.selectList(queryWrapper))
    }

    /**
     * 获取用户的任务统计
     */
    fun getUserTaskStats(userName: String): QueryStatistics {
        val filters = listOf(
            QueryFilter(
                field = com.khm.group.center.datatype.query.enums.QueryField.TASK_USER,
                operator = com.khm.group.center.datatype.query.enums.QueryOperator.EQUALS,
                value = userName
            )
        )

        val queryWrapper = queryBuilder.buildQueryWrapper(filters, null)
        val tasks = gpuTaskInfoMapper.selectList(queryWrapper)

        return statisticsCalculator.calculateStatistics(tasks)
    }

    /**
     * 获取设备的任务统计
     */
    fun getDeviceTaskStats(deviceName: String): QueryStatistics {
        val filters = listOf(
            QueryFilter(
                field = com.khm.group.center.datatype.query.enums.QueryField.SERVER_NAME_ENG,
                operator = com.khm.group.center.datatype.query.enums.QueryOperator.EQUALS,
                value = deviceName
            )
        )

        val queryWrapper = queryBuilder.buildQueryWrapper(filters, null)
        val tasks = gpuTaskInfoMapper.selectList(queryWrapper)

        return statisticsCalculator.calculateStatistics(tasks)
    }

    /**
     * 通过taskId精确匹配任务
     */
    fun getTaskByTaskId(taskId: String): GpuTaskInfoModel? {
        logger.info("Query task by taskId: $taskId")
        
        if (taskId.isBlank()) {
            logger.warn("Empty taskId provided")
            return null
        }

        try {
            val queryWrapper = QueryWrapper<GpuTaskInfoModel>()
            queryWrapper.eq("task_id", taskId.trim())
            
            val task = gpuTaskInfoMapper.selectOne(queryWrapper)
            
            if (task == null) {
                logger.info("No task found with taskId: $taskId")
            } else {
                // 返回给前端前脱敏命令行里的凭据
                CommandLineSanitizer.sanitize(task)
                logger.info("Task found with taskId: $taskId")
            }
            
            return task
        } catch (e: Exception) {
            logger.error("Failed to query task by taskId: $taskId", e)
            return null
        }
    }
}