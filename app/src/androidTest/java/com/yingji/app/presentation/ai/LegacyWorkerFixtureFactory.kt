package com.yingji.app.presentation.ai

import android.content.Context
import com.yingji.app.data.ai.*
import com.yingji.app.domain.ai.*

/** Explicit historical fixture. The installed app no longer constructs this Worker path. */
internal object LegacyWorkerFixtureFactory {
    private fun client(context: Context, keys: AiKeyConfigurationRepository? = null) = WorkerHttpClient(
        "http://127.0.0.1:8787", WorkerRequestSigner(AndroidKeystoreWorkerIdentity(context)), keys, localDemo = true)
    fun verifier(context: Context): AiKeyVerifier = WorkerMockAiKeyVerifier(client(context))
    fun create(demo: MockAiHomeRepository, catalog: CandidateCatalogRepository, context: Context, keys: AiKeyConfigurationRepository): AiHomeRepository =
        WorkerDemoHomeRepository(demo,client(context,keys),catalog,keys)
}
