package com.richjun.liftupai.global.config

import io.github.cdimascio.dotenv.Dotenv
import org.springframework.boot.SpringApplication
import org.springframework.boot.env.EnvironmentPostProcessor
import org.springframework.core.env.ConfigurableEnvironment
import org.springframework.core.env.MapPropertySource

class DotenvEnvironmentPostProcessor : EnvironmentPostProcessor {

    override fun postProcessEnvironment(environment: ConfigurableEnvironment, application: SpringApplication) {
        try {
            val dotenv = Dotenv.configure()
                .ignoreIfMissing()
                .load()

            val envVars = mutableMapOf<String, Any>()
            dotenv.entries().forEach { entry ->
                envVars[entry.key] = entry.value
                System.setProperty(entry.key, entry.value)
            }

            if (envVars.isNotEmpty()) {
                val propertySource = MapPropertySource("dotenv", envVars)
                environment.propertySources.addFirst(propertySource)
                println("Successfully loaded .env file with ${envVars.size} environment variables")
            }
        } catch (e: Exception) {
            println("Warning: .env file not found or could not be loaded. Using default values.")
        }
    }
}