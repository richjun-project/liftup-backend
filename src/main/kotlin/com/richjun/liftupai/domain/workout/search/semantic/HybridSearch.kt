package com.richjun.liftupai.domain.workout.search.semantic

import com.richjun.liftupai.domain.workout.entity.Equipment
import com.richjun.liftupai.domain.workout.entity.ExerciseCategory
import com.richjun.liftupai.domain.workout.search.ExerciseSearchDoc
import com.richjun.liftupai.domain.workout.search.ExerciseSearchEngine
import com.richjun.liftupai.domain.workout.search.ExerciseSearchHit
import com.richjun.liftupai.domain.workout.search.rewrite.QueryRewrite
import com.richjun.liftupai.domain.workout.search.rewrite.RewriteFusion
import java.util.concurrent.CompletableFuture
import java.util.concurrent.Executor
import java.util.concurrent.TimeUnit

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
 * 4. (LLM 질의 해석, 선택) [search] 에 rewrite 가 주어지고 질의가 정확한 이름·브라우즈가 아니면, LLM 이 낸 영어 운동 이름 힌트를 어휘 엔진에 다시 던져
 *    (필터·하드 조건을 통과한 상위 몇 개) 하이브리드 결과와 RRF 로 섞고([RewriteFusion]), LLM 이 낸 제외 장비를 걸러 낸다.
 *    해석 호출은 질의 임베딩과 병렬로(executor 가 있을 때) 한다. 해석을 못 구하면 하이브리드 결과 그대로다.
 *    결정적 분석기의 하드 조건(부정·부상·집·필터·안전 규칙)은 LLM 의 말보다 항상 우선한다 — 힌트 후보는 같은 [SemanticConstraint] 를 통과해야만 쓰인다.
 */
class HybridSearch(
    private val engine: ExerciseSearchEngine,
    private val vectors: ExerciseVectors,
    private val config: Config = Config(),
    /** 있으면 LLM 해석과 질의 임베딩을 병렬로 가져온다. 없으면 차례로(테스트·평가 오라클) */
    private val executor: Executor? = null
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
        /**
         * 어휘 결과가 비었을 때, 의미 1위 유사도가 이 값보다 낮으면 의미 결과도 버린다(운동과 무관한 질의에 억지 결과를 주지 않고 0건 대안 경로로 보낸다).
         * 측정: gemini-embedding-001 에서 운동과 무관한 질의("asdfghjkl", "pizza recipe", "1234567" …)의 1위 코사인은 0.52~0.62,
         * 어휘가 못 찾았지만 정말 운동을 묻는 자연어 질의의 1위는 0.65 이상이었다.
         */
        val emptyLexicalMinSimilarity: Double = 0.63,
        /** 부상 조건이 풀려 있으면 어휘 순위가 의미 검색이 모르는 정보(관절별 권장·주의 동작)를 담고 있어 가중치를 높인다 */
        val injuryLexicalBoost: Double = 2.0,
        /** 어휘가 붙들 긍정 신호가 없는 질의(부정·군더더기뿐)에서 어휘 순위(= 인기 순 목록)에 주는 배율 */
        val uninformativeLexicalWeight: Double = 0.0,
        /** LLM 해석 융합 파라미터 */
        val rewrite: RewriteFusion.Config = RewriteFusion.Config(),
        /**
         * 어휘 1위가 이 점수 이상(정확·단어 경계 이름 일치)이고 질의가 [rewriteSkipMaxWords] 단어 이하이며 사전이 다 알아들었으면 LLM 을 부르지 않는다.
         * null 이면 정확한 이름·브라우즈가 아닌 모든 질의에 부른다. 5번째 검증셋에서는 건너뛰어도 점수가 같고(0.955), 짧은 이름 질의가 많은 개발셋의 MRR 이 오른다(0.934→0.935, 상위5 0.987→0.993).
         */
        val rewriteSkipScore: Double? = 160.0,
        val rewriteSkipMaxWords: Int = 2,
        /** LLM 해석을 기다리는 최대 시간(ms) — 해석기 자체의 시간 제한보다 조금 길게 */
        val rewriteWaitMillis: Long = 3000,
        /** 정확·단어 경계 이름 일치 1위([protectScore])를 LLM 힌트가 밀어내지 못하게 지킨다 */
        val rewriteKeepProtected: Boolean = true,
        val rewriteConstrainHints: Boolean = true,
        /** true 면 의미 검색 대상(자연어·사전이 못 알아들은 덩어리가 있는 질의)일 때만 LLM 을 부른다 — 사전이 다 알아들은 짧은 키워드 질의는 큐레이션된 어휘 순서를 지킨다 */
        val rewriteOnlyNaturalLanguage: Boolean = false,
        /** 부위·장비 키워드뿐인 짧은 질의([SemanticConstraint.broadKeyword], 이 단어 수 이하)는 LLM 을 부르지 않는다. 0 이면 끈다 */
        val rewriteSkipBroadMaxWords: Int = 3,
        /** 힌트의 어휘 1위가 이 점수 이상이면(이름과 거의 같다: 정확 이름 205 안팎, 단어 경계 이름 일치 170~185) 정확한 이름 힌트처럼 다룬다. null 이면 이름이 정확히 같을 때만. 170~180 에서 5개 평가셋이 같은 고원이다 */
        val rewriteStrongHintScore: Double? = 175.0
    )

    /** [hits] 는 어휘 검색과 같은 길이의 전체 순서. [semantic] 은 의미 검색이 실제로 섞였는가. [degraded] 는 의미 검색이나 LLM 해석이 필요했는데 질의 벡터·해석을 못 구해 그만큼 빠진 결과를 낸 경우(호출자는 이 결과를 오래 캐시하지 않는다). [complete] 는 어휘 검색이 시간 예산 안에 끝났는가. */
    class Result(val hits: List<ExerciseSearchHit>, val complete: Boolean, val semantic: Boolean, val degraded: Boolean = false, val rewritten: Boolean = false)

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
        rewrite: (() -> QueryRewrite?)? = null,
        queryVector: () -> FloatArray?
    ): Result {
        val lex = engine.searchOutcome(query, category, equipments, locale)
        // 어휘 검색이 시간 예산을 넘겨 잘렸으면 거기에 섞지 않는다(불완전한 순위에 의미 결과를 얹어 봐야 의미가 없다)
        if (!lex.complete) return Result(lex.hits, false, false)
        val constraint = engine.semanticConstraint(query, category, equipments) ?: return Result(lex.hits, true, false)
        val wantSemantic = needsSemantic(query.orEmpty(), lex.hits, constraint)
        val wantRewrite = rewrite != null && needsRewrite(query.orEmpty(), lex.hits, constraint)
        if (!wantSemantic && !wantRewrite) return Result(lex.hits, true, false)

        // LLM 해석(네트워크, 느림)을 먼저 띄워 두고 질의 임베딩을 구한다 — 둘 중 느린 쪽이 전체 지연이 된다
        val pending: CompletableFuture<QueryRewrite?>? = if (wantRewrite) startRewrite(rewrite!!) else null
        // 힌트 후보에 거는 조건(의도 장비 범위만 어휘 검색용보다 느슨하다) — LLM 을 기다리는 동안 만들어 둔다
        val hintConstraint = if (wantRewrite) engine.semanticConstraint(query, category, equipments, forHints = true) ?: constraint else constraint
        val qv = if (wantSemantic) queryVector()?.let { unitVector(it) } else null
        val rw = pending?.let { await(it) }

        val hybrid = if (qv != null) fuse(lex.hits, constraint, qv) else null
        val base = hybrid ?: lex.hits
        val degraded = (wantSemantic && qv == null) || (wantRewrite && rw == null)
        val withRewrite = if (rw != null && !rw.isEmpty) applyRewrite(base, lex.hits, rw, hintConstraint, category, equipments) else null
        return Result(withRewrite ?: base, true, semantic = hybrid != null, degraded = degraded, rewritten = withRewrite != null)
    }

    private fun startRewrite(call: () -> QueryRewrite?): CompletableFuture<QueryRewrite?> {
        val run = { runCatching(call).getOrNull() }
        val ex = executor ?: return CompletableFuture.completedFuture(run())
        return try { CompletableFuture.supplyAsync(run, ex) } catch (e: Exception) { CompletableFuture.completedFuture(null) }
    }

    private fun await(f: CompletableFuture<QueryRewrite?>): QueryRewrite? =
        try { f.get(config.rewriteWaitMillis, TimeUnit.MILLISECONDS) } catch (e: Exception) { f.cancel(false); null }

    /** LLM 해석을 부를 가치가 있는 질의인가 — 정확한 이름·브라우즈는 [semanticConstraint] 가 null 로 이미 걸렀다. 짧은 정식 이름 질의도 설정으로 건너뛸 수 있다. */
    fun needsRewrite(query: String, lexical: List<ExerciseSearchHit>, constraint: SemanticConstraint): Boolean {
        if (config.rewriteOnlyNaturalLanguage && !needsSemantic(query, lexical, constraint)) return false
        if (config.rewriteSkipBroadMaxWords > 0 && constraint.broadKeyword && query.trim().split(WHITESPACE).count { it.isNotEmpty() } <= config.rewriteSkipBroadMaxWords) return false
        val skip = config.rewriteSkipScore ?: return true
        if (constraint.unrecognized) return true
        val top = lexical.firstOrNull() ?: return true
        val words = query.trim().split(WHITESPACE).count { it.isNotEmpty() }
        return !(top.score >= skip && words <= config.rewriteSkipMaxWords)
    }

    /**
     * 힌트마다 어휘 검색(영어)을 하고, 하이브리드와 같은 하드 조건을 통과한 것만 후보로 쓴다. 호출자의 카테고리·장비 필터도 그대로 건다.
     * 하이브리드 결과 위에 RRF 로 섞고 LLM 제외 장비를 거른다. 섞을 것이 없으면 null.
     */
    private fun applyRewrite(
        base: List<ExerciseSearchHit>, lexical: List<ExerciseSearchHit>, rw: QueryRewrite, constraint: SemanticConstraint,
        category: ExerciseCategory?, equipments: Set<Equipment>?
    ): List<ExerciseSearchHit>? {
        val hintHits = rw.hints.map { hint ->
            val out = engine.searchOutcome(hint, category, equipments, "en")
            if (!out.complete) RewriteFusion.HintHits(emptyList())
            else {
                val hits = (if (config.rewriteConstrainHints) out.hits.filter { constraint.allows(it.doc) } else out.hits).take(config.rewrite.perHint)
                RewriteFusion.HintHits(hits, hits.isNotEmpty() && hits.first() === out.hits.first() && (engine.isExactName(hint) || (config.rewriteStrongHintScore?.let { out.hits.first().score >= it } ?: false)))
            }
        }
        val pinned = if (config.rewriteKeepProtected) config.protectScore?.let { p -> lexical.firstOrNull()?.takeIf { it.score >= p }?.doc?.id } else null
        // LLM 의 제외 장비는 호출자가 고른 장비 필터와 부딪히면 따르지 않는다(필터는 하드 조건이다). 질의 속 장비 말은 사전이 틀릴 수 있어("수건"→밴드) 여기서는 LLM 을 믿는다.
        val forbid = rw.forbidEquipment - (equipments ?: emptySet())
        if (hintHits.all { it.hits.isEmpty() } && forbid.isEmpty()) return null
        return RewriteFusion.fuse(base, hintHits, forbid, pinned, config.rewrite)
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
        val lexW = config.lexicalWeight * (if (constraint.injuryAware) config.injuryLexicalBoost else 1.0) * (if (constraint.lexicalInformative) 1.0 else config.uninformativeLexicalWeight)
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

    /** 질의 벡터를 점검하고 단위 길이로 맞춘다 — 차원이 다르거나 NaN·무한대·영벡터면 null(의미 검색을 건너뛴다) */
    private fun unitVector(v: FloatArray): FloatArray? {
        if (v.size != vectors.dim) return null
        var n = 0.0
        for (x in v) { if (!x.isFinite()) return null; n += x.toDouble() * x }
        if (n <= 1e-12) return null
        val norm = Math.sqrt(n)
        return if (Math.abs(norm - 1.0) < 1e-4) v else FloatArray(v.size) { (v[it] / norm).toFloat() }
    }

    companion object {
        private const val SCORE_SCALE = 1000.0
        private val WHITESPACE = Regex("\\s+")
    }
}
