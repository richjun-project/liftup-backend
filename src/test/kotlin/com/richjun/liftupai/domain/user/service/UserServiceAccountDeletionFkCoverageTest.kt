package com.richjun.liftupai.domain.user.service

import com.richjun.liftupai.config.TestConfig
import com.richjun.liftupai.domain.auth.entity.User
import jakarta.persistence.EntityManagerFactory
import jakarta.persistence.ManyToOne
import jakarta.persistence.OneToOne
import jakarta.persistence.Table
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.context.annotation.Import
import org.springframework.test.context.ActiveProfiles
import java.io.File

/**
 * 회원 탈퇴(UserService.deleteAllUserData) FK 커버리지 감사
 *
 * User를 참조하는 @ManyToOne/@OneToOne 필드를 가진 모든 JPA 엔티티를
 * 메타모델에서 자동으로 찾아내고, 해당 테이블이 UserService.deleteAllUserData()의
 * 삭제 목록에 실제로 포함되어 있는지 소스 코드 수준에서 검증한다.
 *
 * 새로운 User-FK 테이블(엔티티)이 추가되었는데 deleteAllUserData()에
 * 삭제 로직을 추가하지 않으면 이 테스트가 실패해야 한다 — 그래야
 * 회원 탈퇴 시 고아 레코드가 남거나 FK 제약으로 탈퇴 자체가 실패하는
 * 사고를 빌드 단계에서 잡을 수 있다.
 */
@SpringBootTest
@ActiveProfiles("test")
@Import(TestConfig::class)
class UserServiceAccountDeletionFkCoverageTest {

    @Autowired
    private lateinit var entityManagerFactory: EntityManagerFactory

    private val userServiceSourceFile =
        File("src/main/kotlin/com/richjun/liftupai/domain/user/service/UserService.kt")

    @Test
    fun `User를 FK로 참조하는 모든 엔티티의 테이블은 deleteAllUserData에서 삭제된다`() {
        val metamodel = entityManagerFactory.metamodel
        val userClass = User::class.java

        val userReferencingEntities = metamodel.entities
            .filter { it.javaType != userClass }
            .filter { entityType ->
                entityType.javaType.declaredFields.any { field ->
                    val referencesUser = userClass.isAssignableFrom(field.type)
                    val isFkField = field.isAnnotationPresent(ManyToOne::class.java) ||
                        field.isAnnotationPresent(OneToOne::class.java)
                    referencesUser && isFkField
                }
            }

        assertTrue(userReferencingEntities.isNotEmpty(), "메타모델에서 User FK 엔티티를 하나도 찾지 못함 — 테스트 설정을 확인하세요")

        val tableNamesByEntity = userReferencingEntities.associateWith { entityType ->
            entityType.javaType.getAnnotation(Table::class.java)?.name ?: entityType.name
        }

        assertTrue(
            userServiceSourceFile.exists(),
            "UserService.kt 소스 파일을 찾을 수 없습니다: ${userServiceSourceFile.path} (테스트를 프로젝트 루트에서 실행하세요)"
        )

        val deleteMethodSource = extractDeleteAllUserDataBody(userServiceSourceFile.readText())

        val uncovered = tableNamesByEntity.filterValues { table -> !deleteMethodSource.contains(table) }

        assertTrue(
            uncovered.isEmpty(),
            "다음 엔티티는 User에 대한 FK(@ManyToOne/@OneToOne)를 가지고 있지만 " +
                "UserService.deleteAllUserData()의 삭제 목록에 테이블명이 없습니다: " +
                uncovered.entries.joinToString { "${it.key.javaType.simpleName} -> table=${it.value}" } +
                " — 회원 탈퇴 시 고아 레코드가 남거나 FK 제약으로 탈퇴가 실패할 수 있습니다. " +
                "deleteAllUserData()에 해당 테이블 삭제 로직을 추가하세요."
        )
    }

    private fun extractDeleteAllUserDataBody(source: String): String {
        val startMarker = "fun deleteAllUserData(userId: Long)"
        val startIdx = source.indexOf(startMarker)
        require(startIdx >= 0) { "UserService.kt에서 deleteAllUserData 메서드를 찾을 수 없습니다" }

        val bodyStart = source.indexOf('{', startIdx)
        require(bodyStart >= 0) { "deleteAllUserData 메서드 본문을 찾을 수 없습니다" }

        var depth = 0
        var idx = bodyStart
        while (idx < source.length) {
            when (source[idx]) {
                '{' -> depth++
                '}' -> {
                    depth--
                    if (depth == 0) {
                        return source.substring(bodyStart, idx + 1)
                    }
                }
            }
            idx++
        }
        throw IllegalStateException("deleteAllUserData 메서드의 닫는 중괄호를 찾지 못했습니다")
    }
}
