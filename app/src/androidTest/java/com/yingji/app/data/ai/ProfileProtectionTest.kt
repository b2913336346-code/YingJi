package com.yingji.app.data.ai

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import com.yingji.app.data.database.*
import org.junit.Assert.*
import org.junit.Test
import java.io.File
import java.util.UUID

class ProfileProtectionTest {
    @Test fun unknownFutureSchemaIsStillRejectedWithoutChangingOriginalDatabase() {
        DatabaseHealthMonitor.resetForTesting()
        val context=ApplicationProvider.getApplicationContext<Context>()
        val name="a2-unknown-"+UUID.randomUUID()+".db"
        val scratch=File(context.cacheDir,name+"-scratch")
        val db=Room.databaseBuilder(context,AppDatabase::class.java,name).build()
        try { db.openHelper.writableDatabase.execSQL("PRAGMA user_version=6") } finally { db.close() }
        val source=context.getDatabasePath(name)
        val before=source.readBytes()
        try {
            try { DatabaseBackupStore.preflightExisting(source,scratch);fail("Unknown schema was accepted") }
            catch(error:DatabaseMigrationException){assertEquals(DatabaseIssue.MIGRATION,classifyDatabaseIssue(error))}
            assertArrayEquals(before,source.readBytes())
            assertTrue(source.isFile)
        } finally { context.deleteDatabase(name);scratch.deleteRecursively();DatabaseHealthMonitor.resetForTesting() }
    }
}
