package com.khm.group.center.config

import com.khm.group.center.datatype.query.GpuTaskQueryRequest
import com.fasterxml.jackson.databind.ObjectMapper
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.http.MediaType
import org.springframework.web.context.WebApplicationContext
import org.springframework.test.web.servlet.MockMvc
import org.springframework.test.web.servlet.setup.MockMvcBuilders
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.status
import com.khm.group.center.test.H2DatabaseTest

/**
 * HTTP 消息转换器必须真正认得 Kotlin 数据类的默认值。
 *
 * 背景：JacksonConfig 曾用 `ObjectMapper()` 裸构造，没装上 Kotlin 模块；
 * Spring Boot 4 又不再自动注册 ObjectMapper Bean，HTTP 层于是自建了一个
 * 不带 Kotlin 模块的实例。结果 Pagination.page、QueryFilter.logic 等默认值
 * 全部失效，POST /web/open/gpu-tasks/query 对**任何**请求体恒定返回 400。
 *
 * 为什么这类测试以前不存在：ResponseJsonNamingTest 用的是 `jacksonObjectMapper()`
 * ——一个独立 new 出来的 mapper，跟 HTTP 层实际使用的实例毫无关系，
 * 所以无论 HTTP 层换成什么它都会通过。必须打真实的 HTTP 路径才能测出来。
 */
@H2DatabaseTest
class JacksonHttpDeserializationTest {

    // 不用 @AutoConfigureMockMvc：Spring Boot 4 把它拆到了独立测试模块，
    // 当前 classpath 没有。直接用 MockMvcBuilders 走真实的 WebApplicationContext，
    // 同样能打真实的 HTTP 转换器路径，且不必新增依赖。
    @Autowired
    private lateinit var webApplicationContext: WebApplicationContext

    private val mockMvc: MockMvc by lazy {
        MockMvcBuilders.webAppContextSetup(webApplicationContext).build()
    }

    @Autowired
    private lateinit var objectMapper: ObjectMapper

    @Test
    fun `POST query accepts a body that omits every defaulted field`() {
        // 只给 filters，Pagination 的 page/pageSize/sortBy/sortOrder 与
        // QueryFilter 的 logic 全部省略，全部应落到 Kotlin 默认值
        val body = """
            {
              "filters": [
                {"field": "TASK_USER", "operator": "EQUALS", "value": "alice"}
              ]
            }
        """.trimIndent()

        mockMvc.perform(
            post("/web/open/gpu-tasks/query")
                .contentType(MediaType.APPLICATION_JSON)
                .content(body)
        )
            .andExpect(status().isOk)
    }

    @Test
    fun `omitted defaults actually take effect rather than being null`() {
        val body = """{"filters":[{"field":"TASK_USER","operator":"EQUALS","value":"alice"}]}"""
        val request = objectMapper.readValue(body, GpuTaskQueryRequest::class.java)

        assertEquals(1, request.pagination.page, "Pagination.page 默认值未生效")
        assertEquals(20, request.pagination.pageSize, "Pagination.pageSize 默认值未生效")
        assertEquals(
            "AND", request.filters.first().logic.name,
            "QueryFilter.logic 默认值未生效"
        )
    }

    @Test
    fun `unknown fields are tolerated so clients can send extras`() {
        val body = """
            {
              "filters": [],
              "someFutureField": {"nested": 1}
            }
        """.trimIndent()

        mockMvc.perform(
            post("/web/open/gpu-tasks/query")
                .contentType(MediaType.APPLICATION_JSON)
                .content(body)
        )
            .andExpect(status().isOk)
    }

    @Test
    fun `response JSON over the real HTTP layer keeps camelCase naming`() {
        // 守护 ResponseJsonNamingTest 覆盖不到的部分：它测的是独立 mapper，
        // 这里测的是 HTTP 层实际用的那个转换器
        val body = """{"filters":[]}"""
        mockMvc.perform(
            post("/web/open/gpu-tasks/query")
                .contentType(MediaType.APPLICATION_JSON)
                .content(body)
        )
            .andExpect(status().isOk)
            .andExpect { result ->
                val text = result.response.contentAsString
                assertTrue(text.contains("isSucceed"), "响应应含 isSucceed，实际: $text")
                assertFalse(
                    text.contains("is_succeed") || text.contains("IsSucceed"),
                    "响应不应出现下划线或大写开头命名，实际: $text"
                )
            }
    }
}
