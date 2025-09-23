package com.richjun.liftupai.domain.workout.controller

import com.richjun.liftupai.domain.workout.dto.*
import com.richjun.liftupai.domain.workout.service.WorkoutServiceV2
import com.richjun.liftupai.global.common.ApiResponse
import com.richjun.liftupai.global.security.CustomUserDetails
import jakarta.validation.Valid
import org.springframework.http.HttpStatus
import org.springframework.http.ResponseEntity
import org.springframework.security.core.annotation.AuthenticationPrincipal
import org.springframework.web.bind.annotation.*

@RestController
@RequestMapping("/api/v2/workouts")
class WorkoutControllerV2(
    private val workoutServiceV2: WorkoutServiceV2,
    private val workoutService: com.richjun.liftupai.domain.workout.service.WorkoutService,
    private val workoutPlanService: com.richjun.liftupai.domain.workout.service.WorkoutPlanService
) {

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
        @AuthenticationPrincipal userDetails: CustomUserDetails
    ): ResponseEntity<ApiResponse<ProgramStatusResponse>> {
        val response = workoutService.getProgramStatus(userDetails.getId())
        return ResponseEntity.ok(ApiResponse.success(response))
    }

    // 운동 세션 완료 V2 (통계 포함)
    @PutMapping("/{sessionId}/complete")
    fun completeWorkout(
        @AuthenticationPrincipal userDetails: CustomUserDetails,
        @PathVariable sessionId: Long,
        @Valid @RequestBody request: CompleteWorkoutRequestV2
    ): ResponseEntity<ApiResponse<CompleteWorkoutResponseV2>> {
        println("DEBUG Controller: completeWorkout endpoint called")
        println("DEBUG Controller: userId=${userDetails.getId()}, sessionId=$sessionId")
        println("DEBUG Controller: request.exercises.size=${request.exercises.size}")
        println("DEBUG Controller: request.duration=${request.duration}")
        request.exercises.forEach { exercise ->
            println("DEBUG Controller: - exerciseId=${exercise.exerciseId}, sets=${exercise.sets.size}")
        }
        val response = workoutServiceV2.completeWorkout(userDetails.getId(), sessionId, request)
        return ResponseEntity.ok(ApiResponse.success(response))
    }

    // 세션 전체 업데이트 (운동, 세트 모두)
    @PutMapping("/{sessionId}/update")
    fun updateSession(
        @AuthenticationPrincipal userDetails: CustomUserDetails,
        @PathVariable sessionId: Long,
        @Valid @RequestBody request: UpdateSessionRequest
    ): ResponseEntity<ApiResponse<UpdateSessionResponse>> {
        val response = workoutServiceV2.updateSession(userDetails.getId(), sessionId, request)
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
        @Valid @RequestBody request: AdjustNextSetRequest
    ): ResponseEntity<ApiResponse<AdjustNextSetResponse>> {
        val response = workoutServiceV2.adjustNextSet(userDetails.getId(), request)
        return ResponseEntity.ok(ApiResponse.success(response))
    }

    // 휴식 타이머 추천
    @GetMapping("/rest-timer")
    fun getRestTimer(
        @AuthenticationPrincipal userDetails: CustomUserDetails,
        @RequestParam exerciseType: String,
        @RequestParam intensity: String,
        @RequestParam setNumber: Int
    ): ResponseEntity<ApiResponse<RestTimerResponse>> {
        val response = workoutServiceV2.getRestTimer(exerciseType, intensity, setNumber)
        return ResponseEntity.ok(ApiResponse.success(response))
    }

    // === WorkoutControllerV4에서 이동된 메서드들 ===

    // 운동 계획 업데이트
    @PutMapping("/plan")
    fun updateWorkoutPlan(
        @AuthenticationPrincipal userDetails: CustomUserDetails,
        @Valid @RequestBody request: com.richjun.liftupai.domain.user.dto.WorkoutPlanRequest
    ): ResponseEntity<ApiResponse<Map<String, Any>>> {
        val plan = workoutPlanService.updateWorkoutPlan(userDetails.getId(), request)
        return ResponseEntity.ok(ApiResponse.success(mapOf(
            "message" to "운동 계획이 업데이트되었습니다",
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

    // 오늘의 운동 추천 (프로필 기반) - Deprecated, use AI recommendations instead
    @Deprecated("Use GET /api/ai/recommendations/workout instead")
    @PostMapping("/recommendations/today")
    fun getTodayWorkoutRecommendation(
        @AuthenticationPrincipal userDetails: CustomUserDetails,
        @Valid @RequestBody request: com.richjun.liftupai.domain.user.dto.TodayWorkoutRequest
    ): ResponseEntity<ApiResponse<com.richjun.liftupai.domain.user.dto.TodayWorkoutResponse>> {
        val recommendation = workoutPlanService.getTodayWorkoutRecommendation(
            userDetails.getId(), request
        )
        return ResponseEntity.ok(ApiResponse.success(recommendation))
    }

    // 빠른 운동 추천
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

    // 추천 운동 시작 - Deprecated, use /start/new with workout_type instead
    @Deprecated("Use POST /api/v2/workouts/start/new with workout_type='quick' or 'ai' instead")
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