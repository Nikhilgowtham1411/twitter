package com.nikhil.xsaver

import androidx.compose.foundation.background
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.FilterChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import coil.compose.AsyncImage

private val XColors = darkColorScheme(
    primary = Color(0xFF1D9BF0),
    onPrimary = Color.White,
    secondaryContainer = Color(0xFF1B3A57),
    onSecondaryContainer = Color(0xFFE7E9EA),
    background = Color(0xFF0B0F14),
    onBackground = Color(0xFFE7E9EA),
    surface = Color(0xFF0B0F14),
    onSurface = Color(0xFFE7E9EA),
    surfaceVariant = Color(0xFF161C24),
    onSurfaceVariant = Color(0xFFB4BDC6),
    outline = Color(0xFF3A4450),
    error = Color(0xFFFF6B6B),
)

@Composable
fun XSaverApp(
    vm: MainViewModel,
    onDownload: (Tweet, Int, Variant) -> Unit,
    onDownloadAll: (Tweet) -> Unit,
) {
    val clipboard = LocalClipboardManager.current
    val focus = LocalFocusManager.current

    MaterialTheme(colorScheme = XColors) {
        Surface(modifier = Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) {
            LazyColumn(
                modifier = Modifier
                    .fillMaxSize()
                    .statusBarsPadding()
                    .navigationBarsPadding()
                    .imePadding(),
                contentPadding = PaddingValues(start = 20.dp, end = 20.dp, top = 24.dp, bottom = 32.dp),
                verticalArrangement = Arrangement.spacedBy(14.dp),
            ) {
                item { Header() }

                item {
                    OutlinedTextField(
                        value = vm.input,
                        onValueChange = { vm.input = it },
                        modifier = Modifier.fillMaxWidth(),
                        label = { Text("Post link") },
                        placeholder = { Text("https://x.com/…/status/…") },
                        maxLines = 3,
                        keyboardOptions = KeyboardOptions(imeAction = ImeAction.Go),
                        keyboardActions = KeyboardActions(onGo = {
                            focus.clearFocus()
                            vm.fetch()
                        }),
                    )
                }

                item {
                    Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                        OutlinedButton(
                            onClick = {
                                val pasted = clipboard.getText()?.text
                                if (!pasted.isNullOrBlank()) {
                                    vm.input = pasted
                                    focus.clearFocus()
                                    vm.fetch()
                                }
                            },
                            modifier = Modifier.weight(1f),
                        ) { Text("Paste") }
                        Button(
                            onClick = {
                                focus.clearFocus()
                                vm.fetch()
                            },
                            modifier = Modifier.weight(1f),
                        ) { Text("Get media") }
                    }
                }

                when (val s = vm.state) {
                    is UiState.Idle -> item {
                        Note("Share a post from X to this app, or paste its link above.")
                    }

                    is UiState.Loading -> item {
                        Box(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(24.dp),
                            contentAlignment = Alignment.Center,
                        ) { CircularProgressIndicator() }
                    }

                    is UiState.Failed -> item { Note(s.message, error = true) }

                    is UiState.Ready -> {
                        item { AuthorBlock(s.tweet) }
                        if (s.tweet.media.size > 1) {
                            item {
                                Button(
                                    onClick = { onDownloadAll(s.tweet) },
                                    modifier = Modifier.fillMaxWidth(),
                                ) { Text("Download all (${s.tweet.media.size})") }
                            }
                        }
                        itemsIndexed(s.tweet.media) { index, media ->
                            MediaCard(index, media) { variant -> onDownload(s.tweet, index, variant) }
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun Header() {
    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
        Text(
            "X Saver",
            fontSize = 32.sp,
            fontWeight = FontWeight.Bold,
            color = MaterialTheme.colorScheme.onBackground,
        )
        Text(
            "Save photos, videos and GIFs from X posts.",
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

@Composable
private fun Note(text: String, error: Boolean = false) {
    Text(
        text,
        style = MaterialTheme.typography.bodyMedium,
        color = if (error) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurfaceVariant,
    )
}

@Composable
private fun AuthorBlock(tweet: Tweet) {
    val handle = if (tweet.authorHandle.isNotEmpty()) "@${tweet.authorHandle}" else ""
    val who = listOf(tweet.authorName, handle).filter { it.isNotEmpty() }.joinToString("  ")
    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
        if (who.isNotEmpty()) {
            Text(who, fontWeight = FontWeight.SemiBold, color = MaterialTheme.colorScheme.onBackground)
        }
        if (tweet.text.isNotBlank()) {
            Text(
                tweet.text,
                maxLines = 4,
                overflow = TextOverflow.Ellipsis,
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

@Composable
private fun MediaCard(index: Int, media: MediaItem, onDownload: (Variant) -> Unit) {
    var selected by remember(media) { mutableIntStateOf(0) }
    val label = when (media.kind) {
        MediaKind.VIDEO -> "Video"
        MediaKind.GIF -> "GIF"
        MediaKind.PHOTO -> "Photo"
    } + " ${index + 1}"

    Card(
        shape = RoundedCornerShape(20.dp),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant),
    ) {
        Row(
            modifier = Modifier.padding(12.dp),
            horizontalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            AsyncImage(
                model = media.thumbnail,
                contentDescription = label,
                contentScale = ContentScale.Crop,
                modifier = Modifier
                    .size(96.dp)
                    .clip(RoundedCornerShape(14.dp))
                    .background(MaterialTheme.colorScheme.surface),
            )
            Column(
                modifier = Modifier.weight(1f),
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                Text(
                    label,
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.SemiBold,
                    color = MaterialTheme.colorScheme.onSurface,
                )
                if (media.variants.size > 1) {
                    Row(
                        modifier = Modifier.horizontalScroll(rememberScrollState()),
                        horizontalArrangement = Arrangement.spacedBy(6.dp),
                    ) {
                        media.variants.forEachIndexed { i, variant ->
                            FilterChip(
                                selected = i == selected,
                                onClick = { selected = i },
                                label = { Text(variant.label) },
                            )
                        }
                    }
                }
                Button(onClick = { onDownload(media.variants[selected]) }) { Text("Download") }
            }
        }
    }
}
