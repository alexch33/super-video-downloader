package com.myAllVideoBrowser.util.scheduler

import io.reactivex.rxjava3.android.schedulers.AndroidSchedulers
import io.reactivex.rxjava3.core.Scheduler
import javax.inject.Inject
import io.reactivex.rxjava3.schedulers.Schedulers
import java.util.concurrent.Executors

interface BaseSchedulers {
    val computation: Scheduler
    val io: Scheduler
    val newThread: Scheduler
    val single: Scheduler
    val mainThread: Scheduler
    val videoService: Scheduler
}

class BaseSchedulersImpl @Inject constructor() : BaseSchedulers {
    override val computation: Scheduler = Schedulers.computation()
    override val io: Scheduler = Schedulers.io()
    override val newThread: Scheduler = Schedulers.newThread()
    override val single: Scheduler = Schedulers.single()
    override val mainThread: Scheduler = AndroidSchedulers.mainThread()
    override val videoService: Scheduler = Schedulers.from(Executors.newFixedThreadPool(16))
}