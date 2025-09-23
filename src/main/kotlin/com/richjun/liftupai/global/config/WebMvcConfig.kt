package com.richjun.liftupai.global.config

import org.springframework.context.annotation.Configuration
import org.springframework.format.FormatterRegistry
import org.springframework.web.servlet.config.annotation.CorsRegistry
import org.springframework.web.servlet.config.annotation.ResourceHandlerRegistry
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer

@Configuration
class WebMvcConfig(
    private val stringToLocalDateTimeConverter: StringToLocalDateTimeConverter,
    private val stringToLocalDateConverter: StringToLocalDateConverter
) : WebMvcConfigurer {

    override fun addFormatters(registry: FormatterRegistry) {
        registry.addConverter(stringToLocalDateTimeConverter)
        registry.addConverter(stringToLocalDateConverter)
    }

    override fun addCorsMappings(registry: CorsRegistry) {
        registry.addMapping("/api/**")
            .allowedOrigins("*")
            .allowedMethods("GET", "POST", "PUT", "DELETE", "OPTIONS")
            .allowedHeaders("*")
            .exposedHeaders("Authorization")
    }

    override fun addResourceHandlers(registry: ResourceHandlerRegistry) {
        // Serve uploaded files
        registry.addResourceHandler("/uploads/**")
            .addResourceLocations("file:uploads/")
    }
}