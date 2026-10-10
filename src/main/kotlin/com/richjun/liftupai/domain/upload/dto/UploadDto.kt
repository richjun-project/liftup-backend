package com.richjun.liftupai.domain.upload.dto

import com.fasterxml.jackson.annotation.JsonFormat
import java.time.LocalDateTime

data class ImageUploadResponse(
    val success: Boolean,
    val imageUrl: String,
    val thumbnailUrl: String,
    val imageId: String,
    @JsonFormat(pattern = "yyyy-MM-dd'T'HH:mm:ss")
    val uploadedAt: LocalDateTime,
    /** 앱(1.1.27 이하)의 채팅 사진 업로드는 data.url 을 읽는다 — image_url 과 같은 값(없으면 앱이 로컬 경로로 넘어가 사진이 전달되지 않았다) */
    val url: String = imageUrl
)

data class ImageMetadata(
    val originalName: String,
    val mimeType: String,
    val size: Long
)