package org.futo.inputmethod.latin.uix.actions.kitchen

import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Paint
import android.provider.Settings
import android.view.HapticFeedbackConstants
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.awaitLongPressOrCancellation
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.lazy.grid.rememberLazyGridState
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Search
import androidx.compose.material3.Button
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.minimumInteractiveComponentSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.MutableState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.composed
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.painter.BitmapPainter
import androidx.compose.ui.graphics.painter.Painter
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.net.toUri
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.futo.inputmethod.latin.R
import org.futo.inputmethod.latin.common.Constants
import org.futo.inputmethod.latin.uix.Action
import org.futo.inputmethod.latin.uix.ActionTextEditor
import org.futo.inputmethod.latin.uix.ActionWindow
import org.futo.inputmethod.latin.uix.KeyboardManagerForAction
import org.futo.inputmethod.latin.uix.LocalKeyboardScheme
import org.futo.inputmethod.latin.uix.SettingsKey
import org.futo.inputmethod.latin.uix.settings.useDataStore
import org.futo.inputmethod.latin.uix.settings.useDataStoreValue
import org.futo.inputmethod.latin.uix.theme.Typography

private const val MOST_USED_SHOWN = 20

@Composable
private fun KitchenCell(set: KitchenSet, entry: KitchenEntry, onClick: (KitchenEntry) -> Unit) {
    val context = LocalContext.current
    val bitmap by produceState<ImageBitmap?>(null, set, entry.file) {
        value = withContext(Dispatchers.IO) { set.loadThumbnail(context, entry) }
    }

    Box(
        Modifier
            .aspectRatio(1.0f)
            .clickable { onClick(entry) }
            .padding(4.dp),
        contentAlignment = Alignment.Center
    ) {
        bitmap?.let {
            Image(it, contentDescription = "${set.animal.name} + ${entry.name}", modifier = Modifier.fillMaxSize())
        }
    }
}

@Composable
private fun KitchenGrid(
    manager: KeyboardManagerForAction,
    set: KitchenSet,
    entries: List<KitchenEntry>,
    searchFilter: String,
    isSearching: Boolean,
    replaceSetting: SettingsKey<Boolean>,
    replaceLabel: Int,
    onScrollStarted: () -> Unit
) {
    val view = LocalView.current
    val gridState = rememberLazyGridState()
    LaunchedEffect(gridState.isScrollInProgress) {
        if (gridState.isScrollInProgress) onScrollStarted()
    }
    val replaceEmojiTaps = useDataStore(replaceSetting)
    val supportsImages = remember { manager.appSupportsImageInsertion(KITCHEN_MIME_TYPE, true) }

    val shown = remember(entries, searchFilter, isSearching) {
        if (isSearching && searchFilter.isNotBlank()) {
            set.search(entries, searchFilter)
        } else {
            entries
        }
    }

    val context = LocalContext.current
    val mostUsed by produceState(emptyList<KitchenEntry>(), set) {
        value = withContext(Dispatchers.IO) { set.mostUsed(context, MOST_USED_SHOWN) }
    }
    val preview = remember { mutableStateOf<KitchenEntry?>(null) }
    val onClick: (KitchenEntry) -> Unit = {
        set.send(manager, it)
        manager.performHapticAndAudioFeedback(Constants.CODE_EMOJI, view)
    }

    Box(Modifier.fillMaxSize()) {
        Column(Modifier.fillMaxSize()) {
            if (!isSearching && mostUsed.isNotEmpty()) {
                MostUsedBar(set, mostUsed, preview, onClick)
            }

            if (!isSearching) {
                Row(
                    Modifier
                        .fillMaxWidth()
                        .clickable { replaceEmojiTaps.setValue(!replaceEmojiTaps.value) }
                        .padding(horizontal = 12.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text(
                        stringResource(replaceLabel, set.animal.name.lowercase()),
                        style = Typography.SmallMl,
                        modifier = Modifier.weight(1.0f)
                    )
                    Switch(checked = replaceEmojiTaps.value, onCheckedChange = { replaceEmojiTaps.setValue(it) })
                }
            }

            if (!supportsImages) {
                Text(
                    stringResource(R.string.action_clipboard_manager_error_app_image_insertion_unsupported),
                    style = Typography.SmallMl,
                    color = MaterialTheme.colorScheme.error,
                    modifier = Modifier.padding(horizontal = 12.dp, vertical = 4.dp)
                )
            }

            if (shown.isEmpty()) {
                Text(
                    stringResource(R.string.action_emoji_search_no_results_found),
                    style = Typography.SmallMl,
                    textAlign = TextAlign.Center,
                    modifier = Modifier.fillMaxWidth().padding(16.dp)
                )
            }

            LazyVerticalGrid(
                columns = GridCells.Adaptive(56.dp),
                state = gridState,
                modifier = Modifier
                    .fillMaxWidth()
                    .weight(1.0f)
                    .padding(horizontal = 4.dp)
                    .pressAndHoldPreview(shown, preview) { pos ->
                        gridState.layoutInfo.visibleItemsInfo.firstOrNull {
                            pos.x >= it.offset.x && pos.x < it.offset.x + it.size.width &&
                                pos.y >= it.offset.y && pos.y < it.offset.y + it.size.height
                        }?.let { shown.getOrNull(it.index) }
                    }
            ) {
                items(shown, key = { it.file }) { entry ->
                    KitchenCell(set, entry, onClick)
                }
            }
        }

        preview.value?.let { KitchenPreview(set, it) }
    }
}

/** A scrolling strip of the most used combinations, above the full grid */
@Composable
private fun MostUsedBar(
    set: KitchenSet,
    entries: List<KitchenEntry>,
    preview: MutableState<KitchenEntry?>,
    onClick: (KitchenEntry) -> Unit
) {
    val rowState = rememberLazyListState()
    Text(
        stringResource(R.string.action_kitchen_most_used),
        style = Typography.SmallMl,
        modifier = Modifier
            .padding(start = 12.dp, top = 4.dp)
            .alpha(0.75f)
    )
    LazyRow(
        state = rowState,
        modifier = Modifier
            .fillMaxWidth()
            .height(52.dp)
            .padding(horizontal = 4.dp)
            .pressAndHoldPreview(entries, preview) { pos ->
                rowState.layoutInfo.visibleItemsInfo.firstOrNull {
                    pos.x >= it.offset && pos.x < it.offset + it.size
                }?.let { entries.getOrNull(it.index) }
            }
    ) {
        items(entries, key = { it.file }) { entry ->
            Box(Modifier.size(52.dp)) {
                KitchenCell(set, entry, onClick)
            }
        }
    }
}

/**
 * Telegram-style sticker peek: hold an image to show it large, slide to preview others,
 * lift to close without sending. Taps and scrolling work as before.
 */
private fun Modifier.pressAndHoldPreview(
    shown: List<KitchenEntry>,
    preview: MutableState<KitchenEntry?>,
    entryAt: (Offset) -> KitchenEntry?
): Modifier = composed {
    val view = LocalView.current
    pointerInput(shown) {
        awaitEachGesture {
            val down = awaitFirstDown(requireUnconsumed = false)
            // Cancelled if the list starts scrolling or the finger lifts first (a normal tap)
            val hold = awaitLongPressOrCancellation(down.id) ?: return@awaitEachGesture
            preview.value = entryAt(hold.position) ?: return@awaitEachGesture
            view.performHapticFeedback(HapticFeedbackConstants.LONG_PRESS)

            try {
                while (true) {
                    val event = awaitPointerEvent(PointerEventPass.Initial)
                    val change = event.changes.firstOrNull { it.id == down.id } ?: break
                    // Ours now: keeps the list from scrolling and the cell from sending on release
                    change.consume()
                    if (!change.pressed) break
                    entryAt(change.position)?.let {
                        if (it != preview.value) {
                            preview.value = it
                            view.performHapticFeedback(HapticFeedbackConstants.CLOCK_TICK)
                        }
                    }
                }
            } finally {
                preview.value = null
            }
        }
    }
}

@Composable
private fun KitchenPreview(set: KitchenSet, entry: KitchenEntry) {
    val context = LocalContext.current
    val full by produceState<ImageBitmap?>(null, set, entry.file) {
        value = withContext(Dispatchers.IO) { set.loadFull(context, entry) }
    }
    val image = full ?: set.loadThumbnail(context, entry)

    BoxWithConstraints(
        Modifier
            .fillMaxSize()
            .background(Color.Black.copy(alpha = 0.55f)),
        contentAlignment = Alignment.Center
    ) {
        val side = minOf(maxWidth, maxHeight - 32.dp, 280.dp)
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            image?.let {
                Image(it, contentDescription = "${set.animal.name} + ${entry.name}", modifier = Modifier.size(side))
            }
            Text(
                "${entry.emoji}  ${entry.name}",
                style = Typography.SmallMl,
                color = Color.White,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.padding(horizontal = 16.dp)
            )
        }
    }
}

/** Draws an emoji as a picture, for the toolbar button */
private fun emojiPainter(emoji: String): Painter {
    val size = 96
    val bitmap = Bitmap.createBitmap(size, size, Bitmap.Config.ARGB_8888)
    val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        textSize = size * 0.82f
        textAlign = Paint.Align.CENTER
    }
    val y = size / 2f - (paint.descent() + paint.ascent()) / 2f
    Canvas(bitmap).drawText(emoji, size / 2f, y, paint)
    return BitmapPainter(bitmap.asImageBitmap())
}

/** Shown in place of the grid until the animal's images are on the phone */
@Composable
private fun DownloadPanel(set: KitchenSet) {
    val context = LocalContext.current
    val animal = set.animal
    val state = KitchenDownloads.states[set.key]
    Column(
        Modifier
            .fillMaxSize()
            .padding(horizontal = 24.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center
    ) {
        Text(animal.emoji, fontSize = 40.sp)
        Text(
            stringResource(R.string.kitchen_download_body, animal.name.lowercase(), animal.count, set.approximateMegabytes),
            style = Typography.SmallMl,
            textAlign = TextAlign.Center,
            modifier = Modifier.padding(vertical = 8.dp)
        )
        when {
            state is KitchenDownloads.State.Running -> {
                LinearProgressIndicator(
                    progress = { state.done.toFloat() / state.total.coerceAtLeast(1) },
                    modifier = Modifier.fillMaxWidth()
                )
                Text(
                    stringResource(R.string.kitchen_download_progress, state.done, state.total),
                    style = Typography.SmallMl,
                    modifier = Modifier.padding(top = 4.dp)
                )
            }
            !KitchenDownloads.hasNetworkPermission(context) -> {
                Text(
                    stringResource(R.string.kitchen_download_no_network),
                    style = Typography.SmallMl,
                    color = MaterialTheme.colorScheme.error,
                    textAlign = TextAlign.Center
                )
                OutlinedButton(onClick = {
                    val intent = Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, "package:${context.packageName}".toUri())
                        .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                    context.startActivity(intent)
                }) {
                    Text(stringResource(R.string.action_gif_network_permission_button))
                }
            }
            else -> {
                if (state is KitchenDownloads.State.Failed) {
                    Text(
                        stringResource(R.string.kitchen_download_failed, state.message),
                        style = Typography.SmallMl,
                        color = MaterialTheme.colorScheme.error,
                        textAlign = TextAlign.Center
                    )
                }
                Button(onClick = { KitchenDownloads.start(context, set) }) {
                    Text(stringResource(R.string.kitchen_download_button, animal.name.lowercase()))
                }
            }
        }
    }
}

/**
 * The animal panel: every Emoji Kitchen combination of the chosen animal. Registered under the
 * "cow_kitchen" key, from when the cow was the only animal, so existing action layouts keep it.
 */
val AnimalKitchenAction = Action(
    icon = R.drawable.cow,
    name = R.string.action_animal_kitchen_title,
    canShowKeyboard = true,
    simplePressImpl = null,
    iconPainter = {
        val context = LocalContext.current
        val key = useDataStoreValue(KitchenAnimalSetting)
        val emoji = remember(key) { (KitchenAnimals.ready(context, key) ?: KitchenAnimals.active(context)).emoji }
        remember(emoji) { emojiPainter(emoji) }
    },
    windowImpl = { manager, _ ->
        val set = KitchenSet.active(manager.getContext())
        object : ActionWindow() {
            private val searchText = mutableStateOf("")
            private val searching = mutableStateOf(false)
            /** The search box has the keyboard; false while browsing results with the keyboard hidden */
            private val editing = mutableStateOf(false)

            /** Hides the keyboard so the results get the full panel (on Enter or when scrolling) */
            private fun finishEditing() {
                if (!editing.value) return
                editing.value = false
                manager.forceActionWindowAboveKeyboard(false)
            }

            @Composable
            override fun windowName(): String {
                return stringResource(R.string.action_kitchen_window_title, set.animal.name)
            }

            @Composable
            override fun WindowContents(keyboardShown: Boolean) {
                val context = LocalContext.current
                // re-read when a download finishes
                val available = KitchenDownloads.states[set.key] == null && set.isAvailable(context)
                if (!available) {
                    DownloadPanel(set)
                    return
                }

                val entries by produceState<List<KitchenEntry>?>(null) {
                    value = withContext(Dispatchers.IO) { set.getEntries(context) }
                }

                entries?.let {
                    KitchenGrid(
                        manager, set, it, searchText.value, searching.value,
                        KitchenReplaceEmojiTaps, R.string.action_kitchen_replace_emoji_taps,
                        onScrollStarted = { finishEditing() }
                    )
                }
            }

            @Composable
            override fun WindowTitleBar(rowScope: RowScope) {
                if (searching.value) {
                    val placeholder = stringResource(R.string.action_kitchen_search_placeholder)
                    val boxModifier = with(rowScope) {
                        Modifier
                            .weight(1.0f)
                            .minimumInteractiveComponentSize()
                            .padding(2.dp)
                    }
                    if (editing.value) {
                        Surface(
                            color = LocalKeyboardScheme.current.keyboardContainer,
                            contentColor = LocalKeyboardScheme.current.onKeyboardContainer,
                            shape = RoundedCornerShape(24.dp),
                            modifier = boxModifier
                        ) {
                            Box(modifier = Modifier.padding(8.dp), contentAlignment = Alignment.CenterStart) {
                                ActionTextEditor(
                                    text = searchText,
                                    placeholder = placeholder,
                                    onEnter = { finishEditing() }
                                )
                            }
                        }
                    } else {
                        Surface(
                            color = LocalKeyboardScheme.current.keyboardContainer,
                            contentColor = LocalKeyboardScheme.current.onKeyboardContainer,
                            shape = RoundedCornerShape(24.dp),
                            modifier = boxModifier,
                            onClick = { editing.value = true }
                        ) {
                            Box(modifier = Modifier.padding(8.dp), contentAlignment = Alignment.CenterStart) {
                                Row(verticalAlignment = Alignment.CenterVertically) {
                                    Icon(Icons.Default.Search, contentDescription = null)
                                    Spacer(Modifier.width(4.dp))
                                    Text(
                                        searchText.value.ifBlank { placeholder },
                                        style = Typography.SmallMl,
                                        maxLines = 1,
                                        overflow = TextOverflow.Ellipsis
                                    )
                                }
                            }
                        }
                        IconButton(onClick = {
                            searching.value = false
                            searchText.value = ""
                        }) {
                            Icon(painterResource(R.drawable.close), contentDescription = stringResource(R.string.action_kitchen_clear_search))
                        }
                    }
                } else {
                    super.WindowTitleBar(rowScope)
                    Surface(
                        color = LocalKeyboardScheme.current.keyboardContainer,
                        contentColor = LocalKeyboardScheme.current.onKeyboardContainer,
                        shape = RoundedCornerShape(24.dp),
                        modifier = Modifier
                            .minimumInteractiveComponentSize()
                            .padding(2.dp)
                            .width(128.dp),
                        onClick = {
                            searching.value = true
                            editing.value = true
                        }
                    ) {
                        Box(modifier = Modifier.padding(8.dp), contentAlignment = Alignment.CenterStart) {
                            Row {
                                Icon(Icons.Default.Search, contentDescription = null)
                                Text(
                                    stringResource(R.string.action_emoji_search_for_emojis), style = Typography.SmallMl, modifier = Modifier
                                        .alpha(0.75f)
                                        .align(Alignment.CenterVertically))
                            }
                        }
                    }
                }
            }
        }
    }
)
