package com.richjun.liftupai.domain.workout.controller

import com.richjun.liftupai.domain.workout.dto.*
import com.richjun.liftupai.domain.workout.service.WorkoutService
import com.richjun.liftupai.domain.workout.service.WorkoutServiceV2
import com.richjun.liftupai.global.common.ApiResponse
import com.richjun.liftupai.global.security.CustomUserDetails
import jakarta.validation.Valid
import org.springframework.data.domain.PageRequest
import org.springframework.format.annotation.DateTimeFormat
import org.springframework.http.HttpStatus
import org.springframework.http.ResponseEntity
import org.springframework.security.core.annotation.AuthenticationPrincipal
import org.springframework.web.bind.annotation.*
import java.time.LocalDateTime

@RestController
@RequestMapping("/api/workouts")
class WorkoutController(
    private val workoutService: WorkoutService,
    private val workoutServiceV2: WorkoutServiceV2
) {

    // 운동 세션 시작 (V1 - Deprecated, V2 API 사용 권장)
    @Deprecated("Use V2 API: /api/v2/workouts/start/new instead")
    @PostMapping("/start")
    fun startWorkout(
        @AuthenticationPrincipal userDetails: CustomUserDetails,
        @Valid @RequestBody request: StartWorkoutRequest
    ): ResponseEntity<ApiResponse<StartWorkoutResponse>> {
        val response = workoutService.startWorkout(userDetails.getId(), request)
        return ResponseEntity.status(HttpStatus.CREATED)
            .body(ApiResponse.success(response))
    }

    // 운동 세션 종료 (V1 - Deprecated, V2 API 사용 권장)
    @Deprecated("Use V2 API: /api/v2/workouts/{sessionId}/complete instead")
    @PutMapping("/{sessionId}/end")
    fun endWorkout(
        @AuthenticationPrincipal userDetails: CustomUserDetails,
        @PathVariable sessionId: Long,
        @Valid @RequestBody request: EndWorkoutRequest
    ): ResponseEntity<ApiResponse<WorkoutSummaryResponse>> {
        val response = workoutService.endWorkout(userDetails.getId(), sessionId, request)
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

    // 현재 프로그램 진행 상황 조회
    @GetMapping("/program-status")
    fun getProgramStatus(
        @AuthenticationPrincipal userDetails: CustomUserDetails
    ): ResponseEntity<ApiResponse<ProgramStatusResponse>> {
        val response = workoutService.getProgramStatus(userDetails.getId())
        return ResponseEntity.ok(ApiResponse.success(response))
    }

    // 운동 세션 목록 조회
    @GetMapping("/sessions")
    fun getWorkoutSessions(
        @AuthenticationPrincipal userDetails: CustomUserDetails,
        @RequestParam(defaultValue = "1") page: Int,
        @RequestParam(defaultValue = "10") limit: Int,
        @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE_TIME) startDate: LocalDateTime?,
        @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE_TIME) endDate: LocalDateTime?
    ): ResponseEntity<ApiResponse<WorkoutSessionsResponse>> {
        val pageable = PageRequest.of(page - 1, limit)
        val response = workoutService.getWorkoutSessions(userDetails.getId(), pageable, startDate, endDate)
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

    // 운동 세션 수정
    @PutMapping("/{sessionId}")
    fun updateWorkoutSession(
        @AuthenticationPrincipal userDetails: CustomUserDetails,
        @PathVariable sessionId: Long,
        @Valid @RequestBody request: UpdateWorkoutRequest
    ): ResponseEntity<ApiResponse<WorkoutDetailResponse>> {
        val response = workoutService.updateWorkoutSession(userDetails.getId(), sessionId, request)
        return ResponseEntity.ok(ApiResponse.success(response))
    }

    // 운동 세션 삭제
    @DeleteMapping("/{sessionId}")
    fun deleteWorkoutSession(
        @AuthenticationPrincipal userDetails: CustomUserDetails,
        @PathVariable sessionId: Long
    ): ResponseEntity<ApiResponse<Map<String, Boolean>>> {
        workoutService.deleteWorkoutSession(userDetails.getId(), sessionId)
        return ResponseEntity.ok(ApiResponse.success(mapOf("success" to true)))
    }

    // 운동 세트 추가
    @PostMapping("/exercises/{exerciseId}/sets")
    fun addExerciseSet(
        @AuthenticationPrincipal userDetails: CustomUserDetails,
        @PathVariable exerciseId: Long,
        @Valid @RequestBody request: AddSetRequest
    ): ResponseEntity<ApiResponse<AddSetResponse>> {
        val response = workoutService.addExerciseSet(userDetails.getId(), exerciseId, request)
        return ResponseEntity.status(HttpStatus.CREATED)
            .body(ApiResponse.success(response))
    }

    // 오늘의 운동 추천 (Deprecated - Use AI recommendations instead)
    @Deprecated("Use GET /api/ai/recommendations/workout instead")
    @GetMapping("/recommendations/today")
    fun getTodayRecommendations(
        @AuthenticationPrincipal userDetails: CustomUserDetails
    ): ResponseEntity<ApiResponse<TodayWorkoutRecommendation>> {
        val response = workoutService.getTodayRecommendations(userDetails.getId())
        return ResponseEntity.ok(ApiResponse.success(response))
    }

    // 운동 프로그램 생성
    @PostMapping("/programs/generate")
    fun generateProgram(
        @AuthenticationPrincipal userDetails: CustomUserDetails,
        @Valid @RequestBody request: GenerateProgramRequest
    ): ResponseEntity<ApiResponse<WorkoutProgramResponse>> {
        val response = workoutService.generateProgram(userDetails.getId(), request)
        return ResponseEntity.status(HttpStatus.CREATED)
            .body(ApiResponse.success(response))
    }

    // 현재 프로그램 조회
    @GetMapping("/programs/current")
    fun getCurrentProgram(
        @AuthenticationPrincipal userDetails: CustomUserDetails
    ): ResponseEntity<ApiResponse<CurrentProgramResponse>> {
        val response = workoutService.getCurrentProgram(userDetails.getId())
        return ResponseEntity.ok(ApiResponse.success(response))
    }

    // 회복 상태 조회
    @GetMapping("/recovery-status")
    fun getRecoveryStatus(
        @AuthenticationPrincipal userDetails: CustomUserDetails
    ): ResponseEntity<ApiResponse<RecoveryStatusResponse>> {
        val response = workoutService.getRecoveryStatus(userDetails.getId())
        return ResponseEntity.ok(ApiResponse.success(response))
    }

    // 볼륨 조절
    @PostMapping("/adjust-volume")
    fun adjustVolume(
        @AuthenticationPrincipal userDetails: CustomUserDetails,
        @Valid @RequestBody request: AdjustVolumeRequest
    ): ResponseEntity<ApiResponse<AdjustedVolumeResponse>> {
        val response = workoutService.adjustVolume(userDetails.getId(), request)
        return ResponseEntity.ok(ApiResponse.success(response))
    }

    // 추천 중량 계산
    @PostMapping("/calculate-weight")
    fun calculateWeight(
        @AuthenticationPrincipal userDetails: CustomUserDetails,
        @Valid @RequestBody request: CalculateWeightRequest
    ): ResponseEntity<ApiResponse<WeightRecommendation>> {
        val response = workoutService.calculateWeight(userDetails.getId(), request)
        return ResponseEntity.ok(ApiResponse.success(response))
    }

    // 1RM 추정
    @PostMapping("/estimate-1rm")
    fun estimate1RM(
        @AuthenticationPrincipal userDetails: CustomUserDetails,
        @Valid @RequestBody request: Estimate1RMRequest
    ): ResponseEntity<ApiResponse<OneRMEstimation>> {
        val response = workoutService.estimate1RM(request)
        return ResponseEntity.ok(ApiResponse.success(response))
    }

    // 근력 테스트
    @PostMapping("/strength-test")
    fun strengthTest(
        @AuthenticationPrincipal userDetails: CustomUserDetails,
        @Valid @RequestBody request: StrengthTestRequest
    ): ResponseEntity<ApiResponse<StrengthTestResult>> {
        val response = workoutService.processStrengthTest(userDetails.getId(), request)
        return ResponseEntity.ok(ApiResponse.success(response))
    }

    // 근력 기준표 조회
    @GetMapping("/strength-standards")
    fun getStrengthStandards(
        @RequestParam gender: String,
        @RequestParam bodyWeight: Double
    ): ResponseEntity<ApiResponse<StrengthStandardsResponse>> {
        val response = workoutService.getStrengthStandards(gender, bodyWeight)
        return ResponseEntity.ok(ApiResponse.success(response))
    }

    // Quick workout recommendations V3
    @GetMapping("/recommendations/quick")
    fun getQuickWorkoutRecommendation(
        @AuthenticationPrincipal userDetails: CustomUserDetails,
        @RequestParam(required = false) duration: Int?,
        @RequestParam(required = false) equipment: String?,
        @RequestParam(required = false) targetMuscle: String?
    ): ResponseEntity<ApiResponse<QuickWorkoutRecommendationResponse>> {
        val response = workoutServiceV2.getQuickWorkoutRecommendation(
            userDetails.getId(),
            duration,
            equipment,
            targetMuscle
        )
        return ResponseEntity.ok(ApiResponse.success(response))
    }

    // Start recommended workout immediately V3
    @PostMapping("/start-recommended")
    fun startRecommendedWorkout(
        @AuthenticationPrincipal userDetails: CustomUserDetails,
        @Valid @RequestBody request: StartRecommendedWorkoutRequest
    ): ResponseEntity<ApiResponse<StartRecommendedWorkoutResponse>> {
        val response = workoutServiceV2.startRecommendedWorkout(userDetails.getId(), request)
        return ResponseEntity.status(HttpStatus.CREATED)
            .body(ApiResponse.success(response))
    }
}