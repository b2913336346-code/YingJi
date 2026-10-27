package com.yingji.app.data.ai

import com.yingji.app.domain.ai.*
import kotlinx.coroutines.CancellationException

/** Verification never creates a recommendation or transfers a profile. */
interface DeepSeekKeyValidation {
    suspend fun verify(key: AiSecret, isCurrent: () -> Boolean)
    fun cancelPending() {}
}

class DeepSeekAiKeyVerifier(
    private val service: DeepSeekKeyValidation,
    private val enabled: Boolean = true,
    private val mock: Boolean = false,
) : AiKeyVerifier {
    override val directApi = true
    override val kind get() = if (!enabled) null else if (mock) AiKeyVerificationKind.MOCK else AiKeyVerificationKind.LIVE
    override fun cancelPending() = service.cancelPending()
    override suspend fun verify(key: AiSecret) = verifyCurrent(key) { true }
    override suspend fun verifyCurrent(key: AiSecret, isCurrent: () -> Boolean): AiKeyVerificationKind {
        if (!enabled) throw AiKeyException(AiKeyProblem.UNAVAILABLE)
        key.useValue { value ->
            if (mock && !Regex("TEST_ONLY_BYOK_[A-Za-z0-9_-]{1,128}").matches(value)) throw AiKeyException(AiKeyProblem.MOCK_ONLY)
            if (!mock && (!Regex("[!-~]{16,512}").matches(value) || value.startsWith("TEST_ONLY_"))) throw AiKeyException(AiKeyProblem.INVALID_KEY)
        }
        try {
            if (!isCurrent()) throw AiKeyException(AiKeyProblem.STALE_OPERATION)
            service.verify(key, isCurrent)
            if (!isCurrent()) throw AiKeyException(AiKeyProblem.STALE_OPERATION)
            return kind!!
        } catch (e: CancellationException) { throw e
        } catch (e: WorkerProblem) {
            throw AiKeyException(when (e.code) {
                "UPSTREAM_AUTH", "KEY_INVALID" -> AiKeyProblem.INVALID_KEY
                "UPSTREAM_FORBIDDEN", "SECURE_CONNECTION_FAILED" -> AiKeyProblem.ACCESS_DENIED
                "UPSTREAM_RATE_LIMIT" -> AiKeyProblem.RATE_LIMITED
                "UPSTREAM_BILLING" -> AiKeyProblem.ACCOUNT_UNAVAILABLE
                "OFFLINE", "TRANSPORT_TIMEOUT", "UPSTREAM_UNAVAILABLE" -> AiKeyProblem.NETWORK_FAILED
                "STALE_KEY" -> AiKeyProblem.STALE_OPERATION
                else -> AiKeyProblem.UNAVAILABLE
            })
        } catch (e: AiKeyException) { throw e
        } catch (_: Exception) { throw AiKeyException(AiKeyProblem.UNAVAILABLE) }
    }
}
