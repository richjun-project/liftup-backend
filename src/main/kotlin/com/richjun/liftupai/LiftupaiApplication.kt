package com.richjun.liftupai

import io.github.cdimascio.dotenv.Dotenv
import org.springframework.boot.autoconfigure.SpringBootApplication
import org.springframework.boot.runApplication
import org.springframework.scheduling.annotation.EnableScheduling

@SpringBootApplication
@EnableScheduling
class LiftupaiApplication

fun main(args: Array<String>) {
	// Load .env file before Spring Boot starts
	try {
		val dotenv = Dotenv.configure()
			.ignoreIfMissing()
			.load()

		dotenv.entries().forEach { entry ->
			System.setProperty(entry.key, entry.value)
		}
		println("Environment variables loaded from .env file")
		println("DB_PASSWORD is set: ${System.getProperty("DB_PASSWORD") != null}")
		// Don't print actual password for security
	} catch (e: Exception) {
		println("Warning: Could not load .env file: ${e.message}")
	}

	runApplication<LiftupaiApplication>(*args)
}
