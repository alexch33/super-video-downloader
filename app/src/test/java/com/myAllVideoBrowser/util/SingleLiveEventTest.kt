package com.myAllVideoBrowser.util

import androidx.arch.core.executor.testing.InstantTaskExecutorRule
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.LifecycleRegistry
import androidx.lifecycle.Observer
import com.nhaarman.mockito_kotlin.*
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.mockito.ArgumentMatchers.anyInt

class SingleLiveEventTest {

    @get:Rule
    var instantExecutorRule = InstantTaskExecutorRule()

    private lateinit var owner: LifecycleOwner

    private lateinit var eventObserver: Observer<Int>

    private lateinit var lifecycle: LifecycleRegistry

    private val singleLiveEvent = SingleLiveEvent<Int>()

    @Before
    fun setup() {
        owner = mock()
        eventObserver = mock()
        lifecycle = LifecycleRegistry(owner)
        doReturn(lifecycle).`when`(owner).lifecycle
        singleLiveEvent.observe(owner, eventObserver)
        lifecycle.handleLifecycleEvent(Lifecycle.Event.ON_CREATE)
    }

    @Test
    fun `test value is not set on the first onResume`() {
        lifecycle.handleLifecycleEvent(Lifecycle.Event.ON_RESUME)
        verify(eventObserver, never()).onChanged(anyInt())
    }

    @Test
    fun `test update only once`() {
        singleLiveEvent.value = 33

        with(lifecycle) {
            handleLifecycleEvent(androidx.lifecycle.Lifecycle.Event.ON_RESUME)
            handleLifecycleEvent(androidx.lifecycle.Lifecycle.Event.ON_STOP)
            handleLifecycleEvent(androidx.lifecycle.Lifecycle.Event.ON_RESUME)
        }

        verify(eventObserver, times(1)).onChanged(anyInt())
    }

    @Test
    fun `test update twice`() {
        singleLiveEvent.value = 33
        lifecycle.handleLifecycleEvent(Lifecycle.Event.ON_RESUME)
        singleLiveEvent.value = 33

        verify(eventObserver, times(2)).onChanged(anyInt())
    }

    @Test
    fun `test no update util active`() {
        singleLiveEvent.value = 33

        verify(eventObserver, never()).onChanged(33)

        singleLiveEvent.value = 33
        lifecycle.handleLifecycleEvent(Lifecycle.Event.ON_RESUME)

        verify(eventObserver, times(1)).onChanged(anyInt())
    }

}