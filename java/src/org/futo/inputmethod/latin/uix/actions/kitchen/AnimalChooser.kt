package org.futo.inputmethod.latin.uix.actions.kitchen

import android.graphics.BitmapFactory
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Button
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.navigation.NavHostController
import org.futo.inputmethod.latin.R
import org.futo.inputmethod.latin.uix.settings.ScreenTitle
import org.futo.inputmethod.latin.uix.settings.Step
import org.futo.inputmethod.latin.uix.settings.useDataStore
import org.futo.inputmethod.latin.uix.theme.Typography

/** A grid of every animal; WIP ones are greyed out with a badge and can't be picked */
@Composable
fun AnimalChooser(selected: String, onPick: (KitchenAnimal) -> Unit, modifier: Modifier = Modifier) {
    val context = LocalContext.current
    val animals = remember { KitchenAnimals.all(context) }
    LazyVerticalGrid(columns = GridCells.Adaptive(84.dp), modifier = modifier) {
        items(animals, key = { it.key }) { animal ->
            AnimalCard(animal, animal.key == selected) { onPick(animal) }
        }
    }
}

@Composable
private fun AnimalCard(animal: KitchenAnimal, selected: Boolean, onClick: () -> Unit) {
    val context = LocalContext.current
    val shape = RoundedCornerShape(16.dp)
    val colors = MaterialTheme.colorScheme
    Box(
        Modifier
            .padding(4.dp)
            .aspectRatio(0.9f)
            .clip(shape)
            .background(if (selected) colors.primaryContainer else colors.surfaceVariant.copy(alpha = 0.5f))
            .border(2.dp, if (selected) colors.primary else Color.Transparent, shape)
            .clickable(enabled = animal.ready, onClick = onClick),
        contentAlignment = Alignment.Center
    ) {
        Column(
            horizontalAlignment = Alignment.CenterHorizontally,
            modifier = Modifier.alpha(if (animal.ready) 1f else 0.35f)
        ) {
            val picture = remember(animal.picture) {
                animal.picture?.let { path ->
                    runCatching { context.assets.open(path).use { BitmapFactory.decodeStream(it) }?.asImageBitmap() }.getOrNull()
                }
            }
            if (picture != null) {
                Image(picture, contentDescription = null, modifier = Modifier.size(44.dp))
            } else {
                Text(animal.emoji, fontSize = 34.sp)
            }
            Text(
                animal.name,
                style = Typography.SmallMl,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                color = if (selected) colors.onPrimaryContainer else colors.onSurface
            )
        }
        if (!animal.ready) {
            Text(
                stringResource(R.string.kitchen_wip),
                color = colors.onErrorContainer,
                fontWeight = FontWeight.Bold,
                fontSize = 13.sp,
                modifier = Modifier
                    .rotate(-18f)
                    .background(colors.errorContainer, RoundedCornerShape(6.dp))
                    .padding(horizontal = 8.dp, vertical = 2.dp)
            )
        }
    }
}

/** Download progress for an animal that isn't on the phone yet */
@Composable
private fun DownloadStatus(animal: KitchenAnimal) {
    val context = LocalContext.current
    val set = KitchenSet.of(animal)
    when (val state = KitchenDownloads.states[animal.key]) {
        is KitchenDownloads.State.Running -> Column(Modifier.padding(horizontal = 16.dp, vertical = 4.dp)) {
            Text(stringResource(R.string.kitchen_download_progress, state.done, state.total), style = Typography.SmallMl)
            LinearProgressIndicator(
                progress = { state.done.toFloat() / state.total.coerceAtLeast(1) },
                modifier = Modifier.fillMaxWidth()
            )
        }
        is KitchenDownloads.State.Failed -> Text(
            stringResource(R.string.kitchen_download_failed, state.message),
            style = Typography.SmallMl,
            color = MaterialTheme.colorScheme.error,
            modifier = Modifier.padding(horizontal = 16.dp, vertical = 4.dp)
        )
        null -> if (!set.isAvailable(context)) Text(
            stringResource(R.string.kitchen_download_later, animal.name.lowercase(), set.approximateMegabytes),
            style = Typography.SmallMl,
            modifier = Modifier.padding(horizontal = 16.dp, vertical = 4.dp)
        )
    }
}

private fun pick(context: android.content.Context, animal: KitchenAnimal) {
    if (!animal.builtIn && KitchenDownloads.hasNetworkPermission(context)) {
        KitchenDownloads.start(context, KitchenSet.of(animal))
    }
}

/** First-run step: pick the animal before using the keyboard */
@Composable
fun SetupChooseAnimal() {
    val context = LocalContext.current
    val setting = useDataStore(KitchenAnimalSetting)
    val picked = remember { mutableStateOf("cow") }
    val animal = KitchenAnimals.ready(context, picked.value) ?: KitchenAnimals.active(context)

    Column(Modifier.fillMaxSize().padding(16.dp)) {
        Step(fraction = 1.0f, text = stringResource(R.string.kitchen_setup_step))
        Text(
            stringResource(R.string.kitchen_setup_body),
            textAlign = TextAlign.Center,
            modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp)
        )
        AnimalChooser(picked.value, { picked.value = it.key }, Modifier.weight(1f))
        Button(
            onClick = {
                pick(context, animal)
                setting.setValue(animal.key)
            },
            modifier = Modifier.fillMaxWidth().padding(top = 8.dp)
        ) {
            Text(stringResource(R.string.kitchen_setup_continue, "${animal.emoji} ${animal.name}"))
        }
    }
}

/** Settings page for changing the animal */
@Composable
fun AnimalScreen(navController: NavHostController) {
    val context = LocalContext.current
    val setting = useDataStore(KitchenAnimalSetting)
    val current = KitchenAnimals.ready(context, setting.value) ?: KitchenAnimals.active(context)

    Column(Modifier.fillMaxSize()) {
        ScreenTitle(stringResource(R.string.kitchen_settings_title), showBack = true, navController)
        Text(
            stringResource(R.string.kitchen_settings_body),
            style = Typography.SmallMl,
            modifier = Modifier.padding(horizontal = 16.dp, vertical = 4.dp)
        )
        DownloadStatus(current)
        AnimalChooser(
            current.key,
            {
                setting.setValue(it.key)
                pick(context, it)
            },
            Modifier.weight(1f).padding(horizontal = 12.dp)
        )
    }
}
