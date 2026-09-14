package dev.glide.app.di

import android.content.Context
import androidx.room.Room
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.android.qualifiers.ApplicationContext
import dagger.hilt.components.SingletonComponent
import dev.glide.core.ports.HistoryStore
import dev.glide.data.db.GlideDatabase
import dev.glide.data.db.ItemDao
import dev.glide.data.db.ReceivedFileDao
import dev.glide.data.db.SessionDao
import dev.glide.data.store.RoomHistoryStore
import javax.inject.Singleton

/**
 * Hilt module providing singleton Room database and DAO instances.
 */
@Module
@InstallIn(SingletonComponent::class)
object DatabaseModule {

    @Provides
    @Singleton
    fun provideGlideDatabase(@ApplicationContext context: Context): GlideDatabase {
        return Room.databaseBuilder(
            context,
            GlideDatabase::class.java,
            "glide.db"
        )
            .addMigrations(GlideDatabase.MIGRATION_1_2)
            .build()
    }

    @Provides
    fun provideSessionDao(db: GlideDatabase): SessionDao = db.sessionDao()

    @Provides
    fun provideItemDao(db: GlideDatabase): ItemDao = db.itemDao()

    @Provides
    fun provideReceivedFileDao(db: GlideDatabase): ReceivedFileDao = db.receivedFileDao()

    @Provides
    fun provideReceiveJournalDao(db: GlideDatabase): dev.glide.data.db.ReceiveJournalDao = db.receiveJournalDao()

    @Provides
    @Singleton
    fun provideHistoryStore(
        sessionDao: SessionDao,
        itemDao: ItemDao,
        receivedFileDao: ReceivedFileDao
    ): RoomHistoryStore {
        return RoomHistoryStore(sessionDao, itemDao, receivedFileDao)
    }

    private val Context.dataStore: androidx.datastore.core.DataStore<androidx.datastore.preferences.core.Preferences> by androidx.datastore.preferences.preferencesDataStore(
        name = "glide_settings"
    )

    @Provides
    @Singleton
    fun provideSettingsStore(@ApplicationContext context: Context): dev.glide.core.ports.SettingsStore {
        return dev.glide.data.settings.DataStoreSettingsStore(context.dataStore)
    }

    @Provides
    @Singleton
    fun providePrivateFileManager(
        @ApplicationContext context: Context,
        receivedFileDao: ReceivedFileDao,
        receiveJournalDao: dev.glide.data.db.ReceiveJournalDao,
        sessionDao: SessionDao
    ): dev.glide.core.ports.PrivateFileManager {
        val receivedDir = java.io.File(context.filesDir, "received")
        if (!receivedDir.exists()) receivedDir.mkdirs()
        return dev.glide.data.store.AndroidPrivateFileManager(
            receivedFilesDir = receivedDir,
            fileCatalogDao = receivedFileDao,
            receiveJournalDao = receiveJournalDao,
            sessionDao = sessionDao
        )
    }

    @Provides
    @Singleton
    fun provideReceiveStore(
        @ApplicationContext context: Context,
        receiveJournalDao: dev.glide.data.db.ReceiveJournalDao,
        receivedFileDao: ReceivedFileDao
    ): dev.glide.data.store.AndroidReceiveStore {
        return dev.glide.data.store.AndroidReceiveStore(
            rootDir = context.filesDir,
            journalDao = receiveJournalDao,
            fileCatalogDao = receivedFileDao
        )
    }
    @Provides
    @Singleton
    fun provideProcessDeathReconciler(
        receiveStore: dev.glide.data.store.AndroidReceiveStore,
        sessionDao: SessionDao,
        itemDao: ItemDao,
        receivedFileDao: ReceivedFileDao
    ): dev.glide.data.store.ProcessDeathReconciler {
        return dev.glide.data.store.ProcessDeathReconciler(
            receiveStore = receiveStore,
            sessionDao = sessionDao,
            itemDao = itemDao,
            receivedFileDao = receivedFileDao
        )
    }

    @Provides
    @Singleton
    fun provideOrphanMaintenanceCleaner(
        @ApplicationContext context: Context,
        receiveStore: dev.glide.data.store.AndroidReceiveStore,
        receiveJournalDao: dev.glide.data.db.ReceiveJournalDao,
        receivedFileDao: ReceivedFileDao,
        historyStore: RoomHistoryStore
    ): dev.glide.data.maintenance.OrphanMaintenanceCleaner {
        val stagingSourcesDirs = listOf(
            java.io.File(context.cacheDir, "staging_sources"),
            java.io.File(context.cacheDir, "staged_sources")
        )
        return dev.glide.data.maintenance.OrphanMaintenanceCleaner(
            receivingDir = receiveStore.stagingDir,
            receivedDir = receiveStore.receivedDir,
            stagingSourcesDirs = stagingSourcesDirs,
            receiveJournalDao = receiveJournalDao,
            receivedFileDao = receivedFileDao,
            historyStore = historyStore
        )
    }
}
