package com.yingji.app.domain.ai

import java.net.URI
import java.util.Locale

/** Build-time public endpoint only; enabling verification never enables recommendation or profile upload. */
class AiLiveWorkerConfiguration private constructor(val status: Status, val origin: String?) {
    enum class Status { DISABLED, MISSING_ENDPOINT, INVALID_ENDPOINT, CONFIGURED }
    val verificationEnabled get() = status == Status.CONFIGURED
    override fun toString() = "AiLiveWorkerConfiguration(" + status.name + ")"
    companion object {
        fun from(enabled: Boolean, workerOrigin: String): AiLiveWorkerConfiguration {
            if (!enabled) return AiLiveWorkerConfiguration(Status.DISABLED, null)
            if (workerOrigin.isBlank()) return AiLiveWorkerConfiguration(Status.MISSING_ENDPOINT, null)
            val normalized = try {
                val uri = URI(workerOrigin.trim())
                val host = uri.host?.lowercase(Locale.ROOT) ?: throw IllegalArgumentException()
                require(uri.scheme == "https" && uri.userInfo == null && uri.rawQuery == null && uri.rawFragment == null && uri.rawPath in listOf("", "/") && uri.port in listOf(-1,443))
                require(host.endsWith(".workers.dev") && host.split('.').all { Regex("[a-z0-9](?:[a-z0-9-]{0,61}[a-z0-9])?").matches(it) })
                "https://" + host
            } catch (_: Exception) { return AiLiveWorkerConfiguration(Status.INVALID_ENDPOINT, null) }
            return AiLiveWorkerConfiguration(Status.CONFIGURED, normalized)
        }
    }
}
