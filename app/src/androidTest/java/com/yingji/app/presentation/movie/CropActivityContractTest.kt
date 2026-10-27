package com.yingji.app.presentation.movie

import android.content.ComponentName
import android.content.Context
import android.content.pm.ActivityInfo
import android.net.Uri
import androidx.test.core.app.ApplicationProvider
import com.canhub.cropper.CropImageActivity
import com.canhub.cropper.CropImageContract
import com.canhub.cropper.CropImageContractOptions
import com.canhub.cropper.CropImageOptions
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class CropActivityContractTest {
    @Test
    fun privateCropActivityRemainsAnEnabledPortraitTargetForTheInternalContract() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val expected = ComponentName(context, CropImageActivity::class.java)
        val intent = CropImageContract().createIntent(context, CropImageContractOptions(
            Uri.parse("content://yingji.test/selected-image"), CropImageOptions(),
        ))
        assertEquals(expected, intent.component)
        val info = context.packageManager.getActivityInfo(expected, 0)
        assertFalse(info.exported)
        assertTrue(info.enabled)
        assertEquals(ActivityInfo.SCREEN_ORIENTATION_PORTRAIT, info.screenOrientation)
    }
}
