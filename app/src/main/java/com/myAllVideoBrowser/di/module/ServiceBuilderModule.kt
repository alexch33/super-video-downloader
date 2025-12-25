package com.myAllVideoBrowser.di.module

import com.myAllVideoBrowser.util.proxy_utils.ProxyService
import dagger.Module
import dagger.android.ContributesAndroidInjector

@Module
abstract class ServiceBuilderModule {

    @ContributesAndroidInjector
    abstract fun contributeProxyService(): ProxyService
}