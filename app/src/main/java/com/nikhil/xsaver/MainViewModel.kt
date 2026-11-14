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

    /** Indexes of media in the current post that were already sent to the download queue. */
    var added by mutableStateOf(setOf<Int>())
        private set

    private var job: Job? = null

    fun fetch() {
        val text = input.trim()
        if (text.isEmpty()) return
        job?.cancel()
        added = emptySet()
        state = UiState.Loading
        job = viewModelScope.launch {
            state = withContext(Dispatchers.IO) { load(text) }
        }
    }

    /** Clears the link and result so the next link can be pasted straight away. */
    fun reset() {
        job?.cancel()
        input = ""
        added = emptySet()
        state = UiState.Idle
    }

    /** Once every item of the post is queued the screen resets itself, ready for the next link. */
    fun markAdded(indices: Collection<Int>) {
        added = added + indices
        val ready = state as? UiState.Ready ?: return
        if (added.size >= ready.tweet.media.size) reset()
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
