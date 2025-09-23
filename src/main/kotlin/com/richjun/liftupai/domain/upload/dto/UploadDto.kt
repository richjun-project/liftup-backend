package com.richjun.liftupai.domain.upload.dto

import com.fasterxml.jackson.annotation.JsonFormat
import java.time.LocalDateTime

data class ImageUploadResponse(
    val success: Boolean,
    val imageUrl: String,
    val thumbnailUrl: String,
    val imageId: String,
    @JsonFormat(pattern = "yyyy-MM-dd'T'HH:mm:ss")
    val uploadedAt: LocalDateTime
)

data class ImageMetadata(
    val originalName: String,
    val mimeType: String,
    val size: Long
)