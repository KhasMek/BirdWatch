package com.khasmek.flockyou.ui

import androidx.compose.runtime.Composable
import androidx.compose.ui.platform.LocalContext
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import com.khasmek.flockyou.AppContainer
import com.khasmek.flockyou.FlockYouApp

/** Obtain a ViewModel wired to the app's manual DI container, scoped to the current nav entry. */
@Composable
inline fun <reified VM : ViewModel> appViewModel(crossinline create: (AppContainer) -> VM): VM {
    val container = (LocalContext.current.applicationContext as FlockYouApp).container
    return viewModel(factory = viewModelFactory { initializer { create(container) } })
}
