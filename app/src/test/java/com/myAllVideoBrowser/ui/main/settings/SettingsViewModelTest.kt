package com.myAllVideoBrowser.ui.main.settings

import androidx.arch.core.executor.testing.InstantTaskExecutorRule
import com.nhaarman.mockito_kotlin.mock
import org.junit.Assert.assertNull
import org.junit.Before
import org.junit.Rule
import org.junit.Test

class SettingsViewModelTest {

    @get:Rule
    var instantExecutorRule = InstantTaskExecutorRule()

    private lateinit var viewModel: SettingsViewModel

    @Before
    fun setup() {
        viewModel = SettingsViewModel(mock())
    }

    @Test
    fun `test clear cookies`() {
        viewModel.clearCookies()
        assertNull(viewModel.clearCookiesEvent.value)
    }

    @Test
    fun `test open video folder`() {
        viewModel.openVideoFolder()
        assertNull(viewModel.openVideoFolderEvent.value)
    }
}