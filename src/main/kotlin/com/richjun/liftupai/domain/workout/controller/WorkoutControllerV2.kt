package com.richjun.liftupai.domain.workout.controller

import com.richjun.liftupai.domain.workout.dto.*
import com.richjun.liftupai.domain.workout.service.WorkoutServiceV2
import com.richjun.liftupai.domain.workout.util.WorkoutLocalization
import com.richjun.liftupai.global.common.ApiResponse
import com.richjun.liftupai.global.security.CustomUserDetails
import jakarta.validation.Valid
import org.springframework.http.HttpStatus
import org.springframework.http.ResponseEntity
import org.springframework.security.core.annotation.AuthenticationPrincipal
import org.slf4j.LoggerFactory
import org.springframework.web.bind.annotation.*

@RestController
@RequestMapping("/api/v2/workouts")
class WorkoutControllerV2(
    private val workoutServiceV2: WorkoutServiceV2,
    private val workoutService: com.richjun.liftupai.domain.workout.service.WorkoutService,
    private val workoutPlanService: com.richjun.liftupai.domain.workout.service.WorkoutPlanService
) {
    private val logger = LoggerFactory.getLogger(this::class.java)

    // 기존 운동 세션 시작 (호환성 유지 - 진행 중인 세션이 있으면 반환, 없으면 새로 생성)
    @PostMapping("/start")
    fun startWorkout(
        @AuthenticationPrincipal userDetails: CustomUserDetails,
        @Valid @RequestBody request: StartWorkoutRequestV2
    ): ResponseEntity<ApiResponse<StartWorkoutResponseV2>> {
        val response = workoutServiceV2.startWorkout(userDetails.getId(), request)
        return ResponseEntity.status(HttpStatus.CREATED)
            .body(ApiResponse.success(response))
    }

    // 새로운 운동 세션 시작
    @PostMapping("/start/new")
    fun startNewWorkout(
        @AuthenticationPrincipal userDetails: CustomUserDetails,
        @Valid @RequestBody request: StartWorkoutRequestV2
    ): ResponseEntity<ApiResponse<StartWorkoutResponseV2>> {
        val response = workoutServiceV2.startNewWorkout(userDetails.getId(), request)
        return ResponseEntity.status(HttpStatus.CREATED)
            .body(ApiResponse.success(response))
    }

    // 진행 중인 운동 세션 이어하기
    @PostMapping("/start/continue")
    fun continueWorkout(
        @AuthenticationPrincipal userDetails: CustomUserDetails
    ): ResponseEntity<ApiResponse<StartWorkoutResponseV2>> {
        val response = workoutServiceV2.continueWorkout(userDetails.getId())
        return ResponseEntity.ok(ApiResponse.success(response))
    }

    // 현재 진행 중인 운동 세션 조회
    @GetMapping("/current-session")
    fun getCurrentSession(
        @AuthenticationPrincipal userDetails: CustomUserDetails
    ): ResponseEntity<ApiResponse<WorkoutDetailResponse?>> {
        val response = workoutService.getCurrentSession(userDetails.getId())
        return ResponseEntity.ok(ApiResponse.success(response))
    }

    // 운동 기록 조회 (history)
    @GetMapping("/history")
    fun getWorkoutHistory(
        @AuthenticationPrincipal userDetails: CustomUserDetails,
        @RequestParam(defaultValue = "1") page: Int,
        @RequestParam(defaultValue = "10") limit: Int
    ): ResponseEntity<ApiResponse<WorkoutSessionsResponse>> {
        val pageable = org.springframework.data.domain.PageRequest.of(page - 1, limit)
        val response = workoutService.getWorkoutSessions(userDetails.getId(), pageable, null, null)
        return ResponseEntity.ok(ApiResponse.success(response))
    }

    // 특정 운동 세션 조회
    @GetMapping("/{sessionId}")
    fun getWorkoutSession(
        @AuthenticationPrincipal userDetails: CustomUserDetails,
        @PathVariable sessionId: Long
    ): ResponseEntity<ApiResponse<WorkoutDetailResponse>> {
        val response = workoutService.getWorkoutSession(userDetails.getId(), sessionId)
        return ResponseEntity.ok(ApiResponse.success(response))
    }

    // 현재 프로그램 진행 상황 조회
    @GetMapping("/program-status")
    fun getProgramStatus(
        @AuthenticationPrincipal userDetails: CustomUserDetails,
        @RequestParam(required = false) locale: String?
    ): ResponseEntity<ApiResponse<ProgramStatusResponse>> {
        val response = workoutService.getProgramStatus(userDetails.getId(), locale)
        return ResponseEntity.ok(ApiResponse.success(response))
    }

    // 운동 세션 완료 V2 (통계 포함)
    @PutMapping("/{sessionId}/complete")
    fun completeWorkout(
        @AuthenticationPrincipal userDetails: CustomUserDetails,
        @PathVariable sessionId: Long,
        @Valid @RequestBody request: CompleteWorkoutRequestV2
    ): ResponseEntity<ApiResponse<CompleteWorkoutResponseV2>> {
        logger.debug("completeWorkout called: userId={}, sessionId={}, exercises={}, duration={}",
            userDetails.getId(), sessionId, request.exercises.size, request.duration)
        val response = workoutServiceV2.completeWorkout(userDetails.getId(), sessionId, request)
        return ResponseEntity.ok(ApiResponse.success(response))
    }

    // 세션 전체 업데이트 (운동, 세트 모두)
    @PutMapping("/{sessionId}/update")
    fun updateSession(
        @AuthenticationPrincipal userDetails: CustomUserDetails,
        @PathVariable sessionId: Long,
        @Valid @RequestBody request: UpdateSessionRequest,
        @RequestParam(required = false) locale: String?
    ): ResponseEntity<ApiResponse<UpdateSessionResponse>> {
        val response = workoutServiceV2.updateSession(userDetails.getId(), sessionId, request, locale)
        return ResponseEntity.ok(ApiResponse.success(response))
    }

    // 실시간 세트 업데이트
    @PostMapping("/{sessionId}/sets/update")
    fun updateSet(
        @AuthenticationPrincipal userDetails: CustomUserDetails,
        @PathVariable sessionId: Long,
        @Valid @RequestBody request: UpdateSetRequest
    ): ResponseEntity<ApiResponse<UpdateSetResponse>> {
        val response = workoutServiceV2.updateSet(userDetails.getId(), sessionId, request)
        return ResponseEntity.ok(ApiResponse.success(response))
    }

    // 다음 세트 조정
    @PostMapping("/adjust-next-set")
    fun adjustNextSet(
        @AuthenticationPrincipal userDetails: CustomUserDetails,
        @Valid @RequestBody request: AdjustNextSetRequest,
        @RequestParam(required = false) locale: String?
    ): ResponseEntity<ApiResponse<AdjustNextSetResponse>> {
        val response = workoutServiceV2.adjustNextSet(userDetails.getId(), request, locale)
        return ResponseEntity.ok(ApiResponse.success(response))
    }

    // 휴식 타이머 추천
    @GetMapping("/rest-timer")
    fun getRestTimer(
        @AuthenticationPrincipal userDetails: CustomUserDetails,
        @RequestParam exerciseType: String,
        @RequestParam intensity: String,
        @RequestParam setNumber: Int,
        @RequestParam(required = false) locale: String?
    ): ResponseEntity<ApiResponse<RestTimerResponse>> {
        val response = workoutServiceV2.getRestTimer(userDetails.getId(), exerciseType, intensity, setNumber, locale)
        return ResponseEntity.ok(ApiResponse.success(response))
    }

    // === WorkoutControllerV4에서 이동된 메서드들 ===

    // 운동 계획 업데이트
    @PutMapping("/plan")
    fun updateWorkoutPlan(
        @AuthenticationPrincipal userDetails: CustomUserDetails,
        @Valid @RequestBody request: com.richjun.liftupai.domain.user.dto.WorkoutPlanRequest,
        @RequestParam(required = false) locale: String?
    ): ResponseEntity<ApiResponse<Map<String, Any>>> {
        val plan = workoutPlanService.updateWorkoutPlan(userDetails.getId(), request)
        return ResponseEntity.ok(ApiResponse.success(mapOf(
            "message" to WorkoutLocalization.message("workout.plan.updated", WorkoutLocalization.normalizeLocale(locale)),
            "plan" to plan
        )))
    }

    // 맞춤형 운동 프로그램 생성
    @PostMapping("/generate-program")
    fun generateProgram(
        @AuthenticationPrincipal userDetails: CustomUserDetails,
        @Valid @RequestBody request: com.richjun.liftupai.domain.user.dto.GenerateProgramRequest
    ): ResponseEntity<ApiResponse<com.richjun.liftupai.domain.user.dto.GeneratedProgramResponse>> {
        val program = workoutPlanService.generateProgram(userDetails.getId(), request)
        return ResponseEntity.status(HttpStatus.CREATED)
            .body(ApiResponse.success(program))
    }

    // 기본 운동 추천
    @GetMapping("/recommendations/basic")
    fun getBasicWorkoutRecommendation(
        @AuthenticationPrincipal userDetails: CustomUserDetails,
        @RequestParam(required = false) duration: Int?,
        @RequestParam(required = false) equipment: String?,
        @RequestParam(required = false) targetMuscle: String?,
        @RequestParam(required = false) locale: String?
    ): ResponseEntity<ApiResponse<QuickWorkoutRecommendationResponse>> {
        val response = workoutServiceV2.getBasicWorkoutRecommendation(
            userDetails.getId(),
            duration,
            equipment,
            targetMuscle,
            locale
        )
        return ResponseEntity.ok(ApiResponse.success(response))
    }

}
