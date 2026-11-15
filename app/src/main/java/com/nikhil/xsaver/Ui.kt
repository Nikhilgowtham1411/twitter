package com.nikhil.xsaver

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.widget.Toast
import androidx.compose.foundation.background
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
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
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.FilterChip
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.platform.LocalContext
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
    onPanelToggle: (Boolean) -> Unit,
    onBatterySettings: () -> Unit,
) {
    val context = LocalContext.current
    val clipboard = LocalClipboardManager.current
    val focus = LocalFocusManager.current
    val history by DownloadStore.items.collectAsState()
    val links by LinkStore.links.collectAsState()

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
                        ) { Text("Save link") }
                        if (vm.input.isNotEmpty() || vm.error != null) {
                            TextButton(onClick = { vm.reset() }) { Text("Clear") }
                        }
                    }
                }

                if (vm.loading > 0) {
                    item {
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(10.dp),
                        ) {
                            CircularProgressIndicator(modifier = Modifier.size(18.dp), strokeWidth = 2.dp)
                            Text(
                                if (vm.loading == 1) "Looking up the link…" else "Looking up ${vm.loading} links…",
                                style = MaterialTheme.typography.bodyMedium,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                    }
                }

                val err = vm.error
                if (err != null) item { Note(err, error = true) }

                item { PanelCard(vm.panelOn, onPanelToggle) }

                if (!vm.batteryOk) item { BatteryCard(onBatterySettings) }

                if (links.isEmpty() && history.isEmpty() && vm.loading == 0 && err == null) {
                    item {
                        Note(
                            "Share a post from X to this app, or paste its link above. The link is kept here " +
                                "with every quality and how much data it uses. Pick \"X Saver · Quick save\" in " +
                                "the share sheet to download straight away without opening the app."
                        )
                    }
                }

                if (links.isNotEmpty()) {
                    item { SectionTitle("Saved links") }
                    items(links, key = { it.tweet.id }) { link ->
                        LinkCard(
                            link = link,
                            onDownload = { index, variant -> onDownload(link.tweet, index, variant) },
                            onDownloadAll = { onDownloadAll(link.tweet) },
                            onRemove = { LinkStore.remove(link.tweet.id) },
                        )
                    }
                }

                if (history.isNotEmpty()) {
                    item {
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(top = 10.dp),
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            Text(
                                "Downloads",
                                style = MaterialTheme.typography.titleLarge,
                                fontWeight = FontWeight.Bold,
                                color = MaterialTheme.colorScheme.onBackground,
                            )
                            if (history.any { !it.isActive }) {
                                TextButton(onClick = { DownloadStore.clearFinished() }) { Text("Clear finished") }
                            }
                        }
                    }
                    items(history, key = { it.id }) { item ->
                        HistoryRow(
                            item = item,
                            onOpen = { openFile(context, item) },
                            onCancel = { DownloadStore.requestCancel(item.id) },
                            onRetry = { DownloadService.retry(context, item) },
                        )
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
private fun SectionTitle(text: String) {
    Text(
        text,
        modifier = Modifier.padding(top = 10.dp),
        style = MaterialTheme.typography.titleLarge,
        fontWeight = FontWeight.Bold,
        color = MaterialTheme.colorScheme.onBackground,
    )
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
private fun PanelCard(on: Boolean, onToggle: (Boolean) -> Unit) {
    Card(
        shape = RoundedCornerShape(16.dp),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant),
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(14.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Column(modifier = Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                Text("Notification panel", fontWeight = FontWeight.SemiBold, color = MaterialTheme.colorScheme.onSurface)
                Text(
                    "Adds a panel to your notification shade. Copy a link, tap Paste & save (or type it there) " +
                        "and it appears in Saved links, ready to download. No need to open the app.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            Switch(checked = on, onCheckedChange = onToggle)
        }
    }
}

@Composable
private fun BatteryCard(onClick: () -> Unit) {
    Card(
        shape = RoundedCornerShape(16.dp),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant),
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(14.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Text("Downloads stopping midway?", fontWeight = FontWeight.SemiBold, color = MaterialTheme.colorScheme.onSurface)
            Text(
                "Some phones pause apps in the background to save battery. Allow X Saver to run in the " +
                    "background so big downloads finish even with the screen off.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            OutlinedButton(onClick = onClick) { Text("Allow background downloads") }
        }
    }
}

@Composable
private fun LinkCard(
    link: SavedLink,
    onDownload: (Int, Variant) -> Unit,
    onDownloadAll: () -> Unit,
    onRemove: () -> Unit,
) {
    val tweet = link.tweet
    val handle = if (tweet.authorHandle.isNotEmpty()) "@${tweet.authorHandle}" else ""
    val who = listOf(tweet.authorName, handle).filter { it.isNotEmpty() }.joinToString("  ")

    Card(
        shape = RoundedCornerShape(20.dp),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant),
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(14.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Row(verticalAlignment = Alignment.Top, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Column(modifier = Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    if (who.isNotEmpty()) {
                        Text(who, fontWeight = FontWeight.SemiBold, color = MaterialTheme.colorScheme.onSurface)
                    }
                    if (tweet.text.isNotBlank()) {
                        Text(
                            tweet.text,
                            maxLines = 3,
                            overflow = TextOverflow.Ellipsis,
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }
                TextButton(onClick = onRemove) { Text("Remove") }
            }

            if (tweet.media.size > 1) {
                val totalSize = tweet.media.sumOf { it.variants.first().size }
                val sizeText = if (totalSize > 0) " · ${formatSize(totalSize)}" else ""
                Button(onClick = onDownloadAll, modifier = Modifier.fillMaxWidth()) {
                    Text("Download all (${tweet.media.size}) in best quality$sizeText")
                }
            }

            tweet.media.forEachIndexed { index, media ->
                MediaRow(
                    tweetId = tweet.id,
                    index = index,
                    media = media,
                    doneLabel = link.downloaded[index],
                    onDownload = { variant -> onDownload(index, variant) },
                )
            }
        }
    }
}

private fun chipLabel(v: Variant): String =
    if (v.size > 0) "${v.label} · ${formatSize(v.size)}" else v.label

@Composable
private fun MediaRow(
    tweetId: String,
    index: Int,
    media: MediaItem,
    doneLabel: String?,
    onDownload: (Variant) -> Unit,
) {
    var selected by rememberSaveable(tweetId, index) { mutableStateOf(0) }
    val variant = media.variants[selected.coerceIn(0, media.variants.lastIndex)]
    val label = when (media.kind) {
        MediaKind.VIDEO -> "Video"
        MediaKind.GIF -> "GIF"
        MediaKind.PHOTO -> "Photo"
    } + " ${index + 1}"

    Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
        AsyncImage(
            model = media.thumbnail,
            contentDescription = label,
            contentScale = ContentScale.Crop,
            modifier = Modifier
                .size(88.dp)
                .clip(RoundedCornerShape(14.dp))
                .background(MaterialTheme.colorScheme.surface),
        )
        Column(modifier = Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(8.dp)) {
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
                    media.variants.forEachIndexed { i, v ->
                        FilterChip(
                            selected = i == selected,
                            onClick = { selected = i },
                            label = { Text(chipLabel(v)) },
                        )
                    }
                }
            }
            if (variant.size > 0) {
                Text(
                    "Uses about ${formatSize(variant.size)} of data",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            Button(onClick = { onDownload(variant) }) {
                Text(if (doneLabel == null) "Download" else "Download again")
            }
            if (doneLabel != null) {
                Text(
                    "Saved before: $doneLabel. Pick another quality to get it again.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
    }
}

@Composable
private fun HistoryRow(
    item: HistoryItem,
    onOpen: () -> Unit,
    onCancel: () -> Unit,
    onRetry: () -> Unit,
) {
    val failed = item.status == Status.FAILED
    Card(
        shape = RoundedCornerShape(16.dp),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant),
    ) {
        Row(
            modifier = Modifier.padding(10.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            AsyncImage(
                model = item.thumb,
                contentDescription = null,
                contentScale = ContentScale.Crop,
                modifier = Modifier
                    .size(56.dp)
                    .clip(RoundedCornerShape(10.dp))
                    .background(MaterialTheme.colorScheme.surface),
            )
            Column(
                modifier = Modifier.weight(1f),
                verticalArrangement = Arrangement.spacedBy(4.dp),
            ) {
                Text(
                    item.title,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    fontWeight = FontWeight.SemiBold,
                    color = MaterialTheme.colorScheme.onSurface,
                )
                Text(
                    statusText(item),
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                    style = MaterialTheme.typography.bodySmall,
                    color = if (failed) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurfaceVariant,
                )
                if (item.status == Status.DOWNLOADING && item.progress > 0) {
                    LinearProgressIndicator(
                        progress = item.progress / 100f,
                        modifier = Modifier.fillMaxWidth(),
                    )
                } else if (item.status == Status.DOWNLOADING || item.status == Status.FETCHING) {
                    LinearProgressIndicator(modifier = Modifier.fillMaxWidth())
                }
            }
            when {
                item.status == Status.DONE -> TextButton(onClick = onOpen) { Text("Open") }
                item.isActive -> TextButton(onClick = onCancel) { Text("Cancel") }
                item.url != null -> TextButton(onClick = onRetry) { Text("Retry") }
            }
        }
    }
}

private fun statusText(item: HistoryItem): String = when (item.status) {
    Status.FETCHING -> "Looking up the post…"
    Status.QUEUED -> "Waiting in queue"
    Status.DOWNLOADING -> when {
        item.error != null -> item.error
        item.progress > 0 -> "${item.progress}% · ${formatSize(item.bytes)}"
        else -> "Starting… ${formatSize(item.bytes)}"
    }
    Status.DONE -> {
        val folder = if (item.kind == MediaKind.PHOTO) "Pictures/XSaver" else "Movies/XSaver"
        "Saved · $folder"
    }
    Status.FAILED -> "Failed: ${item.error ?: "unknown error"}"
    Status.CANCELLED -> "Cancelled"
}

private fun openFile(context: Context, item: HistoryItem) {
    val uri = item.uri?.let { Uri.parse(it) } ?: return
    val intent = Intent(Intent.ACTION_VIEW)
        .setDataAndType(uri, item.mime ?: "*/*")
        .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
    try {
        context.startActivity(intent)
    } catch (e: Exception) {
        Toast.makeText(context, "Couldn't open it. The file may have been moved or deleted.", Toast.LENGTH_SHORT).show()
    }
}
