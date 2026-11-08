package com.richjun.liftupai.domain.notification.util

import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test

class PtMessagePolicyTest {

    private val allPt = listOf("morning_meal_check", "lunch_meal_check", "workout_reminder", "dinner_meal_check", "sleep_prep")

    private fun sentOn(daysInactive: Long) = allPt.filter { PtMessagePolicy.shouldSend(it, daysInactive) }

    @Test
    fun `active users get two messages a day`() {
        assertThat(sentOn(0)).containsExactly("lunch_meal_check", "workout_reminder")
        assertThat(sentOn(1)).containsExactly("lunch_meal_check", "workout_reminder")
    }

    @Test
    fun `lapsed users only get the workout reminder on re-engagement days`() {
        val nudgeDays = (2L..60L).filter { sentOn(it).isNotEmpty() }
        assertThat(nudgeDays).containsExactly(2L, 4L, 7L, 14L, 30L)
        nudgeDays.forEach { assertThat(sentOn(it)).containsExactly("workout_reminder") }
    }

    @Test
    fun `dormant users get nothing`() {
        assertThat(sentOn(31)).isEmpty()
        assertThat(sentOn(365)).isEmpty()
    }

    @Test
    fun `users before their first workout get only the workout reminder`() {
        val sent = allPt.filter { PtMessagePolicy.shouldSend(it, 0, firstWorkoutPending = true) }
        assertThat(sent).containsExactly("workout_reminder")
        val lapsed = allPt.filter { PtMessagePolicy.shouldSend(it, 2, firstWorkoutPending = true) }
        assertThat(lapsed).containsExactly("workout_reminder")
    }

    @Test
    fun `meal checks are the only first-workout casualties`() {
        assertThat(allPt.filter(PtMessagePolicy::isMealCheck))
            .containsExactly("morning_meal_check", "lunch_meal_check", "dinner_meal_check")
        assertThat(PtMessagePolicy.shouldSend("custom_checkin", 0, firstWorkoutPending = true)).isTrue()
    }

    @Test
    fun `unknown schedules are not filtered`() {
        assertThat(PtMessagePolicy.shouldSend("custom_checkin", 100)).isTrue()
    }
}
