package com.richjun.liftupai.domain.upload.service

import com.richjun.liftupai.domain.upload.dto.ImageUploadResponse
import com.richjun.liftupai.global.exception.CustomException
import com.richjun.liftupai.global.exception.ErrorCode
import org.slf4j.LoggerFactory
import org.springframework.beans.factory.annotation.Value
import org.springframework.stereotype.Service
import org.springframework.web.multipart.MultipartFile
import java.io.File
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.Paths
import java.nio.file.StandardCopyOption
import java.time.LocalDateTime
import java.util.UUID
import javax.imageio.ImageIO
import java.awt.image.BufferedImage
import java.awt.Image

@Service
class FileUploadService(
    @Value("\${upload.dir:uploads}")
    private val uploadDir: String,
    @Value("\${upload.base-url:http://localhost:8080}")
    private val baseUrl: String,
    @Value("\${upload.use-s3:false}")
    private val useS3: Boolean,
    private val s3Service: S3Service? = null
) {
    private val logger = LoggerFactory.getLogger(javaClass)

    companion object {
        const val MAX_FILE_SIZE = 10 * 1024 * 1024 // 10MB
        val ALLOWED_EXTENSIONS = setOf("jpg", "jpeg", "png", "gif", "webp")
        val ALLOWED_MIME_TYPES = setOf(
            "image/jpeg",
            "image/png",
            "image/gif",
            "image/webp"
        )
    }

    init {
        // Create upload directories if they don't exist
        createDirectories()
    }

    fun uploadImage(
        file: MultipartFile,
        type: String,
        metadata: String?,
        userId: Long
    ): ImageUploadResponse {
        // Validate file
        validateImageFile(file)

        // Use S3 if enabled
        if (useS3 && s3Service != null) {
            return uploadToS3(file, type, metadata, userId)
        }

        // Otherwise, use local storage
        return uploadToLocal(file, type, metadata, userId)
    }

    private fun uploadToS3(
        file: MultipartFile,
        type: String,
        metadata: String?,
        userId: Long
    ): ImageUploadResponse {
        try {
            // Create thumbnail
            val thumbnailBytes = createThumbnailBytes(file)

            // Upload to S3
            val (imageUrl, thumbnailUrl) = s3Service!!.uploadImageWithThumbnail(
                file = file,
                thumbnailBytes = thumbnailBytes,
                type = type,
                userId = userId
            )

            logger.info("Image uploaded to S3: $imageUrl")

            return ImageUploadResponse(
                success = true,
                imageUrl = imageUrl,
                thumbnailUrl = thumbnailUrl ?: imageUrl,
                imageId = UUID.randomUUID().toString(),
                uploadedAt = LocalDateTime.now()
            )
        } catch (e: Exception) {
            logger.error("Failed to upload to S3, falling back to local storage", e)
            // Fallback to local storage
            return uploadToLocal(file, type, metadata, userId)
        }
    }

    private fun uploadToLocal(
        file: MultipartFile,
        type: String,
        metadata: String?,
        userId: Long
    ): ImageUploadResponse {
        // Generate unique filename
        val fileExtension = getFileExtension(file.originalFilename ?: "")
        val imageId = UUID.randomUUID().toString()
        val fileName = "$imageId.$fileExtension"

        // Create type-specific directory
        val typeDir = Paths.get(uploadDir, type, userId.toString())
        Files.createDirectories(typeDir)

        // Save original file
        val filePath = typeDir.resolve(fileName)
        Files.copy(file.inputStream, filePath, StandardCopyOption.REPLACE_EXISTING)

        // Create thumbnails
        val thumbnailPath = createThumbnail(filePath, typeDir, imageId)

        // Generate URLs
        val imageUrl = "$baseUrl/uploads/$type/$userId/$fileName"
        val thumbnailUrl = "$baseUrl/uploads/$type/$userId/${imageId}_thumb.jpg"

        logger.info("Image uploaded to local storage: $imageUrl")

        return ImageUploadResponse(
            success = true,
            imageUrl = imageUrl,
            thumbnailUrl = thumbnailUrl,
            imageId = imageId,
            uploadedAt = LocalDateTime.now()
        )
    }

    private fun createThumbnailBytes(file: MultipartFile): ByteArray? {
        return try {
            val originalImage = ImageIO.read(file.inputStream)
            val thumbnail = resizeImage(originalImage, 400, 400)

            val outputStream = java.io.ByteArrayOutputStream()
            ImageIO.write(thumbnail, "jpg", outputStream)
            outputStream.toByteArray()
        } catch (e: Exception) {
            logger.error("Failed to create thumbnail", e)
            null
        }
    }

    private fun validateImageFile(file: MultipartFile) {
        // Check file size
        if (file.size > MAX_FILE_SIZE) {
            throw CustomException(
                ErrorCode.BAD_REQUEST,
                "File size exceeds maximum limit of 10MB"
            )
        }

        // Check MIME type
        if (file.contentType !in ALLOWED_MIME_TYPES) {
            throw CustomException(
                ErrorCode.BAD_REQUEST,
                "Invalid file type. Allowed types: JPEG, PNG, GIF, WebP"
            )
        }

        // Check file extension
        val extension = getFileExtension(file.originalFilename ?: "").lowercase()
        if (extension !in ALLOWED_EXTENSIONS) {
            throw CustomException(
                ErrorCode.BAD_REQUEST,
                "Invalid file extension. Allowed: jpg, jpeg, png, gif, webp"
            )
        }
    }

    private fun getFileExtension(filename: String): String {
        return filename.substringAfterLast('.', "")
    }

    private fun createThumbnail(
        originalPath: Path,
        outputDir: Path,
        imageId: String
    ): Path {
        try {
            val originalImage = ImageIO.read(originalPath.toFile())

            // Create 400x400 thumbnail
            val thumbnail = resizeImage(originalImage, 400, 400)
            val thumbnailPath = outputDir.resolve("${imageId}_thumb.jpg")
            ImageIO.write(thumbnail, "jpg", thumbnailPath.toFile())

            // Create 200x200 thumbnail
            val smallThumbnail = resizeImage(originalImage, 200, 200)
            val smallThumbnailPath = outputDir.resolve("${imageId}_thumb_small.jpg")
            ImageIO.write(smallThumbnail, "jpg", smallThumbnailPath.toFile())

            return thumbnailPath
        } catch (e: Exception) {
            logger.error("Failed to create thumbnail", e)
            return originalPath
        }
    }

    private fun resizeImage(
        originalImage: BufferedImage,
        targetWidth: Int,
        targetHeight: Int
    ): BufferedImage {
        val scaledImage = originalImage.getScaledInstance(
            targetWidth,
            targetHeight,
            Image.SCALE_SMOOTH
        )

        val resizedImage = BufferedImage(targetWidth, targetHeight, BufferedImage.TYPE_INT_RGB)
        val graphics = resizedImage.createGraphics()
        graphics.drawImage(scaledImage, 0, 0, null)
        graphics.dispose()

        return resizedImage
    }

    private fun createDirectories() {
        val dirs = listOf(
            Paths.get(uploadDir),
            Paths.get(uploadDir, "chat"),
            Paths.get(uploadDir, "meal"),
            Paths.get(uploadDir, "form_check"),
            Paths.get(uploadDir, "profile")
        )

        dirs.forEach { dir ->
            if (!Files.exists(dir)) {
                Files.createDirectories(dir)
                logger.info("Created upload directory: $dir")
            }
        }
    }
}