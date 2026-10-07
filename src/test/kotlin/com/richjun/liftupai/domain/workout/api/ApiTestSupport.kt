package com.richjun.liftupai.domain.workout.api

import ch.qos.logback.classic.Level
import ch.qos.logback.classic.Logger
import ch.qos.logback.classic.spi.ILoggingEvent
import ch.qos.logback.core.read.ListAppender
import com.fasterxml.jackson.databind.JsonNode
import com.fasterxml.jackson.databind.ObjectMapper
import com.richjun.liftupai.config.TestConfig
import com.richjun.liftupai.domain.auth.entity.User
import com.richjun.liftupai.domain.auth.repository.UserRepository
import com.richjun.liftupai.global.security.CustomUserDetails
import jakarta.persistence.EntityManagerFactory
import org.hibernate.SessionFactory
import org.hibernate.stat.Statistics
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.BeforeEach
import org.slf4j.LoggerFactory
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.context.annotation.Import
import org.springframework.mock.web.MockHttpServletResponse
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken
import org.springframework.security.core.context.SecurityContextHolder
import org.springframework.test.context.ActiveProfiles
import org.springframework.test.web.servlet.MockMvc
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder
import java.util.UUID

/**
 * API 통합 테스트 공통 기반 — 같은 어노테이션 조합을 써서 스프링 컨텍스트를 테스트 클래스 사이에 공유한다.
 * 필터 체인은 끄고(addFilters=false) SecurityContextHolder 에 직접 로그인 사용자를 넣는다.
 * 각 테스트가 끝나면 ERROR 레벨 로그가 하나도 없었는지 확인한다(클라이언트 실수가 서버 장애처럼 보이면 안 된다).
 */
@SpringBootTest
@ActiveProfiles("test")
@Import(TestConfig::class)
@AutoConfigureMockMvc(addFilters = false)
abstract class ApiTestSupport {
    @Autowired protected lateinit var mvc: MockMvc
    @Autowired protected lateinit var userRepository: UserRepository
    @Autowired protected lateinit var emf: EntityManagerFactory

    protected val json = ObjectMapper()

    /** 일부러 DB 장애를 흉내 내는 테스트에서 하이버네이트가 남기는 ERROR 로거를 허용하는 목록 */
    protected val allowedErrorLoggers = mutableSetOf<String>()

    private val errorAppender = ListAppender<ILoggingEvent>()
    private val rootLogger get() = LoggerFactory.getLogger(Logger.ROOT_LOGGER_NAME) as Logger

    @BeforeEach
    fun attachErrorCapture() {
        errorAppender.list.clear()
        allowedErrorLoggers.clear()
        errorAppender.start()
        rootLogger.addAppender(errorAppender)
        stats().isStatisticsEnabled = true
    }

    @AfterEach
    fun verifyNoErrorLogs() {
        rootLogger.detachAppender(errorAppender)
        SecurityContextHolder.clearContext()
        val errors = errorAppender.list.filter { it.level.isGreaterOrEqual(Level.ERROR) && it.loggerName !in allowedErrorLoggers }
        assertTrue(errors.isEmpty(), "ERROR 로그가 남았다: " + errors.joinToString { "${it.loggerName}: ${it.formattedMessage}" })
    }

    protected fun stats(): Statistics = emf.unwrap(SessionFactory::class.java).statistics

    protected fun newUser(prefix: String = "api"): User =
        userRepository.save(User(email = "$prefix-${UUID.randomUUID()}@example.com", nickname = prefix))

    protected fun loginAs(user: User) {
        val details = CustomUserDetails(user)
        SecurityContextHolder.getContext().authentication = UsernamePasswordAuthenticationToken(details, null, details.authorities)
    }

    protected fun call(request: MockHttpServletRequestBuilder): MockHttpServletResponse = mvc.perform(request).andReturn().response

    protected fun body(response: MockHttpServletResponse): JsonNode = json.readTree(response.contentAsString)

    /** 응답이 400 + VALID001 인지 확인 */
    protected fun assertValidationError(response: MockHttpServletResponse, label: String) {
        org.junit.jupiter.api.Assertions.assertEquals(400, response.status, "$label -> ${response.contentAsString.take(200)}")
        org.junit.jupiter.api.Assertions.assertEquals("VALID001", body(response).path("error").path("code").asText(), label)
    }
}
