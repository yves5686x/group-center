package com.khm.group.center.config

import com.fasterxml.jackson.databind.DeserializationFeature
import com.fasterxml.jackson.databind.ObjectMapper
import com.fasterxml.jackson.module.kotlin.registerKotlinModule
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration
import org.springframework.http.converter.json.MappingJackson2HttpMessageConverter

/**
 * Jackson全局ObjectMapper配置
 *
 * 说明：
 * 1. Spring Boot 4.x 不再自动注册 ObjectMapper Bean，
 *    也不再注册 Jackson2ObjectMapperBuilder Bean，HTTP 消息转换器在缺省情况下
 *    会自建一个不带 Kotlin 模块的 ObjectMapper，导致 Kotlin 数据类的默认值全部失效。
 * 2. 这不是理论问题：此前 POST /web/open/gpu-tasks/query 因「非空参数为 null」
 *    恒定 400（Pagination.page / pageSize、QueryFilter.logic 等默认值不生效），
 *    整个端点无法反序列化任何请求体。
 * 3. 因此这里显式声明带 Kotlin 模块的 ObjectMapper，并把它装进
 *    [MappingJackson2HttpMessageConverter] 供 HTTP 层使用。
 *    只声明 ObjectMapper Bean 是不够的——那只影响注入，不影响请求反序列化。
 */
@Configuration
class JacksonConfig {

    /**
     * 供业务层注入的 ObjectMapper。
     */
    @Bean
    fun objectMapper(): ObjectMapper {
        return ObjectMapper()
            .registerKotlinModule()
            // 与 Spring Boot 既往的 HTTP 行为保持一致：忽略未知字段，
            // 否则前端多传一个字段就会 400
            .configure(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES, false)
    }

    /**
     * HTTP 消息转换器，显式绑定上面的 ObjectMapper。
     *
     * 少了这个 Bean，Spring MVC 会用自己默认构建的 ObjectMapper，
     * 数据类默认值依旧失效，POST 请求继续 400。
     */
    @Bean
    fun jacksonHttpMessageConverter(objectMapper: ObjectMapper): MappingJackson2HttpMessageConverter {
        return MappingJackson2HttpMessageConverter(objectMapper)
    }
}
