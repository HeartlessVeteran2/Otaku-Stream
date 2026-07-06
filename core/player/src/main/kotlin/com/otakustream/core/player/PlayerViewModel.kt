package com.otakustream.core.player

import androidx.lifecycle.ViewModel
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.StateFlow
import javax.inject.Inject

@HiltViewModel
class PlayerViewModel @Inject constructor(
    val controller: PlayerController,
) : ViewModel() {

    val uiState: StateFlow<PlayerUiState> = controller.uiState

    fun play(url: String) = controller.play(url)

    fun seekBy(deltaMs: Long) = controller.seekBy(deltaMs)

    fun adjustVolume(delta: Float) = controller.setVolume(controller.uiState.value.volume + delta)
}
