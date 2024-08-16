package util

import android.app.Activity
import android.app.Application
import android.app.Service
import android.content.BroadcastReceiver
import android.content.ContentProvider
import dagger.android.*

interface HasTestInjectors {
    var activityInjector: AndroidInjector<Activity>
    var serviceInjector: AndroidInjector<Service>
    var broadcastReceiverInjector: AndroidInjector<BroadcastReceiver>
    var contentProviderInjector: AndroidInjector<ContentProvider>
}

open class TestApplication : Application(), HasTestInjectors, HasActivityInjector, HasBroadcastReceiverInjector,
    HasServiceInjector, HasContentProviderInjector {

    override lateinit var activityInjector: AndroidInjector<Activity>
    override lateinit var serviceInjector: AndroidInjector<Service>
    override lateinit var broadcastReceiverInjector: AndroidInjector<BroadcastReceiver>
    override lateinit var contentProviderInjector: AndroidInjector<ContentProvider>

    override fun activityInjector(): AndroidInjector<Activity> = activityInjector

    override fun broadcastReceiverInjector(): AndroidInjector<BroadcastReceiver> = broadcastReceiverInjector

    override fun serviceInjector(): AndroidInjector<Service> = serviceInjector

    override fun contentProviderInjector(): AndroidInjector<ContentProvider> = contentProviderInjector

}