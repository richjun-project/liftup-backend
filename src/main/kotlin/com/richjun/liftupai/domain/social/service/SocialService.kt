package com.richjun.liftupai.domain.social.service

import com.richjun.liftupai.domain.auth.repository.UserRepository
import com.richjun.liftupai.domain.social.dto.*
import com.richjun.liftupai.domain.social.entity.SharedWorkout
import com.richjun.liftupai.domain.social.repository.SharedWorkoutRepository
import com.richjun.liftupai.domain.user.repository.UserProfileRepository
import com.richjun.liftupai.domain.workout.repository.WorkoutSessionRepository
import com.richjun.liftupai.global.exception.ResourceNotFoundException
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional
import java.util.UUID

@Service
@Transactional
class SocialService(
    private val userRepository: UserRepository,
    private val userProfileRepository: UserProfileRepository,
    private val workoutSessionRepository: WorkoutSessionRepository,
    private val sharedWorkoutRepository: SharedWorkoutRepository,
    private val workoutLogRepository: com.richjun.liftupai.domain.workout.repository.WorkoutLogRepository
) {

    fun shareWorkout(userId: Long, request: ShareWorkoutRequest): ShareWorkoutResponse {
        val user = userRepository.findById(userId)
            .orElseThrow { ResourceNotFoundException("사용자를 찾을 수 없습니다") }

        val session = workoutSessionRepository.findById(request.sessionId)
            .orElseThrow { ResourceNotFoundException("운동 세션을 찾을 수 없습니다") }

        if (session.user.id != userId) {
            throw IllegalArgumentException("본인의 운동 세션만 공유할 수 있습니다")
        }

        val shareId = UUID.randomUUID().toString()
        val shareUrl = "https://liftup.ai/workout/$shareId"

        // WorkoutLog를 repository에서 조회
        val logs = workoutLogRepository.findBySession(session)
        val totalVolume = logs.sumOf { log -> (log.weight * log.reps).toInt() }
        val duration = if (session.endTime != null) {
            java.time.Duration.between(session.startTime, session.endTime).toMinutes()
        } else {
            0L
        }
        val totalSets = logs.size

        val title = "${session.name ?: "운동"} 완료! 💪"
        val stats = "${duration}분 • ${totalVolume/1000.0}톤 • ${totalSets}세트"

        val sharedWorkout = SharedWorkout(
            shareId = shareId,
            user = user,
            session = session,
            shareType = request.shareType,
            visibility = request.visibility,
            shareUrl = shareUrl,
            title = title,
            stats = stats,
            imageUrl = "https://example.com/share-image.png"
        )

        sharedWorkoutRepository.save(sharedWorkout)

        return ShareWorkoutResponse(
            shareId = shareId,
            shareUrl = shareUrl,
            preview = SharePreview(
                title = title,
                stats = stats,
                image = sharedWorkout.imageUrl ?: ""
            )
        )
    }

    @Transactional(readOnly = true)
    fun findPartners(userId: Long, gymLocation: String?, workoutTime: String?, level: String?): FindPartnersResponse {
        val user = userRepository.findById(userId)
            .orElseThrow { ResourceNotFoundException("사용자를 찾을 수 없습니다") }

        val userProfile = userProfileRepository.findByUser_Id(userId).orElse(null)

        val allProfiles = userProfileRepository.findAll()
            .filter { it.user.id != userId }

        val partners = allProfiles.mapNotNull { profile ->
            val otherUser = userRepository.findById(profile.user.id).orElse(null) ?: return@mapNotNull null

            var matchScore = 50

            if (gymLocation != null && profile.gymLocation == gymLocation) {
                matchScore += 20
            }

            if (workoutTime != null && profile.preferredWorkoutTime == workoutTime) {
                matchScore += 20
            }

            if (level != null && profile.experienceLevel.name.lowercase() == level.lowercase()) {
                matchScore += 10
            }

            WorkoutPartner(
                userId = otherUser.id.toString(),
                nickname = otherUser.nickname,
                level = profile.experienceLevel.name.lowercase(),
                preferredTime = profile.preferredWorkoutTime ?: "07:00-09:00",
                workoutSplit = profile.workoutSplit ?: "3분할",
                matchScore = matchScore
            )
        }
            .sortedByDescending { it.matchScore }
            .take(10)

        return FindPartnersResponse(partners = partners)
    }
}