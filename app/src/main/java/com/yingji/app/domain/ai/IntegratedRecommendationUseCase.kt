package com.yingji.app.domain.ai

import kotlinx.coroutines.CancellationException
import java.util.UUID
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/** No DAO, billing arithmetic or model-supplied facts. One retry belongs to the same business task. */
class IntegratedRecommendationUseCase(
    private val service: AiWorkerService,
    private val profiles: AiIntegrationProfileSource,
    private val catalog: CandidateCatalogRepository,
    private val clock: () -> Long = System::currentTimeMillis,
    private val requestIds: () -> String = { UUID.randomUUID().toString() },
    private val mockService: Boolean = false,
    private val serviceOwnsRetry: Boolean = false,
    private val uncertainRequestReplay: Boolean = true,
    private val refreshQuotaBeforeTask: Boolean = false,
) {
    private data class Key(val input: AiHintInput, val token: ProfileToken, val consent: Long, val allowed: Boolean, val credentialRevision: String?)
    private data class CacheEntry(val catalog: CandidateCatalog, val hints: WorkerHints, val storedAt: Long)
    private val requests = Mutex()
    private val cache = linkedMapOf<Key, CacheEntry>()
    private val invalidation = java.util.concurrent.atomic.AtomicLong()
    private var observedInvalidation = 0L
    private val pending = linkedMapOf<Key, WorkerHintRequest>()
    private var lastQuota: AiQuotaSnapshot? = null
    private fun unknown() = AiQuotaSnapshot(AiQuotaStatus.UNAVAILABLE, mockService)
    suspend fun refreshQuota(): AiQuotaSnapshot = requests.withLock { fetchQuota() }
    private suspend fun fetchQuota(): AiQuotaSnapshot = try { service.quota().also { lastQuota = it } }
        catch (e: CancellationException) { throw e }
        catch (e: Exception) { lastQuota = unknown(); throw e }
    fun invalidate() { invalidation.incrementAndGet() } // Never changes authoritative quota or remote task IDs.
    private suspend fun fence(context: AiIntegrationContext) {
        if (!profiles.isCurrent(context)) throw WorkerProblem("STALE_PROFILE")
    }
    private suspend fun loaded(): CandidateCatalog? = try { catalog.load() } catch (e: CancellationException) { throw e } catch (_: Exception) { null }

    suspend operator fun invoke(input: AiHintInput): AiIntegratedResult = requests.withLock { execute(input) }
    private suspend fun execute(input: AiHintInput): AiIntegratedResult {
        val revision = invalidation.get()
        if (revision != observedInvalidation) { cache.clear(); observedInvalidation = revision }
        require(input.mode != AiMode.QUERY || input.query.isNotBlank())
        val context = profiles.snapshot()
        val key = Key(input, context.profile.token, context.consentVersion, context.externalProcessingAllowed, context.credentialRevision)
        val current = loaded()
        val now = clock()
        cache.entries.removeAll { now < it.value.storedAt || now - it.value.storedAt >= MAX_CACHE_AGE }
        cache[key]?.let { entry ->
            val permitted = permittedCache(entry, current, input.allowMock)
            if (permitted != null) {
                val items = rank(permitted, input, context, entry.hints)
                fence(context)
                return AiIntegratedResult(items, if (items.isEmpty()) AiRecommendationOrigin.EMPTY else AiRecommendationOrigin.CACHE,
                    lastQuota ?: unknown(), context.profile.token)
            }
            cache.remove(key)
        }
        if (current == null || rank(current, input, context, null).isEmpty()) return fallback(current, input, context, "NO_RELIABLE_CANDIDATES")
        if (!context.externalProcessingAllowed) return fallback(current, input, context, "CONSENT_REQUIRED")
        val existing = pending[key]
        val quota = lastQuota.takeUnless { refreshQuotaBeforeTask } ?: try { fetchQuota() } catch (e: CancellationException) { throw e } catch (_: Exception) { unknown().also { lastQuota = it } }
        // Replaying an uncertain request uses its original ID; the Worker still controls admission.
        if (existing == null && !quota.canRequestNewTask) return fallback(current, input, context, "QUOTA_UNAVAILABLE")
        val request = existing ?: WorkerRequestBuilder.build(input, context, requestIds()).also {
            pending[key] = it
            while (pending.size > 20) pending.remove(pending.keys.first())
        }
        fence(context)
        var attempt = 0
        while (true) {
            try {
                val response = service.hints(request)
                fence(context)
                return finish(response, request, key, input, context, current)
            } catch (e: CancellationException) {
                if (!uncertainRequestReplay) pending.remove(key)
                throw e
            } catch (e: WorkerProblem) {
                if (!uncertainRequestReplay) pending.remove(key)
                if (e.code in setOf("STALE_PROFILE", "STALE_KEY", "KEY_REQUIRED", "KEY_UNAVAILABLE", "KEY_CONFIGURATION_BUSY")) throw e
                if (!serviceOwnsRetry && e.code == "TRANSPORT_TIMEOUT" && attempt++ == 0) { fence(context); continue }
                if (e.code !in UNCERTAIN) pending.remove(key)
                return fallback(current, input, context, e.code)
            }
        }
    }
    private suspend fun finish(response: WorkerTaskResponse, request: WorkerHintRequest, key: Key, input: AiHintInput,
        context: AiIntegrationContext, current: CandidateCatalog?): AiIntegratedResult {
        if ((response.requestId != null && response.requestId != request.requestId) || (response.token != null && response.token != request.token) ||
            (response.status != WorkerTaskStatus.ERROR && (response.requestId == null || response.token == null))) throw WorkerProblem("INVALID_RESPONSE")
        lastQuota = response.quota
        if (response.status == WorkerTaskStatus.PENDING && !uncertainRequestReplay) pending.remove(key)
        if (response.status == WorkerTaskStatus.PENDING) return fallback(current, input, context, "TASK_PENDING", WorkerTaskStatus.PENDING)
        val problem = response.error?.code
        if (response.status == WorkerTaskStatus.ERROR) {
            if (!uncertainRequestReplay || problem !in UNCERTAIN) pending.remove(key)
            return fallback(current, input, context, problem ?: "SERVICE_UNAVAILABLE", WorkerTaskStatus.ERROR)
        }
        val hints = response.hints ?: throw WorkerProblem("INVALID_RESPONSE")
        pending.remove(key)
        val items = if (current == null) emptyList() else rank(current, input, context, hints)
        fence(context)
        if (current != null && items.isNotEmpty()) remember(key, current, items, hints)
        return AiIntegratedResult(items, if (items.isEmpty()) AiRecommendationOrigin.EMPTY else AiRecommendationOrigin.WORKER,
            response.quota, context.profile.token, WorkerTaskStatus.SUCCESS)
    }
    private suspend fun fallback(data: CandidateCatalog?, input: AiHintInput, context: AiIntegrationContext, code: String,
        status: WorkerTaskStatus? = null): AiIntegratedResult {
        // Without a validated interpretation/cache, free text cannot safely be approximated offline.
        val items = if (data == null || input.mode == AiMode.QUERY) emptyList() else rank(data, input, context, null)
        fence(context)
        return AiIntegratedResult(items, if (items.isEmpty()) AiRecommendationOrigin.EMPTY else AiRecommendationOrigin.LOCAL,
            lastQuota ?: unknown(), context.profile.token, status, code)
    }
    private suspend fun rank(data: CandidateCatalog, input: AiHintInput, context: AiIntegrationContext, hints: WorkerHints?): List<RankedCandidate> {
        val original = input.conditions
        val proposed = hints?.conditions ?: CandidateHardConditions()
        fun lower(a: Int?, b: Int?) = if (a == null) b else if (b == null) a else maxOf(a, b)
        fun upper(a: Int?, b: Int?) = if (a == null) b else if (b == null) a else minOf(a, b)
        val hard = original.copy(requiredTopics = original.requiredTopics + proposed.requiredTopics,
            excludedTopics = original.excludedTopics + proposed.excludedTopics, minYear = lower(original.minYear, proposed.minYear),
            maxYear = upper(original.maxYear, proposed.maxYear), maxRuntimeMinutes = upper(original.maxRuntimeMinutes, proposed.maxRuntimeMinutes))
        if (hard.minYear != null && hard.maxYear != null && hard.minYear > hard.maxYear) return emptyList()
        val order = hints?.preferredTopics.orEmpty().flatMap { topic -> data.candidates.filter { candidate ->
            candidate.topics.any { normalizedCandidateText(it) == normalizedCandidateText(topic) }
        }.map { it.identity } }.distinct()
        return RecommendationUseCase(object : CandidateCatalogRepository { override suspend fun load() = data }, clock)(
            CandidateRecommendationRequest(input.mode, CandidatePreferences.fromProfile(context.profile), hard, order, input.allowMock)).items
    }
    private fun remember(key: Key, data: CandidateCatalog, items: List<RankedCandidate>, hints: WorkerHints) {
        val now = clock()
        val eligible = items.mapNotNull { row ->
            val source = data.sources.singleOrNull { it.id == row.candidate.identity.sourceId } ?: return@mapNotNull null
            val age = source.license.maxCacheAgeMillis?.coerceAtMost(MAX_CACHE_AGE) ?: return@mapNotNull null
            if (!source.license.cacheAllowed || age <= 0 || now < 0 || now > Long.MAX_VALUE - age) return@mapNotNull null
            val expiry = minOf(now + age, source.license.validUntil ?: Long.MAX_VALUE)
            val candidate = row.candidate.copy(cache = CandidateCache(CandidateCacheState.CACHED, now, expiry))
            candidate.takeIf { CandidateValidator.error(it, data, now, key.input.allowMock) == null }
        }
        if (eligible.isNotEmpty()) {
            cache[key] = CacheEntry(data.copy(candidates = eligible), hints, now)
            while (cache.size > 20) cache.remove(cache.keys.first())
        }
    }
    private fun permittedCache(entry: CacheEntry, current: CandidateCatalog?, allowMock: Boolean): CandidateCatalog? {
        val sources = current?.sources ?: entry.catalog.sources
        val data = entry.catalog.copy(sources = sources)
        val items = data.candidates.filter { cached ->
            val stillPresent = current == null || current.candidates.any { fresh -> fresh.identity == cached.identity && fresh.title == cached.title &&
                fresh.topics == cached.topics && fresh.year == cached.year && fresh.runtimeMinutes == cached.runtimeMinutes && fresh.description == cached.description }
            stillPresent && CandidateValidator.error(cached, data, clock(), allowMock) == null
        }
        return data.copy(candidates = items).takeIf { items.isNotEmpty() }
    }
    private companion object {
        const val MAX_CACHE_AGE = 30L * 24 * 60 * 60 * 1000
        val UNCERTAIN = setOf("TRANSPORT_TIMEOUT", "OUTCOME_UNKNOWN", "STORAGE_UNAVAILABLE", "REQUEST_IN_PROGRESS", "TASK_PENDING")
    }
}
