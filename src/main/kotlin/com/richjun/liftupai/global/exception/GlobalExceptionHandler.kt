package com.richjun.liftupai.global.exception

import com.richjun.liftupai.global.common.ApiResponse
import com.richjun.liftupai.global.i18n.ErrorLocalization
import jakarta.servlet.http.HttpServletRequest
import org.slf4j.LoggerFactory
import org.springframework.http.HttpStatus
import org.springframework.http.ResponseEntity
import org.springframework.security.authentication.BadCredentialsException
import org.springframework.security.core.AuthenticationException
import org.springframework.beans.TypeMismatchException
import org.springframework.http.converter.HttpMessageNotReadableException
import org.springframework.validation.FieldError
import org.springframework.web.HttpMediaTypeNotSupportedException
import org.springframework.web.HttpRequestMethodNotSupportedException
import org.springframework.web.bind.MethodArgumentNotValidException
import org.springframework.web.bind.ServletRequestBindingException
import org.springframework.web.method.annotation.HandlerMethodValidationException
import org.springframework.web.bind.annotation.ExceptionHandler
import org.springframework.web.bind.annotation.RestControllerAdvice
import org.springframework.web.servlet.resource.NoResourceFoundException

@RestControllerAdvice
class GlobalExceptionHandler {

    private val log = LoggerFactory.getLogger(javaClass)

    private fun resolveLocale(request: HttpServletRequest): String {
        val queryLocale = request.getParameter("locale")
        if (!queryLocale.isNullOrBlank()) {
            return when {
                queryLocale.startsWith("ko") -> "ko"
                queryLocale.startsWith("ja") -> "ja"
                queryLocale.startsWith("en") -> "en"
                else -> "en"
            }
        }
        val acceptLanguage = request.getHeader("Accept-Language") ?: return "en"
        val primary = acceptLanguage.split(",").firstOrNull()?.trim()?.split(";")?.firstOrNull()?.trim() ?: return "en"
        return when {
            primary.startsWith("ko") -> "ko"
            primary.startsWith("ja") -> "ja"
            else -> "en"
        }
    }

    // NOTE: ex.message is intentionally NOT used here. ResourceNotFoundException / DuplicateResourceException /
    // InvalidCredentialsException / UnauthorizedException / BadRequestException all require a non-null message at
    // construction, so `ex.message` is never null and previously always won over locale-aware ErrorLocalization
    // lookups, leaking whatever literal (often Korean) string was hardcoded at the throw site to every locale.
    // Always resolve through ErrorLocalization so the response matches the caller's locale.
    // ex.message is still captured via log.warn (with method+path) for ops debugging — it just never
    // reaches the response body.
    @ExceptionHandler(ResourceNotFoundException::class)
    fun handleResourceNotFoundException(ex: ResourceNotFoundException, request: HttpServletRequest): ResponseEntity<ApiResponse<Nothing>> {
        val locale = resolveLocale(request)
        log.warn("ResourceNotFoundException at {} {}: {}", request.method, request.requestURI, ex.message)
        return ResponseEntity.status(HttpStatus.NOT_FOUND)
            .body(ApiResponse.error("NOT_FOUND", ErrorLocalization.message("error.resource_not_found", locale)))
    }

    @ExceptionHandler(DuplicateResourceException::class)
    fun handleDuplicateResourceException(ex: DuplicateResourceException, request: HttpServletRequest): ResponseEntity<ApiResponse<Nothing>> {
        val locale = resolveLocale(request)
        log.warn("DuplicateResourceException at {} {}: {}", request.method, request.requestURI, ex.message)
        return ResponseEntity.status(HttpStatus.CONFLICT)
            .body(ApiResponse.error("VALID002", ErrorLocalization.message("error.duplicate_resource", locale)))
    }

    @ExceptionHandler(InvalidCredentialsException::class)
    fun handleInvalidCredentialsException(ex: InvalidCredentialsException, request: HttpServletRequest): ResponseEntity<ApiResponse<Nothing>> {
        val locale = resolveLocale(request)
        log.warn("InvalidCredentialsException at {} {}: {}", request.method, request.requestURI, ex.message)
        return ResponseEntity.status(HttpStatus.UNAUTHORIZED)
            .body(ApiResponse.error("AUTH001", ErrorLocalization.message("error.authentication_failed", locale)))
    }

    @ExceptionHandler(UnauthorizedException::class)
    fun handleUnauthorizedException(ex: UnauthorizedException, request: HttpServletRequest): ResponseEntity<ApiResponse<Nothing>> {
        val locale = resolveLocale(request)
        log.warn("UnauthorizedException at {} {}: {}", request.method, request.requestURI, ex.message)
        return ResponseEntity.status(HttpStatus.FORBIDDEN)
            .body(ApiResponse.error("AUTH003", ErrorLocalization.message("error.insufficient_permissions", locale)))
    }

    @ExceptionHandler(BadRequestException::class)
    fun handleBadRequestException(ex: BadRequestException, request: HttpServletRequest): ResponseEntity<ApiResponse<Nothing>> {
        val locale = resolveLocale(request)
        log.warn("BadRequestException at {} {}: {}", request.method, request.requestURI, ex.message)
        return ResponseEntity.status(HttpStatus.BAD_REQUEST)
            .body(ApiResponse.error("VALID001", ErrorLocalization.message("error.bad_request", locale)))
    }

    @ExceptionHandler(MethodArgumentNotValidException::class)
    fun handleValidationExceptions(ex: MethodArgumentNotValidException, request: HttpServletRequest): ResponseEntity<ApiResponse<Nothing>> {
        val locale = resolveLocale(request)
        val errors = ex.bindingResult.allErrors.associate {
            val fieldName = (it as? FieldError)?.field ?: "unknown"
            fieldName to (it.defaultMessage ?: ErrorLocalization.message("error.validation_failed", locale))
        }

        return ResponseEntity.status(HttpStatus.BAD_REQUEST)
            .body(ApiResponse.error("VALID001", ErrorLocalization.message("error.validation_failed", locale), errors))
    }

    // 클라이언트가 잘못 보낸 파라미터·본문(타입 불일치, 깨진 JSON, 빠진 필수 파라미터, 지원 안 하는 콘텐츠 타입)은
    // 서버 장애가 아니라 400 이다. 아래 일반 핸들러로 떨어지면 500 + ERROR 스택트레이스가 남아 진짜 장애 알림을 오염시킨다.
    @ExceptionHandler(
        TypeMismatchException::class,
        HttpMessageNotReadableException::class,
        ServletRequestBindingException::class,
        HttpMediaTypeNotSupportedException::class,
        HandlerMethodValidationException::class
    )
    fun handleMalformedRequest(ex: Exception, request: HttpServletRequest): ResponseEntity<ApiResponse<Nothing>> {
        val locale = resolveLocale(request)
        // 스택트레이스 없이 한 줄만 (본문 내용은 개인정보일 수 있어 남기지 않는다)
        log.warn("Malformed request at {} {}: {}", request.method, request.requestURI, ex.javaClass.simpleName)
        return ResponseEntity.status(HttpStatus.BAD_REQUEST)
            .body(ApiResponse.error("VALID001", ErrorLocalization.message("error.bad_request", locale)))
    }

    @ExceptionHandler(HttpRequestMethodNotSupportedException::class)
    fun handleMethodNotSupported(ex: HttpRequestMethodNotSupportedException, request: HttpServletRequest): ResponseEntity<ApiResponse<Nothing>> {
        val locale = resolveLocale(request)
        log.warn("Method not supported at {} {}", request.method, request.requestURI)
        return ResponseEntity.status(HttpStatus.METHOD_NOT_ALLOWED)
            .body(ApiResponse.error("VALID001", ErrorLocalization.message("error.bad_request", locale)))
    }

    @ExceptionHandler(AuthenticationException::class)
    fun handleAuthenticationException(ex: AuthenticationException, request: HttpServletRequest): ResponseEntity<ApiResponse<Nothing>> {
        val locale = resolveLocale(request)
        return ResponseEntity.status(HttpStatus.UNAUTHORIZED)
            .body(ApiResponse.error("AUTH001", ErrorLocalization.message("error.authentication_required", locale)))
    }

    @ExceptionHandler(BadCredentialsException::class)
    fun handleBadCredentialsException(ex: BadCredentialsException, request: HttpServletRequest): ResponseEntity<ApiResponse<Nothing>> {
        val locale = resolveLocale(request)
        return ResponseEntity.status(HttpStatus.UNAUTHORIZED)
            .body(ApiResponse.error("AUTH001", ErrorLocalization.message("error.invalid_credentials", locale)))
    }

    // 존재하지 않는 정적 경로(.env 같은 스캐너 요청 포함)는 404 다.
    // 아래 일반 핸들러로 떨어지면 500 + 스택트레이스가 남아 진짜 에러를 가린다.
    @ExceptionHandler(NoResourceFoundException::class)
    fun handleNoResourceFound(ex: NoResourceFoundException, request: HttpServletRequest): ResponseEntity<ApiResponse<Nothing>> {
        val locale = resolveLocale(request)
        log.warn("No static resource at {} {}", request.method, request.requestURI)
        return ResponseEntity.status(HttpStatus.NOT_FOUND)
            .body(ApiResponse.error("NOT_FOUND", ErrorLocalization.message("error.resource_not_found", locale)))
    }

    @ExceptionHandler(Exception::class)
    fun handleGeneralException(ex: Exception, request: HttpServletRequest): ResponseEntity<ApiResponse<Nothing>> {
        val locale = resolveLocale(request)
        log.error("Unhandled exception", ex)
        return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR)
            .body(ApiResponse.error("SERVER001", ErrorLocalization.message("error.internal_server_error", locale)))
    }
}
