package com.khm.group.center.test

import org.springframework.boot.test.context.SpringBootTest
import org.springframework.test.context.ActiveProfiles
import org.springframework.test.context.TestPropertySource

/**
 * H2 内存库 Spring Boot 测试组合注解 —— 全项目测试数据源的【唯一权威来源】。
 *
 * 优先级链：这里的内联属性 > 环境变量 > application-test.yml。
 * url 和 driver-class-name 必须在这里同时钉死（不能只写在 application-test.yml）：
 * 外部环境变量 SPRING_DATASOURCE_URL(MySQL) 的优先级高于 yml，会把 url 覆盖成
 * MySQL 而 driver 仍是 H2，造成"驱动-URL 错配"——JDBC 驱动对不认识的 URL 返回
 * null 而非抛错，Druid 建连拿到 null 后无限重试，挂死整个测试进程。
 *
 * 需要真实 MySQL 的连通性测试（如 DruidTest）不要使用本注解，
 * 应保留默认 profile 并用 @EnabledIfEnvironmentVariable 做守卫。
 */
@SpringBootTest
@ActiveProfiles("test")
@TestPropertySource(
    properties = [
        "spring.datasource.url=jdbc:h2:mem:testdb;DB_CLOSE_DELAY=-1;MODE=MySQL",
        "spring.datasource.driver-class-name=org.h2.Driver"
    ]
)
annotation class H2DatabaseTest
