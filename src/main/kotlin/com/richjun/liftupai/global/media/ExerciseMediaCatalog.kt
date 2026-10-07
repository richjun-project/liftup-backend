package com.richjun.liftupai.global.media

import com.fasterxml.jackson.databind.ObjectMapper
import org.slf4j.LoggerFactory
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.beans.factory.annotation.Value
import org.springframework.core.io.ClassPathResource
import org.springframework.stereotype.Component

/**
 * 운동별 미디어(썸네일·영상) 보유 현황. 원본은 catalog/exercise-media.json = {"<slug>": {"thumb": bool, "video": bool}}.
 * - 파일이 없거나 읽을 수 없으면(카탈로그 리프가 아직 만들지 않은 환경) 모든 운동에 미디어가 있다고 본다(이전 동작).
 * - 파일이 있는데 slug 가 없으면 그 운동은 미디어가 없는 것이다 → URL 을 null 로 내려 앱이 깨진 이미지를 그리지 않게 한다.
 */
@Component
class ExerciseMediaCatalog private constructor(private val entries: Map<String, Media>?) {

    data class Media(val thumb: Boolean, val video: Boolean)

    @Autowired
    constructor(
        @Value("\${app.exercise-media.manifest:catalog/exercise-media.json}") resource: String
    ) : this(load(resource))

    /** 매니페스트가 실제로 로드되었는가 (false 면 모든 운동을 미디어 있음으로 취급) */
    val loaded: Boolean get() = entries != null

    fun hasThumb(slug: String): Boolean = entries?.get(slug)?.thumb ?: (entries == null)

    fun hasVideo(slug: String): Boolean = entries?.get(slug)?.video ?: (entries == null)

    companion object {
        private val logger = LoggerFactory.getLogger(ExerciseMediaCatalog::class.java)

        /** 매니페스트가 없는 환경과 같은 동작(모든 운동에 미디어 있음) */
        val ALL_AVAILABLE: ExerciseMediaCatalog = ExerciseMediaCatalog(null as Map<String, Media>?)

        /** 테스트·수동 구성용 */
        fun of(entries: Map<String, Media>?): ExerciseMediaCatalog = ExerciseMediaCatalog(entries)

        private fun load(resource: String): Map<String, Media>? {
            val res = ClassPathResource(resource)
            if (!res.exists()) {
                logger.info("Exercise media manifest {} not found — treating every exercise as having media", resource)
                return null
            }
            return try {
                res.inputStream.use { input ->
                    val root = ObjectMapper().readTree(input)
                    val map = HashMap<String, Media>(root.size() * 2)
                    root.fields().forEach { (slug, node) ->
                        map[slug] = Media(thumb = node.path("thumb").asBoolean(false), video = node.path("video").asBoolean(false))
                    }
                    map
                }
            } catch (e: Exception) {
                logger.warn("Exercise media manifest {} could not be read — treating every exercise as having media: {}", resource, e.message)
                null
            }
        }
    }
}
