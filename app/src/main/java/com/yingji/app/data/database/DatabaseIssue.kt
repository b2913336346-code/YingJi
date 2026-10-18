package com.yingji.app.data.database

import android.database.sqlite.SQLiteCantOpenDatabaseException
import android.database.sqlite.SQLiteConstraintException
import android.database.sqlite.SQLiteDatabaseCorruptException
import android.database.sqlite.SQLiteDatabaseLockedException
import android.database.sqlite.SQLiteFullException

enum class DatabaseIssue(val label: String) {
    CORRUPTION("数据库损坏或完整性异常"),
    MIGRATION("数据库版本或结构不兼容"),
    DISK_FULL("存储空间不足"),
    ACCESS("数据库文件无法访问"),
    LOCKED("数据库被占用"),
    CONSTRAINT("数据约束冲突"),
    UNKNOWN("数据库异常，原因尚未确认"),
}

internal class DatabaseMigrationException(cause: Throwable) : RuntimeException(cause)

fun classifyDatabaseIssue(error: Throwable): DatabaseIssue {
    val causes = generateSequence(error) { it.cause }.take(16).toList()
    return when {
        causes.any { it is SQLiteDatabaseCorruptException } -> DatabaseIssue.CORRUPTION
        causes.any { it is SQLiteFullException } -> DatabaseIssue.DISK_FULL
        causes.any { it is DatabaseMigrationException } -> DatabaseIssue.MIGRATION
        causes.any { it is SQLiteCantOpenDatabaseException || it is SecurityException } -> DatabaseIssue.ACCESS
        causes.any { it is SQLiteDatabaseLockedException } -> DatabaseIssue.LOCKED
        causes.any { it is SQLiteConstraintException } -> DatabaseIssue.CONSTRAINT
        else -> DatabaseIssue.UNKNOWN
    }
}
