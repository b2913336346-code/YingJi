package com.yingji.app.data.ai

import com.yingji.app.domain.ai.*

/** App-authored synthetic fixtures, not a licensed real-world film catalogue. No network or cache. */
class MockCandidateCatalogRepository(private val clock: () -> Long = System::currentTimeMillis) : CandidateCatalogRepository {
    override suspend fun load(): CandidateCatalog {
        val source = CandidateSource(SOURCE_ID, CandidateSourceKind.MOCK,
            "App-authored fixtures: MockCandidateCatalogRepository.kt; not real film metadata",
            CandidateLicense(CandidateLicenseState.ACTIVE, "Local synthetic demo content only", true, true, false))
        return CandidateCatalog(listOf(source), listOf("科幻", "喜剧", "纪录片").mapIndexed { index, topic ->
            CatalogCandidate(CandidateIdentity(SOURCE_ID, "mock-" + index), "Mock 演示作品 " + (index + 1), setOf(topic),
                description = "虚构演示内容，用于验证推荐界面；无真实年份、评分或海报。", verifiedAt = clock())
        })
    }
    companion object { const val SOURCE_ID = "yingji-mock-v1" }
}
