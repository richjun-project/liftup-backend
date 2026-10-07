package com.richjun.liftupai.global.config

import com.richjun.liftupai.global.ratelimit.RateLimitInterceptor
import com.richjun.liftupai.global.ratelimit.RateLimitProperties
import com.richjun.liftupai.global.ratelimit.RequestSizeInterceptor
import com.richjun.liftupai.global.ratelimit.SearchRateLimitInterceptor
import org.springframework.boot.context.properties.EnableConfigurationProperties
import org.springframework.context.annotation.Configuration
import org.springframework.format.FormatterRegistry
import org.springframework.web.servlet.config.annotation.CorsRegistry
import org.springframework.web.servlet.config.annotation.InterceptorRegistry
import org.springframework.web.servlet.config.annotation.ResourceHandlerRegistry
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer

@Configuration
@EnableConfigurationProperties(RateLimitProperties::class)
class WebMvcConfig(
    private val stringToLocalDateTimeConverter: StringToLocalDateTimeConverter,
    private val stringToLocalDateConverter: StringToLocalDateConverter,
    private val rateLimitInterceptor: RateLimitInterceptor,
    private val searchRateLimitInterceptor: SearchRateLimitInterceptor,
    private val requestSizeInterceptor: RequestSizeInterceptor
) : WebMvcConfigurer {

    override fun addFormatters(registry: FormatterRegistry) {
        registry.addConverter(stringToLocalDateTimeConverter)
        registry.addConverter(stringToLocalDateConverter)
    }

    override fun addInterceptors(registry: InterceptorRegistry) {
        registry.addInterceptor(rateLimitInterceptor)
            .addPathPatterns("/api/chat/**", "/api/ai/**")

        // 운동 검색·제안·추천·대체 운동 — 사용자별 분당 120회 (CPU·DB 를 쓰는 경로)
        registry.addInterceptor(requestSizeInterceptor)
            .addPathPatterns("/api/v2/templates/recommend")
        registry.addInterceptor(searchRateLimitInterceptor)
            .addPathPatterns(
                "/api/v2/exercises",
                "/api/v2/exercises/suggestions",
                "/api/v2/exercises/search",
                "/api/v2/exercises/*/substitutes",
                "/api/v2/programs/exercises",
                "/api/v2/templates/recommend"
            )
    }

    override fun addCorsMappings(registry: CorsRegistry) {
        registry.addMapping("/api/**")
            .allowedOrigins("*")
            .allowedMethods("GET", "POST", "PUT", "DELETE", "OPTIONS")
            .allowedHeaders("*")
            .exposedHeaders("Authorization", "X-RateLimit-Remaining", "Retry-After")
    }

    override fun addResourceHandlers(registry: ResourceHandlerRegistry) {
        // Serve uploaded files
        registry.addResourceHandler("/uploads/**")
            .addResourceLocations("file:uploads/")
    }
}