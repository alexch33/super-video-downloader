package com.myAllVideoBrowser.di.module

import com.myAllVideoBrowser.data.local.*
import com.myAllVideoBrowser.data.remote.AdBlockHostsRemoteDataSource
import com.myAllVideoBrowser.data.remote.ConfigRemoteDataSource
import com.myAllVideoBrowser.data.remote.TopPagesRemoteDataSource
import com.myAllVideoBrowser.data.remote.VideoRemoteDataSource
import com.myAllVideoBrowser.data.repository.*
import com.myAllVideoBrowser.di.qualifier.LocalData
import com.myAllVideoBrowser.di.qualifier.RemoteData
import dagger.Binds
import dagger.Module
import javax.inject.Singleton

@Module
abstract class RepositoryModule {

    @Singleton
    @Binds
    @LocalData
    abstract fun bindConfigLocalDataSource(localDataSource: ConfigLocalDataSource): ConfigRepository

    @Singleton
    @Binds
    @RemoteData
    abstract fun bindConfigRemoteDataSource(remoteDataSource: ConfigRemoteDataSource): ConfigRepository

    @Singleton
    @Binds
    abstract fun bindConfigRepositoryImpl(configRepository: ConfigRepositoryImpl): ConfigRepository

    @Singleton
    @Binds
    @LocalData
    abstract fun bindTopPagesLocalDataSource(localDataSource: TopPagesLocalDataSource): TopPagesRepository

    @Singleton
    @Binds
    @RemoteData
    abstract fun bindTopPagesRemoteDataSource(remoteDataSource: TopPagesRemoteDataSource): TopPagesRepository

    @Singleton
    @Binds
    abstract fun bindTopPagesRepositoryImpl(topPagesRepository: TopPagesRepositoryImpl): TopPagesRepository

    @Singleton
    @Binds
    @LocalData
    abstract fun bindVideoLocalDataSource(localDataSource: VideoLocalDataSource): VideoRepository

    @Singleton
    @Binds
    @RemoteData
    abstract fun bindVideoRemoteDataSource(remoteDataSource: VideoRemoteDataSource): VideoRepository

    @Singleton
    @Binds
    abstract fun bindVideoRepositoryImpl(videoRepository: VideoRepositoryImpl): VideoRepository

    @Singleton
    @Binds
    @LocalData
    abstract fun bindProgressLocalDataSource(localDataSource: ProgressLocalDataSource): ProgressRepository

    @Singleton
    @Binds
    @LocalData
    abstract fun bindHistoryLocalDataSource(localDataSource: HistoryLocalDataSource): HistoryRepository


    @Singleton
    @Binds
    abstract fun bindProgressRepositoryImpl(progressRepository: ProgressRepositoryImpl): ProgressRepository

    @Singleton
    @Binds
    abstract fun bindHistoryRepositoryImpl(historyRepository: HistoryRepositoryImpl): HistoryRepository

    @Singleton
    @Binds
    @LocalData
    abstract fun bindAdBlockHostsLocalDataSource(adBlockHostsLocalDataSource: AdBlockHostsLocalDataSource): AdBlockHostsRepository

    @Singleton
    @Binds
    @RemoteData
    abstract fun bindAdBlockHostsRemoteDataSource(adBlockHostsRemoteDataSource: AdBlockHostsRemoteDataSource): AdBlockHostsRepository
    @Singleton
    @Binds
    abstract fun bindAdBlockHostsRepositoryImpl(adBlockHostsRepository: AdBlockHostsRepositoryImpl): AdBlockHostsRepository

}