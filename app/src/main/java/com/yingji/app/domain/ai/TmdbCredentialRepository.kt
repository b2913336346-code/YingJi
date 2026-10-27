package com.yingji.app.domain.ai

import java.util.concurrent.atomic.AtomicBoolean
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/** Local credential only. Never place in UI/SavedState, logs, exceptions or candidate records. */
class TmdbSecret private constructor(private val chars: CharArray) : AutoCloseable {
    internal fun <T> useValue(block: (String) -> T): T = block(String(chars))
    override fun toString() = "TmdbSecret([REDACTED])"
    override fun close() { chars.fill('\u0000') }
    companion object {
        fun fromInput(input: String): TmdbSecret {
            val value = input.trim()
            if (value.isEmpty()) throw TmdbCredentialException(TmdbCredentialProblem.EMPTY_INPUT)
            if (value.length > 4096 || !Regex("[A-Za-z0-9._~+/-]+=*").matches(value))
                throw TmdbCredentialException(TmdbCredentialProblem.INVALID_INPUT)
            return TmdbSecret(value.toCharArray())
        }
    }
}
enum class TmdbCredentialProblem { EMPTY_INPUT, INVALID_INPUT, BUSY, STORAGE_FAILED }
class TmdbCredentialException(val problem: TmdbCredentialProblem) : IllegalStateException(problem.name)
class TmdbCredentialRecoveryRequired : IllegalStateException("TMDB_RECONFIGURATION_REQUIRED")
enum class TmdbCredentialStatus { LOADING, NOT_CONFIGURED, SAVED_UNVERIFIED, RECOVERY_REQUIRED, READ_FAILED }
data class TmdbCredentialState(
    val status: TmdbCredentialStatus = TmdbCredentialStatus.LOADING,
    val saving: Boolean = false,
    val savedAt: Long? = null,
    val revision: String? = null,
    val error: TmdbCredentialProblem? = null,
)
class TmdbCredentialRecord(val key: TmdbSecret?, val savedAt: Long?, val revision: String) : AutoCloseable {
    override fun close() { key?.close() }
    override fun toString() = "TmdbCredentialRecord([REDACTED])"
}
interface TmdbSecureCredentialStore {
    suspend fun read(): TmdbCredentialRecord?
    /** Authenticated atomic replacement. Null is a persistent deletion tombstone. */
    suspend fun replace(key: TmdbSecret?, savedAt: Long?): String
}

/** No HTTP client or model dependency. Local saving never verifies or activates a data source. */
class TmdbCredentialRepository(
    private val store: TmdbSecureCredentialStore,
    private val clock: () -> Long = System::currentTimeMillis,
) {
    private val operations = Mutex()
    private val writing = AtomicBoolean()
    private val epoch = java.util.concurrent.atomic.AtomicLong()
    private val pending = java.util.concurrent.ConcurrentHashMap<String, () -> Unit>()
    private val mutableState = MutableStateFlow(TmdbCredentialState())
    val state = mutableState.asStateFlow()
    private fun metadata(record: TmdbCredentialRecord?) = TmdbCredentialState(
        status = if (record?.key == null) TmdbCredentialStatus.NOT_CONFIGURED else TmdbCredentialStatus.SAVED_UNVERIFIED,
        savedAt = record?.savedAt, revision = record?.revision, saving = writing.get())
    suspend fun refresh() = operations.withLock {
        try { store.read().use { mutableState.value = metadata(it) } }
        catch (e: CancellationException) { throw e }
        catch (_: TmdbCredentialRecoveryRequired) { mutableState.value = TmdbCredentialState(TmdbCredentialStatus.RECOVERY_REQUIRED, writing.get()) }
        catch (_: Exception) { mutableState.value = TmdbCredentialState(TmdbCredentialStatus.READ_FAILED, writing.get()) }
    }
    suspend fun save(input: String) {
        val secret = try { TmdbSecret.fromInput(input) } catch (e: TmdbCredentialException) {
            mutableState.value = mutableState.value.copy(error = e.problem); throw e
        }
        try { mutate(secret) } finally { secret.close() }
    }
    /** Only explicit clients use this lease; local save/refresh never starts a request. */
    internal suspend fun <T> withCurrentToken(onRevoke: () -> Unit = {}, block: suspend (TmdbSecret, () -> Boolean) -> T): T {
        val generation = epoch.get(); val id = java.util.UUID.randomUUID().toString()
        val record = operations.withLock {
            if (writing.get()) throw TmdbProblem(TmdbProblemCode.BUSY)
            val value = try { store.read() } catch (e: CancellationException) { throw e }
                catch (_: Exception) { throw TmdbProblem(TmdbProblemCode.KEY_UNAVAILABLE) }
            if (value?.key == null) { value?.close(); throw TmdbProblem(TmdbProblemCode.KEY_REQUIRED) }
            pending[id] = onRevoke; value
        }
        fun current() = generation == epoch.get() && !writing.get()
        try {
            return record.use {
                if (!current()) throw TmdbProblem(TmdbProblemCode.STALE_KEY)
                val result = block(requireNotNull(record.key), ::current)
                if (!current()) throw TmdbProblem(TmdbProblemCode.STALE_KEY)
                result
            }
        } finally { pending.remove(id) }
    }
    suspend fun delete() = mutate(null)
    private suspend fun mutate(secret: TmdbSecret?) {
        if (!writing.compareAndSet(false, true)) throw TmdbCredentialException(TmdbCredentialProblem.BUSY)
        epoch.incrementAndGet()
        pending.values.forEach { cancel -> runCatching { cancel() } }
        mutableState.value = mutableState.value.copy(saving = true, error = null)
        try {
            operations.withLock {
                val now = if (secret == null) null else clock().also { check(it >= 0) }
                val revision = store.replace(secret, now)
                mutableState.value = TmdbCredentialState(
                    if (secret == null) TmdbCredentialStatus.NOT_CONFIGURED else TmdbCredentialStatus.SAVED_UNVERIFIED,
                    savedAt = now, revision = revision)
            }
        } catch (e: CancellationException) { throw e }
        catch (_: Exception) {
            mutableState.value = mutableState.value.copy(error = TmdbCredentialProblem.STORAGE_FAILED)
            throw TmdbCredentialException(TmdbCredentialProblem.STORAGE_FAILED)
        } finally { writing.set(false); mutableState.value = mutableState.value.copy(saving = false) }
    }
}
