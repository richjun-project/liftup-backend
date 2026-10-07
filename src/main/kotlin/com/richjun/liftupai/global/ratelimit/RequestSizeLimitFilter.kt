package com.richjun.liftupai.global.ratelimit

import com.fasterxml.jackson.databind.ObjectMapper
import jakarta.servlet.FilterChain
import jakarta.servlet.ReadListener
import jakarta.servlet.ServletInputStream
import jakarta.servlet.http.HttpServletRequest
import jakarta.servlet.http.HttpServletRequestWrapper
import jakarta.servlet.http.HttpServletResponse
import org.springframework.stereotype.Component
import org.springframework.web.filter.OncePerRequestFilter
import java.io.BufferedReader
import java.io.ByteArrayInputStream
import java.io.InputStreamReader

/**
 * Content-Length 가 없는(청크 전송) 본문도 [RequestSizeInterceptor] 와 같은 상한을 지키게 한다.
 * 인터셉터는 contentLengthLong(-1) 만 보므로 청크 요청은 길이와 상관없이 통과해 Jackson 이 본문 전체를 읽었다.
 * 여기서는 상한 + 1 바이트까지만 읽어 넘으면 파싱 전에 400 으로 끊고, 아니면 읽은 바이트를 그대로 다시 내려준다.
 */
@Component
class RequestSizeLimitFilter(
    private val objectMapper: ObjectMapper
) : OncePerRequestFilter() {

    override fun shouldNotFilter(request: HttpServletRequest): Boolean =
        request.contentLengthLong >= 0 || request.requestURI !in LIMITED_PATHS

    override fun doFilterInternal(request: HttpServletRequest, response: HttpServletResponse, chain: FilterChain) {
        val limit = RequestSizeInterceptor.MAX_BODY_BYTES.toInt()
        val body = request.inputStream.readNBytes(limit + 1)
        if (body.size > limit) {
            RequestSizeInterceptor.writeTooLarge(request, response, objectMapper)
            return
        }
        chain.doFilter(BufferedBodyRequest(request, body), response)
    }

    private class BufferedBodyRequest(request: HttpServletRequest, private val body: ByteArray) : HttpServletRequestWrapper(request) {
        override fun getInputStream(): ServletInputStream {
            val source = ByteArrayInputStream(body)
            return object : ServletInputStream() {
                override fun read(): Int = source.read()
                override fun read(b: ByteArray, off: Int, len: Int): Int = source.read(b, off, len)
                override fun isFinished(): Boolean = source.available() == 0
                override fun isReady(): Boolean = true
                override fun setReadListener(listener: ReadListener?) = throw UnsupportedOperationException()
            }
        }

        override fun getReader(): BufferedReader =
            BufferedReader(InputStreamReader(inputStream, characterEncoding ?: Charsets.UTF_8.name()))
    }

    companion object {
        /** 인터셉터가 상한을 거는 경로와 같아야 한다 (WebMvcConfig) */
        val LIMITED_PATHS = setOf("/api/v2/templates/recommend")
    }
}
