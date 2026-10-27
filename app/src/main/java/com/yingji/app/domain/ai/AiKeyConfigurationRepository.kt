package com.yingji.app.domain.ai

import java.util.UUID
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicLong
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/** Never put this value in UI state, saved state, logs, exceptions or business DTOs. */
class AiSecret private constructor(private val chars: CharArray) : AutoCloseable {
    internal fun <T> useValue(block: (String) -> T): T = block(String(chars))
    override fun toString() = "AiSecret([REDACTED])"
    override fun close() { chars.fill('\u0000') }
    companion object {
        fun fromInput(input: String): AiSecret {
            val value = input.trim()
            if (value.isEmpty()) throw AiKeyException(AiKeyProblem.EMPTY_INPUT)
            if (value.length > 512 || value.any { it.code < 32 || it.code == 127 }) throw AiKeyException(AiKeyProblem.INVALID_INPUT)
            return AiSecret(value.toCharArray())
        }
    }
}
enum class AiKeyProblem { EMPTY_INPUT, INVALID_INPUT, INVALID_KEY, MOCK_ONLY, UNAVAILABLE, ACCESS_DENIED, RATE_LIMITED, ACCOUNT_UNAVAILABLE, NETWORK_FAILED, STORAGE_FAILED, STALE_OPERATION, BUSY }
class AiKeyException(val problem: AiKeyProblem) : IllegalStateException(problem.name)
enum class AiKeyVerificationKind { MOCK, LIVE }
enum class AiKeyStorageStatus { LOADING, NOT_CONFIGURED, CONFIGURED, RECOVERY_REQUIRED, READ_FAILED }
data class AiKeyConfigurationState(
    val storage: AiKeyStorageStatus = AiKeyStorageStatus.LOADING,
    val verifying: Boolean = false,
    val lastVerifiedAt: Long? = null,
    val kind: AiKeyVerificationKind? = null,
    val revision: String? = null,
    val error: AiKeyProblem? = null,
) {
    val canRequestLiveAi: Boolean get() = storage == AiKeyStorageStatus.CONFIGURED && kind == AiKeyVerificationKind.LIVE && !verifying
}
class AiKeyRecord(val key: AiSecret?, val verifiedAt: Long?, val kind: AiKeyVerificationKind?, val revision: String = UUID.randomUUID().toString()) : AutoCloseable {
    override fun close() { key?.close() }
    override fun toString() = "AiKeyRecord([REDACTED])"
}
class AiKeyRecoveryRequired : IllegalStateException("KEY_RECONFIGURATION_REQUIRED")
interface AiSecureKeyStore {
    suspend fun read(): AiKeyRecord?
    /** Must replace atomically; failure leaves the previous record intact. Null is a durable deletion tombstone. */
    suspend fun replace(key: AiSecret?, verifiedAt: Long?, kind: AiKeyVerificationKind?): String
}
interface AiWorkerKeyVerification {
    suspend fun verify(key: AiSecret)
}
interface AiKeyVerifier {
    val directApi: Boolean get() = false
    fun cancelPending() {}
    suspend fun verifyCurrent(key: AiSecret, isCurrent: () -> Boolean): AiKeyVerificationKind = verify(key)
    val workerOrigin: String? get() = null
    val kind: AiKeyVerificationKind?
    suspend fun verify(key: AiSecret): AiKeyVerificationKind
}
/** No network. Only clearly synthetic inputs can pass; genuine credentials are never validated here. */
class LocalMockAiKeyVerifier : AiKeyVerifier {
    override val kind = AiKeyVerificationKind.MOCK
    override suspend fun verify(key: AiSecret): AiKeyVerificationKind = key.useValue { value ->
        if (!Regex("TEST_ONLY_BYOK_[A-Za-z0-9_-]{1,128}").matches(value)) throw AiKeyException(AiKeyProblem.MOCK_ONLY)
        if (value.contains("INVALID")) throw AiKeyException(AiKeyProblem.INVALID_KEY)
        AiKeyVerificationKind.MOCK
    }
}
class DisabledAiKeyVerifier : AiKeyVerifier {
    override val kind: AiKeyVerificationKind? = null
    override suspend fun verify(key: AiSecret): AiKeyVerificationKind = throw AiKeyException(AiKeyProblem.UNAVAILABLE)
}

class AiKeyConfigurationRepository(
    private val store: AiSecureKeyStore,
    private val verifier: AiKeyVerifier,
    private val clock: () -> Long = System::currentTimeMillis,
) {
    private val operations = Mutex()
    private val verifying = AtomicBoolean()
    private val epoch = AtomicLong()
    private val revocations = java.util.concurrent.ConcurrentHashMap<String, () -> Unit>()
    private fun revokePending() {
        runCatching { verifier.cancelPending() }
        revocations.values.forEach { cancel -> runCatching { cancel() } }
    }
    private val mutableState = MutableStateFlow(AiKeyConfigurationState())
    val state = mutableState.asStateFlow()
    val verificationDirectApi get() = verifier.directApi
    val verificationKind get() = verifier.kind
    val verificationWorkerOrigin get() = verifier.workerOrigin

    private fun metadata(record: AiKeyRecord?) = AiKeyConfigurationState(
        storage = if (record?.key == null) AiKeyStorageStatus.NOT_CONFIGURED else AiKeyStorageStatus.CONFIGURED,
        lastVerifiedAt = record?.verifiedAt, kind = record?.kind, revision = record?.revision, verifying = verifying.get())
    suspend fun refresh() = operations.withLock {
        try { store.read().use { mutableState.value = metadata(it) } }
        catch (e: CancellationException) { throw e }
        catch (_: AiKeyRecoveryRequired) { mutableState.value = AiKeyConfigurationState(AiKeyStorageStatus.RECOVERY_REQUIRED, verifying.get()) }
        catch (_: Exception) { mutableState.value = AiKeyConfigurationState(AiKeyStorageStatus.READ_FAILED, verifying.get()) }
    }
    suspend fun verifyAndSave(input: String) {
        val secret = try { AiSecret.fromInput(input) } catch (e: AiKeyException) { mutableState.value = mutableState.value.copy(error = e.problem); throw e }
        if (!verifying.compareAndSet(false, true)) { secret.close(); throw AiKeyException(AiKeyProblem.BUSY) }
        val generation = epoch.get()
        mutableState.value = mutableState.value.copy(verifying = true, error = null)
        try {
            val kind = verifier.verifyCurrent(secret) { generation == epoch.get() }
            if (kind != verifier.kind) throw AiKeyException(AiKeyProblem.UNAVAILABLE)
            operations.withLock {
                if (generation != epoch.get()) throw AiKeyException(AiKeyProblem.STALE_OPERATION)
                val now = clock(); check(now >= 0)
                val revision = store.replace(secret, now, kind)
                epoch.incrementAndGet()
                revokePending()
                mutableState.value = AiKeyConfigurationState(AiKeyStorageStatus.CONFIGURED, lastVerifiedAt = now, kind = kind, revision = revision)
            }
        } catch (e: CancellationException) { throw e
        } catch (e: AiKeyException) { if (generation == epoch.get()) mutableState.value = mutableState.value.copy(error = e.problem); throw e
        } catch (_: Exception) {
            if (generation == epoch.get()) mutableState.value = mutableState.value.copy(error = AiKeyProblem.STORAGE_FAILED)
            throw AiKeyException(AiKeyProblem.STORAGE_FAILED)
        } finally { secret.close(); verifying.set(false); mutableState.value = mutableState.value.copy(verifying = false) }
    }
    /** A secret exists only during this operation; generation checks fence late replies. */
    internal suspend fun <T> withConfiguredKey(mock: Boolean, onRevoke: () -> Unit = {}, block: suspend (AiSecret) -> T): T {
        val generation = epoch.get()
        val operationId = UUID.randomUUID().toString()
        val record = operations.withLock {
            if (verifying.get()) throw WorkerProblem("KEY_CONFIGURATION_BUSY")
            val record = try { store.read() } catch (e: CancellationException) { throw e } catch (_: Exception) { throw WorkerProblem("KEY_UNAVAILABLE") }
            if (record?.key == null || record.kind != (if (mock) AiKeyVerificationKind.MOCK else AiKeyVerificationKind.LIVE)) {
                record?.close(); throw WorkerProblem("KEY_REQUIRED")
            }
            revocations[operationId] = onRevoke
            record
        }
        try { return record.use {
            if (generation != epoch.get()) throw WorkerProblem("STALE_KEY")
            val result = block(record.key!!)
            operations.withLock {
                if (generation != epoch.get() || verifying.get()) throw WorkerProblem("STALE_KEY")
            }
            result
        } } finally { revocations.remove(operationId) }
    }
    internal suspend fun <T> withCurrentConfiguration(revision: String?, block: suspend () -> T): T = operations.withLock {
        val current = state.value
        if (revision == null || revision != current.revision || current.storage != AiKeyStorageStatus.CONFIGURED || current.verifying)
            throw WorkerProblem("STALE_KEY")
        block()
    }
    suspend fun delete() = operations.withLock {
        // Revoke outstanding validation before waiting for disk. A failure is explicit and never claims deletion.
        epoch.incrementAndGet()
        revokePending()
        try {
            val revision = store.replace(null, null, null)
            mutableState.value = AiKeyConfigurationState(AiKeyStorageStatus.NOT_CONFIGURED, verifying.get(), revision = revision)
        } catch (e: CancellationException) { throw e
        } catch (_: Exception) { mutableState.value = mutableState.value.copy(error = AiKeyProblem.STORAGE_FAILED); throw AiKeyException(AiKeyProblem.STORAGE_FAILED) }
    }
}
