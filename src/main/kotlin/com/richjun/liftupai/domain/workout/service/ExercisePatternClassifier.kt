package com.richjun.liftupai.domain.workout.service

import com.richjun.liftupai.domain.workout.entity.Exercise
import org.springframework.stereotype.Service

/**
 * 운동 패턴 분류기
 *
 * 567개 운동을 패턴별로 분류하여, 한 세션에서 같은 패턴의 운동이 중복되지 않도록 함
 * 전문 PT가 프로그램을 짤 때 사용하는 방식과 동일
 *
 * 예: 하체 운동 시
 * - 스쿼트 패턴 (백/프론트/박스) 중 1개
 * - 런지 패턴 (런지/불가리안/리버스) 중 1개
 * - 힙 힌지 (데드리프트/RDL/굿모닝) 중 1개
 * - 보조 운동 (레그 컬/익스텐션) 각 1개
 */
@Service
class ExercisePatternClassifier {

    enum class MovementPattern {
        // === 하체 패턴 ===
        SQUAT,              // 스쿼트 계열
        LUNGE,              // 런지/단측 계열
        HIP_HINGE,          // 힙 힌지 (데드리프트/RDL/굿모닝)
        LEG_PRESS,          // 레그 프레스/해크 스쿼트
        LEG_CURL,           // 레그 컬
        LEG_EXTENSION,      // 레그 익스텐션
        GLUTE_FOCUSED,      // 힙 쓰러스트/글루트 드라이브
        CALF,               // 카프 레이즈

        // === 상체 밀기 (PUSH) 패턴 ===
        HORIZONTAL_PRESS_BARBELL,    // 바벨 벤치프레스
        HORIZONTAL_PRESS_DUMBBELL,   // 덤벨 벤치프레스
        HORIZONTAL_PRESS_MACHINE,    // 체스트 프레스 머신
        INCLINE_PRESS_BARBELL,       // 인클라인 바벨
        INCLINE_PRESS_DUMBBELL,      // 인클라인 덤벨
        DECLINE_PRESS,               // 디클라인
        VERTICAL_PRESS_BARBELL,      // 오버헤드 프레스/밀리터리
        VERTICAL_PRESS_DUMBBELL,     // 덤벨 숄더 프레스
        VERTICAL_PRESS_MACHINE,      // 숄더 프레스 머신
        DIPS,                        // 딥스
        PUSHUP,                      // 푸시업 변형
        FLY,                         // 플라이 (덤벨/케이블/펙덱)

        // === 상체 당기기 (PULL) 패턴 ===
        PULLUP_CHINUP,               // 풀업/친업
        LAT_PULLDOWN,                // 랫 풀다운
        BARBELL_ROW,                 // 바벨 로우
        DUMBBELL_ROW,                // 덤벨 로우
        CABLE_ROW,                   // 케이블/머신 로우
        INVERTED_ROW,                // 인버티드 로우
        DEADLIFT,                    // 데드리프트 (Pull Day용)

        // === 어깨 패턴 ===
        LATERAL_RAISE,               // 레터럴 레이즈
        FRONT_RAISE,                 // 프론트 레이즈
        REAR_DELT,                   // 리어 델트 플라이
        FACE_PULL,                   // 페이스 풀
        UPRIGHT_ROW,                 // 업라이트 로우
        SHRUG,                       // 슈러그

        // === 팔 패턴 ===
        BICEP_CURL_BARBELL,          // 바벨 컬
        BICEP_CURL_DUMBBELL,         // 덤벨 컬 (스탠딩/인클라인/해머)
        BICEP_CURL_CABLE,            // 케이블 컬
        TRICEP_OVERHEAD,             // 트라이셉스 오버헤드 익스텐션
        TRICEP_LYING,                // 스컬크러셔 (라잉)
        TRICEP_PUSHDOWN,             // 푸시다운/킥백

        // === 코어 패턴 ===
        CRUNCH,                      // 크런치 변형
        LEG_RAISE,                   // 레그 레이즈
        PLANK,                       // 플랭크
        ROTATION,                    // 회전 (러시안 트위스트/우드촉)
        ROLLOUT,                     // AB 휠/롤아웃

        // === 올림픽 리프팅 ===
        CLEAN,                       // 클린 변형
        SNATCH,                      // 스내치 변형

        // === 기타 ===
        COMPOUND_COMPLEX,            // 스러스터/컴플렉스
        CARRY,                       // 파머스 워크/캐리
        CARDIO,                      // 유산소
        PLYOMETRIC,                  // 점프/플라이오
        STRETCHING,                  // 스트레칭/모빌리티
        OTHER                        // 기타
    }

    /**
     * 운동 이름으로 패턴 판별
     */
    fun classifyExercise(exercise: Exercise): MovementPattern {
        val name = exercise.name.lowercase()

        return when {
            // === 하체 패턴 ===
            isSquatPattern(name) -> MovementPattern.SQUAT
            isLungePattern(name) -> MovementPattern.LUNGE
            isHipHingePattern(name) -> MovementPattern.HIP_HINGE
            isLegPressPattern(name) -> MovementPattern.LEG_PRESS
            isLegCurlPattern(name) -> MovementPattern.LEG_CURL
            isLegExtensionPattern(name) -> MovementPattern.LEG_EXTENSION
            isGluteFocusedPattern(name) -> MovementPattern.GLUTE_FOCUSED
            isCalfPattern(name) -> MovementPattern.CALF

            // === 상체 밀기 ===
            isDipsPattern(name) -> MovementPattern.DIPS
            isPushupPattern(name) -> MovementPattern.PUSHUP
            isFlyPattern(name) -> MovementPattern.FLY
            isInclinePressBarbell(name) -> MovementPattern.INCLINE_PRESS_BARBELL
            isInclinePressDumbbell(name) -> MovementPattern.INCLINE_PRESS_DUMBBELL
            isDeclinePress(name) -> MovementPattern.DECLINE_PRESS
            isVerticalPressBarbell(name) -> MovementPattern.VERTICAL_PRESS_BARBELL
            isVerticalPressDumbbell(name) -> MovementPattern.VERTICAL_PRESS_DUMBBELL
            isVerticalPressMachine(name) -> MovementPattern.VERTICAL_PRESS_MACHINE
            isHorizontalPressBarbell(name) -> MovementPattern.HORIZONTAL_PRESS_BARBELL
            isHorizontalPressDumbbell(name) -> MovementPattern.HORIZONTAL_PRESS_DUMBBELL
            isHorizontalPressMachine(name) -> MovementPattern.HORIZONTAL_PRESS_MACHINE

            // === 상체 당기기 ===
            isPullupChinup(name) -> MovementPattern.PULLUP_CHINUP
            isLatPulldown(name) -> MovementPattern.LAT_PULLDOWN
            isBarbellRow(name) -> MovementPattern.BARBELL_ROW
            isDumbbellRow(name) -> MovementPattern.DUMBBELL_ROW
            isCableRow(name) -> MovementPattern.CABLE_ROW
            isInvertedRow(name) -> MovementPattern.INVERTED_ROW
            isDeadlift(name) -> MovementPattern.DEADLIFT

            // === 어깨 ===
            isLateralRaise(name) -> MovementPattern.LATERAL_RAISE
            isFrontRaise(name) -> MovementPattern.FRONT_RAISE
            isRearDelt(name) -> MovementPattern.REAR_DELT
            isFacePull(name) -> MovementPattern.FACE_PULL
            isUprightRow(name) -> MovementPattern.UPRIGHT_ROW
            isShrug(name) -> MovementPattern.SHRUG

            // === 팔 ===
            isBicepCurlBarbell(name) -> MovementPattern.BICEP_CURL_BARBELL
            isBicepCurlDumbbell(name) -> MovementPattern.BICEP_CURL_DUMBBELL
            isBicepCurlCable(name) -> MovementPattern.BICEP_CURL_CABLE
            isTricepOverhead(name) -> MovementPattern.TRICEP_OVERHEAD
            isTricepLying(name) -> MovementPattern.TRICEP_LYING
            isTricepPushdown(name) -> MovementPattern.TRICEP_PUSHDOWN

            // === 코어 ===
            isCrunchPattern(name) -> MovementPattern.CRUNCH
            isLegRaisePattern(name) -> MovementPattern.LEG_RAISE
            isPlankPattern(name) -> MovementPattern.PLANK
            isRotationPattern(name) -> MovementPattern.ROTATION
            isRolloutPattern(name) -> MovementPattern.ROLLOUT

            // === 올림픽 리프팅 ===
            isCleanPattern(name) -> MovementPattern.CLEAN
            isSnatchPattern(name) -> MovementPattern.SNATCH

            // === 기타 ===
            isCompoundComplex(name) -> MovementPattern.COMPOUND_COMPLEX
            isCarryPattern(name) -> MovementPattern.CARRY
            isCardioPattern(name) -> MovementPattern.CARDIO
            isPlyometricPattern(name) -> MovementPattern.PLYOMETRIC

            else -> MovementPattern.OTHER
        }
    }

    // === 하체 패턴 매칭 함수 ===

    private fun isSquatPattern(name: String): Boolean {
        return name.contains("스쿼트") && !name.contains("런지") && !name.contains("스플릿") &&
               !name.contains("불가리안") && !name.contains("시시") && !name.contains("해크") &&
               !name.contains("레그 프레스") && !name.contains("점프")
    }

    private fun isLungePattern(name: String): Boolean {
        return name.contains("런지") || name.contains("스플릿") || name.contains("불가리안") ||
               name.contains("커티시")
    }

    private fun isHipHingePattern(name: String): Boolean {
        if (name.contains("데드리프트") || name.contains("rdl") || name.contains("루마니안")) return true
        if (name.contains("굿모닝") || name.contains("백 익스텐션")) return true
        if (name.contains("스티프 레그") || name.contains("싱글 레그 데드")) return true
        return false
    }

    private fun isLegPressPattern(name: String): Boolean {
        return name.contains("레그 프레스") || name.contains("레그프레스") ||
               (name.contains("해크") && name.contains("머신"))
    }

    private fun isLegCurlPattern(name: String): Boolean {
        return name.contains("레그 컬") || name.contains("햄스트링 컬") ||
               (name.contains("노르딕") && name.contains("컬"))
    }

    private fun isLegExtensionPattern(name: String): Boolean {
        return name.contains("레그 익스텐션")
    }

    private fun isGluteFocusedPattern(name: String): Boolean {
        return name.contains("힙 쓰러스트") || name.contains("글루트 드라이브") ||
               name.contains("글루트 햄")
    }

    private fun isCalfPattern(name: String): Boolean {
        return name.contains("카프") || name.contains("종아리")
    }

    // === 상체 밀기 패턴 ===

    private fun isHorizontalPressBarbell(name: String): Boolean {
        if (name.contains("인클라인") || name.contains("디클라인")) return false
        return (name.contains("바벨") && name.contains("벤치")) ||
               name.contains("바벨 벤치프레스") ||
               (name.contains("보드") && name.contains("프레스")) ||
               name.contains("플로어 프레스") && name.contains("바벨")
    }

    private fun isHorizontalPressDumbbell(name: String): Boolean {
        if (name.contains("인클라인") || name.contains("디클라인")) return false
        return (name.contains("덤벨") && name.contains("벤치프레스")) ||
               (name.contains("덤벨") && name.contains("프레스") && !name.contains("숄더") && !name.contains("오버헤드"))
    }

    private fun isHorizontalPressMachine(name: String): Boolean {
        return name.contains("체스트 프레스 머신") || name.contains("해머스트렝스 체스트")
    }

    private fun isInclinePressBarbell(name: String): Boolean {
        return name.contains("인클라인") && name.contains("바벨")
    }

    private fun isInclinePressDumbbell(name: String): Boolean {
        return name.contains("인클라인") && name.contains("덤벨") && name.contains("프레스")
    }

    private fun isDeclinePress(name: String): Boolean {
        return name.contains("디클라인") && name.contains("프레스")
    }

    private fun isVerticalPressBarbell(name: String): Boolean {
        return (name.contains("밀리터리") || name.contains("오버헤드 프레스") ||
                name.contains("솔더 프레스 바벨") || name.contains("푸시 프레스") ||
                name.contains("푸시 저크")) && !name.contains("덤벨")
    }

    private fun isVerticalPressDumbbell(name: String): Boolean {
        return (name.contains("덤벨") && name.contains("숄더 프레스")) ||
               name.contains("아놀드 프레스") || name.contains("시티드 덤벨 프레스") ||
               name.contains("스탠딩 덤벨 프레스")
    }

    private fun isVerticalPressMachine(name: String): Boolean {
        return name.contains("숄더 프레스 머신") ||
               (name.contains("스미스머신") && name.contains("숄더"))
    }

    private fun isDipsPattern(name: String): Boolean {
        return name.contains("딥스") && !name.contains("디피시트")
    }

    private fun isPushupPattern(name: String): Boolean {
        return name.contains("푸시업") || name.contains("push up")
    }

    private fun isFlyPattern(name: String): Boolean {
        return name.contains("플라이") && !name.contains("데드")
    }

    // === 상체 당기기 패턴 ===

    private fun isPullupChinup(name: String): Boolean {
        return (name.contains("풀업") || name.contains("친업")) && !name.contains("머신")
    }

    private fun isLatPulldown(name: String): Boolean {
        return name.contains("랫 풀다운") || name.contains("풀다운 머신")
    }

    private fun isBarbellRow(name: String): Boolean {
        return (name.contains("바벨") && name.contains("로우")) ||
               name.contains("펜들레이") || name.contains("t-바")
    }

    private fun isDumbbellRow(name: String): Boolean {
        return (name.contains("덤벨") && name.contains("로우")) ||
               name.contains("원암 덤벨") || name.contains("벤트오버 덤벨")
    }

    private fun isCableRow(name: String): Boolean {
        return (name.contains("케이블") && name.contains("로우")) ||
               name.contains("시티드 케이블") || name.contains("로우 머신")
    }

    private fun isInvertedRow(name: String): Boolean {
        return name.contains("인버티드")
    }

    private fun isDeadlift(name: String): Boolean {
        // 데드리프트는 HIP_HINGE와 DEADLIFT 둘 다 가능하지만, 여기서는 Pull Day용으로 분류
        return name.contains("데드리프트") && !name.contains("루마니안") && !name.contains("rdl")
    }

    // === 어깨 패턴 ===

    private fun isLateralRaise(name: String): Boolean {
        return (name.contains("레터럴") || name.contains("사이드")) && name.contains("레이즈")
    }

    private fun isFrontRaise(name: String): Boolean {
        return name.contains("프론트 레이즈") || name.contains("전면 레이즈")
    }

    private fun isRearDelt(name: String): Boolean {
        return (name.contains("리어 델트") || name.contains("후면")) ||
               (name.contains("리버스") && name.contains("플라이"))
    }

    private fun isFacePull(name: String): Boolean {
        return name.contains("페이스 풀")
    }

    private fun isUprightRow(name: String): Boolean {
        return name.contains("업라이트 로우")
    }

    private fun isShrug(name: String): Boolean {
        return name.contains("슈러그")
    }

    // === 팔 패턴 ===

    private fun isBicepCurlBarbell(name: String): Boolean {
        return (name.contains("바벨") && name.contains("컬")) ||
               name.contains("ez-바 컬") || name.contains("프리처") && name.contains("바벨")
    }

    private fun isBicepCurlDumbbell(name: String): Boolean {
        return (name.contains("덤벨") && name.contains("컬")) ||
               name.contains("해머 컬") || name.contains("컨센트레이션") ||
               name.contains("인클라인 덤벨 컬")
    }

    private fun isBicepCurlCable(name: String): Boolean {
        return name.contains("케이블") && name.contains("컬")
    }

    private fun isTricepOverhead(name: String): Boolean {
        return (name.contains("트라이") || name.contains("삼두")) &&
               (name.contains("오버헤드") || name.contains("익스텐션")) &&
               !name.contains("스컬") && !name.contains("라잉")
    }

    private fun isTricepLying(name: String): Boolean {
        return name.contains("스컬크러셔") || (name.contains("라잉") && name.contains("익스텐션"))
    }

    private fun isTricepPushdown(name: String): Boolean {
        return name.contains("푸시다운") || name.contains("킥백")
    }

    // === 코어 패턴 ===

    private fun isCrunchPattern(name: String): Boolean {
        return name.contains("크런치") || name.contains("싯업")
    }

    private fun isLegRaisePattern(name: String): Boolean {
        return (name.contains("레그 레이즈") || name.contains("니 레이즈") ||
                name.contains("행잉") && name.contains("레그"))
    }

    private fun isPlankPattern(name: String): Boolean {
        return name.contains("플랭크")
    }

    private fun isRotationPattern(name: String): Boolean {
        return name.contains("러시안 트위스트") || name.contains("우드촉") ||
               name.contains("로테이션")
    }

    private fun isRolloutPattern(name: String): Boolean {
        return name.contains("롤아웃") || name.contains("ab 휠") || name.contains("앱 롤러")
    }

    // === 올림픽 리프팅 ===

    private fun isCleanPattern(name: String): Boolean {
        return name.contains("클린") && !name.contains("머신")
    }

    private fun isSnatchPattern(name: String): Boolean {
        return name.contains("스내치") || name.contains("스네이치")
    }

    // === 기타 ===

    private fun isCompoundComplex(name: String): Boolean {
        return name.contains("스러스터") || name.contains("컴플렉스") ||
               name.contains("맨메이커") || name.contains("데빌 프레스")
    }

    private fun isCarryPattern(name: String): Boolean {
        return name.contains("캐리") || name.contains("워크") && name.contains("파머스")
    }

    private fun isCardioPattern(name: String): Boolean {
        return name.contains("로잉 머신") || name.contains("바이크") ||
               name.contains("트레드밀") || name.contains("러닝") ||
               name.contains("에르그") || name.contains("줄넘기")
    }

    private fun isPlyometricPattern(name: String): Boolean {
        return name.contains("점프") || name.contains("박스 점프") ||
               name.contains("플라이오메트릭") || name.contains("버피")
    }
}
