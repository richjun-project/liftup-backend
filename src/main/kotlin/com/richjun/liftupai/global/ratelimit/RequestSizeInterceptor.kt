package com.richjun.liftupai.global.ratelimit

import com.richjun.liftupai.global.common.ApiResponse
import com.richjun.liftupai.global.i18n.ErrorLocalization
import jakarta.servlet.http.HttpServletRequest
import jakarta.servlet.http.HttpServletResponse
import org.springframework.http.HttpStatus
import org.springframework.stereotype.Component
import org.springframework.web.servlet.HandlerInterceptor
import com.fasterxml.jackson.databind.ObjectMapper

/**
 * JSON 본문이 큰 요청을 파싱하기 전에 400 으로 끊는다(Content-Length 기준). 추천 요청의 정상 본문은 1KB 안팎이다.
 * 청크 전송(길이 미상)은 [RequestSizeLimitFilter] 가 스트림에서 읽은 바이트로 같은 상한을 지킨다.
 */
@Component
class RequestSizeInterceptor(
    private val objectMapper: ObjectMapper
) : HandlerInterceptor {

    override fun preHandle(request: HttpServletRequest, response: HttpServletResponse, handler: Any): Boolean {
        val length = request.contentLengthLong
        if (length <= MAX_BODY_BYTES) return true
        writeTooLarge(request, response, objectMapper)
        return false
    }

    companion object {
        const val MAX_BODY_BYTES = 32L * 1024

        fun writeTooLarge(request: HttpServletRequest, response: HttpServletResponse, objectMapper: ObjectMapper) {
            val locale = request.getParameter("locale") ?: request.getHeader("Accept-Language") ?: "en"
            response.status = HttpStatus.BAD_REQUEST.value()
            response.contentType = "application/json;charset=UTF-8"
            response.writer.write(
                objectMapper.writeValueAsString(
                    ApiResponse.error<Nothing>("VALID001", ErrorLocalization.message("error.bad_request", locale.take(10)))
                )
            )
        }
    }
}
