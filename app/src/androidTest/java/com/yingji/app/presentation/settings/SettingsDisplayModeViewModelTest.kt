package com.yingji.app.presentation.settings

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.emptyPreferences
import androidx.lifecycle.ViewModelStore
import androidx.test.core.app.ApplicationProvider
import androidx.test.platform.app.InstrumentationRegistry
import com.yingji.app.data.repository.ThemeRepository
import com.yingji.app.domain.model.MovieDisplayMode
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import java.io.IOException

class SettingsDisplayModeViewModelTest {
    private val store = ViewModelStore()

    @After
    fun tearDown() {
        onMain { store.clear() }
    }

    @Test
    fun failedWriteReportsErrorWithoutChangingDefaultAndRetrySucceeds() = runBlocking {
        val dataStore = FailingDataStore()
        val context = ApplicationProvider.getApplicationContext<Context>()
        val repository = ThemeRepository(context, dataStore)
        lateinit var viewModel: SettingsViewModel
        onMain {
            viewModel = SettingsViewModel(repository)
            store.put("settings", viewModel)
            viewModel.setMovieDisplayMode(MovieDisplayMode.POSTER_WALL)
        }
        val failure = withTimeout(5_000L) { viewModel.displayModeSaveError.first { it != null } }
        assertEquals("默认展示方式保存失败，请重试", failure)
        assertEquals(MovieDisplayMode.LIST, repository.movieDisplayMode.first())

        dataStore.failWrites = false
        onMain { viewModel.setMovieDisplayMode(MovieDisplayMode.POSTER_WALL) }
        withTimeout(5_000L) {
            viewModel.movieDisplayMode.first { it == MovieDisplayMode.POSTER_WALL }
        }
        assertNull(viewModel.displayModeSaveError.value)
        assertEquals(MovieDisplayMode.POSTER_WALL, repository.movieDisplayMode.first())
    }

    private fun onMain(action: () -> Unit) {
        InstrumentationRegistry.getInstrumentation().runOnMainSync(action)
    }

    private class FailingDataStore : DataStore<Preferences> {
        override val data = MutableStateFlow(emptyPreferences())
        var failWrites = true

        override suspend fun updateData(transform: suspend (Preferences) -> Preferences): Preferences {
            if (failWrites) throw IOException("Simulated write failure")
            return transform(data.value).also { data.value = it }
        }
    }
}
