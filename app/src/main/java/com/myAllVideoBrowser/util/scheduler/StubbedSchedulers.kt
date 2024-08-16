package com.myAllVideoBrowser.util.scheduler

import io.reactivex.rxjava3.core.Scheduler
import io.reactivex.rxjava3.schedulers.Schedulers

class StubbedSchedulers(
    override val computation: Scheduler = Schedulers.trampoline(),
    override val io: Scheduler = Schedulers.trampoline(),
    override val newThread: Scheduler = Schedulers.trampoline(),
    override val single: Scheduler = Schedulers.trampoline(),
    override val mainThread: Scheduler = Schedulers.trampoline(),
    override val videoService: Scheduler = Schedulers.trampoline(),
) : BaseSchedulers