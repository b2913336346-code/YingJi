package com.yingji.app.data.ai

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import com.yingji.app.domain.ai.WorkerRequestSigner
import java.io.File
import java.security.KeyStore
import java.security.Signature
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test

class WorkerIdentityTest {
    @Test fun exportPublicPinAndProveNonExportableKeystoreIdentitySurvivesRecreation() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val identity = AndroidKeystoreWorkerIdentity(context); val pin = identity.publicPin()
        val value = JSONObject(pin); assertEquals(identity.clientId, value.getString("id"))
        assertEquals(setOf("id", "jwk"), value.keys().asSequence().toSet())
        assertEquals(setOf("kty", "crv", "x", "y"), value.getJSONObject("jwk").keys().asSequence().toSet())
        val store = KeyStore.getInstance("AndroidKeyStore").apply { load(null) }
        assertNull(store.getKey(AndroidKeystoreWorkerIdentity.ALIAS, null).encoded)
        val input = "Keystore signing identity proof".toByteArray()
        val signature = identity.sign(input)
        assertEquals(64, WorkerRequestSigner.derToRaw(signature).size)
        assertTrue(Signature.getInstance("SHA256withECDSA").run { initVerify(store.getCertificate(AndroidKeystoreWorkerIdentity.ALIAS)); update(input); verify(signature) })
        assertEquals(pin, AndroidKeystoreWorkerIdentity(context).publicPin())
        File(context.noBackupFilesDir, "byok-worker-public-pin.json").writeText(pin)
    }
}
