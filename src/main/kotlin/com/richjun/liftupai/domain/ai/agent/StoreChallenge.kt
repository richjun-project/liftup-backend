package com.richjun.liftupai.domain.ai.agent

import com.richjun.liftupai.domain.auth.entity.User
import com.richjun.liftupai.domain.workout.entity.SessionStatus
import com.richjun.liftupai.domain.workout.repository.WorkoutSessionRepository
import com.richjun.liftupai.global.time.AppTime
import org.springframework.stereotype.Component
import org.springframework.transaction.annotation.Transactional
import java.time.LocalDate
import java.time.ZoneId
import java.time.temporal.ChronoUnit

/**
 * App Store 인앱 이벤트 "4주 12회 챌린지"(2026-11-03 ~ 11-30, 이벤트 6829379583)를 AI PT 가 안다(2026-11-01).
 *
 * 이벤트 카드를 보고 들어온 사람이 "챌린지 어떻게 해?"라고 물으면 AI 가 몰랐다. 기간 안에서만 에이전트 프롬프트에
 * 규칙과 이 사용자의 진행(기간 안 완료 운동 수)을 넣는다. 통계 도구의 month 는 최근 30일이라 기간과 안 맞아 서버가 직접 센다.
 * 가입·보상은 없다 — 앱에서 끝낸 운동이 그대로 센다(진행은 통계 캘린더·홈 연속 기록).
 */
internal object StoreChallenge {
    val START: LocalDate = LocalDate.of(2026, 11, 3)
    val END: LocalDate = LocalDate.of(2026, 11, 30)
    const val GOAL = 12

    fun active(today: LocalDate): Boolean = !today.isBefore(START) && !today.isAfter(END)

    fun section(today: LocalDate, done: Int): String = buildString {
        val left = ChronoUnit.DAYS.between(today, END) + 1
        appendLine("## LIFTUP CHALLENGE (App Store event, Nov 3 - Nov 30)")
        appendLine("The app is running a \"4-week, 12-workout challenge\": finish $GOAL workouts between Nov 3 and Nov 30 (about 3 a week). " +
            "There is nothing to join — every workout finished in the app counts. Progress shows on the stats calendar and the streak on Home.")
        appendLine("This user's progress: $done/$GOAL finished workouts so far, $left days left including today.")
        appendLine("- Bring it up only when the user asks about the challenge, or briefly when it clearly fits (they just finished a workout, or ask how they are doing). Not in every reply.")
        append("- There are no prizes, rewards or rankings — never promise any.")
    }
}

@Component
@Transactional(readOnly = true)
class StoreChallengeService(private val sessions: WorkoutSessionRepository) {
    internal var today: (ZoneId) -> LocalDate = { AppTime.toUserLocalDate(AppTime.utcNow(), it) }

    /** 기간 밖이면 null */
    fun section(user: User, zoneId: ZoneId): String? {
        val day = today(zoneId)
        if (!StoreChallenge.active(day)) return null
        val since = AppTime.toUtc(StoreChallenge.START.atStartOfDay(), zoneId)
        val done = sessions.findByUserAndStartTimeAfter(user, since).count { it.status == SessionStatus.COMPLETED }
        return StoreChallenge.section(day, done)
    }
}
