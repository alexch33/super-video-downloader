package com.myAllVideoBrowser.di.module

import android.content.Context
import com.franmontiel.persistentcookiejar.PersistentCookieJar
import com.franmontiel.persistentcookiejar.cache.SetCookieCache
import com.franmontiel.persistentcookiejar.persistence.SharedPrefsCookiePersistor
import com.myAllVideoBrowser.data.remote.service.ConfigService
import com.myAllVideoBrowser.data.remote.service.VideoService
import com.myAllVideoBrowser.data.remote.service.VideoServiceSuperX
import com.myAllVideoBrowser.data.remote.service.VideoServiceLocal
import com.myAllVideoBrowser.di.qualifier.ApplicationContext
import com.myAllVideoBrowser.util.ProxyRetryInterceptor
import com.myAllVideoBrowser.util.proxy_utils.CustomProxyController
import com.myAllVideoBrowser.util.proxy_utils.OkHttpProxyClient
import dagger.Module
import dagger.Provides
import okhttp3.OkHttpClient
import retrofit2.Retrofit
import retrofit2.adapter.rxjava3.RxJava3CallAdapterFactory
import retrofit2.converter.gson.GsonConverterFactory
import java.util.concurrent.TimeUnit
import javax.inject.Singleton

@Module
class NetworkModule {

    companion object {
        private const val DATA_URL = "https://some-url.com/youtube-dl/"
    }

    @Singleton
    @Provides
    fun provideOkHttpClient(
        cookieJar: PersistentCookieJar,
        @ApplicationContext context: Context
    ): OkHttpClient {
        return OkHttpClient.Builder()
            .cookieJar(cookieJar)
            .addInterceptor(ProxyRetryInterceptor(context))
            .connectTimeout(30, TimeUnit.SECONDS)
            .readTimeout(30, TimeUnit.SECONDS)
            .writeTimeout(30, TimeUnit.SECONDS)
            .build()
    }

    @Provides
    @Singleton
    fun provideConfigService(okHttpClient: OkHttpClient): ConfigService = Retrofit.Builder()
        .baseUrl(DATA_URL)
        .client(okHttpClient)
        .addConverterFactory(GsonConverterFactory.create())
        .addCallAdapterFactory(RxJava3CallAdapterFactory.create())
        .build()
        .create(ConfigService::class.java)

    @Provides
    @Singleton
    fun provideVideoService(
        proxyController: CustomProxyController,
    ): VideoService = VideoServiceLocal(
        proxyController
    )

    @Provides
    @Singleton
    fun provideFfmpegVideoService(httpClient: OkHttpProxyClient): VideoServiceSuperX =
        VideoServiceSuperX(
            httpClient
        )

    @Singleton
    @Provides
    fun provideCookieJar(@ApplicationContext context: Context): PersistentCookieJar {
        return PersistentCookieJar(SetCookieCache(), SharedPrefsCookiePersistor(context))
    }
}
