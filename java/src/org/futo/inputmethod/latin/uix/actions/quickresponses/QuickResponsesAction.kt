package org.futo.inputmethod.latin.uix.actions.quickresponses

import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material3.Button
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.MutableState
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.platform.LocalResources
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.datastore.preferences.core.stringPreferencesKey
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonPrimitive
import org.futo.inputmethod.latin.R
import org.futo.inputmethod.latin.common.Constants
import org.futo.inputmethod.latin.uix.Action
import org.futo.inputmethod.latin.uix.ActionTextEditor
import org.futo.inputmethod.latin.uix.ActionWindow
import org.futo.inputmethod.latin.uix.DialogRequestItem
import org.futo.inputmethod.latin.uix.KeyboardManagerForAction
import org.futo.inputmethod.latin.uix.LocalKeyboardScheme
import org.futo.inputmethod.latin.uix.SettingsKey
import org.futo.inputmethod.latin.uix.settings.DataStoreItem
import org.futo.inputmethod.latin.uix.settings.useDataStore
import org.futo.inputmethod.latin.uix.theme.Typography

private val DefaultQuickResponses = listOf(
    "On my way!",
    "Running a bit late, be there soon.",
    "Can't talk right now, I'll call you back.",
    "Sounds good 👍",
    "Thank you!",
)

private fun List<String>.toJsonString(): String = JsonArray(map { JsonPrimitive(it) }).toString()

private fun parseQuickResponses(json: String): List<String> = try {
    Json.parseToJsonElement(json).jsonArray.map { it.jsonPrimitive.content }
} catch (e: Exception) {
    DefaultQuickResponses
}

/** Stored as a JSON array of strings */
val QuickResponsesSetting = SettingsKey(
    stringPreferencesKey("quick_responses"),
    DefaultQuickResponses.toJsonString()
)

private fun DataStoreItem<String>.update(transform: (MutableList<String>) -> Unit) {
    val list = parseQuickResponses(value).toMutableList()
    transform(list)
    setValue(list.toJsonString())
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun QuickResponseList(
    responses: List<String>,
    onSend: (String) -> Unit,
    onLongPress: (Int) -> Unit
) {
    if (responses.isEmpty()) {
        Box(Modifier.fillMaxSize().padding(16.dp), contentAlignment = Alignment.Center) {
            Text(
                stringResource(R.string.action_quick_responses_empty),
                style = Typography.SmallMl,
                textAlign = TextAlign.Center
            )
        }
        return
    }

    LazyColumn(
        modifier = Modifier.fillMaxSize().padding(horizontal = 8.dp),
        verticalArrangement = Arrangement.spacedBy(6.dp)
    ) {
        itemsIndexed(responses) { index, phrase ->
            Surface(
                color = LocalKeyboardScheme.current.keyboardContainer,
                contentColor = LocalKeyboardScheme.current.onKeyboardContainer,
                shape = RoundedCornerShape(12.dp),
                modifier = Modifier.fillMaxWidth()
            ) {
                Text(
                    phrase,
                    style = Typography.Body.Regular,
                    maxLines = 3,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier
                        .combinedClickable(
                            onClick = { onSend(phrase) },
                            onLongClick = { onLongPress(index) }
                        )
                        .fillMaxWidth()
                        .padding(horizontal = 14.dp, vertical = 12.dp)
                )
            }
        }

        item {
            Text(
                stringResource(R.string.action_quick_responses_hint),
                style = Typography.SmallMl,
                textAlign = TextAlign.Center,
                modifier = Modifier.fillMaxWidth().padding(8.dp).alpha(0.6f)
            )
        }
    }
}

@Composable
private fun QuickResponseEditor(
    text: MutableState<String>,
    onCancel: () -> Unit,
    onSave: () -> Unit
) {
    Column(Modifier.fillMaxSize().padding(8.dp)) {
        Surface(
            color = LocalKeyboardScheme.current.keyboardContainer,
            contentColor = LocalKeyboardScheme.current.onKeyboardContainer,
            shape = RoundedCornerShape(12.dp),
            modifier = Modifier.fillMaxWidth().weight(1.0f)
        ) {
            Box(Modifier.padding(12.dp)) {
                ActionTextEditor(
                    text = text,
                    multiline = true,
                    autocorrect = true,
                    placeholder = stringResource(R.string.action_quick_responses_placeholder),
                    modifier = Modifier.fillMaxSize()
                )
            }
        }
        Spacer(Modifier.height(8.dp))
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) {
            TextButton(onClick = onCancel) {
                Text(stringResource(R.string.action_emoji_clear_recent_emojis_cancel))
            }
            Button(onClick = onSave, enabled = text.value.isNotBlank()) {
                Text(stringResource(R.string.action_quick_responses_save))
            }
        }
    }
}

val QuickResponsesAction = Action(
    icon = R.drawable.quick_responses,
    name = R.string.action_quick_responses_title,
    canShowKeyboard = true,
    simplePressImpl = null,
    windowImpl = { manager, _ ->
        object : ActionWindow() {
            /** null = browsing, -1 = adding a new phrase, otherwise the index being edited */
            private val editingIndex = mutableStateOf<Int?>(null)
            private val editText = mutableStateOf("")

            private fun startEditing(index: Int, initial: String) {
                editText.value = initial
                editingIndex.value = index
            }

            private fun stopEditing() {
                editingIndex.value = null
                // The editor showed the keyboard; give the list the full panel again
                manager.forceActionWindowAboveKeyboard(false)
            }

            private fun showOptions(
                manager: KeyboardManagerForAction,
                responses: DataStoreItem<String>,
                index: Int,
                labels: Map<String, String>
            ) {
                val phrase = parseQuickResponses(responses.value).getOrNull(index) ?: return
                manager.requestDialog(
                    phrase,
                    listOf(
                        DialogRequestItem(labels.getValue("cancel")) {},
                        DialogRequestItem(labels.getValue("delete")) {
                            responses.update { if (index in it.indices) it.removeAt(index) }
                        },
                        DialogRequestItem(labels.getValue("top")) {
                            responses.update { if (index in it.indices) it.add(0, it.removeAt(index)) }
                        },
                        DialogRequestItem(labels.getValue("edit")) {
                            startEditing(index, phrase)
                        },
                    ),
                    {}
                )
            }

            @Composable
            override fun windowName(): String = stringResource(R.string.action_quick_responses_title)

            @Composable
            override fun WindowContents(keyboardShown: Boolean) {
                val view = LocalView.current
                val resources = LocalResources.current
                val responses = useDataStore(QuickResponsesSetting)
                val editing = editingIndex.value

                if (editing != null) {
                    QuickResponseEditor(
                        text = editText,
                        onCancel = { stopEditing() },
                        onSave = {
                            val phrase = editText.value.trim()
                            if (phrase.isNotEmpty()) {
                                responses.update {
                                    if (editing in it.indices) it[editing] = phrase else it.add(0, phrase)
                                }
                            }
                            stopEditing()
                        }
                    )
                } else {
                    val labels = mapOf(
                        "cancel" to resources.getString(R.string.action_emoji_clear_recent_emojis_cancel),
                        "delete" to resources.getString(R.string.action_quick_responses_delete),
                        "top" to resources.getString(R.string.action_quick_responses_move_top),
                        "edit" to resources.getString(R.string.action_quick_responses_edit),
                    )
                    QuickResponseList(
                        responses = parseQuickResponses(responses.value),
                        onSend = { phrase ->
                            manager.typeText(phrase)
                            manager.performHapticAndAudioFeedback(Constants.CODE_OUTPUT_TEXT, view)
                            manager.closeActionWindow()
                        },
                        onLongPress = { index -> showOptions(manager, responses, index, labels) }
                    )
                }
            }

            @Composable
            override fun WindowTitleBar(rowScope: RowScope) {
                with(rowScope) {
                    Text(
                        stringResource(
                            when (editingIndex.value) {
                                null -> R.string.action_quick_responses_title
                                -1 -> R.string.action_quick_responses_add
                                else -> R.string.action_quick_responses_edit_title
                            }
                        ),
                        modifier = Modifier.align(Alignment.CenterVertically)
                    )
                    Spacer(modifier = Modifier.weight(1.0f))
                }
                if (editingIndex.value == null) {
                    IconButton(onClick = { startEditing(-1, "") }) {
                        Icon(Icons.Default.Add, contentDescription = stringResource(R.string.action_quick_responses_add))
                    }
                }
            }
        }
    }
)
