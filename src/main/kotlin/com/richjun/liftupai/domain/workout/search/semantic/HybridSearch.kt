package com.richjun.liftupai.domain.workout.search.semantic

import com.richjun.liftupai.domain.workout.entity.Equipment
import com.richjun.liftupai.domain.workout.entity.ExerciseCategory
import com.richjun.liftupai.domain.workout.search.ExerciseSearchDoc
import com.richjun.liftupai.domain.workout.search.ExerciseSearchEngine
import com.richjun.liftupai.domain.workout.search.ExerciseSearchHit

/**
 * 하이브리드 검색 — 어휘 엔진([ExerciseSearchEngine])의 결과에 의미(임베딩) 검색 결과를 RRF(Reciprocal Rank Fusion)로 섞는다.
 *
 * 순수·결정적이다: 같은 엔진·벡터·질의 벡터면 같은 결과. 네트워크는 모른다 — 질의 벡터는 호출자가 넘기는 함수([queryVector])로 받고,
 * 의미 검색이 필요 없다고 판단되면 그 함수를 부르지도 않는다(그래야 비용·지연이 안 든다).
 *
 * 1. 어휘 검색을 먼저 한다(몇 ms). 질의가 의미 검색 대상이 아니거나(브라우즈·초성·정확한 이름) 어휘 결과로 충분하면 그대로 돌려준다([needsSemantic]).
 * 2. 의미 후보 = 문서 벡터와 질의 벡터의 코사인 상위 [Config.semanticDepth] 개. 단, 어휘 검색이 그 질의에 거는 하드 조건
 *    ([SemanticConstraint] — 카테고리·장비 필터, 부정한 운동·장비·부위, 부상, 집·사물 의도)을 똑같이 통과한 문서만 후보가 된다.
 * 3. 어휘 상위 [Config.lexicalDepth] 개와 의미 후보를 RRF 로 합친다(점수 = Σ weight / (k + 순위)).
 *    합친 결과 뒤에 어휘 결과의 나머지를 원래 순서로 이어 붙인다 — 페이지 넘김·전체 길이는 어휘 검색과 같다.
 */
class HybridSearch(
    private val engine: ExerciseSearchEngine,
    private val vectors: ExerciseVectors,
    private val config: Config = Config()
) {
    data class Config(
        /** RRF 상수 k — 클수록 순위 차이의 영향이 작다 */
        val rrfK: Int = 60,
        val lexicalWeight: Double = 1.0,
        val semanticWeight: Double = 1.0,
        /** 융합에 쓰는 어휘 상위 개수 — 작게 잡아 어휘 상위권의 확신을 살리고, 의미는 더 넓게(아래) 본다 */
        val lexicalDepth: Int = 5,
        val semanticDepth: Int = 50,
        /**
         * 어휘 1위가 이 점수 이상이면(정확·단어 경계 이름 일치) 그 운동을 1위로 지킨다 — "squat" 같은 정식 이름 질의에서
         * 어휘 엔진이 고른 대표 운동이 의미 유사도 때문에 밀려나지 않게. null 이면 지키지 않는다.
         */
        val protectScore: Double? = 160.0,
        /** 자연어로 볼 최소 단어 수(공백 기준) */
        val minWords: Int = 3,
        /** 공백 없이 쓴 한·일·중 문장을 자연어로 볼 최소 글자 수 */
        val minCjkLetters: Int = 8,
        /** 어휘 결과가 비었을 때, 의미 1위 유사도가 이 값보다 낮으면 의미 결과도 버린다(운동과 무관한 질의에 억지 결과를 주지 않는다) */
        val emptyLexicalMinSimilarity: Double = 0.0,
        /** 부상 조건이 풀려 있으면 어휘 순위가 의미 검색이 모르는 정보(관절별 권장·주의 동작)를 담고 있어 가중치를 높인다 */
        val injuryLexicalBoost: Double = 2.0
    )

    /** [hits] 는 어휘 검색과 같은 길이의 전체 순서. [semantic] 은 의미 검색이 실제로 섞였는가. [degraded] 는 의미 검색이 필요했는데 질의 벡터를 못 구해 어휘 결과만 낸 경우(호출자는 이 결과를 오래 캐시하지 않는다). [complete] 는 어휘 검색이 시간 예산 안에 끝났는가. */
    class Result(val hits: List<ExerciseSearchHit>, val complete: Boolean, val semantic: Boolean, val degraded: Boolean = false)

    /** 엔진 문서 → 벡터 행(벡터가 없는 문서는 의미 후보가 될 수 없다) */
    private val docs: List<ExerciseSearchDoc> = engine.documents()
    private val rows: IntArray = IntArray(docs.size) { vectors.rowOf(docs[it].slug) ?: -1 }

    /** 의미 검색을 섞을 수 있는 문서 수(테스트·점검용) */
    val coveredDocs: Int get() = rows.count { it >= 0 }

    fun search(
        query: String?,
        category: ExerciseCategory? = null,
        equipments: Set<Equipment>? = null,
        locale: String? = null,
        queryVector: () -> FloatArray?
    ): Result {
        val lex = engine.searchOutcome(query, category, equipments, locale)
        // 어휘 검색이 시간 예산을 넘겨 잘렸으면 거기에 섞지 않는다(불완전한 순위에 의미 결과를 얹어 봐야 의미가 없다)
        if (!lex.complete) return Result(lex.hits, false, false)
        val constraint = engine.semanticConstraint(query, category, equipments) ?: return Result(lex.hits, true, false)
        if (!needsSemantic(query.orEmpty(), lex.hits, constraint)) return Result(lex.hits, true, false)
        val qv = queryVector()?.takeIf { it.size == vectors.dim } ?: return Result(lex.hits, true, semantic = false, degraded = true)
        val fused = fuse(lex.hits, constraint, qv) ?: return Result(lex.hits, true, false)
        return Result(fused, true, true)
    }

    /**
     * 의미 검색이 도움이 되는 질의인가.
     * 어휘 결과가 비었거나, 자연어 문장이거나, 사전이 못 알아들은 덩어리(별칭·오타·새 표현)가 있으면 그렇다.
     * "squat", "햄스트링", "ダンベル 背中" 같이 사전이 완전히 알아들은 짧은 키워드 질의는 어휘 엔진이 큐레이션한 순서가 더 낫다.
     */
    fun needsSemantic(query: String, lexical: List<ExerciseSearchHit>, constraint: SemanticConstraint): Boolean {
        if (lexical.isEmpty()) return true
        if (constraint.unrecognized) return true
        val words = query.trim().split(WHITESPACE).count { it.isNotEmpty() }
        if (words >= config.minWords) return true
        val cjk = query.count { it.code >= 0x250 && it.isLetter() }
        return words == 1 && cjk >= config.minCjkLetters
    }

    private fun fuse(lexical: List<ExerciseSearchHit>, constraint: SemanticConstraint, qv: FloatArray): List<ExerciseSearchHit>? {
        // 의미 후보: 하드 조건을 통과한 문서 중 코사인 상위 N
        val sims = ArrayList<Pair<Int, Float>>()
        for (i in docs.indices) {
            val row = rows[i]
            if (row < 0 || !constraint.allows(docs[i])) continue
            sims.add(i to vectors.dot(qv, row))
        }
        sims.sortWith(compareByDescending<Pair<Int, Float>> { it.second }.thenBy { docs[it.first].id })
        val semTop = sims.take(config.semanticDepth)
        if (semTop.isEmpty()) return null
        if (lexical.isEmpty() && semTop.first().second < config.emptyLexicalMinSimilarity) return null

        val k = config.rrfK.toDouble()
        val score = HashMap<Long, Double>()
        val doc = HashMap<Long, ExerciseSearchDoc>()
        val lexRank = HashMap<Long, Int>()
        val lexW = config.lexicalWeight * (if (constraint.injuryAware) config.injuryLexicalBoost else 1.0)
        lexical.take(config.lexicalDepth).forEachIndexed { r, h ->
            score.merge(h.doc.id, lexW / (k + r + 1), Double::plus)
            doc[h.doc.id] = h.doc
            lexRank[h.doc.id] = r
        }
        val semSim = HashMap<Long, Float>()
        semTop.forEachIndexed { r, (i, sim) ->
            val d = docs[i]
            score.merge(d.id, config.semanticWeight / (k + r + 1), Double::plus)
            doc[d.id] = d
            semSim[d.id] = sim
        }
        val protectedId = config.protectScore?.let { p -> lexical.firstOrNull()?.takeIf { it.score >= p }?.doc?.id }
        val fused = score.entries.sortedWith(
            compareByDescending<Map.Entry<Long, Double>> { it.key == protectedId }
                .thenByDescending { it.value }
                .thenBy { lexRank[it.key] ?: Int.MAX_VALUE }
                .thenByDescending { semSim[it.key] ?: -1f }
                .thenBy { it.key }
        )
        val out = ArrayList<ExerciseSearchHit>(lexical.size + semTop.size)
        val seen = HashSet<Long>()
        // 점수는 순서를 그대로 반영하도록 단조 감소로 매긴다(융합 점수는 작은 수라 읽기 쉽게 키운다)
        for (e in fused) { out.add(ExerciseSearchHit(doc.getValue(e.key), e.value * SCORE_SCALE)); seen.add(e.key) }
        val floor = out.lastOrNull()?.score ?: 0.0
        var tail = 0
        for (h in lexical) if (seen.add(h.doc.id)) out.add(ExerciseSearchHit(h.doc, floor - (++tail) * 1e-6))
        return out
    }

    companion object {
        private const val SCORE_SCALE = 1000.0
        private val WHITESPACE = Regex("\\s+")
    }
}
