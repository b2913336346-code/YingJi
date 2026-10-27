package com.yingji.app.domain.ai

import org.junit.Assert.*
import org.junit.Test

class AiLiveWorkerConfigurationTest {
    @Test fun defaultOffNeverExposesAnEndpointEvenWhenAnOriginIsSupplied() {
        val disabled = AiLiveWorkerConfiguration.from(false,"https://approved-test.workers.dev")
        assertEquals(AiLiveWorkerConfiguration.Status.DISABLED,disabled.status); assertFalse(disabled.verificationEnabled); assertNull(disabled.origin)
    }
    @Test fun explicitOptInStillRequiresAnEndpoint() {
        val missing=AiLiveWorkerConfiguration.from(true," ")
        assertEquals(AiLiveWorkerConfiguration.Status.MISSING_ENDPOINT,missing.status); assertFalse(missing.verificationEnabled); assertNull(missing.origin)
    }
    @Test fun onlyCanonicalControlledHttpsWorkerOriginsCanBeConfigured() {
        val configured=AiLiveWorkerConfiguration.from(true," https://APPROVED-test.workers.dev:443/ ")
        assertEquals(AiLiveWorkerConfiguration.Status.CONFIGURED,configured.status); assertTrue(configured.verificationEnabled)
        assertEquals("https://approved-test.workers.dev",configured.origin)
    }
    @Test fun providerUrlsHttpPathsCredentialsQueriesFragmentsAndOtherPortsFailClosed() {
        for(url in listOf("https://api.deepseek.com","https://example.com","http://approved.workers.dev","https://approved.workers.dev/v1/ai/verify","https://user:secret@approved.workers.dev","https://approved.workers.dev?key=never-include","https://approved.workers.dev#private","https://approved.workers.dev:8443","https://127.0.0.1","https://bad_host.workers.dev","https://approved.workers.dev.evil.invalid")) {
            val value=AiLiveWorkerConfiguration.from(true,url)
            assertEquals(AiLiveWorkerConfiguration.Status.INVALID_ENDPOINT,value.status); assertFalse(value.verificationEnabled); assertNull(value.origin)
        }
    }
    @Test fun invalidInputsNeverAppearInDiagnostics() {
        val input="https://secret-value@approved.workers.dev?key=private"
        assertEquals("AiLiveWorkerConfiguration(INVALID_ENDPOINT)",AiLiveWorkerConfiguration.from(true,input).toString())
    }
}
