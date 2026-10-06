package com.richjun.liftupai.domain.workout.service

import com.richjun.liftupai.domain.workout.util.WorkoutLocalization
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

/** 프리셋 템플릿 정의의 무결성: 카탈로그에 있는 운동만, 번역은 세 언어 모두, 코드·정렬 중복 없음 */
class PresetTemplatesTest {

    private val catalog = RecoFitEvalTest.loadExercises().associateBy { it.slug }

    @Test
    fun `템플릿 코드와 정렬 순서는 중복이 없다`() {
        assertEquals(PresetTemplates.ALL.size, PresetTemplates.ALL.map { it.code }.toSet().size)
        assertEquals(PresetTemplates.ALL.size, PresetTemplates.ALL.map { it.sortOrder }.toSet().size)
        assertTrue(PresetTemplates.ALL.all { it.code.length <= 50 && it.name.length <= 100 })
    }

    @Test
    fun `모든 종목 slug 가 카탈로그에 있다`() {
        val missing = PresetTemplates.ALL.flatMap { t -> t.days.flatMap { d -> d.exercises.map { it.slug } } }.filter { it !in catalog }
        assertTrue(missing.isEmpty(), "카탈로그에 없는 slug: $missing")
    }

    @Test
    fun `날 번호는 1부터 연속이고 일수와 맞고 날마다 4개 이상`() {
        PresetTemplates.ALL.forEach { t ->
            assertEquals((1..t.totalDays).toList(), t.days.map { it.dayNumber }, t.code)
            t.days.forEach { d -> assertTrue(d.exercises.size >= 4, "${t.code} day ${d.dayNumber}") }
        }
    }

    @Test
    fun `템플릿 이름·설명·날 이름이 ko en ja 모두 있다`() {
        PresetTemplates.ALL.forEach { t ->
            listOf("ko", "en", "ja").forEach { locale ->
                assertTrue(WorkoutLocalization.maybeMessage("template.${t.code}.name", locale)?.isNotBlank() == true, "${t.code} name $locale")
                assertTrue(WorkoutLocalization.maybeMessage("template.${t.code}.description", locale)?.isNotBlank() == true, "${t.code} desc $locale")
                t.days.forEach { d ->
                    assertTrue(WorkoutLocalization.maybeMessage("template.${t.code}.day.${d.dayNumber}.name", locale)?.isNotBlank() == true, "${t.code} day ${d.dayNumber} $locale")
                }
            }
        }
    }

    @Test
    fun `수준별로 쓰는 장비가 템플릿 성격과 맞다 - 홈트 템플릿에 바벨·머신·케이블이 없다`() {
        listOf("home_bodyweight_beginner", "home_dumbbell_beginner", "glute_leg_home").forEach { code ->
            val t = PresetTemplates.ALL.first { it.code == code }
            val equipment = t.days.flatMap { it.exercises }.mapNotNull { catalog[it.slug]?.equipment?.name }.toSet()
            assertTrue(equipment.none { it in setOf("BARBELL", "MACHINE", "CABLE") }, "$code uses $equipment")
        }
        val bodyweight = PresetTemplates.ALL.first { it.code == "home_bodyweight_beginner" }
        assertTrue(bodyweight.days.flatMap { it.exercises }.all { catalog[it.slug]?.equipment?.name == "BODYWEIGHT" })
    }

    @Test
    fun `추천 이유와 제안 사유 문구가 세 언어에 모두 있다`() {
        val keys = listOf(
            "template.reco.reason", "template.reco.goal.beginner_loss", "template.reco.goal.beginner", "template.reco.goal.loss",
            "template.reco.goal.strength", "template.reco.goal.general", "template.reco.goal.muscle", "template.reco.days.exact",
            "template.reco.days.near", "template.reco.equipment.bodyweight", "template.reco.equipment.home", "template.reco.equipment.fit",
            "template.reco.injury", "template.reco.focus.added", "template.reco.focus.natural",
            "suggest.reason.muscle", "suggest.reason.focus", "suggest.reason.essential", "suggest.reason.beginner",
            "suggest.reason.equipment", "suggest.reason.popular",
        ) + listOf("shoulder", "knee", "lower_back", "hip", "wrist", "elbow").map { "template.reco.injury.$it" } +
            FocusArea.values().map { "template.reco.focus.${it.name.lowercase()}" }
        listOf("ko", "en", "ja").forEach { locale ->
            keys.forEach { k -> assertTrue(WorkoutLocalization.maybeMessage(k, locale) != null, "$k ($locale)") }
        }
    }
}
