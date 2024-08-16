package com.myAllVideoBrowser.ui.main.home

import org.junit.Before
import org.junit.Test

class MainViewModelTest {

    private lateinit var mainViewModel: MainViewModel

    @Before
    fun setup() {
        mainViewModel = MainViewModel()
    }

    @Test
    fun `test MainViewModel here`() {
        mainViewModel.start()
        mainViewModel.stop()
    }
}