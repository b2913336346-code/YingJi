package com.yingji.app

import android.app.Application
import com.yingji.app.data.database.DatabaseModule
import com.yingji.app.data.database.DatabaseBackupStore
import com.yingji.app.data.database.DatabaseProtectionCoordinator
import java.io.File
import com.yingji.app.data.repository.HistoryRepository
import com.yingji.app.data.repository.CategoryRepository
import com.yingji.app.data.repository.MovieRepository
import com.yingji.app.data.repository.MovieTypeRepository
import com.yingji.app.data.repository.PosterRepository
import com.yingji.app.data.repository.ThemeRepository
import com.yingji.app.di.AppModule

class YingJiApplication : Application() {
    internal var databaseProtectionForTesting: DatabaseProtectionCoordinator? = null
    val databaseProtection: DatabaseProtectionCoordinator
        get() = databaseProtectionForTesting ?: productionDatabaseProtection

    private val productionDatabaseProtection: DatabaseProtectionCoordinator by lazy {
        DatabaseProtectionCoordinator(
            marker = File(noBackupFilesDir, "database-protection.state"),
            backups = DatabaseBackupStore(getDatabasePath("yingji.db"), File(noBackupFilesDir, "db-preservation")),
        )
    }

    private val database by lazy {
        DatabaseModule.provideDatabase(this)
    }

    val movieRepository: MovieRepository by lazy {
        AppModule.provideMovieRepository(database)
    }

    val movieTypeRepository: MovieTypeRepository by lazy {
        AppModule.provideMovieTypeRepository(database)
    }

    val historyRepository: HistoryRepository by lazy {
        AppModule.provideHistoryRepository(database)
    }

    val categoryRepository: CategoryRepository by lazy {
        AppModule.provideCategoryRepository(database)
    }

    val posterRepository: PosterRepository by lazy {
        AppModule.providePosterRepository(this)
    }

    val saveRemotePosterUseCase by lazy {
        AppModule.provideSaveRemotePosterUseCase(movieRepository, posterRepository)
    }

    val profileRepository: com.yingji.app.data.ai.ProfileRepository by lazy {
        AppModule.provideProfileRepository(database, movieRepository)
    }

    val localProfileManagementRepository by lazy {
        com.yingji.app.data.ai.RoomLocalProfileManagementRepository(profileRepository, categoryRepository)
    }

    val candidateCatalog: com.yingji.app.domain.ai.CandidateCatalogRepository by lazy {
        com.yingji.app.data.ai.MockCandidateCatalogRepository()
    }

    val addRecommendedWork: com.yingji.app.domain.ai.AddRecommendedWorkUseCase by lazy {
        com.yingji.app.domain.ai.AddRecommendedWorkUseCase(candidateCatalog,
            com.yingji.app.data.ai.CandidateImportRepository(database, movieRepository),
            com.yingji.app.domain.usecase.AddMovieUseCase(movieRepository))
    }

    val localAiUsageRepository by lazy { com.yingji.app.data.ai.LocalAiUsageRepository(this) }
    val mockAiHomeRepository: com.yingji.app.data.ai.MockAiHomeRepository by lazy {
        com.yingji.app.data.ai.MockAiHomeRepository(this, catalog = candidateCatalog, localUsage = localAiUsageRepository)
    }

    val aiKeyConfigurationRepository: com.yingji.app.domain.ai.AiKeyConfigurationRepository by lazy {
        com.yingji.app.domain.ai.AiKeyConfigurationRepository(com.yingji.app.data.ai.AndroidKeystoreAiKeyStore(this),
            com.yingji.app.data.ai.AiHomeRepositoryFactory.verifier(this))
    }

    val tmdbCredentialRepository by lazy {
        com.yingji.app.domain.ai.TmdbCredentialRepository(com.yingji.app.data.ai.TmdbCredentialStore(this))
    }

    private fun tmdbPermission(importAllowed: Boolean = false) = com.yingji.app.domain.ai.TmdbSourcePermission(
        com.yingji.app.domain.ai.CandidateLicenseState.ACTIVE,
        if (importAllowed) "User-attested TMDB development import permission; scope recorded in 2026-10-23-tmdb-authorization-scope.json"
        else "User-attested TMDB development permission; scope recorded in 2026-10-23-tmdb-authorization-scope.json",
        aiRecommendationAllowed = true, displayAllowed = true, importAllowed = importAllowed)

    val personalizedRecommendation by lazy {
        com.yingji.app.data.ai.AiHomeRepositoryFactory.personalized(profileRepository, aiKeyConfigurationRepository,
            { tmdbPermission() }, localAiUsageRepository, localProfileManagementRepository)
    }

    // User-attested development permission; not an independent provider license verification.
    // Preview only: no imports, persistent metadata cache or history snapshots.
    val tmdbCandidatePreview by lazy {
        com.yingji.app.domain.ai.TmdbCandidatePreview(com.yingji.app.data.ai.TmdbCandidateCatalogRepository(
            com.yingji.app.data.ai.TmdbAuthenticatedTransport(com.yingji.app.data.ai.TmdbHttpClient.forReadOnlyPreview(), tmdbCredentialRepository),
            permission = { tmdbPermission() }))
    }

    val tmdbPosterLookup by lazy {
        com.yingji.app.data.ai.TmdbPosterLookup(
            com.yingji.app.data.ai.TmdbAuthenticatedTransport(com.yingji.app.data.ai.TmdbHttpClient.forReadOnlyPreview(), tmdbCredentialRepository),
            tmdbCredentialRepository, permission = { tmdbPermission() })
    }

    // Local Mock directions use only the demo store; no model, key or history dependency.
    val tmdbMockRanking by lazy {
        com.yingji.app.domain.ai.TmdbMockRecommendationUseCase(mockAiHomeRepository::readIntegrationProfile)
    }

    val importTmdbWork by lazy {
        com.yingji.app.data.ai.TmdbImportUseCase(
            com.yingji.app.data.ai.TmdbAuthenticatedTransport(com.yingji.app.data.ai.TmdbHttpClient.forReadOnlyPreview(), tmdbCredentialRepository),
            tmdbCredentialRepository,
            permission = { tmdbPermission(importAllowed = true) },
            imports = com.yingji.app.data.ai.CandidateImportRepository(database, movieRepository),
            addMovie = com.yingji.app.domain.usecase.AddMovieUseCase(movieRepository))
    }

    val aiHomeRepository: com.yingji.app.domain.ai.AiHomeRepository by lazy {
        com.yingji.app.data.ai.AiHomeRepositoryFactory.create(mockAiHomeRepository, candidateCatalog, this, aiKeyConfigurationRepository, localAiUsageRepository)
    }

    val themeRepository: ThemeRepository by lazy {
        AppModule.provideThemeRepository(this)
    }

    val portableBackup by lazy {
        com.yingji.app.data.backup.PortableBackupCoordinator(this, database, themeRepository,
            rebuildProfile = { profileRepository.getProfile(); Unit })
    }
}
