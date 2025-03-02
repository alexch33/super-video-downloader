package com.myAllVideoBrowser.ui.main.home

import com.nhaarman.mockito_kotlin.mock
import org.junit.Before
import org.junit.Test

class MainViewModelTest {

    private lateinit var mainViewModel: MainViewModel

    @Before
    fun setup() {
        mainViewModel = MainViewModel(mock())
    }

    @Test
    fun `test MainViewModel here`() {
        mainViewModel.start()
        mainViewModel.stop()
    }
}