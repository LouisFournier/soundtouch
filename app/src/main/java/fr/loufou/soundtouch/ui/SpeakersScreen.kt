package fr.loufou.soundtouch.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.toggleable
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.VolumeOff
import androidx.compose.material.icons.automirrored.filled.VolumeUp
import androidx.compose.material.icons.filled.Pause
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.PowerSettingsNew
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.SkipNext
import androidx.compose.material.icons.filled.SkipPrevious
import androidx.compose.material.icons.filled.Speaker
import androidx.compose.material.icons.filled.SpeakerGroup
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.Checkbox
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ElevatedCard
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilledIconButton
import androidx.compose.material3.FilledTonalIconButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.IconButtonDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Slider
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import coil.compose.AsyncImage
import fr.loufou.soundtouch.SpeakersViewModel
import fr.loufou.soundtouch.UiState
import fr.loufou.soundtouch.api.NowPlaying
import fr.loufou.soundtouch.api.SpeakerInfo
import fr.loufou.soundtouch.api.Zone
import fr.loufou.soundtouch.data.SpeakerState
import fr.loufou.soundtouch.data.shortName
import fr.loufou.soundtouch.data.sourceLabel
import kotlin.math.roundToInt

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SpeakersScreen(vm: SpeakersViewModel) {
    val state by vm.state.collectAsState()
    val snackbar = remember { SnackbarHostState() }
    // null = fermé ; sinon le groupe à modifier (ou un groupe vide pour en créer un)
    var editing by remember { mutableStateOf<Zone?>(null) }

    LaunchedEffect(state.message) {
        state.message?.let {
            snackbar.showSnackbar(it)
            vm.messageShown()
        }
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("SoundTouch") },
                actions = {
                    if (state.scanning) {
                        CircularProgressIndicator(Modifier.padding(12.dp).size(24.dp), strokeWidth = 2.dp)
                    } else {
                        IconButton(onClick = vm::rescan) { Icon(Icons.Default.Refresh, "Rechercher les enceintes") }
                    }
                },
            )
        },
        snackbarHost = { SnackbarHost(snackbar) },
    ) { padding ->
        LazyColumn(
            modifier = Modifier.fillMaxSize().padding(padding),
            contentPadding = PaddingValues(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            if (state.speakers.isEmpty()) {
                item {
                    Text(
                        if (state.scanning) "Recherche des enceintes sur le Wi-Fi…"
                        else "Aucune enceinte. Vérifie que le téléphone est sur le même Wi-Fi, puis relance la recherche.",
                        style = MaterialTheme.typography.bodyLarge,
                        modifier = Modifier.padding(vertical = 32.dp),
                    )
                }
            }
            if (state.speakers.size >= 2) {
                item {
                    MultiroomCard(
                        state = state,
                        onCreate = { editing = Zone(masterId = "", members = emptyList()) },
                        onEdit = { editing = it },
                        onDissolve = vm::dissolveGroup,
                        onAutoGroupChange = vm::setAutoGroup,
                        onGroupVolume = vm::setGroupVolume,
                    )
                }
            }
            items(state.speakers, key = { it.info.id }) { speaker ->
                SpeakerCard(speaker, state, vm)
            }
        }
    }

    editing?.let { zone ->
        GroupDialog(
            speakers = state.speakers,
            initial = zone.takeIf { it.masterId.isNotEmpty() },
            onDismiss = { editing = null },
            onConfirm = { master, slaves ->
                vm.createGroup(master, slaves)
                editing = null
            },
        )
    }
}

@Composable
private fun MultiroomCard(
    state: UiState,
    onCreate: () -> Unit,
    onEdit: (Zone) -> Unit,
    onDissolve: (Zone) -> Unit,
    onAutoGroupChange: (Boolean) -> Unit,
    onGroupVolume: (Zone, Int) -> Unit,
) {
    Card(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(Icons.Default.SpeakerGroup, contentDescription = null)
                Spacer(Modifier.width(8.dp))
                Text("Multiroom", style = MaterialTheme.typography.titleMedium)
            }
            Row(
                Modifier.fillMaxWidth().toggleable(
                    value = state.autoGroup,
                    onValueChange = onAutoGroupChange,
                    role = Role.Switch,
                ),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Column(Modifier.weight(1f)) {
                    Text("Toujours grouper", style = MaterialTheme.typography.bodyLarge)
                    Text(
                        "Dès qu'une enceinte s'allume, les autres la suivent",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                Switch(checked = state.autoGroup, onCheckedChange = null)
            }
            val zones = state.zones
            if (zones.isEmpty()) {
                Text("Chaque enceinte joue de son côté.", style = MaterialTheme.typography.bodyMedium)
                Button(onClick = onCreate) { Text("Grouper des enceintes") }
            }
            zones.forEach { zone ->
                val master = state.speaker(zone.masterId)?.info?.name?.let(::shortName) ?: "?"
                val followers = zone.members
                    .filter { it.id != zone.masterId }
                    .joinToString { m -> state.speaker(m.id)?.info?.name?.let(::shortName) ?: m.ip }
                Text("$master → $followers", style = MaterialTheme.typography.bodyMedium)
                state.speaker(zone.masterId)?.volume?.let { masterVolume ->
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text("Volume du groupe", style = MaterialTheme.typography.labelLarge)
                        Spacer(Modifier.width(8.dp))
                        VolumeSlider(masterVolume.target, onVolume = { onGroupVolume(zone, it) }, modifier = Modifier.weight(1f))
                    }
                }
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    OutlinedButton(onClick = { onEdit(zone) }) { Text("Modifier") }
                    Button(onClick = { onDissolve(zone) }) { Text("Dégrouper") }
                }
            }
        }
    }
}

@Composable
private fun SpeakerCard(speaker: SpeakerState, state: UiState, vm: SpeakersViewModel) {
    val info = speaker.info
    val np = speaker.nowPlaying
    val on = speaker.isOn

    ElevatedCard(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(Icons.Default.Speaker, contentDescription = null)
                Spacer(Modifier.width(8.dp))
                Column(Modifier.weight(1f)) {
                    Text(shortName(info.name), style = MaterialTheme.typography.titleLarge)
                    groupRole(speaker, state)?.let {
                        Text(it, style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.primary)
                    }
                }
                val powerColors = if (on) IconButtonDefaults.filledIconButtonColors()
                else IconButtonDefaults.filledTonalIconButtonColors()
                FilledIconButton(onClick = { vm.togglePower(info) }, enabled = speaker.reachable, colors = powerColors) {
                    Icon(Icons.Default.PowerSettingsNew, if (on) "Éteindre" else "Allumer")
                }
            }

            when {
                !speaker.reachable -> Text("Injoignable", color = MaterialTheme.colorScheme.error)
                np == null -> Text("…")
                np.isStandby -> Text("En veille", color = MaterialTheme.colorScheme.onSurfaceVariant)
                else -> {
                    NowPlayingText(np)
                    Transport(np, onPrevious = { vm.previous(info) }, onPlayPause = { vm.playPause(info) }, onNext = { vm.next(info) })
                    VolumeRow(speaker, onMute = { vm.toggleMute(info) }, onVolume = { vm.setVolume(info, it) })
                }
            }
        }
    }
}

private fun groupRole(speaker: SpeakerState, state: UiState): String? {
    val zone = speaker.zone ?: return null
    if (speaker.isMaster) return "Maître du groupe"
    val master = state.speaker(zone.masterId)?.info?.name ?: return "Dans un groupe"
    return "Suit ${shortName(master)}"
}

@Composable
private fun NowPlayingText(np: NowPlaying) {
    val title = np.track ?: np.station ?: np.itemName ?: sourceLabel(np.source)
    val subtitle = listOfNotNull(np.artist, np.album).joinToString(" · ").ifEmpty { null }
    Row(Modifier.padding(top = 8.dp), verticalAlignment = Alignment.CenterVertically) {
        np.artUrl?.let { url ->
            AsyncImage(
                model = url,
                contentDescription = "Pochette",
                contentScale = ContentScale.Crop,
                modifier = Modifier.size(64.dp).clip(RoundedCornerShape(8.dp)),
            )
            Spacer(Modifier.width(12.dp))
        }
        Column(Modifier.weight(1f)) {
            Text(sourceLabel(np.source), style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            Text(title, style = MaterialTheme.typography.bodyLarge, maxLines = 1, overflow = TextOverflow.Ellipsis)
            subtitle?.let {
                Text(it, style = MaterialTheme.typography.bodyMedium, maxLines = 1, overflow = TextOverflow.Ellipsis,
                    color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
    }
}

@Composable
private fun Transport(np: NowPlaying, onPrevious: () -> Unit, onPlayPause: () -> Unit, onNext: () -> Unit) {
    Row(
        Modifier.fillMaxWidth().padding(vertical = 4.dp),
        horizontalArrangement = Arrangement.spacedBy(16.dp, Alignment.CenterHorizontally),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        IconButton(onClick = onPrevious) { Icon(Icons.Default.SkipPrevious, "Précédent") }
        FilledTonalIconButton(onClick = onPlayPause, modifier = Modifier.size(56.dp)) {
            if (np.isPlaying) Icon(Icons.Default.Pause, "Pause") else Icon(Icons.Default.PlayArrow, "Lecture")
        }
        IconButton(onClick = onNext) { Icon(Icons.Default.SkipNext, "Suivant") }
    }
}

@Composable
private fun VolumeRow(speaker: SpeakerState, onMute: () -> Unit, onVolume: (Int) -> Unit) {
    val volume = speaker.volume ?: return
    Row(verticalAlignment = Alignment.CenterVertically) {
        IconButton(onClick = onMute) {
            if (volume.muted) Icon(Icons.AutoMirrored.Filled.VolumeOff, "Réactiver le son")
            else Icon(Icons.AutoMirrored.Filled.VolumeUp, "Couper le son")
        }
        VolumeSlider(volume.target, onVolume, Modifier.weight(1f))
    }
}

@Composable
private fun VolumeSlider(value: Int, onVolume: (Int) -> Unit, modifier: Modifier = Modifier) {
    // Valeur locale pendant le glissement, pour que les mises à jour ne fassent pas sauter le curseur
    var dragging by remember { mutableStateOf<Float?>(null) }
    val shown = dragging ?: value.toFloat()
    Row(modifier, verticalAlignment = Alignment.CenterVertically) {
        Slider(
            value = shown,
            onValueChange = { dragging = it },
            onValueChangeFinished = {
                dragging?.let { onVolume(it.roundToInt()) }
                dragging = null
            },
            valueRange = 0f..100f,
            modifier = Modifier.weight(1f),
        )
        Text(shown.roundToInt().toString(), Modifier.width(36.dp), style = MaterialTheme.typography.labelLarge)
    }
}

@Composable
private fun GroupDialog(
    speakers: List<SpeakerState>,
    initial: Zone?,
    onDismiss: () -> Unit,
    onConfirm: (SpeakerInfo, List<SpeakerInfo>) -> Unit,
) {
    val defaultMaster = speakers.firstOrNull { it.nowPlaying?.isPlaying == true } ?: speakers.first()
    var masterId by remember { mutableStateOf(initial?.masterId ?: defaultMaster.info.id) }
    var selected by remember {
        mutableStateOf(initial?.members?.map { it.id }?.toSet() ?: speakers.map { it.info.id }.toSet())
    }
    val slaves = speakers.map { it.info }.filter { it.id != masterId && it.id in selected }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Grouper les enceintes") },
        text = {
            Column {
                Text("Enceinte principale (sa musique est partagée)", style = MaterialTheme.typography.labelLarge)
                speakers.forEach { s ->
                    Row(
                        Modifier.fillMaxWidth()
                            .selectable(selected = s.info.id == masterId, onClick = { masterId = s.info.id }, role = Role.RadioButton),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        RadioButton(selected = s.info.id == masterId, onClick = null)
                        Text(s.info.name, Modifier.padding(start = 8.dp))
                    }
                }
                Spacer(Modifier.size(12.dp))
                Text("Enceintes qui la suivent", style = MaterialTheme.typography.labelLarge)
                speakers.filter { it.info.id != masterId }.forEach { s ->
                    val checked = s.info.id in selected
                    Row(
                        Modifier.fillMaxWidth().toggleable(
                            value = checked,
                            onValueChange = { selected = if (it) selected + s.info.id else selected - s.info.id },
                            role = Role.Checkbox,
                        ),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Checkbox(checked = checked, onCheckedChange = null)
                        Text(s.info.name, Modifier.padding(start = 8.dp))
                    }
                }
            }
        },
        confirmButton = {
            TextButton(
                onClick = { speakers.first { it.info.id == masterId }.let { onConfirm(it.info, slaves) } },
                enabled = slaves.isNotEmpty(),
            ) { Text("Grouper") }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Annuler") } },
    )
}
