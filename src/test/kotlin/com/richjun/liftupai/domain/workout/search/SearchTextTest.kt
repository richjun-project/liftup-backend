package com.richjun.liftupai.domain.workout.search

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class SearchTextTest {

    @Test
    fun `NFKC 로 전각 영문과 반각 가타카나를 통일한다`() {
        assertEquals("squat", SearchText.fold("ＳＱＵＡＴ"))
        assertEquals(SearchText.strictKey("ベンチプレス"), SearchText.strictKey("ﾍﾞﾝﾁﾌﾟﾚｽ"))
    }

    @Test
    fun `가타카나와 히라가나는 같은 키가 된다`() {
        assertEquals(SearchText.strictKey("スクワット"), SearchText.strictKey("すくわっと"))
        assertEquals(SearchText.strictKey("バーベル・フルスクワット"), SearchText.strictKey("ばーべる ふる すくわっと"))
    }

    @Test
    fun `공백 기호 중점이 달라도 같은 키가 된다`() {
        assertEquals(SearchText.strictKey("레그 프레스"), SearchText.strictKey("레그프레스"))
        assertEquals(SearchText.strictKey("ケーブル・プッシュダウン"), SearchText.strictKey("ケーブル プッシュダウン"))
        assertEquals(SearchText.strictKey("Push-up"), SearchText.strictKey("push up"))
    }

    @Test
    fun `라틴과 CJK 경계에서 단어가 나뉜다`() {
        assertEquals(listOf("bb", "스쿼트"), SearchText.words("BB스쿼트"))
        assertEquals(listOf("db", "ぷれす"), SearchText.words("DBプレス")) // 가나는 히라가나로 접힌다
    }

    @Test
    fun `영문 복수형은 단어 단위로 줄인다`() {
        assertEquals(listOf("squat"), SearchText.words("squats"))
        assertEquals(listOf("press"), SearchText.words("press"))
        assertEquals(SearchText.strictKey("pushups"), SearchText.strictKey("Push-up"))
    }

    @Test
    fun `느슨한 키는 한글 표기 변이를 흡수한다`() {
        val loose = { s: String -> SearchText.looseKey(SearchText.strictKey(s)) }
        assertEquals(loose("벤치프레스"), loose("밴치프레스"))
        assertEquals(loose("푸시업"), loose("푸쉬업"))
        assertEquals(loose("스쿼트"), loose("스콰트"))
        assertEquals(loose("힙 쓰러스트"), loose("힙 스러스트"))
    }

    @Test
    fun `느슨한 키는 일본어 장음과 촉음을 무시한다`() {
        val loose = { s: String -> SearchText.looseKey(SearchText.strictKey(s)) }
        assertEquals(loose("デッドリフト"), loose("デドリフト"))
        assertEquals(loose("スクワット"), loose("スクワト"))
    }

    @Test
    fun `초성열을 만든다`() {
        assertEquals("ㅂㅂㅍㅅㅋㅌ", SearchText.choseong("바벨 풀 스쿼트"))
        assertEquals("ㅂㅊㅍㄹㅅ", SearchText.choseong("벤치프레스"))
        assertTrue(SearchText.isChoseongQuery("ㅅㅋㅌ"))
        assertTrue(SearchText.isChoseongQuery("ㄷㄷ ㄹㅍㅌ"))
        assertTrue(SearchText.isChoseongQuery("스ㅋㅌ"))  // 완성 글자 + 초성 2개 이상은 초성 질의
        assertFalse(SearchText.isChoseongQuery("스ㅋ"))  // 초성 1개뿐이면 일반 검색
        assertFalse(SearchText.isChoseongQuery("스쿼트"))
        assertFalse(SearchText.isChoseongQuery("ㅅ"))
    }

    @Test
    fun `쌍자음 초성은 평음으로 접는다`() {
        assertEquals("ㅎㅅㄹㅅㅌ", SearchText.choseong("힙 쓰러스트"))
    }

    @Test
    fun `한글 자모 분해는 종성을 초성과 같은 문자로 매핑한다`() {
        assertEquals("ㅂㅔㄴㅊㅣ", SearchText.looseKey("벤치"))
        assertEquals("ㅅㅡㅋㅝㅌㅡ", SearchText.jamo("스쿼트"))
        assertEquals("ㅂㅔㄴㅊㅣ", SearchText.jamo("벤치"))
    }

    @Test
    fun `근사 부분문자열 매칭은 치환 삭제 삽입 전치를 오타 1개로 센다`() {
        assertEquals(0, SearchText.approxSubstring("squat", "barbellfullsquat", 2))
        assertEquals(1, SearchText.approxSubstring("squatt", "barbellfullsquat", 2)) // 삽입
        assertEquals(1, SearchText.approxSubstring("sqaut", "barbellfullsquat", 2)) // 인접 전치
        assertEquals(1, SearchText.approxSubstring("deadlfit", "barbelldeadlift", 2)) // 전치
        assertEquals(0, SearchText.approxSubstring("benchpres", "barbellbenchpress", 2)) // 입력 중인 접두
        assertEquals(3, SearchText.approxSubstring("zzzzzz", "barbellfullsquat", 2)) // maxK+1
    }

    @Test
    fun `자모 단위 오타는 1 로 센다`() {
        val a = SearchText.looseKey("스쿼드")
        val b = SearchText.looseKey("바벨스쿼트")
        assertEquals(1, SearchText.approxSubstring(a, b, 2))
    }

    @Test
    fun `허용 오타 수는 길이에 비례한다`() {
        assertEquals(0, SearchText.allowedTypos(3))
        assertEquals(1, SearchText.allowedTypos(6))
        assertEquals(2, SearchText.allowedTypos(10))
    }

    @Test
    fun `토크나이저 - 가타카나 위치와 단어 경계를 보존한다`() {
        val t = SearchText.tokenize("ダンベルだけで脚")
        assertEquals("だんべるだけで脚", t.strict)
        assertEquals(listOf(true, true, true, true, false, false, false, false), t.kata.toList())
        val latin = SearchText.tokenize("bench press")
        assertEquals(listOf(0, 5), latin.starts.toList())
        assertTrue(latin.isWordStart(5) && latin.isWordEnd(5) && latin.isWordEnd(latin.strict.length))
    }

    @Test
    fun `아포스트로피는 단어를 가르지 않는다 - captain's chair`() {
        assertEquals(SearchText.strictKey("captains chair"), SearchText.strictKey("captain's chair"))
        assertEquals(listOf("farmer", "walk"), SearchText.words("farmer's walks").map { it.replace("farmers", "farmer") })
    }

    @Test
    fun `복수형 처리 - crunches flies ups 와 ss·us 유지`() {
        assertEquals("crunch", SearchText.stem("crunches"))
        assertEquals("fly", SearchText.stem("flies"))
        assertEquals("up", SearchText.stem("ups"))
        assertEquals("press", SearchText.stem("press"))
        assertEquals("press", SearchText.stem("presses"))
        assertEquals("glass", SearchText.stem("glass"))
        assertEquals("bus", SearchText.stem("bus"))
    }

    @Test
    fun `입력 상한 - 160자 14단어 이후는 버린다`() {
        assertEquals(SearchText.MAX_QUERY_CHARS, SearchText.normalize("ab".repeat(5_000)).length)
        assertEquals(SearchText.MAX_QUERY_WORDS, SearchText.words((1..100).joinToString(" ") { "w$it" }).size)
        assertTrue(SearchText.normalize("\uFDFA".repeat(10_000)).length <= SearchText.MAX_QUERY_CHARS)
    }

    @Test
    fun `편집거리 - 치환 삽입 삭제 전치는 1이고 상한을 넘으면 상한+1`() {
        assertEquals(0, SearchText.editDistance("squat", "squat", 2))
        assertEquals(1, SearchText.editDistance("squat", "squatt", 2))
        assertEquals(1, SearchText.editDistance("squat", "sqaut", 2))
        assertEquals(1, SearchText.editDistance("squat", "sqiat", 2))
        assertEquals(3, SearchText.editDistance("squat", "deadlift", 2))
    }

    @Test
    fun `ヴ 계열과 작은 모음 가나는 느슨한 키에서 같다`() {
        val loose = { s: String -> SearchText.looseKey(SearchText.strictKey(s)) }
        assertEquals(loose("バーティカル"), loose("ヴァーティカル"))
        assertEquals(loose("スクワット"), loose("スクワァット"))
    }

    @Test
    fun `늘임 표기와 낱자 자모(웃음)는 단어를 해치지 않는다`() {
        assertEquals("squaat", SearchText.normalize("squaaaat"))
        assertEquals(SearchText.strictKey("스쿼트"), SearchText.strictKey("스쿼트트트트"))
        assertEquals(SearchText.strictKey("스쿼트"), SearchText.strictKey("스쿼트ㅋㅋㅋ"))
        assertEquals("1000", SearchText.normalize("1000"))
        assertFalse(SearchText.isChoseongQuery("스쿼트ㅋㅋ"))
        assertFalse(SearchText.isChoseongQuery("ㅎ"))
    }
}
