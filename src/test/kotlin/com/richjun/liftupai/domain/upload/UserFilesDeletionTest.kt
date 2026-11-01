package com.richjun.liftupai.domain.upload

import com.richjun.liftupai.domain.upload.service.FileUploadService
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Files
import java.nio.file.Path

/** 회원 탈퇴 때 그 사용자가 올린 사진만 지운다(로컬 저장소 경로, uploads/<종류>/<userId>). */
class UserFilesDeletionTest {

    @TempDir
    lateinit var dir: Path

    private fun put(rel: String) {
        val p = dir.resolve(rel)
        Files.createDirectories(p.parent)
        Files.writeString(p, "x")
    }

    @Test
    fun `탈퇴한 사용자의 업로드 종류 폴더만 지운다`() {
        put("chat/5/a.jpg")
        put("meal/5/b.jpg")
        put("meal/5/b_thumb.jpg")
        put("form_check/5/old.png") // 예전 폴더 이름
        put("chat/55/keep.jpg") // 5 로 시작하는 다른 사용자
        put("chat/50/keep.jpg")
        put("exercises/5/thumb.jpg") // 공용 파일 폴더는 사용자 폴더처럼 생겨도 건드리지 않는다

        val service = FileUploadService(dir.toString(), "http://localhost", false, null)
        val deleted = service.deleteUserFiles(5)

        assertEquals(4, deleted)
        assertFalse(Files.exists(dir.resolve("chat/5")))
        assertFalse(Files.exists(dir.resolve("meal/5")))
        assertFalse(Files.exists(dir.resolve("form_check/5")))
        assertTrue(Files.exists(dir.resolve("exercises/5/thumb.jpg")))
        assertTrue(Files.exists(dir.resolve("chat/55/keep.jpg")))
        assertTrue(Files.exists(dir.resolve("chat/50/keep.jpg")))
    }

    @Test
    fun `올린 파일이 없으면 0 이고 오류가 없다`() {
        val service = FileUploadService(dir.toString(), "http://localhost", false, null)
        assertEquals(0, service.deleteUserFiles(7))
    }
}
