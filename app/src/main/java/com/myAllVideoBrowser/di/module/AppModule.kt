package com.myAllVideoBrowser.di.module

import android.app.Application
import android.content.Context
import com.myAllVideoBrowser.DLApplication
import com.myAllVideoBrowser.di.qualifier.ApplicationContext
import com.myAllVideoBrowser.util.downloaders.NotificationReceiver
import com.myAllVideoBrowser.util.scheduler.BaseSchedulers
import com.myAllVideoBrowser.util.scheduler.BaseSchedulersImpl
import dagger.Binds
import dagger.Module
import dagger.android.ContributesAndroidInjector
import javax.inject.Singleton

@Module
abstract class AppModule {

    @Binds
    @ApplicationContext
    abstract fun bindApplicationContext(application: DLApplication): Context

    @Binds
    abstract fun bindApplication(application: DLApplication): Application

    @Singleton
    @Binds
    abstract fun bindBaseSchedulers(baseSchedulers: BaseSchedulersImpl): BaseSchedulers

    @ContributesAndroidInjector
    abstract fun contributesNotificationReceiver(): NotificationReceiver
}