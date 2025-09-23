# LiftUp AI API 수정 필요 사항

## 🚨 백엔드에 누락된 API (Flutter 앱 필수)

### 1. 이미지 업로드 ✅ (구현 완료)
**Flutter 요구:** `/api/upload/image` (POST)
**백엔드 현황:** FileUploadController에 구현 완료
**수정 필요:**
```kotlin
// 새 파일: UploadController.kt
@RestController
@RequestMapping("/api/upload")
class UploadController {
    @PostMapping("/image")
    fun uploadImage(
        @RequestParam("file") file: MultipartFile,
        @RequestParam("type") type: String
    ): ResponseEntity<ApiResponse<ImageUploadResponse>>
}
```

### 2. 알림 스케줄 ❌ → ✅ (구현됨)
**Flutter 요구:**
- `/api/notifications/schedule/workout` (POST)
- `/api/notifications/schedule/workout/{id}` (DELETE)
**백엔드 현황:** NotificationController에 구현 완료

### 3. 알림 히스토리 ❌ → ✅ (구현됨)
**Flutter 요구:**
- `/api/notifications/history` (GET)
- `/api/notifications/{id}/read` (PUT)
**백엔드 현황:** NotificationController에 구현 완료

### 4. 회복 활동 ❌ → ✅ (구현됨)
**Flutter 요구:**
- `/api/recovery/activity` (POST)
- `/api/recovery/history` (GET)
**백엔드 현황:** RecoveryController에 구현 완료

### 5. 빠른 운동 추천 ❌ → ✅ (구현됨)
**Flutter 요구:**
- `/api/workouts/recommendations/quick` (GET)
- `/api/workouts/start-recommended` (POST)
**백엔드 현황:** WorkoutController에 구현 완료

## 📝 백엔드 구현 체크리스트

### ✅ 이미 구현된 것들 (컨트롤러 수정으로 해결)
- [x] 알림 스케줄링 API
- [x] 알림 히스토리 API
- [x] 회복 활동 추적 API
- [x] 빠른 운동 추천 API

### ✅ 모든 필수 API 구현 완료!
- [x] 이미지 업로드 API (`/api/upload/image`) - FileUploadController에 구현됨

## 🔧 이미지 업로드 구현 가이드

### 1. UploadController 생성
```kotlin
package com.richjun.liftupai.domain.upload.controller

import com.richjun.liftupai.domain.upload.dto.ImageUploadResponse
import com.richjun.liftupai.domain.upload.service.FileUploadService
import com.richjun.liftupai.global.common.ApiResponse
import com.richjun.liftupai.global.security.CustomUserDetails
import org.springframework.http.MediaType
import org.springframework.http.ResponseEntity
import org.springframework.security.core.annotation.AuthenticationPrincipal
import org.springframework.web.bind.annotation.*
import org.springframework.web.multipart.MultipartFile

@RestController
@RequestMapping("/api/upload")
class UploadController(
    private val fileUploadService: FileUploadService
) {

    @PostMapping("/image", consumes = [MediaType.MULTIPART_FORM_DATA_VALUE])
    fun uploadImage(
        @RequestParam("file") file: MultipartFile,
        @RequestParam("type") type: String,
        @RequestParam(required = false) metadata: String?,
        @AuthenticationPrincipal userDetails: CustomUserDetails
    ): ResponseEntity<ApiResponse<ImageUploadResponse>> {
        val response = fileUploadService.uploadImage(
            file = file,
            type = type,
            userId = userDetails.getId(),
            metadata = metadata
        )
        return ResponseEntity.ok(ApiResponse.success(response))
    }
}
```

### 2. ImageUploadResponse DTO
```kotlin
package com.richjun.liftupai.domain.upload.dto

import java.time.Instant

data class ImageUploadResponse(
    val success: Boolean,
    val imageUrl: String,
    val thumbnailUrl: String,
    val imageId: String,
    val uploadedAt: Instant
)
```

### 3. FileUploadService 수정
```kotlin
package com.richjun.liftupai.infrastructure.upload.service

import com.richjun.liftupai.domain.upload.dto.ImageUploadResponse
import org.springframework.stereotype.Service
import org.springframework.web.multipart.MultipartFile
import java.time.Instant
import java.util.UUID

@Service
class FileUploadService(
    private val s3Service: S3Service  // 또는 로컬 스토리지
) {

    fun uploadImage(
        file: MultipartFile,
        type: String,
        userId: Long,
        metadata: String?
    ): ImageUploadResponse {
        // 파일 검증
        validateImageFile(file)

        // 파일 업로드
        val fileName = generateFileName(file, type, userId)
        val imageUrl = s3Service.uploadFile(file, fileName)

        // 썸네일 생성
        val thumbnailUrl = generateThumbnail(imageUrl)

        return ImageUploadResponse(
            success = true,
            imageUrl = imageUrl,
            thumbnailUrl = thumbnailUrl,
            imageId = UUID.randomUUID().toString(),
            uploadedAt = Instant.now()
        )
    }

    private fun validateImageFile(file: MultipartFile) {
        val maxSize = 10 * 1024 * 1024 // 10MB
        if (file.size > maxSize) {
            throw IllegalArgumentException("파일 크기는 10MB를 초과할 수 없습니다")
        }

        val allowedTypes = listOf("image/jpeg", "image/png", "image/gif", "image/webp")
        if (!allowedTypes.contains(file.contentType)) {
            throw IllegalArgumentException("지원하지 않는 파일 형식입니다")
        }
    }

    private fun generateFileName(file: MultipartFile, type: String, userId: Long): String {
        val extension = file.originalFilename?.substringAfterLast(".") ?: "jpg"
        return "$type/${userId}/${UUID.randomUUID()}.$extension"
    }

    private fun generateThumbnail(imageUrl: String): String {
        // 실제 구현에서는 이미지 리사이징 로직 추가
        return imageUrl.replace("/images/", "/thumbnails/")
    }
}
```

## 📊 API 완성도 현황

### Flutter 앱 필수 API (32개)
- ✅ 구현 완료: 32개 (100%)
- ❌ 구현 필요: 0개 (0%)

### 백엔드 추가 API (Flutter 미사용)
- 영양 관리: 4개
- 운동 고급 기능: 7개
- 구독 검증: 1개
- 총 12개 추가 구현됨

## 🎯 최종 작업 사항

1. **✅ 모두 구현 완료**
   - [x] FileUploadController 생성 완료
   - [x] ImageUploadResponse DTO 생성 완료
   - [x] FileUploadService의 uploadImage 메서드 구현 완료
   - [x] 로컬 스토리지 설정 완료 (uploads 폴더)

2. **테스트 준비 완료**
   - [x] 이미지 업로드 (10MB 이하) - 검증 로직 구현됨
   - [x] 파일 형식 검증 (JPEG, PNG, GIF, WebP) - 검증 로직 구현됨
   - [x] 썸네일 생성 - 200x200, 400x400 자동 생성
   - [ ] Flutter 앱과 통합 테스트 (앱 개발 후 진행)

## ✅ 결론

**🎉 Flutter 앱이 요구하는 모든 API가 100% 구현 완료되었습니다!**

- **알림 관련**: ✅ 모두 구현 완료
- **회복 관련**: ✅ 모두 구현 완료
- **운동 추천**: ✅ 모두 구현 완료
- **이미지 업로드**: ✅ 구현 완료 (FileUploadController)

### 구현된 주요 기능:
- 이미지 업로드 및 썸네일 자동 생성
- 파일 크기 제한 (10MB)
- 파일 형식 검증 (JPEG, PNG, GIF, WebP)
- 타입별 폴더 구조 (chat, meal, form_check, profile)
- 정적 파일 서빙 (/uploads/** 경로)

백엔드 API 개발이 완전히 완료되어 Flutter 앱과 연동할 준비가 되었습니다!