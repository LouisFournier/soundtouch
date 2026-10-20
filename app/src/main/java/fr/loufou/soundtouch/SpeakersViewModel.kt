package fr.loufou.soundtouch

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import fr.loufou.soundtouch.api.Discovery
import fr.loufou.soundtouch.api.Key
import fr.loufou.soundtouch.api.SoundTouchClient
import fr.loufou.soundtouch.api.SpeakerEvents
import fr.loufou.soundtouch.api.SpeakerInfo
import fr.loufou.soundtouch.api.Zone
import fr.loufou.soundtouch.api.asMember
import fr.loufou.soundtouch.data.SpeakerState
import fr.loufou.soundtouch.data.SpeakerStore
import fr.loufou.soundtouch.data.refreshed
import fr.loufou.soundtouch.widget.WidgetSummary
import fr.loufou.soundtouch.widget.WidgetUpdater
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.conflate
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

data class UiState(
    val speakers: List<SpeakerState> = emptyList(),
    val scanning: Boolean = false,
    val message: String? = null,
    val autoGroup: Boolean = true,
) {
    /** Les groupes multiroom actifs, chacun vu une seule fois. */
    val zones: List<Zone> get() = speakers.mapNotNull { it.zone }.distinctBy { it.masterId }

    fun speaker(id: String) = speakers.firstOrNull { it.info.id == id }
}

class SpeakersViewModel(app: Application) : AndroidViewModel(app) {

    private val store = SpeakerStore(app)
    private val _state = MutableStateFlow(UiState(autoGroup = store.autoGroup))
    val state: StateFlow<UiState> = _state.asStateFlow()
    private var lastAutoGroupAt = 0L
    private var lastWidget: WidgetSummary? = null

    init {
        // On essaie d'abord les adresses mémorisées, et on ne scanne le réseau que s'il en manque.
        viewModelScope.launch {
            val known = store.speakers
            val found = coroutineScope { known.map { async { Discovery.probe(it.ip) } }.awaitAll() }.filterNotNull()
            if (found.isNotEmpty()) setSpeakers(found)
            if (found.isEmpty() || found.size < known.size) rescan()
        }
    }

    fun rescan() {
        if (_state.value.scanning) return
        viewModelScope.launch {
            _state.update { it.copy(scanning = true) }
            val found = Discovery.scan(getApplication())
            _state.update { it.copy(scanning = false) }
            if (found.isEmpty()) {
                _state.update { it.copy(message = "Aucune enceinte trouvée sur ce Wi-Fi") }
            } else {
                setSpeakers(found)
            }
        }
    }

    /**
     * Tourne tant que l'appli est visible (voir MainActivity) : les WebSockets donnent le temps réel,
     * un polling lent rattrape les enceintes qui disparaissent du réseau.
     */
    suspend fun watch() = coroutineScope {
        launch { listenForUpdates() }
        launch { followAnnouncements() }
        while (true) {
            refreshAll()
            maybeAutoGroup()
            delay(10_000)
        }
    }

    private suspend fun listenForUpdates() {
        _state.map { st -> st.speakers.map { it.info } }.distinctUntilChanged().collectLatest { infos ->
            coroutineScope {
                infos.forEach { info ->
                    launch {
                        SpeakerEvents.updates(info.ip).conflate().collect {
                            refreshOne(info.id)
                            maybeAutoGroup()
                        }
                    }
                }
            }
        }
    }

    /** Une enceinte qui change d'adresse (DHCP) ou qui arrive sur le réseau se signale en mDNS : on la suit. */
    private suspend fun followAnnouncements() {
        try {
            Discovery.announced(getApplication()).collect { ip ->
                if (_state.value.speakers.any { it.info.ip == ip }) return@collect
                val info = Discovery.probe(ip) ?: return@collect
                setSpeakers(_state.value.speakers.map { it.info }.filter { it.id != info.id } + info)
            }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            // mDNS indisponible : la recherche manuelle (balayage du réseau) reste possible
        }
    }

    fun messageShown() =_state.update { it.copy(message = null) }

    fun togglePower(s: SpeakerInfo) = act(s) { it.pressKey(Key.POWER) }
    fun playPause(s: SpeakerInfo) = act(s) { it.pressKey(Key.PLAY_PAUSE) }
    fun next(s: SpeakerInfo) = act(s) { it.pressKey(Key.NEXT_TRACK) }
    fun previous(s: SpeakerInfo) = act(s) { it.pressKey(Key.PREV_TRACK) }
    fun toggleMute(s: SpeakerInfo) = act(s) { it.pressKey(Key.MUTE) }

    fun setVolume(s: SpeakerInfo, value: Int) {
        updateSpeaker(s.id) { it.copy(volume = it.volume?.copy(target = value)) }
        act(s) { it.setVolume(value) }
    }

    /** Déplace tout le groupe du même écart que le maître, pour garder l'équilibre entre les pièces. */
    fun setGroupVolume(zone: Zone, value: Int) {
        val st = _state.value
        val masterVolume = st.speaker(zone.masterId)?.volume?.target ?: return
        val delta = value - masterVolume
        (listOf(zone.masterId) + zone.members.map { it.id }).distinct().mapNotNull { st.speaker(it) }.forEach { s ->
            s.volume?.let { setVolume(s.info, (it.target + delta).coerceIn(0, 100)) }
        }
    }

    fun setAutoGroup(enabled: Boolean) {
        store.autoGroup = enabled
        store.autoGroupPaused = false
        _state.update { it.copy(autoGroup = enabled) }
    }

    /** Remplace les groupes existants par un groupe [master] + [slaves]. */
    fun createGroup(master: SpeakerInfo, slaves: List<SpeakerInfo>) = launchAction(master.name) {
        // Ce choix de maître sert aussi pour le regroupement automatique
        store.preferredMaster = master.id
        store.autoGroupPaused = false
        _state.value.zones.forEach { dissolve(it) }
        SoundTouchClient(master.ip).setZone(master.asMember(), slaves.map { it.asMember() })
    }

    fun dissolveGroup(zone: Zone) {
        val masterName = _state.value.speaker(zone.masterId)?.info?.name ?: "le maître"
        if (_state.value.autoGroup) {
            store.autoGroupPaused = true
            _state.update { it.copy(message = "Regroupement auto en pause jusqu'à la mise en veille") }
        }
        launchAction(masterName) { dissolve(zone) }
    }

    private suspend fun dissolve(zone: Zone) {
        val master = _state.value.speaker(zone.masterId)?.info ?: return
        val slaves = zone.members.filter { it.id != master.id }
        if (slaves.isNotEmpty()) SoundTouchClient(master.ip).removeZoneSlaves(master.asMember(), slaves)
    }

    /**
     * Dès qu'une enceinte est allumée et qu'il n'y a pas de groupe, toutes les autres la suivent.
     * Si l'utilisateur a dégroupé à la main, on attend que tout soit en veille avant de recommencer.
     */
    private suspend fun maybeAutoGroup() {
        val st = _state.value
        if (!st.autoGroup || st.zones.isNotEmpty()) return
        val reachable = st.speakers.filter { it.reachable && it.nowPlaying != null }
        if (reachable.size < 2) return
        val on = reachable.filter { it.isOn }
        if (on.isEmpty()) {
            store.autoGroupPaused = false
            return
        }
        if (store.autoGroupPaused) return
        // Évite de marteler une enceinte qui refuse le groupe
        val now = System.currentTimeMillis()
        if (now - lastAutoGroupAt < 10_000) return
        lastAutoGroupAt = now

        val preferred = store.preferredMaster
        val master = on.maxBy {
            (if (it.nowPlaying?.isPlaying == true) 2 else 0) + (if (it.info.id == preferred) 1 else 0)
        }.info
        val slaves = reachable.map { it.info }.filter { it.id != master.id }
        try {
            SoundTouchClient(master.ip).setZone(master.asMember(), slaves.map { it.asMember() })
            refreshAll()
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            _state.update { it.copy(message = "Regroupement automatique impossible") }
        }
    }

    private fun act(s: SpeakerInfo, block: suspend (SoundTouchClient) -> Unit) =
        launchAction(s.name) { block(SoundTouchClient(s.ip)) }

    private fun launchAction(name: String, block: suspend () -> Unit) {
        viewModelScope.launch {
            try {
                block()
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                _state.update { it.copy(message = "$name ne répond pas") }
            }
            refreshAll()
        }
    }

    private fun setSpeakers(found: List<SpeakerInfo>) {
        store.speakers = found
        _state.update { st ->
            val sorted = found.sortedBy { it.name.lowercase() }
            st.copy(speakers = sorted.map { info -> st.speaker(info.id)?.copy(info = info) ?: SpeakerState(info) })
        }
        viewModelScope.launch { refreshAll() }
    }

    private suspend fun refreshAll() {
        val updated = coroutineScope { _state.value.speakers.map { async { it.refreshed() } }.awaitAll() }
        updated.forEach { fresh -> updateSpeaker(fresh.info.id) { fresh } }
        pushWidget()
    }

    private suspend fun refreshOne(id: String) {
        val fresh = _state.value.speaker(id)?.refreshed() ?: return
        updateSpeaker(id) { fresh }
        pushWidget()
    }

    /** Le widget profite de ce que voit l'appli, sans attendre son propre rafraîchissement. */
    private fun pushWidget() {
        val summary = WidgetSummary.of(_state.value.speakers)
        if (summary == lastWidget) return
        lastWidget = summary
        viewModelScope.launch { WidgetUpdater.push(getApplication(), summary) }
    }

    private fun updateSpeaker(id: String, transform: (SpeakerState) -> SpeakerState) =
        _state.update { st -> st.copy(speakers = st.speakers.map { if (it.info.id == id) transform(it) else it }) }
}
