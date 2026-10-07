package com.richjun.liftupai.domain.workout.service

import com.richjun.liftupai.domain.workout.entity.SplitType
import com.richjun.liftupai.domain.workout.entity.WorkoutType

/** 프리셋 템플릿의 한 종목 정의 (운동은 slug 로 참조) */
data class PresetExercise(
    val slug: String,
    val sets: Int,
    val minReps: Int,
    val maxReps: Int,
    val restSeconds: Int,
    val isCompound: Boolean,
    val targetRPE: Double,
    val notes: String? = null
)

data class PresetDay(
    val dayNumber: Int,
    val dayName: String,
    val workoutType: WorkoutType,
    val durationMinutes: Int,
    val exercises: List<PresetExercise>
)

data class PresetTemplate(
    val code: String,
    val name: String,
    val description: String,
    val targetGoal: String,
    val targetExperience: String,
    val splitType: SplitType,
    val totalDays: Int,
    val iconName: String,
    val sortOrder: Int,
    val days: List<PresetDay>
)

/**
 * 코드로 정의한 프리셋 플랜 템플릿.
 * - TemplateBootstrapService 가 DB 에 한 번만 심는다(이미 있는 코드는 건드리지 않는다 — 기존 사용자 플랜 불변).
 * - 템플릿 추천기·평가 하네스가 DB 없이 같은 정의를 읽는다.
 * 이름/설명/요일 이름의 en·ja 번역은 workout_messages*.properties 의 template.<code>.* 에 둔다.
 */
object PresetTemplates {
    /** 하루에 적은 분이 실제 운동 구성 시간과 달랐다(평균 25% 부풀림) — 새로 심는 템플릿은 구성에서 계산한 분을 쓴다 */
    val ALL: List<PresetTemplate> by lazy {
        DEFINITIONS.map { t ->
            t.copy(days = t.days.map { d ->
                d.copy(durationMinutes = com.richjun.liftupai.domain.workout.util.SessionDurationFitter.estimateMinutes(
                    d.exercises.map { com.richjun.liftupai.domain.workout.util.SessionDurationFitter.Slot(it.sets, "${it.minReps}-${it.maxReps}", it.restSeconds, it.restSeconds) }
                ))
            })
        }
    }

    private val DEFINITIONS: List<PresetTemplate> = listOf(
        // 초급자 전신 루틴 (3일/주)
        PresetTemplate(
            code = "beginner_fullbody",
            name = "초급자 전신 루틴",
            description = "운동을 처음 시작하는 분을 위한 전신 프로그램입니다. 주 3일, 기본 복합 운동 중심으로 전신 근력을 키웁니다.",
            targetGoal = "muscle_gain",
            targetExperience = "beginner",
            splitType = SplitType.FULL_BODY,
            totalDays = 3,
            iconName = "fitness_center",
            sortOrder = 1,
            days = listOf(
                PresetDay(1, "전신 기초 (스쿼트·벤치)", WorkoutType.FULL_BODY, 50, listOf(
                    PresetExercise("barbell-full-squat", 4, 8, 12, 90, true, 7.0),
                    PresetExercise("barbell-bench-press", 4, 8, 12, 90, true, 7.0),
                    PresetExercise("cable-bar-lateral-pulldown", 3, 10, 12, 60, true, 7.0),
                    PresetExercise("dumbbell-seated-shoulder-press", 3, 10, 12, 60, true, 7.0),
                    PresetExercise("high-plank", 3, 30, 60, 60, false, 6.0, "초 단위")
                )),
                PresetDay(2, "전신 기초 (데드리프트·인클라인)", WorkoutType.FULL_BODY, 50, listOf(
                    PresetExercise("barbell-deadlift", 4, 6, 10, 120, true, 7.0),
                    PresetExercise("barbell-incline-bench-press", 3, 8, 12, 90, true, 7.0),
                    PresetExercise("cable-pulldown", 3, 10, 12, 60, true, 7.0),
                    PresetExercise("barbell-lunge", 3, 10, 12, 60, true, 7.0),
                    PresetExercise("lying-leg-raise", 3, 12, 15, 45, false, 6.0)
                )),
                PresetDay(3, "전신 기초 (레그프레스·로우)", WorkoutType.FULL_BODY, 50, listOf(
                    PresetExercise("leg-press-machine-normal-stance", 4, 10, 12, 90, true, 7.0),
                    PresetExercise("dumbbell-bench-press", 3, 10, 12, 60, true, 7.0),
                    PresetExercise("barbell-bent-over-row-pronated-grip", 3, 8, 12, 90, true, 7.0),
                    PresetExercise("lateral-raises-dumbbell", 3, 12, 15, 45, false, 6.0),
                    PresetExercise("russian-twist", 3, 15, 20, 45, false, 6.0)
                ))
            )
        ),
        // 근비대 기초 루틴 (4일/주, 상하체)
        PresetTemplate(
            code = "hypertrophy_basic",
            name = "근비대 기초 루틴",
            description = "근육량 증가를 목표로 하는 상하체 분할 프로그램입니다. 주 4일, 복합+고립 운동을 조합합니다.",
            targetGoal = "muscle_gain",
            targetExperience = "beginner",
            splitType = SplitType.UPPER_LOWER,
            totalDays = 4,
            iconName = "trending_up",
            sortOrder = 2,
            days = listOf(
                PresetDay(1, "상체 가슴·어깨·삼두", WorkoutType.UPPER, 55, listOf(
                    PresetExercise("barbell-bench-press", 4, 8, 12, 90, true, 7.0),
                    PresetExercise("barbell-bent-over-row-pronated-grip", 4, 8, 12, 90, true, 7.0),
                    PresetExercise("dumbbell-seated-shoulder-press", 3, 10, 12, 60, true, 7.0),
                    PresetExercise("barbell-biceps-curl", 3, 10, 12, 60, false, 7.0),
                    PresetExercise("dumbbell-standing-triceps-extension", 3, 10, 12, 60, false, 7.0)
                )),
                PresetDay(2, "하체 스쿼트·레그프레스", WorkoutType.LOWER, 50, listOf(
                    PresetExercise("barbell-full-squat", 4, 8, 12, 120, true, 7.0),
                    PresetExercise("leg-press-machine-normal-stance", 3, 10, 12, 90, true, 7.0),
                    PresetExercise("lying-leg-curl-machine", 3, 10, 12, 60, false, 7.0),
                    PresetExercise("bodyweight-standing-calf-raise", 3, 15, 20, 45, false, 6.0),
                    PresetExercise("high-plank", 3, 30, 60, 60, false, 6.0, "초 단위")
                )),
                PresetDay(3, "상체 등·이두·측면", WorkoutType.UPPER, 55, listOf(
                    PresetExercise("barbell-incline-bench-press", 4, 8, 12, 90, true, 7.0),
                    PresetExercise("cable-bar-lateral-pulldown", 4, 10, 12, 60, true, 7.0),
                    PresetExercise("lateral-raises-dumbbell", 3, 12, 15, 45, false, 6.0),
                    PresetExercise("dumbbell-hammer-curl", 3, 10, 12, 60, false, 7.0),
                    PresetExercise("cable-pushdown", 3, 10, 12, 60, false, 7.0)
                )),
                PresetDay(4, "하체 데드리프트·런지", WorkoutType.LOWER, 50, listOf(
                    PresetExercise("barbell-deadlift", 4, 6, 10, 120, true, 7.0),
                    PresetExercise("barbell-lunge", 3, 10, 12, 60, true, 7.0),
                    PresetExercise("leg-press-machine-normal-stance", 3, 10, 12, 90, true, 7.0),
                    PresetExercise("bodyweight-standing-calf-raise", 3, 15, 20, 45, false, 6.0),
                    PresetExercise("abdominal-crunches", 3, 15, 20, 45, false, 6.0)
                ))
            )
        ),
        // 다이어트 서킷 (3일/주)
        PresetTemplate(
            code = "diet_circuit",
            name = "다이어트 서킷",
            description = "체지방 감량을 목표로 하는 서킷 트레이닝입니다. 복합 운동 위주로 높은 칼로리 소모를 유도합니다.",
            targetGoal = "weight_loss",
            targetExperience = "intermediate",
            splitType = SplitType.FULL_BODY,
            totalDays = 3,
            iconName = "local_fire_department",
            sortOrder = 3,
            days = listOf(
                PresetDay(1, "전신 연소 (스쿼트·벤치·로우)", WorkoutType.FULL_BODY, 45, listOf(
                    PresetExercise("barbell-full-squat", 4, 10, 15, 60, true, 7.0),
                    PresetExercise("barbell-bench-press", 4, 10, 15, 60, true, 7.0),
                    PresetExercise("barbell-bent-over-row-pronated-grip", 3, 10, 15, 60, true, 7.0),
                    PresetExercise("barbell-lunge", 3, 12, 15, 45, true, 7.0),
                    PresetExercise("high-plank", 3, 30, 60, 45, false, 7.0, "초 단위")
                )),
                PresetDay(2, "전신 연소 (데드·푸시업·풀다운)", WorkoutType.FULL_BODY, 45, listOf(
                    PresetExercise("barbell-deadlift", 4, 8, 12, 60, true, 7.0),
                    PresetExercise("deep-push-up", 3, 12, 20, 45, true, 7.0),
                    PresetExercise("cable-bar-lateral-pulldown", 3, 12, 15, 45, true, 7.0),
                    PresetExercise("dumbbell-seated-shoulder-press", 3, 10, 15, 45, true, 7.0),
                    PresetExercise("russian-twist", 3, 15, 20, 45, false, 7.0)
                )),
                PresetDay(3, "전신 연소 (레그프레스·인클라인)", WorkoutType.FULL_BODY, 45, listOf(
                    PresetExercise("leg-press-machine-normal-stance", 4, 12, 15, 60, true, 7.0),
                    PresetExercise("barbell-incline-bench-press", 3, 10, 15, 60, true, 7.0),
                    PresetExercise("cable-pulldown", 3, 12, 15, 45, true, 7.0),
                    PresetExercise("lateral-raises-dumbbell", 3, 12, 15, 45, false, 7.0),
                    PresetExercise("abdominal-crunches", 3, 15, 20, 45, false, 7.0)
                ))
            )
        ),
        // PPL 클래식 (6일/주)
        PresetTemplate(
            code = "ppl_classic",
            name = "PPL 클래식",
            description = "Push/Pull/Legs 6일 분할 프로그램입니다. 각 근육군을 주 2회 자극하여 근비대를 극대화합니다.",
            targetGoal = "muscle_gain",
            targetExperience = "intermediate",
            splitType = SplitType.PPL,
            totalDays = 6,
            iconName = "bolt",
            sortOrder = 4,
            days = listOf(
                PresetDay(1, "밀기 가슴·어깨·삼두", WorkoutType.PUSH, 60, listOf(
                    PresetExercise("barbell-bench-press", 4, 6, 10, 120, true, 8.0),
                    PresetExercise("barbell-incline-bench-press", 3, 8, 12, 90, true, 7.0),
                    PresetExercise("dumbbell-seated-shoulder-press", 3, 8, 12, 90, true, 7.0),
                    PresetExercise("lateral-raises-dumbbell", 3, 12, 15, 45, false, 7.0),
                    PresetExercise("dumbbell-standing-triceps-extension", 3, 10, 12, 60, false, 7.0),
                    PresetExercise("cable-pushdown", 3, 10, 12, 60, false, 7.0)
                )),
                PresetDay(2, "당기기 등·이두·후면", WorkoutType.PULL, 60, listOf(
                    PresetExercise("barbell-deadlift", 4, 5, 8, 150, true, 8.0),
                    PresetExercise("barbell-bent-over-row-pronated-grip", 4, 8, 12, 90, true, 7.0),
                    PresetExercise("cable-bar-lateral-pulldown", 3, 10, 12, 60, true, 7.0),
                    PresetExercise("barbell-biceps-curl", 3, 10, 12, 60, false, 7.0),
                    PresetExercise("dumbbell-hammer-curl", 3, 10, 12, 60, false, 7.0),
                    PresetExercise("bent-over-rear-delt-fly-dumbbell", 3, 12, 15, 45, false, 7.0)
                )),
                PresetDay(3, "하체 스쿼트·런지·종아리", WorkoutType.LEGS, 60, listOf(
                    PresetExercise("barbell-full-squat", 4, 6, 10, 120, true, 8.0),
                    PresetExercise("leg-press-machine-normal-stance", 3, 10, 12, 90, true, 7.0),
                    PresetExercise("barbell-lunge", 3, 10, 12, 60, true, 7.0),
                    PresetExercise("lying-leg-curl-machine", 3, 10, 12, 60, false, 7.0),
                    PresetExercise("bodyweight-standing-calf-raise", 4, 15, 20, 45, false, 6.0),
                    PresetExercise("abdominal-crunches", 3, 15, 20, 45, false, 6.0)
                )),
                PresetDay(4, "밀기 인클라인·어깨·코어", WorkoutType.PUSH, 60, listOf(
                    PresetExercise("dumbbell-bench-press", 4, 8, 12, 90, true, 7.0),
                    PresetExercise("barbell-incline-bench-press", 3, 8, 12, 90, true, 7.0),
                    PresetExercise("dumbbell-seated-shoulder-press", 3, 10, 12, 60, true, 7.0),
                    PresetExercise("dumbbell-front-raise", 3, 12, 15, 45, false, 6.0),
                    PresetExercise("cable-pushdown", 3, 10, 12, 60, false, 7.0),
                    PresetExercise("high-plank", 3, 30, 60, 60, false, 6.0, "초 단위")
                )),
                PresetDay(5, "당기기 풀다운·로우·이두", WorkoutType.PULL, 60, listOf(
                    PresetExercise("cable-bar-lateral-pulldown", 4, 8, 12, 90, true, 7.0),
                    PresetExercise("barbell-bent-over-row-pronated-grip", 3, 8, 12, 90, true, 7.0),
                    PresetExercise("cable-rear-pulldown", 3, 10, 12, 60, true, 7.0),
                    PresetExercise("barbell-biceps-curl", 3, 10, 12, 60, false, 7.0),
                    PresetExercise("dumbbell-hammer-curl", 3, 10, 12, 60, false, 7.0),
                    PresetExercise("bent-over-rear-delt-fly-dumbbell", 3, 12, 15, 45, false, 7.0)
                )),
                PresetDay(6, "하체 스쿼트·루마니안·햄스트링", WorkoutType.LEGS, 60, listOf(
                    PresetExercise("barbell-full-squat", 4, 8, 12, 120, true, 7.0),
                    PresetExercise("barbell-romanian-deadlift", 3, 8, 12, 90, true, 7.0),
                    PresetExercise("leg-press-machine-normal-stance", 3, 10, 12, 90, true, 7.0),
                    PresetExercise("lying-leg-curl-machine", 3, 10, 12, 60, false, 7.0),
                    PresetExercise("bodyweight-standing-calf-raise", 4, 15, 20, 45, false, 6.0),
                    PresetExercise("lying-leg-raise", 3, 12, 15, 45, false, 6.0)
                ))
            )
        ),
        // 상하체 분할 (4일/주)
        PresetTemplate(
            code = "upper_lower_muscle",
            name = "상하체 분할",
            description = "상체·하체를 각각 주 2회씩 훈련하는 4일 분할 프로그램입니다. 근력과 근비대를 균형 있게 발전시킵니다.",
            targetGoal = "muscle_gain",
            targetExperience = "intermediate",
            splitType = SplitType.UPPER_LOWER,
            totalDays = 4,
            iconName = "swap_vert",
            sortOrder = 5,
            days = listOf(
                PresetDay(1, "상체 벤치·로우·어깨", WorkoutType.UPPER, 60, listOf(
                    PresetExercise("barbell-bench-press", 4, 6, 10, 120, true, 8.0),
                    PresetExercise("barbell-bent-over-row-pronated-grip", 4, 8, 12, 90, true, 7.0),
                    PresetExercise("dumbbell-seated-shoulder-press", 3, 8, 12, 60, true, 7.0),
                    PresetExercise("barbell-biceps-curl", 3, 10, 12, 60, false, 7.0),
                    PresetExercise("dumbbell-standing-triceps-extension", 3, 10, 12, 60, false, 7.0),
                    PresetExercise("lateral-raises-dumbbell", 3, 12, 15, 45, false, 6.0)
                )),
                PresetDay(2, "하체 스쿼트·레그프레스·코어", WorkoutType.LOWER, 55, listOf(
                    PresetExercise("barbell-full-squat", 4, 6, 10, 120, true, 8.0),
                    PresetExercise("leg-press-machine-normal-stance", 3, 10, 12, 90, true, 7.0),
                    PresetExercise("lying-leg-curl-machine", 3, 10, 12, 60, false, 7.0),
                    PresetExercise("barbell-lunge", 3, 10, 12, 60, true, 7.0),
                    PresetExercise("bodyweight-standing-calf-raise", 3, 15, 20, 45, false, 6.0),
                    PresetExercise("high-plank", 3, 30, 60, 60, false, 6.0, "초 단위")
                )),
                PresetDay(3, "상체 인클라인·풀다운·후면", WorkoutType.UPPER, 60, listOf(
                    PresetExercise("barbell-incline-bench-press", 4, 8, 12, 90, true, 7.0),
                    PresetExercise("cable-bar-lateral-pulldown", 4, 10, 12, 60, true, 7.0),
                    PresetExercise("cable-pulldown", 3, 10, 12, 60, true, 7.0),
                    PresetExercise("dumbbell-hammer-curl", 3, 10, 12, 60, false, 7.0),
                    PresetExercise("cable-pushdown", 3, 10, 12, 60, false, 7.0),
                    PresetExercise("bent-over-rear-delt-fly-dumbbell", 3, 12, 15, 45, false, 7.0)
                )),
                PresetDay(4, "하체 데드리프트·런지·종아리", WorkoutType.LOWER, 55, listOf(
                    PresetExercise("barbell-deadlift", 4, 5, 8, 150, true, 8.0),
                    PresetExercise("barbell-lunge", 3, 10, 12, 60, true, 7.0),
                    PresetExercise("leg-press-machine-normal-stance", 3, 10, 12, 90, true, 7.0),
                    PresetExercise("lying-leg-curl-machine", 3, 10, 12, 60, false, 7.0),
                    PresetExercise("bodyweight-standing-calf-raise", 3, 15, 20, 45, false, 6.0),
                    PresetExercise("abdominal-crunches", 3, 15, 20, 45, false, 6.0)
                ))
            )
        ),
        // 고급자 전신 (3일/주)
        PresetTemplate(
            code = "advanced_fullbody",
            name = "고급자 전신 루틴",
            description = "높은 강도의 전신 프로그램. 복합 운동 중심 + 고중량 세트로 근력과 근비대를 동시에.",
            targetGoal = "strength",
            targetExperience = "advanced",
            splitType = SplitType.FULL_BODY,
            totalDays = 3,
            iconName = "bolt",
            sortOrder = 6,
            days = listOf(
                PresetDay(1, "전신 고중량 (스쿼트·벤치·로우)", WorkoutType.FULL_BODY, 65, listOf(
                    PresetExercise("barbell-full-squat", 5, 5, 8, 150, true, 8.5),
                    PresetExercise("barbell-bench-press", 5, 5, 8, 120, true, 8.5),
                    PresetExercise("barbell-bent-over-row-pronated-grip", 4, 6, 10, 90, true, 8.0),
                    PresetExercise("dumbbell-seated-shoulder-press", 3, 8, 12, 60, true, 7.5),
                    PresetExercise("barbell-biceps-curl", 3, 8, 12, 60, false, 7.0),
                    PresetExercise("lying-leg-raise", 3, 12, 15, 45, false, 7.0)
                )),
                PresetDay(2, "전신 고중량 (데드·인클라인·풀다운)", WorkoutType.FULL_BODY, 65, listOf(
                    PresetExercise("barbell-deadlift", 5, 3, 6, 180, true, 9.0),
                    PresetExercise("barbell-incline-bench-press", 4, 6, 10, 90, true, 8.0),
                    PresetExercise("cable-bar-lateral-pulldown", 4, 8, 12, 60, true, 7.5),
                    PresetExercise("barbell-lunge", 3, 8, 12, 90, true, 7.5),
                    PresetExercise("lateral-raises-dumbbell", 3, 12, 15, 45, false, 7.0),
                    PresetExercise("high-plank", 3, 30, 60, 60, false, 7.0, "초 단위")
                )),
                PresetDay(3, "전신 고중량 (프론트스쿼트·오버헤드)", WorkoutType.FULL_BODY, 65, listOf(
                    PresetExercise("barbell-full-squat", 4, 6, 10, 120, true, 8.0),
                    PresetExercise("dumbbell-bench-press", 4, 8, 12, 90, true, 7.5),
                    PresetExercise("barbell-bent-over-row-pronated-grip", 4, 8, 12, 90, true, 8.0),
                    PresetExercise("barbell-seated-overhead-press", 3, 6, 10, 90, true, 8.0),
                    PresetExercise("dumbbell-hammer-curl", 3, 10, 12, 60, false, 7.0),
                    PresetExercise("russian-twist", 3, 15, 20, 45, false, 7.0)
                ))
            )
        ),
        // 고급자 5일 PPL+상하체 (5일/주)
        PresetTemplate(
            code = "advanced_pplul",
            name = "고급자 5일 분할",
            description = "PPL + 상하체 하이브리드 5일 프로그램. 각 근육군 주 2회 + 고강도 훈련.",
            targetGoal = "muscle_gain",
            targetExperience = "advanced",
            splitType = SplitType.PPL,
            totalDays = 5,
            iconName = "whatshot",
            sortOrder = 7,
            days = listOf(
                PresetDay(1, "밀기 가슴·어깨·삼두 (고중량)", WorkoutType.PUSH, 65, listOf(
                    PresetExercise("barbell-bench-press", 5, 5, 8, 150, true, 8.5),
                    PresetExercise("barbell-incline-bench-press", 4, 6, 10, 90, true, 8.0),
                    PresetExercise("dumbbell-seated-shoulder-press", 3, 8, 12, 60, true, 7.5),
                    PresetExercise("lateral-raises-dumbbell", 4, 12, 15, 45, false, 7.0),
                    PresetExercise("cable-pushdown", 3, 10, 12, 60, false, 7.0),
                    PresetExercise("dumbbell-standing-triceps-extension", 3, 10, 12, 60, false, 7.0)
                )),
                PresetDay(2, "당기기 등·이두·후면 (고중량)", WorkoutType.PULL, 65, listOf(
                    PresetExercise("barbell-deadlift", 4, 4, 6, 180, true, 9.0),
                    PresetExercise("barbell-bent-over-row-pronated-grip", 4, 6, 10, 90, true, 8.0),
                    PresetExercise("cable-bar-lateral-pulldown", 4, 8, 12, 60, true, 7.5),
                    PresetExercise("barbell-biceps-curl", 3, 8, 12, 60, false, 7.5),
                    PresetExercise("dumbbell-hammer-curl", 3, 10, 12, 60, false, 7.0),
                    PresetExercise("bent-over-rear-delt-fly-dumbbell", 3, 12, 15, 45, false, 7.0)
                )),
                PresetDay(3, "하체 스쿼트·런지·종아리", WorkoutType.LEGS, 60, listOf(
                    PresetExercise("barbell-full-squat", 5, 5, 8, 150, true, 8.5),
                    PresetExercise("leg-press-machine-normal-stance", 4, 8, 12, 90, true, 7.5),
                    PresetExercise("barbell-lunge", 3, 10, 12, 60, true, 7.5),
                    PresetExercise("lying-leg-curl-machine", 3, 10, 12, 60, false, 7.0),
                    PresetExercise("bodyweight-standing-calf-raise", 4, 15, 20, 45, false, 7.0),
                    PresetExercise("abdominal-crunches", 3, 15, 20, 45, false, 7.0)
                )),
                PresetDay(4, "상체 벤치·로우·어깨 (볼륨)", WorkoutType.UPPER, 60, listOf(
                    PresetExercise("dumbbell-bench-press", 4, 8, 12, 90, true, 7.5),
                    PresetExercise("cable-bar-lateral-pulldown", 4, 8, 12, 60, true, 7.5),
                    PresetExercise("barbell-seated-overhead-press", 3, 8, 12, 90, true, 7.5),
                    PresetExercise("barbell-biceps-curl", 3, 10, 12, 60, false, 7.0),
                    PresetExercise("cable-pushdown", 3, 10, 12, 60, false, 7.0),
                    PresetExercise("lateral-raises-dumbbell", 3, 12, 15, 45, false, 7.0)
                )),
                PresetDay(5, "하체 데드·루마니안·햄스트링", WorkoutType.LOWER, 55, listOf(
                    PresetExercise("barbell-romanian-deadlift", 4, 6, 10, 120, true, 8.0),
                    PresetExercise("leg-press-machine-normal-stance", 3, 10, 12, 90, true, 7.5),
                    PresetExercise("lying-leg-curl-machine", 3, 10, 12, 60, false, 7.0),
                    PresetExercise("barbell-lunge", 3, 10, 12, 60, true, 7.0),
                    PresetExercise("bodyweight-standing-calf-raise", 4, 15, 20, 45, false, 7.0),
                    PresetExercise("lying-leg-raise", 3, 12, 15, 45, false, 7.0)
                ))
            )
        ),
        // 초급자 2일 전신 (2일/주)
        PresetTemplate(
            code = "beginner_2day",
            name = "초급자 2일 전신",
            description = "주 2일만으로 전신 운동. 시간이 부족한 초보자를 위한 최소 효과 프로그램.",
            targetGoal = "general_fitness",
            targetExperience = "beginner",
            splitType = SplitType.FULL_BODY,
            totalDays = 2,
            iconName = "timer",
            sortOrder = 8,
            days = listOf(
                PresetDay(1, "전신 기초 (스쿼트·벤치·풀다운)", WorkoutType.FULL_BODY, 45, listOf(
                    PresetExercise("barbell-full-squat", 3, 8, 12, 90, true, 7.0),
                    PresetExercise("barbell-bench-press", 3, 8, 12, 90, true, 7.0),
                    PresetExercise("cable-bar-lateral-pulldown", 3, 10, 12, 60, true, 7.0),
                    PresetExercise("dumbbell-seated-shoulder-press", 2, 10, 12, 60, true, 6.5),
                    PresetExercise("high-plank", 2, 30, 45, 60, false, 6.0, "초 단위")
                )),
                PresetDay(2, "전신 기초 (데드·로우·런지)", WorkoutType.FULL_BODY, 45, listOf(
                    PresetExercise("barbell-deadlift", 3, 6, 10, 120, true, 7.0),
                    PresetExercise("barbell-bent-over-row-pronated-grip", 3, 8, 12, 90, true, 7.0),
                    PresetExercise("barbell-lunge", 3, 10, 12, 60, true, 7.0),
                    PresetExercise("lateral-raises-dumbbell", 2, 12, 15, 45, false, 6.5),
                    PresetExercise("abdominal-crunches", 2, 15, 20, 45, false, 6.0)
                ))
            )
        ),

        // ── 9~15. 실사용자 페르소나 점검에서 확인된 빈틈을 메우는 템플릿 (2026-10-07) ──
        // 홈트(덤벨·맨몸) 초보, 맨몸 전용, 다이어트 초보(기구), 하체·둔근 집중, 초보 5일 분할, 중급 전신.
        // 기존 8개는 바벨 중심이라 장비가 없으면 대부분 걸러졌고, 다이어트 서킷은 중급 이상용이었다.
        PresetTemplate(
            code = "home_bodyweight_beginner",
            name = "맨몸 홈트 전신",
            description = "기구 없이 집에서 하는 초보자 전신 프로그램입니다. 주 3일, 하루 30분 안팎으로 하체·가슴·코어를 고르게 단련합니다.",
            targetGoal = "general_fitness",
            targetExperience = "beginner",
            splitType = SplitType.FULL_BODY,
            totalDays = 3,
            iconName = "home",
            sortOrder = 9,
            days = listOf(
                PresetDay(1, "맨몸 전신 (스쿼트·푸시업)", WorkoutType.FULL_BODY, 30, listOf(
                    PresetExercise("bodyweight-squat", 3, 12, 15, 45, true, 6.5),
                    PresetExercise("push-ups-bodyweight", 3, 6, 12, 60, true, 6.5),
                    PresetExercise("bodyweight-forward-lunge", 3, 8, 12, 45, true, 6.5),
                    PresetExercise("glute-bridge-with-abduction-bodyweight", 3, 12, 15, 45, false, 6.0),
                    PresetExercise("high-plank", 3, 20, 40, 45, false, 6.0, "초 단위")
                )),
                PresetDay(2, "맨몸 둔근·코어", WorkoutType.FULL_BODY, 30, listOf(
                    PresetExercise("bodyweight-wall-squat", 3, 12, 15, 45, true, 6.0),
                    PresetExercise("glute-bridge-with-abduction-bodyweight", 3, 12, 15, 45, false, 6.0),
                    PresetExercise("close-grip-push-up", 3, 6, 10, 60, true, 6.5),
                    PresetExercise("bird-dog", 3, 10, 12, 45, false, 6.0),
                    PresetExercise("abdominal-crunches", 3, 12, 20, 45, false, 6.0)
                )),
                PresetDay(3, "맨몸 전신 서킷", WorkoutType.FULL_BODY, 30, listOf(
                    PresetExercise("bulgarian-split-squat", 3, 8, 10, 60, true, 6.5),
                    PresetExercise("push-ups-bodyweight", 3, 6, 12, 60, true, 6.5),
                    PresetExercise("bodyweight-standing-calf-raise", 3, 15, 20, 30, false, 6.0),
                    PresetExercise("mountain-climbers", 3, 20, 30, 30, false, 6.5),
                    PresetExercise("side-plank", 3, 20, 40, 30, false, 6.0, "초 단위")
                ))
            )
        ),
        PresetTemplate(
            code = "home_dumbbell_beginner",
            name = "홈트 덤벨 전신",
            description = "덤벨 한 쌍으로 집에서 하는 초보자 전신 프로그램입니다. 주 3일, 하체·밀기·당기기를 번갈아 단련합니다.",
            targetGoal = "muscle_gain",
            targetExperience = "beginner",
            splitType = SplitType.FULL_BODY,
            totalDays = 3,
            iconName = "fitness_center",
            sortOrder = 10,
            days = listOf(
                PresetDay(1, "덤벨 전신 (고블릿 스쿼트·푸시업)", WorkoutType.FULL_BODY, 45, listOf(
                    PresetExercise("dumbbell-goblet-squat", 3, 10, 12, 60, true, 7.0),
                    PresetExercise("push-ups-bodyweight", 3, 6, 12, 60, true, 7.0),
                    PresetExercise("dumbbell-seated-shoulder-press", 3, 10, 12, 60, true, 7.0),
                    PresetExercise("dumbbell-rear-lunge", 3, 8, 12, 60, true, 7.0),
                    PresetExercise("high-plank", 3, 20, 40, 45, false, 6.0, "초 단위")
                )),
                PresetDay(2, "덤벨 전신 (로우·힙쓰러스트)", WorkoutType.FULL_BODY, 45, listOf(
                    PresetExercise("dumbbell-bent-over-row", 3, 10, 12, 60, true, 7.0),
                    PresetExercise("dumbbell-hip-thrust", 3, 10, 15, 60, true, 7.0),
                    PresetExercise("dumbbell-curls", 3, 10, 12, 45, false, 6.5),
                    PresetExercise("dumbbell-standing-triceps-extension", 3, 10, 12, 45, false, 6.5),
                    PresetExercise("lying-leg-raise", 3, 10, 15, 45, false, 6.0)
                )),
                PresetDay(3, "덤벨 전신 (런지·레터럴 레이즈)", WorkoutType.FULL_BODY, 45, listOf(
                    PresetExercise("dumbbell-split-squat", 3, 8, 12, 60, true, 7.0),
                    PresetExercise("dumbbell-single-arm-row", 3, 10, 12, 60, true, 7.0),
                    PresetExercise("deep-push-up", 3, 6, 12, 60, true, 7.0),
                    PresetExercise("lateral-raises-dumbbell", 3, 12, 15, 45, false, 6.5),
                    PresetExercise("russian-twist", 3, 12, 20, 45, false, 6.0)
                ))
            )
        ),
        PresetTemplate(
            code = "beginner_fat_burn",
            name = "초급자 다이어트 전신",
            description = "체지방 감량을 목표로 하는 초보자용 전신 프로그램입니다. 머신·덤벨 중심의 안전한 동작과 짧은 휴식으로 주 3일 진행합니다.",
            targetGoal = "weight_loss",
            targetExperience = "beginner",
            splitType = SplitType.FULL_BODY,
            totalDays = 3,
            iconName = "local_fire_department",
            sortOrder = 11,
            days = listOf(
                PresetDay(1, "전신 연소 (레그프레스·풀다운)", WorkoutType.FULL_BODY, 45, listOf(
                    PresetExercise("leg-press-machine-normal-stance", 3, 12, 15, 45, true, 6.5),
                    PresetExercise("cable-pulldown", 3, 12, 15, 45, true, 6.5),
                    PresetExercise("push-ups-bodyweight", 3, 6, 12, 45, true, 6.5),
                    PresetExercise("iso-lateral-shoulder-press-both-arms", 3, 12, 15, 45, true, 6.5),
                    PresetExercise("high-plank", 3, 20, 40, 45, false, 6.0, "초 단위")
                )),
                PresetDay(2, "전신 연소 (고블릿 스쿼트·시티드 로우)", WorkoutType.FULL_BODY, 45, listOf(
                    PresetExercise("dumbbell-goblet-squat", 3, 12, 15, 45, true, 6.5),
                    PresetExercise("cable-seated-row", 3, 12, 15, 45, true, 6.5),
                    PresetExercise("pec-deck-fly-machine", 3, 12, 15, 45, false, 6.5),
                    PresetExercise("bodyweight-forward-lunge", 3, 10, 12, 45, true, 6.5),
                    PresetExercise("abdominal-crunches", 3, 15, 20, 30, false, 6.0)
                )),
                PresetDay(3, "전신 연소 (핵스쿼트·벤치)", WorkoutType.FULL_BODY, 45, listOf(
                    PresetExercise("hack-squat-machine", 3, 12, 15, 45, true, 6.5),
                    PresetExercise("cable-bar-lateral-pulldown", 3, 12, 15, 45, true, 6.5),
                    PresetExercise("dumbbell-bench-press", 3, 10, 15, 45, true, 6.5),
                    PresetExercise("glute-bridge-with-abduction-bodyweight", 3, 15, 20, 30, false, 6.0),
                    PresetExercise("russian-twist", 3, 15, 20, 30, false, 6.0)
                ))
            )
        ),
        PresetTemplate(
            code = "glute_leg_focus",
            name = "하체·둔근 집중",
            description = "하체와 둔근을 중심으로 키우는 초보자용 프로그램입니다. 주 3일, 힙쓰러스트·레그프레스·런지로 하체를 주 2회 자극하고 하루는 상체와 코어를 다룹니다.",
            targetGoal = "muscle_gain",
            targetExperience = "beginner",
            splitType = SplitType.UPPER_LOWER,
            totalDays = 3,
            iconName = "directions_run",
            sortOrder = 12,
            days = listOf(
                PresetDay(1, "하체 둔근 (힙쓰러스트·레그프레스)", WorkoutType.LOWER, 50, listOf(
                    PresetExercise("barbell-hip-thrust", 4, 10, 12, 90, true, 7.0),
                    PresetExercise("leg-press-machine-normal-stance", 3, 10, 15, 90, true, 7.0),
                    PresetExercise("lying-leg-curl-machine", 3, 10, 15, 60, false, 7.0),
                    PresetExercise("dumbbell-rear-lunge", 3, 8, 12, 60, true, 7.0),
                    PresetExercise("bodyweight-standing-calf-raise", 3, 15, 20, 45, false, 6.0)
                )),
                PresetDay(2, "상체·코어 (풀다운·푸시업)", WorkoutType.UPPER, 45, listOf(
                    PresetExercise("cable-pulldown", 3, 10, 12, 60, true, 6.5),
                    PresetExercise("push-ups-bodyweight", 3, 6, 12, 60, true, 6.5),
                    PresetExercise("dumbbell-seated-shoulder-press", 3, 10, 12, 60, true, 6.5),
                    PresetExercise("cable-pushdown", 3, 10, 15, 45, false, 6.5),
                    PresetExercise("high-plank", 3, 20, 40, 45, false, 6.0, "초 단위")
                )),
                PresetDay(3, "하체 스쿼트·런지", WorkoutType.LOWER, 50, listOf(
                    PresetExercise("dumbbell-goblet-squat", 4, 10, 12, 75, true, 7.0),
                    PresetExercise("barbell-glute-bridge", 3, 10, 15, 60, true, 7.0),
                    PresetExercise("dumbbell-split-squat", 3, 8, 12, 60, true, 7.0),
                    PresetExercise("glute-bridge-with-abduction-bodyweight", 3, 15, 20, 45, false, 6.0),
                    PresetExercise("side-plank", 3, 20, 40, 45, false, 6.0, "초 단위")
                ))
            )
        ),
        PresetTemplate(
            code = "glute_leg_home",
            name = "홈트 하체·둔근 집중",
            description = "덤벨과 맨몸으로 집에서 하체와 둔근을 집중적으로 단련하는 초보자용 프로그램입니다. 주 3일, 하체 2일과 상체·코어 1일로 구성됩니다.",
            targetGoal = "muscle_gain",
            targetExperience = "beginner",
            splitType = SplitType.UPPER_LOWER,
            totalDays = 3,
            iconName = "home",
            sortOrder = 13,
            days = listOf(
                PresetDay(1, "홈트 둔근 (덤벨 힙쓰러스트)", WorkoutType.LOWER, 45, listOf(
                    PresetExercise("dumbbell-hip-thrust", 4, 10, 15, 60, true, 7.0),
                    PresetExercise("dumbbell-goblet-squat", 3, 10, 12, 60, true, 7.0),
                    PresetExercise("dumbbell-rear-lunge", 3, 8, 12, 60, true, 7.0),
                    PresetExercise("glute-bridge-with-abduction-bodyweight", 3, 15, 20, 45, false, 6.0),
                    PresetExercise("bodyweight-standing-calf-raise", 3, 15, 20, 45, false, 6.0)
                )),
                PresetDay(2, "홈트 상체·코어", WorkoutType.UPPER, 40, listOf(
                    PresetExercise("push-ups-bodyweight", 3, 6, 12, 60, true, 6.5),
                    PresetExercise("dumbbell-bent-over-row", 3, 10, 12, 60, true, 6.5),
                    PresetExercise("dumbbell-seated-shoulder-press", 3, 10, 12, 60, true, 6.5),
                    PresetExercise("dumbbell-curls", 3, 10, 12, 45, false, 6.5),
                    PresetExercise("high-plank", 3, 20, 40, 45, false, 6.0, "초 단위")
                )),
                PresetDay(3, "홈트 하체 (스플릿 스쿼트)", WorkoutType.LOWER, 45, listOf(
                    PresetExercise("dumbbell-squats", 4, 10, 12, 60, true, 7.0),
                    PresetExercise("bulgarian-split-squat", 3, 8, 12, 60, true, 7.0),
                    PresetExercise("dumbbell-split-squat", 3, 8, 12, 60, true, 7.0),
                    PresetExercise("bodyweight-wall-squat", 3, 12, 15, 45, true, 6.0),
                    PresetExercise("side-plank", 3, 20, 40, 45, false, 6.0, "초 단위")
                ))
            )
        ),
        PresetTemplate(
            code = "beginner_split_5day",
            name = "초급자 5일 분할",
            description = "주 5일 운동하는 초보자를 위한 분할 프로그램입니다. 머신과 덤벨 중심의 쉬운 동작으로 부위별로 나눠 가볍게 반복합니다.",
            targetGoal = "muscle_gain",
            targetExperience = "beginner",
            splitType = SplitType.PPLUL,
            totalDays = 5,
            iconName = "view_week",
            sortOrder = 14,
            days = listOf(
                PresetDay(1, "밀기 가슴·어깨·삼두", WorkoutType.PUSH, 45, listOf(
                    PresetExercise("dumbbell-bench-press", 3, 10, 12, 75, true, 6.5),
                    PresetExercise("iso-lateral-shoulder-press-both-arms", 3, 10, 12, 60, true, 6.5),
                    PresetExercise("pec-deck-fly-machine", 3, 12, 15, 45, false, 6.5),
                    PresetExercise("lateral-raises-dumbbell", 3, 12, 15, 45, false, 6.5),
                    PresetExercise("cable-pushdown", 3, 10, 15, 45, false, 6.5)
                )),
                PresetDay(2, "당기기 등·이두", WorkoutType.PULL, 45, listOf(
                    PresetExercise("cable-pulldown", 3, 10, 12, 60, true, 6.5),
                    PresetExercise("cable-seated-row", 3, 10, 12, 60, true, 6.5),
                    PresetExercise("bent-over-rear-delt-fly-dumbbell", 3, 12, 15, 45, false, 6.5),
                    PresetExercise("dumbbell-curls", 3, 10, 12, 45, false, 6.5),
                    PresetExercise("abdominal-crunches", 3, 15, 20, 30, false, 6.0)
                )),
                PresetDay(3, "하체 레그프레스·햄스트링", WorkoutType.LEGS, 45, listOf(
                    PresetExercise("leg-press-machine-normal-stance", 3, 10, 15, 75, true, 6.5),
                    PresetExercise("dumbbell-goblet-squat", 3, 10, 12, 60, true, 6.5),
                    PresetExercise("lying-leg-curl-machine", 3, 10, 15, 60, false, 6.5),
                    PresetExercise("bodyweight-standing-calf-raise", 3, 15, 20, 30, false, 6.0),
                    PresetExercise("high-plank", 3, 20, 40, 45, false, 6.0, "초 단위")
                )),
                PresetDay(4, "상체 풀다운·프레스", WorkoutType.UPPER, 45, listOf(
                    PresetExercise("push-ups-bodyweight", 3, 6, 12, 60, true, 6.5),
                    PresetExercise("cable-bar-lateral-pulldown", 3, 10, 12, 60, true, 6.5),
                    PresetExercise("dumbbell-seated-shoulder-press", 3, 10, 12, 60, true, 6.5),
                    PresetExercise("dumbbell-hammer-curl", 3, 10, 12, 45, false, 6.5),
                    PresetExercise("cable-overhead-extension-rope", 3, 10, 15, 45, false, 6.5)
                )),
                PresetDay(5, "하체 힙쓰러스트·런지", WorkoutType.LOWER, 45, listOf(
                    PresetExercise("hack-squat-machine", 3, 10, 12, 75, true, 6.5),
                    PresetExercise("barbell-hip-thrust", 3, 10, 12, 75, true, 6.5),
                    PresetExercise("dumbbell-rear-lunge", 3, 8, 12, 60, true, 6.5),
                    PresetExercise("russian-twist", 3, 15, 20, 30, false, 6.0),
                    PresetExercise("lying-leg-raise", 3, 10, 15, 45, false, 6.0)
                ))
            )
        ),
        PresetTemplate(
            code = "intermediate_fullbody",
            name = "중급자 전신 루틴",
            description = "주 3일 전신 운동을 하는 중급자용 프로그램입니다. 스쿼트·벤치·로우·데드리프트 계열을 번갈아 하며 근력과 근비대를 함께 키웁니다.",
            targetGoal = "strength",
            targetExperience = "intermediate",
            splitType = SplitType.FULL_BODY,
            totalDays = 3,
            iconName = "fitness_center",
            sortOrder = 15,
            days = listOf(
                PresetDay(1, "전신 중급 (스쿼트·벤치·로우)", WorkoutType.FULL_BODY, 60, listOf(
                    PresetExercise("barbell-full-squat", 4, 6, 10, 120, true, 7.5),
                    PresetExercise("barbell-bench-press", 4, 6, 10, 120, true, 7.5),
                    PresetExercise("cable-bar-lateral-pulldown", 3, 8, 12, 75, true, 7.0),
                    PresetExercise("dumbbell-seated-shoulder-press", 3, 8, 12, 75, true, 7.0),
                    PresetExercise("barbell-biceps-curl", 3, 10, 12, 60, false, 7.0),
                    PresetExercise("high-plank", 3, 30, 60, 60, false, 6.5, "초 단위")
                )),
                PresetDay(2, "전신 중급 (루마니안·인클라인·로우)", WorkoutType.FULL_BODY, 60, listOf(
                    PresetExercise("barbell-romanian-deadlift", 4, 6, 10, 120, true, 7.5),
                    PresetExercise("barbell-incline-bench-press", 3, 8, 12, 90, true, 7.0),
                    PresetExercise("dumbbell-bent-over-row", 3, 8, 12, 75, true, 7.0),
                    PresetExercise("leg-press-machine-normal-stance", 3, 10, 12, 90, true, 7.0),
                    PresetExercise("cable-pushdown", 3, 10, 12, 60, false, 7.0),
                    PresetExercise("lying-leg-raise", 3, 12, 15, 45, false, 6.5)
                )),
                PresetDay(3, "전신 중급 (프론트 스쿼트·오버헤드)", WorkoutType.FULL_BODY, 60, listOf(
                    PresetExercise("barbell-front-squats", 4, 6, 10, 120, true, 7.5),
                    PresetExercise("dumbbell-bench-press", 4, 8, 12, 90, true, 7.0),
                    PresetExercise("cable-seated-row", 3, 8, 12, 75, true, 7.0),
                    PresetExercise("barbell-standing-military-press", 3, 6, 10, 90, true, 7.5),
                    PresetExercise("dumbbell-hammer-curl", 3, 10, 12, 60, false, 7.0),
                    PresetExercise("russian-twist", 3, 15, 20, 45, false, 6.5)
                ))
            )
        )
    )
}
