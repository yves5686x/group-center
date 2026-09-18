package com.khm.group.center

import org.junit.jupiter.api.Test
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.context.SpringBootTest
import javax.sql.DataSource

/**
 * 真实 MySQL + Druid 连通性测试（默认 profile，不走 H2）。
 * 依赖外部数据库：仅在设置了 SPRING_DATASOURCE_URL 环境变量时运行，
 * 否则自动跳过 —— 保证测试套件在没有外部 MySQL 的环境也能全绿。
 */
@SpringBootTest
@EnabledIfEnvironmentVariable(named = "SPRING_DATASOURCE_URL", matches = ".+")
class DruidTest {

    @Autowired
    private lateinit var dataSource: DataSource

    @Test
    fun test() {
        println("[Test]dataSource.javaClass")
        println(dataSource.javaClass)
    }

}
