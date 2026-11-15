package com.nikhil.xsaver

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

class MainViewModel : ViewModel() {

    var input by mutableStateOf("")

    /** How many links are being looked up right now (several can run at the same time). */
    var loading by mutableIntStateOf(0)
        private set

    var error by mutableStateOf<String?>(null)
        private set

    var panelOn by mutableStateOf(false)

    /** True when Android's battery optimisation is already switched off for this app. */
    var batteryOk by mutableStateOf(true)

    /**
     * Looks the pasted link up (qualities and sizes) and keeps it in "Saved links".
     * The box is cleared straight away, so the next link can be pasted while this one loads.
     */
    fun fetch() {
        val text = input.trim()
        if (text.isEmpty()) return
        input = ""
        error = null
        loading++
        viewModelScope.launch {
            val result = withContext(Dispatchers.IO) {
                runCatching {
                    val tweet = TweetFetcher.load(text)
                    LinkStore.add(tweet)
                    tweet
                }
            }
            loading--
            result.onFailure { error = it.message ?: "Something went wrong. Try again." }
        }
    }

    fun reset() {
        input = ""
        error = null
    }
}
