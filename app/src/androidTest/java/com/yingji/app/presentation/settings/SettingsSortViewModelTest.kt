package com.yingji.app.presentation.settings

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.emptyPreferences
import androidx.lifecycle.ViewModelStore
import androidx.test.core.app.ApplicationProvider
import androidx.test.platform.app.InstrumentationRegistry
import com.yingji.app.data.repository.ThemeRepository
import com.yingji.app.domain.model.MovieSortOption
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import java.io.IOException

class SettingsSortViewModelTest {
    @Test
    fun failedSortWriteReportsErrorAndRetryPersistsWithoutFalseSuccess() = runBlocking {
        var failWrites = true
        val dataStore = object : DataStore<Preferences> {
            override val data = MutableStateFlow(emptyPreferences())
            override suspend fun updateData(transform: suspend (Preferences) -> Preferences): Preferences {
                if (failWrites) throw IOException("Simulated sort write failure")
                return transform(data.value).also { data.value = it }
            }
        }
        val repository = ThemeRepository(ApplicationProvider.getApplicationContext<Context>(), dataStore)
        val store = ViewModelStore()
        lateinit var vm: SettingsViewModel
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        try {
            instrumentation.runOnMainSync {
                vm = SettingsViewModel(repository)
                store.put("sort", vm)
                vm.setMovieSortOption(MovieSortOption.WATCH_END_DESC)
            }
            assertEquals("默认排序保存失败，请重试", withTimeout(5_000) { vm.sortSaveError.first { it != null } })
            assertEquals(MovieSortOption.CREATED_DESC, repository.movieSortOption.first())
            failWrites = false
            instrumentation.runOnMainSync { vm.setMovieSortOption(MovieSortOption.WATCH_END_DESC) }
            withTimeout(5_000) { vm.movieSortOption.first { it == MovieSortOption.WATCH_END_DESC } }
            assertNull(vm.sortSaveError.value)
            assertEquals(MovieSortOption.WATCH_END_DESC, repository.movieSortOption.first())
        } finally {
            instrumentation.runOnMainSync { store.clear() }
        }
    }
}
