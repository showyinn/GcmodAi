package com.gcmodai.app.data.db

import androidx.room.AutoMigration
import androidx.room.Database
import androidx.room.RoomDatabase
import androidx.room.TypeConverter
import androidx.room.TypeConverters
import com.gcmodai.ai.core.TokenUsage
import com.gcmodai.app.data.db.dao.ConversationDAO
import com.gcmodai.app.data.db.dao.FavoriteDAO
import com.gcmodai.app.data.db.dao.FolderDAO
import com.gcmodai.app.data.db.dao.GenMediaDAO
import com.gcmodai.app.data.db.dao.ManagedFileDAO
import com.gcmodai.app.data.db.dao.MediaCreationDAO
import com.gcmodai.app.data.db.dao.MemoryConsolidationDAO
import com.gcmodai.app.data.db.dao.MemoryDAO
import com.gcmodai.app.data.db.dao.MessageNodeDAO
import com.gcmodai.app.data.db.dao.TranslationHistoryDAO
import com.gcmodai.app.data.db.dao.UsageRecordDAO
import com.gcmodai.app.data.db.dao.WorkspaceDAO
import com.gcmodai.app.data.db.entity.ConversationEntity
import com.gcmodai.app.data.db.entity.FavoriteEntity
import com.gcmodai.app.data.db.entity.FolderEntity
import com.gcmodai.app.data.db.entity.GenMediaEntity
import com.gcmodai.app.data.db.entity.ManagedFileEntity
import com.gcmodai.app.data.db.entity.MediaCreationNodeEntity
import com.gcmodai.app.data.db.entity.MediaCreationRecordEntity
import com.gcmodai.app.data.db.entity.MediaCreationSessionEntity
import com.gcmodai.app.data.db.entity.MemoryConsolidationEntity
import com.gcmodai.app.data.db.entity.MemoryFileEntity
import com.gcmodai.app.data.db.entity.MessageNodeEntity
import com.gcmodai.app.data.db.entity.TranslationHistoryEntity
import com.gcmodai.app.data.db.entity.UsageRecordEntity
import com.gcmodai.app.data.db.entity.WorkspaceEntity
import com.gcmodai.app.data.db.migrations.Migration_16_17
import com.gcmodai.app.data.db.migrations.Migration_22_23
import com.gcmodai.app.data.db.migrations.Migration_28_29
import com.gcmodai.app.data.db.migrations.Migration_8_9
import com.gcmodai.app.utils.JsonInstant

@Database(
    entities = [
        ConversationEntity::class,
        MemoryFileEntity::class,
        MemoryConsolidationEntity::class,
        GenMediaEntity::class,
        MessageNodeEntity::class,
        ManagedFileEntity::class,
        FavoriteEntity::class,
        WorkspaceEntity::class,
        FolderEntity::class,
        MediaCreationSessionEntity::class,
        MediaCreationNodeEntity::class,
        MediaCreationRecordEntity::class,
        TranslationHistoryEntity::class,
        UsageRecordEntity::class,
    ],
    version = 32,
    autoMigrations = [
        AutoMigration(from = 1, to = 2),
        AutoMigration(from = 2, to = 3),
        AutoMigration(from = 3, to = 4),
        AutoMigration(from = 4, to = 5),
        AutoMigration(from = 5, to = 6),
        AutoMigration(from = 7, to = 8),
        AutoMigration(from = 8, to = 9, spec = Migration_8_9::class),
        AutoMigration(from = 9, to = 10),
        AutoMigration(from = 10, to = 11),
        AutoMigration(from = 12, to = 13),
        AutoMigration(from = 16, to = 17, spec = Migration_16_17::class),
        AutoMigration(from = 17, to = 18),
        AutoMigration(from = 18, to = 19),
        AutoMigration(from = 19, to = 20),
        AutoMigration(from = 20, to = 21),
        AutoMigration(from = 21, to = 22),
        AutoMigration(from = 22, to = 23, spec = Migration_22_23::class),
        AutoMigration(from = 23, to = 24),
        AutoMigration(from = 24, to = 25),
        AutoMigration(from = 25, to = 26),
        AutoMigration(from = 26, to = 27),
        AutoMigration(from = 28, to = 29, spec = Migration_28_29::class),
        AutoMigration(from = 29, to = 30),
        AutoMigration(from = 31, to = 32),
    ]
)
@TypeConverters(TokenUsageConverter::class)
abstract class AppDatabase : RoomDatabase() {
    abstract fun conversationDao(): ConversationDAO

    abstract fun memoryDao(): MemoryDAO

    abstract fun memoryConsolidationDao(): MemoryConsolidationDAO

    abstract fun genMediaDao(): GenMediaDAO

    abstract fun messageNodeDao(): MessageNodeDAO

    abstract fun managedFileDao(): ManagedFileDAO

    abstract fun favoriteDao(): FavoriteDAO

    abstract fun workspaceDao(): WorkspaceDAO

    abstract fun folderDao(): FolderDAO

    abstract fun mediaCreationDao(): MediaCreationDAO

    abstract fun translationHistoryDao(): TranslationHistoryDAO

    abstract fun usageRecordDao(): UsageRecordDAO
}

object TokenUsageConverter {
    @TypeConverter
    fun fromTokenUsage(usage: TokenUsage?): String {
        return JsonInstant.encodeToString(usage)
    }

    @TypeConverter
    fun toTokenUsage(usage: String): TokenUsage? {
        return JsonInstant.decodeFromString(usage)
    }
}
