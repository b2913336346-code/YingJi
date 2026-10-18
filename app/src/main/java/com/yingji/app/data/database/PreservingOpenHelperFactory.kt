package com.yingji.app.data.database

import androidx.sqlite.db.SupportSQLiteDatabase
import androidx.sqlite.db.SupportSQLiteOpenHelper
import androidx.sqlite.db.framework.FrameworkSQLiteOpenHelperFactory
import kotlinx.coroutines.CancellationException
import java.io.File

/** Never delegate corruption to the default callback: its implementation deletes files. */
class PreservingOpenHelperFactory(
    private val delegate: SupportSQLiteOpenHelper.Factory = FrameworkSQLiteOpenHelperFactory(),
) : SupportSQLiteOpenHelper.Factory {
    override fun create(configuration: SupportSQLiteOpenHelper.Configuration): SupportSQLiteOpenHelper {
        val original = configuration.callback
        val callback = object : SupportSQLiteOpenHelper.Callback(original.version) {
            override fun onConfigure(db: SupportSQLiteDatabase) {
                DatabaseHealthMonitor.ensureAccessible()
                original.onConfigure(db)
            }

            override fun onCreate(db: SupportSQLiteDatabase) {
                DatabaseHealthMonitor.ensureAccessible()
                original.onCreate(db)
            }

            override fun onUpgrade(db: SupportSQLiteDatabase, oldVersion: Int, newVersion: Int) =
                migrate { original.onUpgrade(db, oldVersion, newVersion) }

            override fun onDowngrade(db: SupportSQLiteDatabase, oldVersion: Int, newVersion: Int) =
                migrate { original.onDowngrade(db, oldVersion, newVersion) }

            override fun onOpen(db: SupportSQLiteDatabase) {
                DatabaseHealthMonitor.ensureAccessible()
                try {
                    original.onOpen(db)
                } catch (error: IllegalStateException) {
                    // Room's identity/schema validation happens in onOpen, before its callbacks.
                    throw DatabaseMigrationException(error)
                }
                DatabaseHealthMonitor.ensureAccessible()
            }

            override fun onCorruption(db: SupportSQLiteDatabase) {
                // Preserve synchronously before the framework unwinds/closes the failed open.
                // Do not call super, original.onCorruption, close, checkpoint, or delete.
                DatabaseHealthMonitor.stop(DatabaseIssue.CORRUPTION)
            }

            private fun migrate(action: () -> Unit) {
                DatabaseHealthMonitor.ensureAccessible()
                try {
                    action()
                } catch (error: CancellationException) {
                    throw error
                } catch (error: Exception) {
                    throw DatabaseMigrationException(error)
                }
            }
        }
        val safeConfiguration = SupportSQLiteOpenHelper.Configuration.builder(configuration.context)
            .name(configuration.name)
            .callback(callback)
            .noBackupDirectory(configuration.useNoBackupDirectory)
            .allowDataLossOnRecovery(false)
            .build()
        val helper = delegate.create(safeConfiguration)
        return object : SupportSQLiteOpenHelper by helper {
            private var preflightDone = false
            override val writableDatabase: SupportSQLiteDatabase
                get() = open { helper.writableDatabase }
            override val readableDatabase: SupportSQLiteDatabase
                get() = open { helper.readableDatabase }

            @Synchronized
            private fun open(action: () -> SupportSQLiteDatabase): SupportSQLiteDatabase {
                DatabaseHealthMonitor.ensureAccessible()
                try {
                    if (!preflightDone) {
                        configuration.name?.let { name ->
                            DatabaseBackupStore.preflightExisting(configuration.context.getDatabasePath(name),
                                File(configuration.context.noBackupFilesDir, "db-open-checks"))
                        }
                        preflightDone = true
                    }
                    return action()
                } catch (error: CancellationException) {
                    throw error
                } catch (error: Exception) {
                    if (error !is DatabaseUnavailableException) DatabaseHealthMonitor.stop(classifyDatabaseIssue(error))
                    throw error
                }
            }
        }
    }
}
