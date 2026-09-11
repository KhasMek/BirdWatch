package com.khasmek.birdwatch.ui

import androidx.compose.runtime.Composable
import androidx.compose.ui.platform.LocalContext
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import com.khasmek.birdwatch.AppContainer
import com.khasmek.birdwatch.BirdWatchApp

/** Obtain a ViewModel wired to the app's manual DI container, scoped to the current nav entry. */
@Composable
inline fun <reified VM : ViewModel> appViewModel(crossinline create: (AppContainer) -> VM): VM {
    val container = (LocalContext.current.applicationContext as BirdWatchApp).container
    return viewModel(factory = viewModelFactory { initializer { create(container) } })
}
