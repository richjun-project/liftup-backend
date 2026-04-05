package com.richjun.liftupai.global.config

import com.richjun.liftupai.global.ratelimit.RateLimitInterceptor
import com.richjun.liftupai.global.ratelimit.RateLimitProperties
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
    private val rateLimitInterceptor: RateLimitInterceptor
) : WebMvcConfigurer {

    override fun addFormatters(registry: FormatterRegistry) {
        registry.addConverter(stringToLocalDateTimeConverter)
        registry.addConverter(stringToLocalDateConverter)
    }

    override fun addInterceptors(registry: InterceptorRegistry) {
        registry.addInterceptor(rateLimitInterceptor)
            .addPathPatterns("/api/chat/**", "/api/ai/**")
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