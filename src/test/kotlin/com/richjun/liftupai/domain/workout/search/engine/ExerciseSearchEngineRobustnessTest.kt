package com.richjun.liftupai.domain.workout.search.engine

import com.richjun.liftupai.domain.workout.entity.Equipment
import com.richjun.liftupai.domain.workout.entity.ExerciseCategory
import com.richjun.liftupai.domain.workout.search.ExerciseSearchDoc
import com.richjun.liftupai.domain.workout.search.ExerciseSearchEngine
import com.richjun.liftupai.domain.workout.search.ExerciseSearchEvalTest
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.BeforeAll
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.TestInstance
import java.util.Random
import java.util.concurrent.Callable
import java.util.concurrent.Executors

/**
 * 견고성: 어떤 입력도 예외 없이, 입력 길이와 무관하게(8000자·10만 자) 100ms 안에 끝나야 한다.
 * 한 요청이 코어를 오래 붙잡는 질의(R1)와 이모지·메타문자·공백 입력(F7)을 막는 회귀 테스트다.
 * 시간 측정은 캐시를 피하려고 매 시도마다 질의 끝에 다른 숫자를 붙이고, 세 번 중 가장 빠른 값을 쓴다(CI 부하 흡수).
 */
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class ExerciseSearchEngineRobustnessTest {

    private lateinit var docs: List<ExerciseSearchDoc>
    private lateinit var engine: ExerciseSearchEngine

    @BeforeAll
    fun setUp() {
        docs = ExerciseSearchEvalTest.loadCatalogDocs()
        engine = ExerciseSearchEngine(docs)
        // 클래스 로딩·JIT 를 미리 치른다
        listOf("squat", "スクワット", "하체운동", "without deadlifts", "chest at home", "ab".repeat(150)).forEach { engine.search(it) }
    }

    private fun bestMillis(base: String, category: ExerciseCategory? = null, equipments: Set<Equipment>? = null): Double {
        var best = Double.MAX_VALUE
        for (attempt in 1..3) {
            val q = base + attempt
            val started = System.nanoTime()
            engine.search(q, category, equipments, "ko")
            best = minOf(best, (System.nanoTime() - started) / 1e6)
        }
        return best
    }

    private fun assertFast(label: String, base: String) {
        val ms = bestMillis(base)
        assertTrue(ms < LIMIT_MS, "$label (${base.length}자) ${"%.1f".format(ms)}ms >= ${LIMIT_MS}ms")
    }

    @Test
    fun `8000자 영문 동의어 반복은 100ms 안에 끝난다`() = assertFast("curl row", "dumbbell curl row ".repeat(450))

    @Test
    fun `8000자 두 글자 반복은 100ms 안에 끝난다`() = assertFast("ab", "ab".repeat(4000))

    @Test
    fun `10만 자 두 글자 반복도 100ms 안에 끝난다`() = assertFast("xy", "xy".repeat(50_000))

    @Test
    fun `8000자 한국어 문장 반복은 100ms 안에 끝난다`() = assertFast("ko", "하체 운동하고 싶어 ".repeat(800))

    @Test
    fun `8000자 일본어 문장 반복은 100ms 안에 끝난다`() = assertFast("ja", "背中を鍛えたい脚を細くしたい".repeat(600))

    @Test
    fun `한일영 동의어가 섞인 긴 질의는 100ms 안에 끝난다`() = assertFast("mixed", "dumbbell 덤벨 ダンベル curl 컬 カール ".repeat(300))

    @Test
    fun `이모지만 있는 질의는 빠르게 빈 결과를 돌려준다`() {
        assertFast("emoji", "💪🔥".repeat(2000))
        assertTrue(engine.search("💪🔥🏋️").isEmpty())
    }

    @Test
    fun `이모지가 섞인 질의는 이모지를 무시하고 검색한다`() {
        val plain = engine.search("squat").map { it.doc.slug }
        assertEquals(plain.take(5), engine.search("🔥 squat 💪").map { it.doc.slug }.take(5))
    }

    @Test
    fun `정규식 메타문자는 문자 그대로 다루고 예외가 없다`() {
        assertFast("regex", ".*+?[](){}|\\^$".repeat(500))
        listOf("(", "[", "*", "+", "?", "\\", ".*", "(?i)squat", "squat|deadlift", "[a-z]+", "^squat$", "squat\\").forEach { engine.search(it) }
    }

    @Test
    fun `SQL 메타문자는 문자 그대로 다루고 예외가 없다`() {
        assertFast("sql", "'; DROP TABLE exercises; -- OR 1=1 ".repeat(200))
        listOf("' OR '1'='1", "\"; --", "%", "_", "%' --", "squat'; DELETE FROM exercise; --").forEach { engine.search(it) }
    }

    @Test
    fun `공백만 있는 질의는 브라우즈와 같다`() {
        val browse = engine.browse().map { it.doc.id }
        assertEquals(browse, engine.search(" \t\n ".repeat(3000)).map { it.doc.id })
        assertEquals(browse, engine.search("   ").map { it.doc.id })
    }

    @Test
    fun `null 과 빈 문자열은 브라우즈다`() {
        val browse = engine.browse().map { it.doc.id }
        assertEquals(browse, engine.search(null).map { it.doc.id })
        assertEquals(browse, engine.search("").map { it.doc.id })
    }

    @Test
    fun `제로폭·제어문자·널 문자가 섞여도 같은 단어로 검색된다`() {
        assertFast("zero-width", "sq​uat ".repeat(500))
        assertFast("nul", "squat\u0000deadlift\u0007".repeat(500))
        assertTrue(engine.search("squat\u0000").isNotEmpty())
    }

    @Test
    fun `NFKC 로 길이가 폭증하는 문자도 입력 상한으로 막힌다`() = assertFast("nfkc bomb", "ﷺ㌀ﬃ㏂".repeat(2000))

    @Test
    fun `짝 없는 서로게이트도 예외 없이 처리한다`() {
        engine.search("squat\uD83D")
        engine.search("\uDE00squat")
        assertFast("lone surrogate", "\uD800".repeat(4000))
    }

    @Test
    fun `2000 단어 질의는 100ms 안에 끝난다`() = assertFast("words", (1..2000).joinToString(" ") { "w$it" })

    @Test
    fun `부정 표현이 반복되는 긴 질의도 100ms 안에 끝난다`() = assertFast("negation", "without deadlifts no jumping 점프 없이 以外 ".repeat(200))

    @Test
    fun `숫자만 8000자도 100ms 안에 끝난다`() = assertFast("digits", "1234567890".repeat(800))

    @Test
    fun `동의어가 많은 단어 반복도 100ms 안에 끝난다`() = assertFast("synonyms", "벤치 풀업 덤벨 컬 로우 스쿼트 ".repeat(200))

    @Test
    fun `카테고리와 장비 필터가 있어도 긴 질의는 100ms 안에 끝난다`() {
        val ms = bestMillis("dumbbell curl row ".repeat(450), ExerciseCategory.ARMS, setOf(Equipment.DUMBBELL))
        assertTrue(ms < LIMIT_MS, "필터 + 긴 질의 ${"%.1f".format(ms)}ms")
    }

    @Test
    fun `무작위 문자열 300개가 모두 예외 없이 100ms 안에 끝난다`() {
        val rnd = Random(20261007)
        val alphabets = listOf(
            "abcdefghijklmnopqrstuvwxyz ",
            "ㄱㄴㄷㄹㅁㅂㅅㅇㅈㅊㅋㅌㅍㅎ가나다라마바사아자차카타파하 ",
            "あいうえおかきくけこさしすせそ・ー背胸脚腹 ",
            "0123456789xX% +-./ ",
            "!@#\$%^&*()_+{}[]|\\:;\"'<>?,./`~ \t"
        )
        var worst = 0.0
        repeat(300) { i ->
            val a = alphabets[rnd.nextInt(alphabets.size)] + alphabets[rnd.nextInt(alphabets.size)]
            val len = 1 + rnd.nextInt(if (i % 10 == 0) 3000 else 120)
            val q = String(CharArray(len) { a[rnd.nextInt(a.length)] })
            val started = System.nanoTime()
            val hits = engine.search(q, null, null, "ja")
            val ms = (System.nanoTime() - started) / 1e6
            worst = maxOf(worst, ms)
            assertTrue(hits.map { it.score }.all { it.isFinite() }, "점수가 유한하지 않음: $q")
            assertEquals(hits.size, hits.map { it.doc.id }.toSet().size, "중복 결과: $q")
        }
        assertTrue(worst < LIMIT_MS * 2, "무작위 질의 최악 ${"%.1f".format(worst)}ms")
    }

    @Test
    fun `같은 질의는 같은 결과이고 반환 목록을 바꿔도 캐시가 오염되지 않는다`() {
        val first = engine.search("스쿼트")
        val ids = first.map { it.doc.id }
        val mutable = engine.search("스쿼트") as MutableList
        mutable.clear()
        assertEquals(ids, engine.search("스쿼트").map { it.doc.id })
        assertEquals(ids, engine.search("  스쿼트  ").map { it.doc.id })
    }

    @Test
    fun `여러 스레드가 동시에 검색해도 결과가 단일 스레드와 같다`() {
        val queries = listOf("스쿼트", "bad knees leg workout", "背中を鍛えたい", "without deadlifts", "벤치프레스", "ヒップアップ", "점프 없는 유산소", "chest at home")
        val expected = queries.associateWith { ExerciseSearchEngine(docs).search(it).map { h -> h.doc.id } }
        val pool = Executors.newFixedThreadPool(8)
        try {
            val futures = (1..64).map { n ->
                pool.submit(Callable { queries[n % queries.size] to engine.search(queries[n % queries.size]).map { h -> h.doc.id } })
            }
            futures.forEach { f -> val (q, ids) = f.get(); assertEquals(expected[q], ids, "스레드 결과 불일치: $q") }
        } finally {
            pool.shutdown()
        }
    }

    @Test
    fun `질의는 앞 160자만 본다`() {
        val head = "dumbbell curl "
        val a = head.repeat(11) + "zzzzzz" // 154 + 6
        val longer = a + "x".repeat(5000)
        // 결정성 검사라 시간 예산(40ms)에 걸리지 않게 넉넉한 엔진을 쓴다 — 부하가 높은 기계에서 예산 초과로 잘린 결과끼리 비교하면 흔들렸다
        val unhurried = ExerciseSearchEngine(docs, budgetNanos = 10_000_000_000L)
        assertEquals(unhurried.search(a).map { it.doc.id }.take(20), unhurried.search(longer).map { it.doc.id }.take(20))
    }

    @Test
    fun `형식이 틀린 런타임 동의어 줄은 무시하고 정상 줄만 적용한다`() {
        val e = ExerciseSearchEngine(docs, listOf("", "# 주석", "=>", "a|b", "=> x", "x =>", "가나다라 => 스쿼트|squat", "||=>||"))
        val hits = e.search("가나다라").map { it.doc.slug }
        assertTrue(hits.isNotEmpty() && hits.any { "squat" in it }, "정상 줄이 적용되지 않음: ${hits.take(5)}")
        assertFalse(e.search("zzzzzz").isNotEmpty() && e.search("zzzzzz").size > 50)
    }

    @Test
    fun `이름이 없는 문서와 빈 카탈로그도 안전하다`() {
        val empty = ExerciseSearchEngine(emptyList())
        assertTrue(empty.search("squat").isEmpty())
        assertTrue(empty.search("").isEmpty())
        val blank = ExerciseSearchEngine(docs.take(5).map { it.copy(names = mapOf("en" to " ")) })
        blank.search("squat")
        blank.search("")
    }

    companion object {
        private const val LIMIT_MS = 100.0
    }
}
