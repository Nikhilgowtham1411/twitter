package com.nikhil.xsaver

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

sealed interface UiState {
    object Idle : UiState
    object Loading : UiState
    data class Failed(val message: String) : UiState
    data class Ready(val tweet: Tweet) : UiState
}

class MainViewModel : ViewModel() {

    var input by mutableStateOf("")

    var state by mutableStateOf<UiState>(UiState.Idle)
        private set

    private var job: Job? = null

    fun fetch() {
        val text = input.trim()
        if (text.isEmpty()) return
        job?.cancel()
        state = UiState.Loading
        job = viewModelScope.launch {
            state = withContext(Dispatchers.IO) { load(text) }
        }
    }

    private fun load(text: String): UiState {
        return try {
            val id = TweetFetcher.resolveTweetId(text)
                ?: return UiState.Failed("That doesn't look like a link to an X post.")
            val tweet = TweetFetcher.fetch(id)
            if (tweet.media.isEmpty()) {
                UiState.Failed("No photo, video or GIF found in this post.")
            } else {
                UiState.Ready(tweet)
            }
        } catch (e: Exception) {
            UiState.Failed(e.message ?: "Something went wrong. Try again.")
        }
    }
}
