package com.richjun.liftupai.domain.workout.controller

import com.richjun.liftupai.global.exception.BadRequestException

/**
 * 운동·검색·추천 요청 파라미터 검증. 형식이 잘못되었거나 상한을 넘으면 BadRequestException(→ 400 VALID001).
 * 상한은 서버 비용(검색 엔진 CPU, SQL 수)을 요청 한 건이 키우지 못하게 하는 장치다.
 */
object ExerciseRequestValidator {
    const val MAX_QUERY_LENGTH = 100
    const val MAX_PAGE_SIZE = 100
    const val MAX_PAGE = 10_000
    const val MAX_LOCALE_LENGTH = 20
    const val MAX_CSV_LENGTH = 400
    const val MAX_EQUIPMENT_TOKENS = 20
    const val MAX_MUSCLE_TOKENS = 20
    const val MAX_EXCLUDE_IDS = 200
    const val MAX_EXCLUDE_IDS_LENGTH = 4_000
    const val MAX_SUGGESTION_LIMIT = 30

    // 온보딩 목표 선택지가 20개이고 "모두 고르라"고 묻는다 — 예전 10개 제한은 많이 고른 사용자의 추천을 400 으로 막았다
    const val MAX_GOALS = 30
    const val MAX_EQUIPMENT = 20
    const val MAX_INJURIES = 15
    const val MAX_FOCUS_AREAS = 10
    const val MAX_ITEM_LENGTH = 64

    private val LOCALE = Regex("^[A-Za-z]{2,3}([-_][A-Za-z0-9]{2,8}){0,2}$")

    fun bad(message: String): Nothing = throw BadRequestException(message)

    fun query(raw: String?, name: String = "search") {
        if (raw != null && raw.length > MAX_QUERY_LENGTH) bad("$name too long (max $MAX_QUERY_LENGTH)")
    }

    fun locale(raw: String?) {
        if (raw == null || raw.isEmpty()) return
        if (raw.length > MAX_LOCALE_LENGTH || !LOCALE.matches(raw.trim())) bad("Invalid locale")
    }

    /** page 는 0 이상, size 는 1 이상(100 초과는 100 으로 줄인다). 반환: 안전한 페이지 크기 */
    fun page(page: Int?, size: Int?): Int {
        if (page != null && (page < 0 || page > MAX_PAGE)) bad("Invalid page")
        if (size != null && size < 1) bad("Invalid size")
        return (size ?: 20).coerceAtMost(MAX_PAGE_SIZE)
    }

    /** limit 는 1 이상(상한 초과는 상한으로 줄인다) */
    fun limit(limit: Int?, default: Int, max: Int): Int {
        if (limit != null && limit < 1) bad("Invalid limit")
        return (limit ?: default).coerceAtMost(max)
    }

    fun csv(raw: String?, name: String, maxTokens: Int, maxLength: Int = MAX_CSV_LENGTH) {
        if (raw == null) return
        if (raw.length > maxLength) bad("$name too long")
        if (raw.count { it == ',' } + 1 > maxTokens) bad("$name has too many values (max $maxTokens)")
    }

    fun list(raw: List<String?>?, name: String, maxSize: Int) {
        if (raw == null) return
        if (raw.size > maxSize) bad("$name has too many items (max $maxSize)")
        raw.forEach {
            if (it == null) bad("$name contains null")
            if (it.length > MAX_ITEM_LENGTH) bad("$name item too long")
        }
    }

    fun text(raw: String?, name: String, maxLength: Int = MAX_ITEM_LENGTH) {
        if (raw != null && raw.length > maxLength) bad("$name too long")
    }

    fun range(value: Int?, name: String, min: Int, max: Int) {
        if (value != null && (value < min || value > max)) bad("$name out of range ($min..$max)")
    }
}
