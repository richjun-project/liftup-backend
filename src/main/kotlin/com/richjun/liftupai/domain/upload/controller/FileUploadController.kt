package com.richjun.liftupai.domain.upload.controller

import com.richjun.liftupai.domain.upload.dto.*
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
class FileUploadController(
    private val fileUploadService: FileUploadService
) {

    @PostMapping("/image", consumes = [MediaType.MULTIPART_FORM_DATA_VALUE])
    fun uploadImage(
        @RequestParam("file") file: MultipartFile,
        // 앱(1.1.27 이하)의 채팅 사진 업로드는 type 을 보내지 않는다 — 필수였던 탓에 채팅 사진이 전부 400 이었다
        @RequestParam("type", required = false) type: String?,
        @RequestParam("metadata", required = false) metadata: String?,
        @AuthenticationPrincipal userDetails: CustomUserDetails
    ): ResponseEntity<ApiResponse<ImageUploadResponse>> {
        val response = fileUploadService.uploadImage(
            file = file,
            type = FileUploadService.resolveType(type),
            metadata = metadata,
            userId = userDetails.getId()
        )
        return ResponseEntity.ok(ApiResponse.success(response))
    }
}