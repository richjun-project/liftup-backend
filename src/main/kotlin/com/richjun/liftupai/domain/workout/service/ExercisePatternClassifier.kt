package com.richjun.liftupai.domain.workout.service

import com.richjun.liftupai.domain.workout.entity.Exercise
import org.springframework.stereotype.Service

/**
 * 운동 패턴 분류기
 *
 * 전문 PT가 프로그램을 짤 때 사용하는 방식으로 운동을 패턴별로 분류
 * 같은 패턴의 운동이 한 세션에서 중복되지 않도록 함
 */
@Service
class ExercisePatternClassifier {

    enum class MovementPattern {
        // 하체 패턴
        SQUAT, LUNGE, HIP_HINGE, LEG_PRESS, LEG_CURL, LEG_EXTENSION, GLUTE_FOCUSED, CALF,

        // 상체 밀기 (PUSH) 패턴
        HORIZONTAL_PRESS_BARBELL, HORIZONTAL_PRESS_DUMBBELL, HORIZONTAL_PRESS_MACHINE,
        INCLINE_PRESS_BARBELL, INCLINE_PRESS_DUMBBELL, DECLINE_PRESS,
        VERTICAL_PRESS_BARBELL, VERTICAL_PRESS_DUMBBELL, VERTICAL_PRESS_MACHINE,
        DIPS, PUSHUP, FLY,

        // 상체 당기기 (PULL) 패턴
        PULLUP_CHINUP, LAT_PULLDOWN, BARBELL_ROW, DUMBBELL_ROW, CABLE_ROW, INVERTED_ROW, DEADLIFT,

        // 어깨 패턴
        LATERAL_RAISE, FRONT_RAISE, REAR_DELT, FACE_PULL, UPRIGHT_ROW, SHRUG,

        // 팔 패턴
        BICEP_CURL_BARBELL, BICEP_CURL_DUMBBELL, BICEP_CURL_CABLE,
        TRICEP_OVERHEAD, TRICEP_LYING, TRICEP_PUSHDOWN,

        // 코어 패턴
        CRUNCH, LEG_RAISE, PLANK, ROTATION, ROLLOUT,

        // 기타
        CLEAN, SNATCH, COMPOUND_COMPLEX, CARRY, CARDIO, PLYOMETRIC, STRETCHING, OTHER
    }

    // 패턴별 키워드 매핑 (순서 중요: 구체적인 것부터 체크)
    private val patternKeywords = mapOf(
        // === 하체 ===
        MovementPattern.HIP_HINGE to listOf(
            "루마니안", "romanian", "rdl", "굿모닝", "good morning",
            "백 익스텐션", "back extension", "하이퍼 익스텐션",
            "스티프 레그", "stiff leg", "글루트 햄 레이즈", "ghr"
        ),
        MovementPattern.LUNGE to listOf(
            "런지", "lunge", "불가리안", "bulgarian", "스플릿", "split",
            "커티시", "스텝업", "step up"
        ),
        MovementPattern.LEG_PRESS to listOf(
            "레그 프레스", "레그프레스", "leg press", "해크 스쿼트", "hack squat"
        ),
        MovementPattern.LEG_CURL to listOf(
            "레그 컬", "leg curl", "햄스트링 컬", "노르딕 컬", "nordic curl"
        ),
        MovementPattern.LEG_EXTENSION to listOf(
            "레그 익스텐션", "leg extension", "니 익스텐션"
        ),
        MovementPattern.GLUTE_FOCUSED to listOf(
            "힙 쓰러스트", "hip thrust", "글루트 드라이브", "글루트 브릿지",
            "glute bridge", "케이블 풀 쓰루", "케틀벨 스윙", "kettlebell swing"
        ),
        MovementPattern.CALF to listOf("카프", "calf", "종아리"),
        MovementPattern.SQUAT to listOf("스쿼트", "squat"),  // 마지막에 체크 (다른 패턴 제외 후)

        // === 상체 밀기 ===
        MovementPattern.DIPS to listOf("딥스", "dips"),
        MovementPattern.PUSHUP to listOf("푸시업", "push up", "pushup"),
        MovementPattern.FLY to listOf("플라이", "fly", "펙덱", "pec deck"),
        MovementPattern.INCLINE_PRESS_BARBELL to listOf("인클라인") to listOf("바벨"),
        MovementPattern.INCLINE_PRESS_DUMBBELL to listOf("인클라인") to listOf("덤벨"),
        MovementPattern.DECLINE_PRESS to listOf("디클라인", "decline"),
        MovementPattern.VERTICAL_PRESS_BARBELL to listOf(
            "밀리터리 프레스", "오버헤드 프레스", "푸시 프레스", "푸시 저크"
        ),
        MovementPattern.VERTICAL_PRESS_DUMBBELL to listOf("아놀드 프레스"),
        MovementPattern.VERTICAL_PRESS_MACHINE to listOf("숄더 프레스 머신"),
        MovementPattern.HORIZONTAL_PRESS_MACHINE to listOf("체스트 프레스 머신", "해머스트렝스 체스트"),

        // === 상체 당기기 ===
        MovementPattern.PULLUP_CHINUP to listOf("풀업", "pullup", "친업", "chinup"),
        MovementPattern.LAT_PULLDOWN to listOf("랫 풀다운", "lat pulldown", "풀다운"),
        MovementPattern.BARBELL_ROW to listOf("펜들레이", "pendlay", "t-바", "t-bar"),
        MovementPattern.CABLE_ROW to listOf("케이블 로우", "cable row", "시티드 로우"),
        MovementPattern.INVERTED_ROW to listOf("인버티드", "inverted"),
        MovementPattern.DEADLIFT to listOf("데드리프트", "deadlift"),

        // === 어깨 ===
        MovementPattern.LATERAL_RAISE to listOf("레터럴 레이즈", "사이드 레이즈", "lateral raise"),
        MovementPattern.FRONT_RAISE to listOf("프론트 레이즈", "front raise"),
        MovementPattern.REAR_DELT to listOf("리어 델트", "rear delt", "후면 삼각"),
        MovementPattern.FACE_PULL to listOf("페이스 풀", "face pull"),
        MovementPattern.UPRIGHT_ROW to listOf("업라이트 로우", "upright row"),
        MovementPattern.SHRUG to listOf("슈러그", "shrug"),

        // === 팔 ===
        MovementPattern.TRICEP_OVERHEAD to listOf("트라이셉스 익스텐션", "오버헤드 익스텐션"),
        MovementPattern.TRICEP_LYING to listOf("스컬크러셔", "skull crusher", "라잉 익스텐션"),
        MovementPattern.TRICEP_PUSHDOWN to listOf("푸시다운", "pushdown", "킥백", "kickback"),
        MovementPattern.BICEP_CURL_CABLE to listOf("케이블 컬", "cable curl"),

        // === 코어 ===
        MovementPattern.CRUNCH to listOf("크런치", "crunch", "싯업", "situp"),
        MovementPattern.LEG_RAISE to listOf("레그 레이즈", "leg raise", "니 레이즈", "행잉"),
        MovementPattern.PLANK to listOf("플랭크", "plank"),
        MovementPattern.ROTATION to listOf("러시안 트위스트", "우드촉", "로테이션"),
        MovementPattern.ROLLOUT to listOf("롤아웃", "ab 휠", "앱 롤러"),

        // === 올림픽 리프팅 ===
        MovementPattern.CLEAN to listOf("클린", "clean"),
        MovementPattern.SNATCH to listOf("스내치", "snatch"),

        // === 기타 ===
        MovementPattern.COMPOUND_COMPLEX to listOf("스러스터", "thruster", "맨메이커", "데빌 프레스"),
        MovementPattern.CARRY to listOf("파머스 워크", "farmer", "캐리", "carry"),
        MovementPattern.CARDIO to listOf("로잉 머신", "바이크", "트레드밀", "러닝", "줄넘기"),
        MovementPattern.PLYOMETRIC to listOf("점프", "jump", "버피", "burpee", "플라이오")
    )

    /**
     * 운동을 패턴으로 분류
     */
    fun classifyExercise(exercise: Exercise): MovementPattern {
        val name = exercise.name.lowercase()

        // 1. HIP_HINGE vs DEADLIFT 구분 (먼저 체크)
        if (isHipHinge(name)) return MovementPattern.HIP_HINGE

        // 2. 스쿼트 제외 조건 체크
        if (isSquatExclusion(name)) {
            return classifyNonSquat(name)
        }

        // 3. 일반 패턴 매칭
        return findMatchingPattern(name) ?: MovementPattern.OTHER
    }

    private fun isHipHinge(name: String): Boolean {
        val hipHingeKeywords = listOf("루마니안", "romanian", "rdl", "굿모닝", "good morning",
            "백 익스텐션", "스티프 레그", "stiff leg", "글루트 햄")
        return hipHingeKeywords.any { name.contains(it) }
    }

    private fun isSquatExclusion(name: String): Boolean {
        val exclusions = listOf("런지", "스플릿", "불가리안", "해크", "레그 프레스", "점프")
        return name.contains("스쿼트") && exclusions.any { name.contains(it) }
    }

    private fun classifyNonSquat(name: String): MovementPattern {
        return when {
            name.contains("런지") || name.contains("스플릿") || name.contains("불가리안") -> MovementPattern.LUNGE
            name.contains("해크") || name.contains("레그 프레스") -> MovementPattern.LEG_PRESS
            name.contains("점프") -> MovementPattern.PLYOMETRIC
            else -> MovementPattern.OTHER
        }
    }

    private fun findMatchingPattern(name: String): MovementPattern? {
        // 복합 조건 먼저 체크 (인클라인 + 바벨/덤벨)
        if (name.contains("인클라인") || name.contains("incline")) {
            return when {
                name.contains("바벨") || name.contains("barbell") -> MovementPattern.INCLINE_PRESS_BARBELL
                name.contains("덤벨") || name.contains("dumbbell") -> MovementPattern.INCLINE_PRESS_DUMBBELL
                else -> MovementPattern.INCLINE_PRESS_DUMBBELL
            }
        }

        // 벤치프레스 분류 (바벨/덤벨/머신)
        if (name.contains("벤치") || name.contains("bench")) {
            return when {
                name.contains("머신") || name.contains("machine") -> MovementPattern.HORIZONTAL_PRESS_MACHINE
                name.contains("덤벨") || name.contains("dumbbell") -> MovementPattern.HORIZONTAL_PRESS_DUMBBELL
                else -> MovementPattern.HORIZONTAL_PRESS_BARBELL
            }
        }

        // 숄더 프레스 분류
        if ((name.contains("숄더") || name.contains("shoulder")) && name.contains("프레스")) {
            return when {
                name.contains("머신") || name.contains("machine") -> MovementPattern.VERTICAL_PRESS_MACHINE
                name.contains("덤벨") || name.contains("dumbbell") -> MovementPattern.VERTICAL_PRESS_DUMBBELL
                else -> MovementPattern.VERTICAL_PRESS_BARBELL
            }
        }

        // 로우 분류
        if (name.contains("로우") || name.contains("row")) {
            return when {
                name.contains("바벨") || name.contains("barbell") || name.contains("펜들레이") || name.contains("t-바") -> MovementPattern.BARBELL_ROW
                name.contains("덤벨") || name.contains("dumbbell") || name.contains("원암") -> MovementPattern.DUMBBELL_ROW
                name.contains("케이블") || name.contains("cable") || name.contains("시티드") -> MovementPattern.CABLE_ROW
                name.contains("인버티드") -> MovementPattern.INVERTED_ROW
                name.contains("업라이트") -> MovementPattern.UPRIGHT_ROW
                else -> MovementPattern.BARBELL_ROW
            }
        }

        // 컬 분류 (이두)
        if (name.contains("컬") || name.contains("curl")) {
            // 레그 컬은 제외
            if (name.contains("레그") || name.contains("leg") || name.contains("햄스트링") || name.contains("노르딕")) {
                return MovementPattern.LEG_CURL
            }
            return when {
                name.contains("케이블") || name.contains("cable") -> MovementPattern.BICEP_CURL_CABLE
                name.contains("바벨") || name.contains("barbell") || name.contains("ez") || name.contains("프리처") -> MovementPattern.BICEP_CURL_BARBELL
                else -> MovementPattern.BICEP_CURL_DUMBBELL
            }
        }

        // 단순 키워드 매칭
        for ((pattern, keywords) in simplePatternKeywords) {
            if (keywords.any { name.contains(it) }) {
                return pattern
            }
        }

        // 스쿼트 (마지막에 체크)
        if (name.contains("스쿼트") || name.contains("squat")) {
            return MovementPattern.SQUAT
        }

        return null
    }

    // 단순 키워드 매핑 (복합 조건이 필요 없는 패턴들)
    private val simplePatternKeywords = mapOf(
        // 하체
        MovementPattern.LEG_PRESS to listOf("레그 프레스", "레그프레스", "leg press", "해크 스쿼트", "hack squat"),
        MovementPattern.LEG_EXTENSION to listOf("레그 익스텐션", "leg extension"),
        MovementPattern.GLUTE_FOCUSED to listOf("힙 쓰러스트", "hip thrust", "글루트", "glute", "케틀벨 스윙"),
        MovementPattern.CALF to listOf("카프", "calf", "종아리"),
        MovementPattern.LUNGE to listOf("런지", "lunge", "불가리안", "스플릿", "스텝업"),

        // 상체 밀기
        MovementPattern.DIPS to listOf("딥스", "dips"),
        MovementPattern.PUSHUP to listOf("푸시업", "push up"),
        MovementPattern.FLY to listOf("플라이", "fly", "펙덱"),
        MovementPattern.DECLINE_PRESS to listOf("디클라인", "decline"),
        MovementPattern.VERTICAL_PRESS_BARBELL to listOf("밀리터리", "오버헤드 프레스", "푸시 프레스"),

        // 상체 당기기
        MovementPattern.PULLUP_CHINUP to listOf("풀업", "pullup", "친업", "chinup"),
        MovementPattern.LAT_PULLDOWN to listOf("랫 풀다운", "lat pulldown", "풀다운"),
        MovementPattern.DEADLIFT to listOf("데드리프트", "deadlift"),

        // 어깨
        MovementPattern.LATERAL_RAISE to listOf("레터럴", "사이드 레이즈", "lateral"),
        MovementPattern.FRONT_RAISE to listOf("프론트 레이즈", "front raise"),
        MovementPattern.REAR_DELT to listOf("리어 델트", "rear delt", "후면"),
        MovementPattern.FACE_PULL to listOf("페이스 풀", "face pull"),
        MovementPattern.SHRUG to listOf("슈러그", "shrug"),

        // 삼두
        MovementPattern.TRICEP_OVERHEAD to listOf("오버헤드 익스텐션", "트라이셉스 익스텐션"),
        MovementPattern.TRICEP_LYING to listOf("스컬크러셔", "skull crusher", "라잉 익스텐션"),
        MovementPattern.TRICEP_PUSHDOWN to listOf("푸시다운", "pushdown", "킥백"),

        // 코어
        MovementPattern.CRUNCH to listOf("크런치", "crunch", "싯업"),
        MovementPattern.LEG_RAISE to listOf("레그 레이즈", "leg raise", "행잉"),
        MovementPattern.PLANK to listOf("플랭크", "plank"),
        MovementPattern.ROTATION to listOf("러시안 트위스트", "우드촉", "로테이션"),
        MovementPattern.ROLLOUT to listOf("롤아웃", "ab 휠"),

        // 올림픽
        MovementPattern.CLEAN to listOf("클린", "clean"),
        MovementPattern.SNATCH to listOf("스내치", "snatch"),

        // 기타
        MovementPattern.COMPOUND_COMPLEX to listOf("스러스터", "thruster", "맨메이커"),
        MovementPattern.CARRY to listOf("파머스", "farmer", "캐리"),
        MovementPattern.CARDIO to listOf("로잉 머신", "바이크", "트레드밀", "러닝"),
        MovementPattern.PLYOMETRIC to listOf("점프", "jump", "버피", "플라이오")
    )
}
