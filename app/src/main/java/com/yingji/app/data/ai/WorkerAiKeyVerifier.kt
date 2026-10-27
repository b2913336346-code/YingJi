package com.yingji.app.data.ai

import com.yingji.app.domain.ai.*
import kotlinx.coroutines.CancellationException

/** Mock-only integration. A genuine Key is rejected before any HTTP request. */
class WorkerMockAiKeyVerifier(private val client: WorkerHttpClient) : AiKeyVerifier {
    override val kind = AiKeyVerificationKind.MOCK
    override suspend fun verify(key: AiSecret): AiKeyVerificationKind {
        key.useValue { if (!Regex("TEST_ONLY_BYOK_[A-Za-z0-9_-]{1,128}").matches(it)) throw AiKeyException(AiKeyProblem.MOCK_ONLY) }
        try { client.verify(key); return kind }
        catch (e: CancellationException) { throw e }
        catch (e: WorkerProblem) {
            val problem = when (e.code) {
                "UPSTREAM_AUTH", "KEY_INVALID" -> AiKeyProblem.INVALID_KEY
                "UNAUTHORIZED", "REQUEST_EXPIRED", "NONCE_REPLAY", "CLIENT_IDENTITY_UNAVAILABLE", "UPSTREAM_FORBIDDEN" -> AiKeyProblem.ACCESS_DENIED
                "UPSTREAM_RATE_LIMIT", "RATE_LIMITED", "ACCESS_RATE_LIMITED" -> AiKeyProblem.RATE_LIMITED
                "UPSTREAM_BILLING" -> AiKeyProblem.ACCOUNT_UNAVAILABLE
                "OFFLINE", "TRANSPORT_TIMEOUT", "UPSTREAM_TIMEOUT" -> AiKeyProblem.NETWORK_FAILED
                else -> AiKeyProblem.UNAVAILABLE
            }
            throw AiKeyException(problem)
        }
    }
}

/** Production verification path. The build must explicitly opt in and supply a controlled HTTPS Worker. */
class WorkerLiveAiKeyVerifier(private val configuration: AiLiveWorkerConfiguration,
    private val service: AiWorkerKeyVerification) : AiKeyVerifier {
    override val kind get() = AiKeyVerificationKind.LIVE.takeIf { configuration.verificationEnabled }
    override val workerOrigin get() = configuration.origin
    override suspend fun verify(key: AiSecret): AiKeyVerificationKind {
        if (!configuration.verificationEnabled) throw AiKeyException(AiKeyProblem.UNAVAILABLE)
        key.useValue { if (!Regex("[!-~]{16,512}").matches(it) || it.startsWith("TEST_ONLY_")) throw AiKeyException(AiKeyProblem.INVALID_KEY) }
        try { service.verify(key); return AiKeyVerificationKind.LIVE }
        catch (e: CancellationException) { throw e }
        catch (e: WorkerProblem) {
            throw AiKeyException(when (e.code) {
                "UPSTREAM_AUTH", "KEY_INVALID" -> AiKeyProblem.INVALID_KEY
                "UNAUTHORIZED", "REQUEST_EXPIRED", "NONCE_REPLAY", "CLIENT_IDENTITY_UNAVAILABLE", "UPSTREAM_FORBIDDEN" -> AiKeyProblem.ACCESS_DENIED
                "UPSTREAM_RATE_LIMIT", "RATE_LIMITED", "ACCESS_RATE_LIMITED" -> AiKeyProblem.RATE_LIMITED
                "UPSTREAM_BILLING" -> AiKeyProblem.ACCOUNT_UNAVAILABLE
                "OFFLINE", "TRANSPORT_TIMEOUT", "UPSTREAM_TIMEOUT" -> AiKeyProblem.NETWORK_FAILED
                else -> AiKeyProblem.UNAVAILABLE
            })
        }
    }
}
