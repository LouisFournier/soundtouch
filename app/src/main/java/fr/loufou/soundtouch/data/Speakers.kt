package fr.loufou.soundtouch.data

import fr.loufou.soundtouch.api.Key
import fr.loufou.soundtouch.api.NowPlaying
import fr.loufou.soundtouch.api.SoundTouchClient
import fr.loufou.soundtouch.api.SpeakerInfo
import fr.loufou.soundtouch.api.Volume
import fr.loufou.soundtouch.api.Zone
import fr.loufou.soundtouch.api.asMember
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay

data class SpeakerState(
    val info: SpeakerInfo,
    val nowPlaying: NowPlaying? = null,
    val volume: Volume? = null,
    val zone: Zone? = null,
    val reachable: Boolean = true,
) {
    val isOn get() = reachable && nowPlaying?.isStandby == false
    val isMaster get() = zone?.masterId == info.id
}

suspend fun SpeakerState.refreshed(): SpeakerState {
    val client = SoundTouchClient(info.ip)
    return try {
        coroutineScope {
            val np = async { client.nowPlaying() }
            val vol = async { client.volume() }
            val zone = async { client.zone() }
            copy(nowPlaying = np.await(), volume = vol.await(), zone = zone.await(), reachable = true)
        }
    } catch (e: CancellationException) {
        throw e
    } catch (e: Exception) {
        copy(reachable = false)
    }
}

suspend fun List<SpeakerInfo>.fetchStates(): List<SpeakerState> =
    coroutineScope { map { async { SpeakerState(it).refreshed() } }.awaitAll() }

/** L'enceinte qui représente ce qui joue : le maître du groupe, sinon celle qui joue, sinon une allumée. */
fun List<SpeakerState>.focus(): SpeakerState? {
    val on = filter { it.isOn }
    return on.firstOrNull { it.isMaster } ?: on.firstOrNull { it.nowPlaying?.isPlaying == true } ?: on.firstOrNull()
}

fun shortName(name: String) = name.removePrefix("SoundTouch ").replaceFirstChar { it.uppercase() }

fun sourceLabel(source: String) = when (source) {
    "BLUETOOTH" -> "Bluetooth"
    "AUX" -> "Entrée AUX"
    "INTERNET_RADIO", "LOCAL_INTERNET_RADIO" -> "Radio internet"
    "TUNEIN" -> "TuneIn"
    "SPOTIFY" -> "Spotify"
    "DEEZER" -> "Deezer"
    "AMAZON" -> "Amazon Music"
    "STORED_MUSIC" -> "Bibliothèque musicale"
    "UPNP" -> "DLNA"
    "INVALID_SOURCE" -> "Aucune source"
    else -> source.lowercase().replaceFirstChar { it.uppercase() }
}

/** Commandes « tout le groupe » utilisées par le widget. */
object GroupCommands {

    /** Tout éteindre si quelque chose est allumé ; sinon allumer le maître préféré et grouper les autres. */
    suspend fun togglePower(store: SpeakerStore, states: List<SpeakerState>) {
        val on = states.filter { it.isOn }
        if (on.isNotEmpty()) {
            for (s in on.sortedByDescending { it.isMaster }) {
                // Éteindre le maître peut éteindre tout le groupe : on revérifie avant d'appuyer
                if (s.refreshed().isOn) SoundTouchClient(s.info.ip).pressKey(Key.POWER)
            }
            return
        }
        val reachable = states.filter { it.reachable }
        val master = reachable.firstOrNull { it.info.id == store.preferredMaster } ?: reachable.firstOrNull() ?: return
        val client = SoundTouchClient(master.info.ip)
        client.pressKey(Key.POWER)
        val others = reachable.filter { it.info.id != master.info.id }.map { it.info.asMember() }
        if (store.autoGroup && others.isNotEmpty()) {
            delay(1500) // laisse le maître sortir de veille avant de lui confier le groupe
            client.setZone(master.info.asMember(), others)
        }
    }

    suspend fun press(states: List<SpeakerState>, key: Key) {
        states.focus()?.let { SoundTouchClient(it.info.ip).pressKey(key) }
    }

    /** Monte ou baisse toutes les enceintes allumées du même pas, pour garder leur équilibre. */
    suspend fun stepVolume(states: List<SpeakerState>, delta: Int) = coroutineScope {
        states.filter { it.isOn }.map { s ->
            async { s.volume?.let { SoundTouchClient(s.info.ip).setVolume(it.target + delta) } }
        }.awaitAll()
    }
}
