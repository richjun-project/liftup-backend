package com.richjun.liftupai.domain.workout.controller

import com.richjun.liftupai.domain.workout.dto.*
import com.richjun.liftupai.domain.workout.service.ProgramService
import com.richjun.liftupai.global.common.ApiResponse
import com.richjun.liftupai.global.security.CustomUserDetails
import org.springframework.http.HttpStatus
import org.springframework.http.ResponseEntity
import org.springframework.security.core.annotation.AuthenticationPrincipal
import org.springframework.web.bind.annotation.*

@RestController
@RequestMapping("/api/v2")
class ProgramController(
    private val programService: ProgramService
) {

    // ── Program Catalog ───────────────────────────────────────────────────────

    @GetMapping("/programs")
    fun getAllPrograms(): ResponseEntity<ApiResponse<ProgramListResponse>> {
        val response = programService.getAllPrograms()
        return ResponseEntity.ok(ApiResponse.success(response))
    }

    @GetMapping("/programs/recommended")
    fun getRecommendedProgram(
        @AuthenticationPrincipal userDetails: CustomUserDetails
    ): ResponseEntity<ApiResponse<ProgramDetailResponse>> {
        val response = programService.getRecommendedProgram(userDetails.getId())
        return ResponseEntity.ok(ApiResponse.success(response))
    }

    @GetMapping("/programs/{code}")
    fun getProgramDetail(
        @PathVariable code: String
    ): ResponseEntity<ApiResponse<ProgramDetailResponse>> {
        val response = programService.getProgramDetail(code)
        return ResponseEntity.ok(ApiResponse.success(response))
    }

    // ── Enrollment ────────────────────────────────────────────────────────────

    @PostMapping("/programs/enroll")
    fun enrollInProgram(
        @AuthenticationPrincipal userDetails: CustomUserDetails,
        @RequestBody request: EnrollRequest
    ): ResponseEntity<ApiResponse<EnrollmentStatusResponse>> {
        val response = programService.enrollInProgram(userDetails.getId(), request)
        return ResponseEntity.status(HttpStatus.CREATED).body(ApiResponse.success(response))
    }

    @GetMapping("/programs/enrollment/current")
    fun getCurrentEnrollment(
        @AuthenticationPrincipal userDetails: CustomUserDetails
    ): ResponseEntity<ApiResponse<EnrollmentStatusResponse>> {
        val response = programService.getCurrentEnrollment(userDetails.getId())
        return ResponseEntity.ok(ApiResponse.success(response))
    }

    @PutMapping("/programs/enrollment/current")
    fun updateEnrollment(
        @AuthenticationPrincipal userDetails: CustomUserDetails,
        @RequestParam status: String
    ): ResponseEntity<ApiResponse<EnrollmentStatusResponse>> {
        val response = programService.updateEnrollmentStatus(userDetails.getId(), status)
        return ResponseEntity.ok(ApiResponse.success(response))
    }

    @DeleteMapping("/programs/enrollment/current")
    fun abandonEnrollment(
        @AuthenticationPrincipal userDetails: CustomUserDetails
    ): ResponseEntity<ApiResponse<Map<String, Boolean>>> {
        programService.abandonEnrollment(userDetails.getId())
        return ResponseEntity.ok(ApiResponse.success(mapOf("success" to true)))
    }

    // ── Today's Workout ───────────────────────────────────────────────────────

    @GetMapping("/programs/enrollment/today")
    fun getTodayWorkout(
        @AuthenticationPrincipal userDetails: CustomUserDetails
    ): ResponseEntity<ApiResponse<TodayWorkoutResponse>> {
        val response = programService.getTodayWorkout(userDetails.getId())
        return ResponseEntity.ok(ApiResponse.success(response))
    }

    @GetMapping("/programs/enrollment/schedule")
    fun getWeeklySchedule(
        @AuthenticationPrincipal userDetails: CustomUserDetails
    ): ResponseEntity<ApiResponse<WeeklyScheduleResponse>> {
        val response = programService.getWeeklySchedule(userDetails.getId())
        return ResponseEntity.ok(ApiResponse.success(response))
    }

    // ── Exercise Substitution ─────────────────────────────────────────────────

    @GetMapping("/exercises/{id}/substitutes")
    fun getExerciseSubstitutes(
        @PathVariable id: Long
    ): ResponseEntity<ApiResponse<SubstituteListResponse>> {
        val response = programService.getExerciseSubstitutes(id)
        return ResponseEntity.ok(ApiResponse.success(response))
    }

    @PostMapping("/programs/enrollment/override")
    fun overrideExercise(
        @AuthenticationPrincipal userDetails: CustomUserDetails,
        @RequestBody request: ExerciseOverrideRequest
    ): ResponseEntity<ApiResponse<Map<String, Boolean>>> {
        programService.overrideExercise(userDetails.getId(), request)
        return ResponseEntity.ok(ApiResponse.success(mapOf("success" to true)))
    }
}
