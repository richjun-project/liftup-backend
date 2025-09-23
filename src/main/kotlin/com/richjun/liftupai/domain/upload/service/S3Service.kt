package com.richjun.liftupai.domain.upload.service

import org.slf4j.LoggerFactory
import org.springframework.beans.factory.annotation.Value
import org.springframework.stereotype.Service
import software.amazon.awssdk.auth.credentials.AwsBasicCredentials
import software.amazon.awssdk.auth.credentials.StaticCredentialsProvider
import software.amazon.awssdk.core.sync.RequestBody
import software.amazon.awssdk.regions.Region
import software.amazon.awssdk.services.s3.S3Client
import software.amazon.awssdk.services.s3.model.*
import org.springframework.web.multipart.MultipartFile
import java.util.UUID
import javax.annotation.PostConstruct

@Service
class S3Service(
    @Value("\${cloud.aws.credentials.access-key}")
    private val accessKey: String,

    @Value("\${cloud.aws.credentials.secret-key}")
    private val secretKey: String,

    @Value("\${cloud.aws.region.static}")
    private val region: String,

    @Value("\${cloud.aws.s3.bucket}")
    private val bucketName: String,

    @Value("\${cloud.aws.s3.folder}")
    private val folder: String
) {
    private val logger = LoggerFactory.getLogger(javaClass)
    private lateinit var s3Client: S3Client

    @PostConstruct
    fun init() {
        val awsCredentials = AwsBasicCredentials.create(accessKey, secretKey)

        s3Client = S3Client.builder()
            .region(Region.of(region))
            .credentialsProvider(StaticCredentialsProvider.create(awsCredentials))
            .build()

        logger.info("S3 client initialized for bucket: $bucketName in region: $region")
    }

    fun uploadFile(
        file: MultipartFile,
        type: String,
        userId: Long
    ): String {
        val fileExtension = getFileExtension(file.originalFilename ?: "")
        val fileName = "${UUID.randomUUID()}.$fileExtension"
        val key = "$folder$type/$userId/$fileName"

        return try {
            // Prepare the file for upload
            val putObjectRequest = PutObjectRequest.builder()
                .bucket(bucketName)
                .key(key)
                .contentType(file.contentType ?: "application/octet-stream")
                .build()

            // Upload file to S3
            s3Client.putObject(
                putObjectRequest,
                RequestBody.fromBytes(file.bytes)
            )

            val fileUrl = "https://$bucketName.s3.$region.amazonaws.com/$key"
            logger.info("File uploaded successfully to S3: $fileUrl")

            fileUrl
        } catch (e: Exception) {
            logger.error("Failed to upload file to S3", e)
            throw RuntimeException("Failed to upload file to S3: ${e.message}", e)
        }
    }

    fun uploadImageWithThumbnail(
        file: MultipartFile,
        thumbnailBytes: ByteArray?,
        type: String,
        userId: Long
    ): Pair<String, String?> {
        val fileExtension = getFileExtension(file.originalFilename ?: "")
        val imageId = UUID.randomUUID().toString()
        val fileName = "$imageId.$fileExtension"
        val key = "$folder$type/$userId/$fileName"

        try {
            // Upload original image
            val putObjectRequest = PutObjectRequest.builder()
                .bucket(bucketName)
                .key(key)
                .contentType(file.contentType ?: "image/jpeg")
                .build()

            s3Client.putObject(
                putObjectRequest,
                RequestBody.fromBytes(file.bytes)
            )

            val imageUrl = "https://$bucketName.s3.$region.amazonaws.com/$key"
            logger.info("Image uploaded to S3: $imageUrl")

            // Upload thumbnail if provided
            var thumbnailUrl: String? = null
            if (thumbnailBytes != null) {
                val thumbnailKey = "$folder$type/$userId/${imageId}_thumb.jpg"
                val thumbnailRequest = PutObjectRequest.builder()
                    .bucket(bucketName)
                    .key(thumbnailKey)
                    .contentType("image/jpeg")
                    .build()

                s3Client.putObject(
                    thumbnailRequest,
                    RequestBody.fromBytes(thumbnailBytes)
                )

                thumbnailUrl = "https://$bucketName.s3.$region.amazonaws.com/$thumbnailKey"
                logger.info("Thumbnail uploaded to S3: $thumbnailUrl")
            }

            return Pair(imageUrl, thumbnailUrl)
        } catch (e: Exception) {
            logger.error("Failed to upload image to S3", e)
            throw RuntimeException("Failed to upload image to S3: ${e.message}", e)
        }
    }

    fun deleteFile(fileUrl: String): Boolean {
        return try {
            // Extract key from URL
            val key = extractKeyFromUrl(fileUrl)

            val deleteRequest = DeleteObjectRequest.builder()
                .bucket(bucketName)
                .key(key)
                .build()

            s3Client.deleteObject(deleteRequest)
            logger.info("File deleted from S3: $fileUrl")
            true
        } catch (e: Exception) {
            logger.error("Failed to delete file from S3", e)
            false
        }
    }

    private fun getFileExtension(filename: String): String {
        return filename.substringAfterLast('.', "jpg")
    }

    private fun extractKeyFromUrl(url: String): String {
        // URL format: https://bucketname.s3.region.amazonaws.com/key
        return url.substringAfter(".amazonaws.com/")
    }
}