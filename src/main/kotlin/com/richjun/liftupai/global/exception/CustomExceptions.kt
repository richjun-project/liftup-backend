package com.richjun.liftupai.global.exception

class ResourceNotFoundException(message: String) : RuntimeException(message)

class DuplicateResourceException(message: String) : RuntimeException(message)

class InvalidCredentialsException(message: String) : RuntimeException(message)

class UnauthorizedException(message: String) : RuntimeException(message)

class BadRequestException(message: String) : RuntimeException(message)

class CustomException(val errorCode: ErrorCode, message: String) : RuntimeException(message)

enum class ErrorCode {
    BAD_REQUEST,
    UNAUTHORIZED,
    FORBIDDEN,
    NOT_FOUND,
    INTERNAL_SERVER_ERROR
}