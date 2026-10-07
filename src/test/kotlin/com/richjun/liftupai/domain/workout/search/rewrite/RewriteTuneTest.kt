package com.richjun.liftupai.domain.workout.search.rewrite

import com.fasterxml.jackson.databind.ObjectMapper
import com.richjun.liftupai.domain.workout.entity.Equipment
import com.richjun.liftupai.domain.workout.entity.ExerciseCategory
import com.richjun.liftupai.domain.workout.search.ExerciseSearchEngine
import com.richjun.liftupai.domain.workout.search.ExerciseSearchEvalTest
import com.richjun.liftupai.domain.workout.search.semantic.ExerciseVectors
import com.richjun.liftupai.domain.workout.search.semantic.HybridSearch
import com.richjun.liftupai.domain.workout.search.semantic.QueryEmbedder
import org.junit.jupiter.api.Test
import java.io.File

/**
 * 융합 파라미터 튜닝 도구(평가 전용) — 환경변수 RW_TUNE_ROOT 가 없으면 아무것도 하지 않는다.
 * RW_TUNE_ROOT/<세트>/ 에 eval.json(평가셋)·qvec.json(질의 벡터)·rewrites.json(질의 해석)이 있으면, 한 JVM 에서 여러 설정을 한꺼번에 채점해 표로 낸다.
 * 채점 방식은 저장소 밖 채점기(search-eval.mjs)와 같다(상위 5 안에 허용 운동, 금지 장비 위반). 운영 설정은 바꾸지 않는다.
 * 실행: RW_TUNE_ROOT=<dir> [RW_TUNE_SAFETY=<search-safety.json>] ./gradlew cleanTest test --tests '*RewriteTuneTest'
 */
class RewriteTuneTest {
    private val mapper = ObjectMapper()

    private class Variant(val name: String, val config: HybridSearch.Config?)

    @Test
    fun sweep() {
        val root = System.getenv("RW_TUNE_ROOT")?.takeIf { it.isNotBlank() } ?: return
        val docs = ExerciseSearchEvalTest.loadCatalogDocs()
        System.getenv("RW_DEBUG_Q")?.takeIf { it.isNotBlank() }?.let { dq ->
            val e = ExerciseSearchEngine(docs)
            println("RWDEBUG explain: " + e.explain(dq))
            println("RWDEBUG lexical: " + e.search(dq).take(10).joinToString { "${it.doc.slug}(${"%.0f".format(it.score)})" })
            val con = e.semanticConstraint(dq)
            System.getenv("RW_DEBUG_SET")?.let { set ->
                val qv = mapper.readTree(File(set, "qvec.json"))[dq]?.let { n -> FloatArray(n.size()) { i -> n[i].floatValue() } }
                val rwn = mapper.readTree(File(set, "rewrites.json"))[dq]
                val rwq = QueryRewrite.parse(rwn.toString(), mapper)
                println("RWDEBUG rewrite=$rwq")
                val hsd = HybridSearch(e, ExerciseVectors.loadDefault()!!, HybridSearch.Config(rewriteKeepProtected = true))
                println("RWDEBUG hybrid: " + hsd.search(dq, null, null, "ja", null) { qv?.let(QueryEmbedder::normalize) }.hits.take(12).joinToString { it.doc.slug })
                println("RWDEBUG full: " + hsd.search(dq, null, null, "ja", { rwq }) { qv?.let(QueryEmbedder::normalize) }.hits.take(12).joinToString { it.doc.slug })
            }
            System.getenv("RW_DEBUG_HINTS")?.split("|")?.forEach { h ->
                println("RWDEBUG hint '$h': " + e.search(h, null, null, "en").take(5).joinToString { "${it.doc.slug}${if (con?.allows(it.doc) == false) "[X]" else ""}(${"%.0f".format(it.score)})" })
            }
        }
        val vectors = ExerciseVectors.loadDefault() ?: error("embeddings missing")
        val base = HybridSearch.Config()
        fun rw(f: RewriteFusion.Config.() -> RewriteFusion.Config) = base.copy(rewrite = RewriteFusion.Config().f())
        val variants = listOf(
            Variant("hybrid", null),
            Variant("default-uninf1", base.copy(uninformativeLexicalWeight = 1.0)),
            Variant("default", base),
            Variant("pin", base.copy(rewriteKeepProtected = true)),
            Variant("ex1.5", rw { copy(exactBoost = 1.5) }.copy(rewriteKeepProtected = true)),
            Variant("ex2", rw { copy(exactBoost = 2.0) }.copy(rewriteKeepProtected = true)),
            Variant("ex3", rw { copy(exactBoost = 3.0) }.copy(rewriteKeepProtected = true)),
            Variant("ex2+skip", base.copy(rewrite = RewriteFusion.Config(exactBoost = 2.0), rewriteKeepProtected = true, rewriteSkipScore = 160.0)),
            Variant("ex2+decay.7", base.copy(rewrite = RewriteFusion.Config(exactBoost = 2.0, decay = 0.7), rewriteKeepProtected = true)),
            Variant("ex2+per2", base.copy(rewrite = RewriteFusion.Config(exactBoost = 2.0, perHint = 2), rewriteKeepProtected = true)),
            Variant("ex2+per4", base.copy(rewrite = RewriteFusion.Config(exactBoost = 2.0, perHint = 4), rewriteKeepProtected = true)),
            Variant("ex2+base.8", base.copy(rewrite = RewriteFusion.Config(exactBoost = 2.0, baseWeight = 0.8), rewriteKeepProtected = true)),
            Variant("ex2+depth7", base.copy(rewrite = RewriteFusion.Config(exactBoost = 2.0, baseDepth = 7), rewriteKeepProtected = true)),
            Variant("ex2+k30", base.copy(rewrite = RewriteFusion.Config(exactBoost = 2.0, rrfK = 30), rewriteKeepProtected = true)),
            Variant("ex2+k120", base.copy(rewrite = RewriteFusion.Config(exactBoost = 2.0, rrfK = 120), rewriteKeepProtected = true)),
            Variant("noconstr", base.copy(rewriteConstrainHints = false)),
            Variant("noconstr-pin", base.copy(rewriteConstrainHints = false, rewriteKeepProtected = true)),
            Variant("skip160w2", base.copy(rewriteSkipScore = 160.0, rewriteSkipMaxWords = 2)),
            Variant("skip160w1", base.copy(rewriteSkipScore = 160.0, rewriteSkipMaxWords = 1)),
            Variant("base0.7", rw { copy(baseWeight = 0.7) }),
            Variant("base1.3", rw { copy(baseWeight = 1.3) }),
            Variant("depth5", rw { copy(baseDepth = 5) }),
            Variant("depth20", rw { copy(baseDepth = 20) }),
            Variant("per2", rw { copy(perHint = 2) }),
            Variant("per5", rw { copy(perHint = 5) }),
            Variant("decay.7", rw { copy(decay = 0.7) }),
            Variant("decay1", rw { copy(decay = 1.0) }),
        ).filter { v -> System.getenv("RW_TUNE_ONLY")?.split(",")?.let { v.name in it } ?: true }
        val safety = System.getenv("RW_TUNE_SAFETY")?.takeIf { it.isNotBlank() }?.let { mapper.readTree(File(it))["rules"] }
        val sets = File(root).listFiles { f -> File(f, "eval.json").exists() }!!.sortedBy { it.name }
        val header = StringBuilder("%-14s".format("variant"))
        sets.forEach { header.append("%-30s".format(it.name)) }
        println("RWTUNE $header")
        val engine = ExerciseSearchEngine(docs)
        for (v in variants) {
            val line = StringBuilder("%-14s".format(v.name))
            for (dir in sets) {
                val cases = mapper.readTree(File(dir, "eval.json"))["cases"]
                val qvec = mapper.readTree(File(dir, "qvec.json")).fields().asSequence().associate { (k, n) -> k to FloatArray(n.size()) { i -> n[i].floatValue() } }
                val rws = mapper.readTree(File(dir, "rewrites.json")).fields().asSequence().mapNotNull { (k, n) -> QueryRewrite.parse(n.toString(), mapper)?.let { k to it } }.toMap()
                val hs = HybridSearch(engine, vectors, v.config ?: base)
                var calls = 0; var n = 0; var h5 = 0; var rr = 0.0; var fv = 0; var sv = 0
                val locs = HashMap<String, IntArray>()
                val misses = ArrayList<String>()
                fun run(q: String, locale: String?, category: ExerciseCategory?, equipments: Set<Equipment>?) =
                    hs.search(q, category, equipments, locale, if (v.config == null) null else ({ calls++; rws[q] })) { qvec[q]?.let(QueryEmbedder::normalize) }.hits.take(10)
                for (c in cases) {
                    val acc = c["acceptable"]?.map { it.asText() }?.toSet() ?: continue
                    if (acc.isEmpty()) continue
                    val q = c["query"].asText()
                    val category = c["category"]?.takeIf { !it.isNull && it.asText().isNotBlank() }?.asText()?.let { ExerciseCategory.valueOf(it) }
                    val equipments = c["equipment"]?.takeIf { !it.isNull && it.asText().isNotBlank() }?.asText()?.split(",")?.map { Equipment.valueOf(it.trim()) }?.toSet()
                    val top = run(q, c["locale"]?.asText(), category, equipments)
                    val rank = top.indexOfFirst { it.doc.slug in acc }
                    val k = c["k"]?.asInt(5) ?: 5
                    n++; if (rank in 0 until k) h5++; if (rank >= 0) rr += 1.0 / (rank + 1)
                    val loc = locs.getOrPut(c["locale"].asText()) { IntArray(2) }; loc[0]++; if (rank in 0 until k) loc[1]++
                    if (rank !in 0 until k) {
                        var diag = ""
                        if (System.getenv("RW_TUNE_DIAG") == v.name) {
                            val con = engine.semanticConstraint(q, category, equipments, forHints = true)
                            val baseHits = hs.search(q, category, equipments, c["locale"]?.asText(), null) { qvec[q]?.let(QueryEmbedder::normalize) }.hits
                            val br = baseHits.indexOfFirst { it.doc.slug in acc }
                            diag = " || baseRank=$br hints=" + (rws[q]?.hints?.joinToString("; ") { h ->
                                val hits = engine.search(h, category, equipments, "en").filter { con == null || con.allows(it.doc) }.take(10)
                                val gr = hits.indexOfFirst { it.doc.slug in acc }
                                "$h@" + gr
                            } ?: "none") + " forbid=" + rws[q]?.forbidEquipment
                        }
                        misses.add("${c["id"].asText()}:$q -> ${top.take(5).joinToString { it.doc.slug }}$diag")
                    }
                    val forb = c["forbid_equipment_top5"]?.map { it.asText() }?.toSet()
                    if (!forb.isNullOrEmpty() && top.take(5).any { it.doc.equipment?.name in forb }) { fv++; misses.add("FORBID ${c["id"].asText()}:$q -> ${top.take(5).joinToString { it.doc.slug }}") }
                }
                safety?.forEach { r ->
                    val top = run(r["query"].asText(), r["locale"]?.asText(), null, null).take(5)
                    val re = r["forbid_slug_regex"]?.asText()?.let { Regex(it, RegexOption.IGNORE_CASE) }
                    val fe = r["forbid_equipment"]?.map { it.asText() }?.toSet().orEmpty()
                    val empty = r["require_nonempty"]?.asBoolean() == true && top.isEmpty()
                    if (empty || top.any { (re != null && re.containsMatchIn(it.doc.slug)) || it.doc.equipment?.name in fe }) sv++
                }
                line.append("%-30s".format("%.3f/%.3f f%d s%d c%.2f".format(h5.toDouble() / n, rr / n, fv, sv, calls.toDouble() / (n + (safety?.size() ?: 0))) + locs.entries.sortedBy { it.key }.joinToString("") { " " + "%.2f".format(it.value[1].toDouble() / it.value[0]) }))
                if (System.getenv("RW_TUNE_MISSES") == v.name) File(dir, "misses-${v.name}.txt").writeText(misses.joinToString("\n"))
            }
            println("RWTUNE $line")
        }
    }
}
