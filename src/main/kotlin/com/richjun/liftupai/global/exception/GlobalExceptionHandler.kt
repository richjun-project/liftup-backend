package com.richjun.liftupai.global.exception

import com.richjun.liftupai.global.common.ApiResponse
import com.richjun.liftupai.global.i18n.ErrorLocalization
import jakarta.servlet.http.HttpServletRequest
import org.springframework.http.HttpStatus
import org.springframework.http.ResponseEntity
import org.springframework.security.authentication.BadCredentialsException
import org.springframework.security.core.AuthenticationException
import org.springframework.validation.FieldError
import org.springframework.web.bind.MethodArgumentNotValidException
import org.springframework.web.bind.annotation.ExceptionHandler
import org.springframework.web.bind.annotation.RestControllerAdvice

@RestControllerAdvice
class GlobalExceptionHandler {

    private fun resolveLocale(request: HttpServletRequest): String {
        val acceptLanguage = request.getHeader("Accept-Language") ?: return "en"
        val primary = acceptLanguage.split(",").firstOrNull()?.trim()?.split(";")?.firstOrNull()?.trim() ?: return "en"
        return when {
            primary.startsWith("ko") -> "ko"
            primary.startsWith("ja") -> "ja"
            else -> "en"
        }
    }

    @ExceptionHandler(ResourceNotFoundException::class)
    fun handleResourceNotFoundException(ex: ResourceNotFoundException, request: HttpServletRequest): ResponseEntity<ApiResponse<Nothing>> {
        val locale = resolveLocale(request)
        return ResponseEntity.status(HttpStatus.NOT_FOUND)
            .body(ApiResponse.error("NOT_FOUND", ex.message ?: ErrorLocalization.message("error.resource_not_found", locale)))
    }

    @ExceptionHandler(DuplicateResourceException::class)
    fun handleDuplicateResourceException(ex: DuplicateResourceException, request: HttpServletRequest): ResponseEntity<ApiResponse<Nothing>> {
        val locale = resolveLocale(request)
        return ResponseEntity.status(HttpStatus.CONFLICT)
            .body(ApiResponse.error("VALID002", ex.message ?: ErrorLocalization.message("error.duplicate_resource", locale)))
    }

    @ExceptionHandler(InvalidCredentialsException::class)
    fun handleInvalidCredentialsException(ex: InvalidCredentialsException, request: HttpServletRequest): ResponseEntity<ApiResponse<Nothing>> {
        val locale = resolveLocale(request)
        return ResponseEntity.status(HttpStatus.UNAUTHORIZED)
            .body(ApiResponse.error("AUTH001", ex.message ?: ErrorLocalization.message("error.authentication_failed", locale)))
    }

    @ExceptionHandler(UnauthorizedException::class)
    fun handleUnauthorizedException(ex: UnauthorizedException, request: HttpServletRequest): ResponseEntity<ApiResponse<Nothing>> {
        val locale = resolveLocale(request)
        return ResponseEntity.status(HttpStatus.FORBIDDEN)
            .body(ApiResponse.error("AUTH003", ex.message ?: ErrorLocalization.message("error.insufficient_permissions", locale)))
    }

    @ExceptionHandler(BadRequestException::class)
    fun handleBadRequestException(ex: BadRequestException, request: HttpServletRequest): ResponseEntity<ApiResponse<Nothing>> {
        val locale = resolveLocale(request)
        return ResponseEntity.status(HttpStatus.BAD_REQUEST)
            .body(ApiResponse.error("VALID001", ex.message ?: ErrorLocalization.message("error.bad_request", locale)))
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

    @ExceptionHandler(Exception::class)
    fun handleGeneralException(ex: Exception, request: HttpServletRequest): ResponseEntity<ApiResponse<Nothing>> {
        val locale = resolveLocale(request)
        ex.printStackTrace()
        return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR)
            .body(ApiResponse.error("SERVER001", ErrorLocalization.message("error.internal_server_error", locale)))
    }
}
