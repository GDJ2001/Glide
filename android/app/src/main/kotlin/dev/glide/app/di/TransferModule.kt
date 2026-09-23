package dev.glide.app.di

import android.content.Context
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.android.qualifiers.ApplicationContext
import dagger.hilt.components.SingletonComponent
import dev.glide.app.service.TransferSessionFactory
import dev.glide.core.ports.HistoryStore
import dev.glide.core.ports.ReceiveStore
import dev.glide.core.ports.SourceAccess
import dev.glide.data.source.AndroidContentResolverWrapper
import dev.glide.data.source.AndroidSourceAccess
import dev.glide.data.source.BoundedSourceStager
import dev.glide.data.source.ContentResolverWrapper
import dev.glide.data.source.ResumeSourceVerifier
import dev.glide.data.store.AndroidReceiveStore
import dev.glide.data.store.RoomHistoryStore
import dev.glide.data.store.ResumableStagingStore
import java.io.File
import javax.inject.Singleton

/**
 * Hilt module providing transfer session dependencies, ports, and session factory (LAN-003).
 */
@Module
@InstallIn(SingletonComponent::class)
object TransferModule {

    @Provides
    @Singleton
    fun provideContentResolverWrapper(@ApplicationContext context: Context): ContentResolverWrapper {
        return AndroidContentResolverWrapper(context.contentResolver)
    }

    @Provides
    @Singleton
    fun provideBoundedSourceStager(
        @ApplicationContext context: Context,
        resolverWrapper: ContentResolverWrapper
    ): BoundedSourceStager {
        val stagingDir = File(context.cacheDir, "staging_sources")
        if (!stagingDir.exists()) stagingDir.mkdirs()
        return BoundedSourceStager(
            stagingDir = stagingDir,
            resolverWrapper = resolverWrapper
        )
    }

    @Provides
    @Singleton
    fun provideSourceAccess(
        resolverWrapper: ContentResolverWrapper,
        stager: BoundedSourceStager
    ): SourceAccess {
        return AndroidSourceAccess(
            resolverWrapper = resolverWrapper,
            stager = stager
        )
    }

    @Provides
    @Singleton
    fun provideResumeSourceVerifier(
        sourceAccess: SourceAccess
    ): ResumeSourceVerifier =
        ResumeSourceVerifier(
            sourceAccess
        )

    @Provides
    @Singleton
    fun provideHistoryStorePort(roomHistoryStore: RoomHistoryStore): HistoryStore {
        return roomHistoryStore
    }

    @Provides
    @Singleton
    fun provideReceiveStorePort(androidReceiveStore: AndroidReceiveStore): ReceiveStore {
        return androidReceiveStore
    }

    @Provides
    @Singleton
    fun provideTransferSessionFactory(
        sourceAccess: SourceAccess,
        receiveStore: ReceiveStore,
        historyStore: HistoryStore,
        resumeSourceVerifier:
            ResumeSourceVerifier,
        resumableStagingStore:
            ResumableStagingStore
    ): TransferSessionFactory {
        return TransferSessionFactory(
            sourceAccess = sourceAccess,
            receiveStore = receiveStore,
            historyStore = historyStore,
            resumeSourceVerifier =
                resumeSourceVerifier,
            resumableStagingStore =
                resumableStagingStore
        )
    }

    @Provides
    @Singleton
    fun provideLanTransportFactory(@ApplicationContext context: Context): dev.glide.transport.lan.LanTransportFactory? {
        return if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.Q &&
            dev.glide.app.config.LanFeatureConfig.isLanTransportEnabled
        ) {
            dev.glide.transport.lan.LanTransportFactory(context)
        } else {
            null
        }
    }

    @Provides
    @Singleton
    fun provideLanNetworkScope(@ApplicationContext context: Context): dev.glide.transport.lan.network.LanNetworkScope {
        return dev.glide.transport.lan.network.LanNetworkScope(context)
    }

    @Provides
    @Singleton
    fun provideTransportRouter(
        @ApplicationContext context: Context,
        lanTransportFactory: dev.glide.transport.lan.LanTransportFactory?,
        lanNetworkScope: dev.glide.transport.lan.network.LanNetworkScope
    ): dev.glide.app.routing.TransportRouter {
        return dev.glide.app.routing.TransportRouter(
            context = context,
            lanTransportFactory = lanTransportFactory,
            lanNetworkScope = lanNetworkScope
        )
    }
}
