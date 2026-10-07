package com.richjun.liftupai.domain.workout.search.ops

import org.springframework.data.jpa.repository.JpaRepository
import org.springframework.stereotype.Repository

@Repository
interface SearchSynonymRepository : JpaRepository<SearchSynonym, Long> {
    fun findByEnabledTrueOrderByIdAsc(): List<SearchSynonym>
}
