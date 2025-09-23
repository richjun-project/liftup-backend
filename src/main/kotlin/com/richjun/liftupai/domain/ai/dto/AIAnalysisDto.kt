package com.richjun.liftupai.domain.ai.dto

import com.fasterxml.jackson.annotation.JsonProperty
import jakarta.validation.constraints.NotBlank
import jakarta.validation.constraints.NotNull
import jakarta.validation.constraints.Min

// Form Analysis DTOs
data class FormAnalysisRequest(
    @JsonProperty("exercise_id")
    @field:NotNull
    val exerciseId: Long,

    @JsonProperty("video_url")
    val videoUrl: String? = null,

    @JsonProperty("image_url")
    val imageUrl: String? = null
)

data class FormAnalysisResponse(
    val analysis: String,
    val score: Int,
    val improvements: List<String>,
    val corrections: List<String>
)

// Recommendations DTOs
data class RecommendationsResponse(
    val workouts: List<WorkoutRecommendation>,
    val nutrition: List<NutritionRecommendation>,
    val recovery: List<RecoveryRecommendation>
)

data class WorkoutRecommendation(
    @JsonProperty("exercise_id")
    val exerciseId: Long,

    val name: String,
    val sets: Int,
    val reps: String,
    val reason: String,
    val difficulty: String
)

data class NutritionRecommendation(
    val food: String,
    val calories: Int,
    val macros: Macros,
    val timing: String,
    val reason: String
)

data class RecoveryRecommendation(
    val activity: String,
    val duration: Int,
    val intensity: String,
    val benefits: List<String>
)

// Meal Analysis DTOs
data class MealAnalysisRequest(
    @JsonProperty("image_url")
    @field:NotBlank
    val imageUrl: String
)

data class MealAnalysisResponse(
    @JsonProperty("meal_info")
    val mealInfo: MealInfo,

    val calories: Int,
    val macros: Macros,
    val suggestions: List<String>
)

data class MealInfo(
    val name: String,
    val ingredients: List<String>,
    val portion: String,
    val type: String
)

data class Macros(
    val protein: Double,
    val carbs: Double,
    val fat: Double
)

// Image Upload DTOs
data class ImageUploadResponse(
    val url: String
)

// Nutrition History DTOs
data class NutritionHistoryResponse(
    val meals: List<MealEntry>,

    @JsonProperty("total_calories")
    val totalCalories: Int,

    @JsonProperty("avg_macros")
    val avgMacros: Macros
)

data class MealEntry(
    @JsonProperty("meal_id")
    val mealId: Long,

    @JsonProperty("meal_type")
    val mealType: String,

    val foods: List<FoodItem>,
    val calories: Int,
    val macros: Macros,
    val timestamp: String
)

data class FoodItem(
    val name: String,
    val quantity: Double,
    val unit: String,
    val calories: Int,
    val protein: Double,
    val carbs: Double,
    val fat: Double
)

// Nutrition Log DTOs
data class NutritionLogRequest(
    @JsonProperty("meal_type")
    @field:NotBlank
    val mealType: String,

    val foods: List<FoodItem>,

    @field:NotNull
    @field:Min(0)
    val calories: Int,

    val macros: Macros,

    val timestamp: String
)

data class NutritionLogResponse(
    val success: Boolean,

    @JsonProperty("meal_id")
    val mealId: Long
)

// Daily Meal Plan DTOs
data class DailyMealPlanResponse(
    val date: String,

    @JsonProperty("ai_message")
    val aiMessage: String? = null,  // AI의 첫 인사/분석 메시지

    val breakfast: MealRecommendation,
    val lunch: MealRecommendation,
    val dinner: MealRecommendation,

    @JsonProperty("total_calories")
    val totalCalories: Int,

    @JsonProperty("total_macros")
    val totalMacros: Macros,

    val tips: List<String>
)

data class MealRecommendation(
    @JsonProperty("meal_name")
    val mealName: String,

    val description: String,
    val calories: Int,
    val macros: Macros
)