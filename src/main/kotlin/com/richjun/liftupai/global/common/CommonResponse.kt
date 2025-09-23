package com.richjun.liftupai.global.common

import com.fasterxml.jackson.annotation.JsonInclude
import java.time.LocalDateTime
import java.time.format.DateTimeFormatter

@JsonInclude(JsonInclude.Include.NON_NULL)
data class ApiResponse<T>(
    val success: Boolean,
    val data: T? = null,
    val error: ErrorResponse? = null,
    val timestamp: String = LocalDateTime.now().format(DateTimeFormatter.ISO_LOCAL_DATE_TIME)
) {
    companion object {
        fun <T> success(data: T): ApiResponse<T> {
            return ApiResponse(success = true, data = data)
        }

        fun <T> error(code: String, message: String, details: Map<String, Any>? = null): ApiResponse<T> {
            return ApiResponse(
                success = false,
                error = ErrorResponse(code, message, details)
            )
        }
    }
}

data class ErrorResponse(
    val code: String,
    val message: String,
    val details: Map<String, Any>? = null
)

data class PaginationResponse<T>(
    val data: List<T>,
    val pagination: PaginationMeta
)

data class PaginationMeta(
    val page: Int,
    val limit: Int,
    val totalPages: Int,
    val totalCount: Long,
    val hasNext: Boolean,
    val hasPrev: Boolean
)