package org.futo.inputmethod.latin.uix.actions.gif

import android.Manifest
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.provider.Settings
import android.graphics.drawable.Animatable
import android.graphics.drawable.Drawable
import android.widget.ImageView
import android.widget.Toast
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
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
import androidx.compose.foundation.lazy.staggeredgrid.LazyVerticalStaggeredGrid
import androidx.compose.foundation.lazy.staggeredgrid.StaggeredGridCells
import androidx.compose.foundation.lazy.staggeredgrid.StaggeredGridItemSpan
import androidx.compose.foundation.lazy.staggeredgrid.items
import androidx.compose.foundation.lazy.staggeredgrid.rememberLazyStaggeredGridState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Search
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.minimumInteractiveComponentSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalResources
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.core.net.toUri
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.withPermit
import kotlinx.coroutines.withContext
import org.futo.inputmethod.latin.R
import org.futo.inputmethod.latin.common.Constants
import org.futo.inputmethod.latin.content.FONT_AUTHORITY
import org.futo.inputmethod.latin.uix.Action
import org.futo.inputmethod.latin.uix.ActionTextEditor
import org.futo.inputmethod.latin.uix.ActionWindow
import org.futo.inputmethod.latin.uix.CloseResult
import org.futo.inputmethod.latin.uix.DialogRequestItem
import org.futo.inputmethod.latin.uix.KeyboardManagerForAction
import org.futo.inputmethod.latin.uix.LocalKeyboardScheme
import org.futo.inputmethod.latin.uix.SettingsKey
import org.futo.inputmethod.latin.uix.getSetting
import org.futo.inputmethod.latin.uix.setSetting
import org.futo.inputmethod.latin.uix.settings.useDataStore
import org.futo.inputmethod.latin.uix.theme.Typography
import java.util.UUID

/** When on, searches go to RedGIFs (adult content) instead of Klipy */
val GifNsfwEnabled = SettingsKey(booleanPreferencesKey("gif_nsfw_enabled"), false)
/** The user confirmed they are 18 or older; asked once before NSFW is first turned on */
val GifNsfwAgeConfirmed = SettingsKey(booleanPreferencesKey("gif_nsfw_age_confirmed"), false)
val GifKlipyApiKey = SettingsKey(stringPreferencesKey("gif_klipy_api_key"), "")
/** Random ID Klipy asks for to personalise trending results; not tied to anything else */
val GifKlipyCustomerId = SettingsKey(stringPreferencesKey("gif_klipy_customer_id"), "")
/** Optional; adds GIPHY's R-rated GIFs to 18+ results */
val GifGiphyApiKey = SettingsKey(stringPreferencesKey("gif_giphy_api_key"), "")

private object GifSender {
    private suspend fun toast(context: Context, text: String) = withContext(Dispatchers.Main) {
        Toast.makeText(context, text, Toast.LENGTH_SHORT).show()
    }

    /**
     * Picks the best format the target app accepts: GIF if available, else MP4, else an MP4
     * converted to GIF on the device (RedGIFs only has MP4s).
     */
    suspend fun send(manager: KeyboardManagerForAction, result: GifResult, status: (String?) -> Unit) {
        val context = manager.getContext()
        val res = context.resources
        val acceptsGif = manager.appSupportsImageInsertion("image/gif", true)
        val acceptsMp4 = manager.appSupportsImageInsertion("video/mp4", true)

        status(res.getString(R.string.action_gif_status_downloading))
        val file = when {
            result.gif != null && acceptsGif -> GifFiles.download(context, result.gif.url)
            result.mp4 != null && acceptsMp4 -> GifFiles.download(context, result.mp4.url)
            result.mp4 != null && acceptsGif -> {
                val mp4 = GifFiles.download(context, result.mp4.url)
                var lastPercent = -1
                GifFiles.convertMp4ToGif(context, mp4) { progress ->
                    val percent = (progress * 100).toInt()
                    if (percent != lastPercent) {
                        lastPercent = percent
                        status(res.getString(R.string.action_gif_status_converting, percent))
                    }
                }
            }
            else -> {
                toast(context, res.getString(R.string.action_gif_error_app_unsupported))
                return
            }
        }

        val uri = "content://${FONT_AUTHORITY}/gif/${file.name}".toUri()
        withContext(Dispatchers.Main) {
            manager.typeUri(uri, listOf(GifFiles.mimeTypeFor(file.name)), true)
        }
        GifFiles.trimCache(context)
    }

    suspend fun reportError(context: Context, e: Exception) {
        toast(context, context.getString(R.string.action_gif_error_generic, e.message ?: e.javaClass.simpleName))
    }
}

@Composable
private fun GifThumbnail(result: GifResult, onClick: () -> Unit) {
    val context = LocalContext.current
    val targetWidth = with(LocalDensity.current) { 180.dp.roundToPx() }
    val drawable by produceState<Drawable?>(null, result.preview.url) {
        value = try {
            GifFiles.loadPreview(context, result.preview, targetWidth)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            null
        }
    }

    val aspect = if (result.preview.width > 0 && result.preview.height > 0) {
        (result.preview.width.toFloat() / result.preview.height).coerceIn(0.5f, 2.0f)
    } else 1.0f

    Box(
        Modifier
            .fillMaxWidth()
            .aspectRatio(aspect)
            .clip(RoundedCornerShape(8.dp))
            .background(LocalKeyboardScheme.current.keyboardContainer)
            .clickable(onClick = onClick)
    ) {
        drawable?.let { d ->
            DisposableEffect(d) {
                onDispose { (d as? Animatable)?.stop() }
            }
            AndroidView(
                factory = { ImageView(it).apply { scaleType = ImageView.ScaleType.CENTER_CROP } },
                update = {
                    it.setImageDrawable(d)
                    (d as? Animatable)?.start()
                },
                modifier = Modifier.fillMaxSize()
            )
        }

        // RedGIFs previews are stills, so stream the MP4 on top once a playback slot is free
        if (result.source == GifSource.RedGifs && result.mp4 != null) {
            var hasSlot by remember { mutableStateOf(false) }
            var firstFrameShown by remember { mutableStateOf(false) }
            LaunchedEffect(result.mp4.url) {
                VideoPreviewSlots.withPermit {
                    hasSlot = true
                    try {
                        awaitCancellation()
                    } finally {
                        hasSlot = false
                    }
                }
            }

            if (hasSlot) {
                AndroidView(
                    factory = { ctx ->
                        LoopingVideoView(ctx).apply {
                            onFirstFrame = { firstFrameShown = true }
                            setVideo(result.mp4.url)
                        }
                    },
                    onRelease = { it.release() },
                    modifier = Modifier
                        .fillMaxSize()
                        .alpha(if (firstFrameShown) 1.0f else 0.0f)
                )
            }
        }
    }
}

private suspend fun <T> attempt(block: suspend () -> T): Result<T> = try {
    Result.success(block())
} catch (e: CancellationException) {
    throw e
} catch (e: Exception) {
    Result.failure(e)
}

private val Throwable.displayMessage: String get() = message ?: javaClass.simpleName

private fun <T> interleave(a: List<T>, b: List<T>): List<T> = buildList {
    for (i in 0 until maxOf(a.size, b.size)) {
        a.getOrNull(i)?.let(::add)
        b.getOrNull(i)?.let(::add)
    }
}

/** Returns the clipboard text if it looks like an API key, otherwise null */
private fun readKeyFromClipboard(context: Context): String? {
    val clipboard = context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
    val text = clipboard.primaryClip?.takeIf { it.itemCount > 0 }?.getItemAt(0)?.coerceToText(context)?.toString()?.trim()
    return text?.takeIf { it.length >= 8 && it.none(Char::isWhitespace) }
}

@Composable
private fun GiphyKeyRow() {
    val context = LocalContext.current
    val giphyKey = useDataStore(GifGiphyApiKey)
    var message by remember { mutableStateOf<String?>(null) }

    Column(
        Modifier.fillMaxWidth().padding(8.dp),
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        Text(stringResource(R.string.action_gif_giphy_setup), style = Typography.SmallMl, textAlign = TextAlign.Center)
        TextButton(onClick = {
            val key = readKeyFromClipboard(context)
            if (key == null) {
                message = context.getString(R.string.action_gif_klipy_setup_no_key)
            } else {
                giphyKey.setValue(key)
            }
        }) {
            Text(stringResource(R.string.action_gif_giphy_setup_paste))
        }
        message?.let { Text(it, style = Typography.SmallMl, color = MaterialTheme.colorScheme.error) }
    }
}

@Composable
private fun GifResultsGrid(
    nsfw: Boolean,
    query: String,
    apiKey: String,
    giphyKey: String,
    customerId: String,
    onResetKey: () -> Unit,
    onResetGiphyKey: () -> Unit,
    onScrollStarted: () -> Unit,
    onClick: (GifResult) -> Unit
) {
    val useGiphy = nsfw && giphyKey.isNotBlank()

    val results = remember { mutableStateListOf<GifResult>() }
    var page by remember { mutableIntStateOf(0) }
    // In 18+ mode RedGIFs and GIPHY page independently; in normal mode only the first is used
    var primaryHasMore by remember { mutableStateOf(true) }
    var giphyHasMore by remember { mutableStateOf(useGiphy) }
    var loading by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<String?>(null) }
    var giphyError by remember { mutableStateOf<String?>(null) }
    val gridState = rememberLazyStaggeredGridState()

    suspend fun loadNext() {
        if (loading || !(primaryHasMore || giphyHasMore)) return
        loading = true
        error = null
        try {
            val next = page + 1
            val fetched = if (nsfw) {
                val (red, giphy) = coroutineScope {
                    val red = if (primaryHasMore) async { attempt { RedGifsProvider.fetch(query, next) } } else null
                    val giphy = if (giphyHasMore) async { attempt { GiphyProvider.fetch(giphyKey, query, next) } } else null
                    red?.await() to giphy?.await()
                }

                red?.onSuccess { primaryHasMore = it.hasMore && it.results.isNotEmpty() }
                    ?.onFailure { primaryHasMore = false; error = it.displayMessage }
                giphy?.onSuccess { giphyHasMore = it.hasMore && it.results.isNotEmpty() }
                    ?.onFailure { giphyHasMore = false; giphyError = it.displayMessage }

                interleave(red?.getOrNull()?.results.orEmpty(), giphy?.getOrNull()?.results.orEmpty())
            } else {
                val klipy = attempt { KlipyProvider.fetch(apiKey, customerId, query, next) }
                klipy.onSuccess { primaryHasMore = it.hasMore && it.results.isNotEmpty() }
                    .onFailure { error = it.displayMessage }
                klipy.getOrNull()?.results.orEmpty()
            }

            val known = results.mapTo(HashSet()) { it.source.name + it.id }
            results.addAll(fetched.filter { (it.source.name + it.id) !in known })
            if (fetched.isNotEmpty() || error == null) page = next
        } finally {
            loading = false
        }
    }

    LaunchedEffect(Unit) {
        // Wait for the user to stop typing before searching
        if (query.isNotBlank()) delay(400)
        loadNext()
    }

    val nearEnd by remember {
        derivedStateOf {
            val info = gridState.layoutInfo
            val last = info.visibleItemsInfo.lastOrNull()?.index ?: 0
            info.totalItemsCount > 0 && last >= info.totalItemsCount - 8
        }
    }
    LaunchedEffect(nearEnd, results.size) {
        if (nearEnd && page > 0) loadNext()
    }

    LaunchedEffect(gridState.isScrollInProgress) {
        if (gridState.isScrollInProgress) onScrollStarted()
    }

    LazyVerticalStaggeredGrid(
        columns = StaggeredGridCells.Adaptive(140.dp),
        state = gridState,
        modifier = Modifier.fillMaxSize().padding(horizontal = 4.dp),
        verticalItemSpacing = 4.dp,
        horizontalArrangement = Arrangement.spacedBy(4.dp)
    ) {
        if (nsfw && giphyKey.isBlank()) {
            item(span = StaggeredGridItemSpan.FullLine) { GiphyKeyRow() }
        }

        items(results, key = { it.source.name + it.id }) { result ->
            GifThumbnail(result) { onClick(result) }
        }

        item(span = StaggeredGridItemSpan.FullLine) {
            Column(
                Modifier.fillMaxWidth().padding(12.dp),
                horizontalAlignment = Alignment.CenterHorizontally
            ) {
                when {
                    loading -> CircularProgressIndicator(Modifier.size(24.dp))
                    error != null -> {
                        Text(
                            stringResource(R.string.action_gif_error_generic, error!!),
                            style = Typography.SmallMl,
                            color = MaterialTheme.colorScheme.error,
                            textAlign = TextAlign.Center
                        )
                        if (!nsfw) {
                            TextButton(onClick = onResetKey) {
                                Text(stringResource(R.string.action_gif_klipy_change_key))
                            }
                        }
                    }
                    results.isEmpty() -> Text(
                        stringResource(R.string.action_emoji_search_no_results_found),
                        style = Typography.SmallMl
                    )
                    else -> Text(
                        stringResource(
                            when {
                                !nsfw -> R.string.action_gif_powered_by_klipy
                                useGiphy -> R.string.action_gif_powered_by_redgifs_giphy
                                else -> R.string.action_gif_powered_by_redgifs
                            }
                        ),
                        style = Typography.SmallMl,
                        modifier = Modifier.alpha(0.6f)
                    )
                }

                if (nsfw && giphyError != null) {
                    Text(
                        stringResource(R.string.action_gif_giphy_error, giphyError!!),
                        style = Typography.SmallMl,
                        color = MaterialTheme.colorScheme.error,
                        textAlign = TextAlign.Center
                    )
                    TextButton(onClick = onResetGiphyKey) {
                        Text(stringResource(R.string.action_gif_giphy_change_key))
                    }
                }
            }
        }
    }
}

@Composable
private fun KlipyKeySetup() {
    val context = LocalContext.current
    val apiKey = useDataStore(GifKlipyApiKey)
    var message by remember { mutableStateOf<String?>(null) }

    Column(
        Modifier.fillMaxSize().padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp, Alignment.CenterVertically),
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        Text(stringResource(R.string.action_gif_klipy_setup_title), style = Typography.Body.Medium, textAlign = TextAlign.Center)
        Text(stringResource(R.string.action_gif_klipy_setup_body), style = Typography.SmallMl, textAlign = TextAlign.Center)
        Button(onClick = {
            val key = readKeyFromClipboard(context)
            if (key == null) {
                message = context.getString(R.string.action_gif_klipy_setup_no_key)
            } else {
                apiKey.setValue(key)
            }
        }) {
            Text(stringResource(R.string.action_gif_klipy_setup_paste))
        }
        message?.let { Text(it, style = Typography.SmallMl, color = MaterialTheme.colorScheme.error) }
        Text(stringResource(R.string.action_gif_klipy_setup_nsfw_hint), style = Typography.SmallMl, modifier = Modifier.alpha(0.7f), textAlign = TextAlign.Center)
    }
}

/** Some Android versions let the user switch off INTERNET per app; GIF search can't work without it */
private fun Context.hasNetworkPermission(): Boolean =
    checkSelfPermission(Manifest.permission.INTERNET) == PackageManager.PERMISSION_GRANTED

@Composable
private fun NetworkPermissionNeeded(manager: KeyboardManagerForAction) {
    val context = LocalContext.current
    Column(
        Modifier.fillMaxSize().padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp, Alignment.CenterVertically),
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        Text(stringResource(R.string.action_gif_network_permission_title), style = Typography.Body.Medium, textAlign = TextAlign.Center)
        Text(stringResource(R.string.action_gif_network_permission_body), style = Typography.SmallMl, textAlign = TextAlign.Center)
        Button(onClick = {
            val intent = Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, "package:${context.packageName}".toUri())
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            context.startActivity(intent)
            manager.closeActionWindow()
        }) {
            Text(stringResource(R.string.action_gif_network_permission_button))
        }
    }
}

@Composable
private fun NsfwToggle(manager: KeyboardManagerForAction) {
    val resources = LocalResources.current
    val nsfw = useDataStore(GifNsfwEnabled)
    val confirmed = useDataStore(GifNsfwAgeConfirmed)

    Surface(
        color = if (nsfw.value) MaterialTheme.colorScheme.error else LocalKeyboardScheme.current.keyboardContainer,
        contentColor = if (nsfw.value) MaterialTheme.colorScheme.onError else LocalKeyboardScheme.current.onKeyboardContainer,
        shape = RoundedCornerShape(24.dp),
        modifier = Modifier.minimumInteractiveComponentSize().padding(2.dp),
        onClick = {
            if (nsfw.value) {
                nsfw.setValue(false)
            } else if (confirmed.value) {
                nsfw.setValue(true)
            } else {
                manager.requestDialog(
                    resources.getString(R.string.action_gif_nsfw_confirm),
                    listOf(
                        DialogRequestItem(resources.getString(R.string.action_emoji_clear_recent_emojis_cancel)) {},
                        DialogRequestItem(resources.getString(R.string.action_gif_nsfw_confirm_yes)) {
                            manager.getLifecycleScope().launch {
                                manager.getContext().setSetting(GifNsfwAgeConfirmed, true)
                                manager.getContext().setSetting(GifNsfwEnabled, true)
                            }
                        }
                    ),
                    {}
                )
            }
        }
    ) {
        Text(
            stringResource(R.string.action_gif_nsfw_toggle),
            style = Typography.SmallMl,
            modifier = Modifier.padding(horizontal = 12.dp, vertical = 8.dp)
        )
    }
}

val GifAction = Action(
    icon = R.drawable.gif,
    name = R.string.action_gif_title,
    canShowKeyboard = true,
    simplePressImpl = null,
    windowImpl = { manager, _ ->
        object : ActionWindow() {
            private val searchText = mutableStateOf("")
            private val searching = mutableStateOf(false)
            /** The search box has the keyboard; false while browsing results with the keyboard hidden */
            private val editing = mutableStateOf(false)
            private val sendStatus = mutableStateOf<String?>(null)
            private var sendJob: Job? = null

            /** Hides the keyboard so the results get the full panel (on Enter or when scrolling) */
            private fun finishEditing() {
                if (!editing.value) return
                editing.value = false
                manager.forceActionWindowAboveKeyboard(false)
            }

            private fun send(result: GifResult) {
                if (sendJob?.isActive == true) return
                sendJob = manager.getLifecycleScope().launch {
                    try {
                        GifSender.send(manager, result) { sendStatus.value = it }
                    } catch (e: CancellationException) {
                        throw e
                    } catch (e: Exception) {
                        GifSender.reportError(manager.getContext(), e)
                    } finally {
                        sendStatus.value = null
                    }
                }
            }

            override fun close(): CloseResult {
                sendJob?.cancel()
                return CloseResult.Default
            }

            @Composable
            override fun windowName(): String = stringResource(R.string.action_gif_title)

            @Composable
            override fun WindowContents(keyboardShown: Boolean) {
                val view = LocalView.current
                val context = LocalContext.current
                val nsfw = useDataStore(GifNsfwEnabled)
                val apiKey = useDataStore(GifKlipyApiKey)
                val giphyKey = useDataStore(GifGiphyApiKey)
                val customerId = remember {
                    context.getSetting(GifKlipyCustomerId).ifEmpty {
                        UUID.randomUUID().toString().also {
                            manager.getLifecycleScope().launch { context.setSetting(GifKlipyCustomerId, it) }
                        }
                    }
                }
                val query = if (searching.value) searchText.value.trim() else ""

                val hasNetwork = remember { context.hasNetworkPermission() }

                Box(Modifier.fillMaxSize()) {
                    if (!hasNetwork) {
                        NetworkPermissionNeeded(manager)
                    } else if (!nsfw.value && apiKey.value.isBlank()) {
                        KlipyKeySetup()
                    } else {
                        key(nsfw.value, query, apiKey.value, giphyKey.value) {
                            GifResultsGrid(
                                nsfw = nsfw.value,
                                query = query,
                                apiKey = apiKey.value,
                                giphyKey = giphyKey.value,
                                customerId = customerId,
                                onResetKey = { apiKey.setValue("") },
                                onResetGiphyKey = { giphyKey.setValue("") },
                                onScrollStarted = { finishEditing() }
                            ) {
                                manager.performHapticAndAudioFeedback(Constants.CODE_EMOJI, view)
                                send(it)
                            }
                        }
                    }

                    sendStatus.value?.let { status ->
                        Box(
                            Modifier
                                .fillMaxSize()
                                .background(MaterialTheme.colorScheme.background.copy(alpha = 0.7f))
                                .clickable(enabled = true) { },
                            contentAlignment = Alignment.Center
                        ) {
                            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                                CircularProgressIndicator()
                                Spacer(Modifier.height(8.dp))
                                Text(status, style = Typography.SmallMl)
                                TextButton(onClick = { sendJob?.cancel() }) {
                                    Text(stringResource(R.string.action_emoji_clear_recent_emojis_cancel))
                                }
                            }
                        }
                    }
                }
            }

            @Composable
            override fun WindowTitleBar(rowScope: RowScope) {
                if (searching.value) {
                    val placeholder = stringResource(R.string.action_gif_search_placeholder)
                    val boxModifier = with(rowScope) {
                        Modifier
                            .weight(1.0f)
                            .minimumInteractiveComponentSize()
                            .padding(2.dp)
                    }
                    if (editing.value) {
                        // Typing: the search box takes over the keyboard, which shows below
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
                        // Browsing: keyboard hidden, tap the query to edit it again
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
                            Icon(painterResource(R.drawable.close), contentDescription = stringResource(R.string.action_gif_clear_search))
                        }
                    }
                    NsfwToggle(manager)
                } else {
                    super.WindowTitleBar(rowScope)
                    NsfwToggle(manager)
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
                                    stringResource(R.string.action_emoji_search_for_emojis),
                                    style = Typography.SmallMl,
                                    modifier = Modifier
                                        .alpha(0.75f)
                                        .align(Alignment.CenterVertically)
                                )
                            }
                        }
                    }
                }
            }
        }
    }
)
