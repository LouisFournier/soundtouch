package fr.loufou.soundtouch.widget

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import androidx.datastore.preferences.core.MutablePreferences
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.glance.GlanceId
import androidx.glance.appwidget.GlanceAppWidgetManager
import androidx.glance.appwidget.state.updateAppWidgetState
import fr.loufou.soundtouch.data.SpeakerState
import fr.loufou.soundtouch.data.SpeakerStore
import fr.loufou.soundtouch.data.fetchStates
import fr.loufou.soundtouch.data.focus
import fr.loufou.soundtouch.data.shortName
import fr.loufou.soundtouch.data.sourceLabel
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.net.URL

/** Ce qu'affiche le widget, calculé à partir de l'état des enceintes. */
data class WidgetSummary(
    val title: String,
    val subtitle: String,
    val detail: String?,
    val on: Boolean,
    val playing: Boolean,
    val artUrl: String?,
) {
    companion object {
        fun of(states: List<SpeakerState>): WidgetSummary {
            if (states.isEmpty()) {
                return WidgetSummary("SoundTouch", "Ouvre l'appli pour trouver tes enceintes", null, false, false, null)
            }
            val focus = states.focus()
                ?: return WidgetSummary("SoundTouch", "En veille", null, false, false, null)
            val np = focus.nowPlaying!!
            val title = focus.zone?.let { zone ->
                val ids = (listOf(zone.masterId) + zone.members.map { it.id }).distinct()
                ids.mapNotNull { id -> states.firstOrNull { it.info.id == id }?.info?.name?.let(::shortName) }
                    .joinToString(" + ")
            } ?: shortName(focus.info.name)
            return WidgetSummary(
                title = title,
                subtitle = np.track ?: np.station ?: np.itemName ?: sourceLabel(np.source),
                detail = np.artist,
                on = true,
                playing = np.isPlaying,
                artUrl = np.artUrl,
            )
        }
    }
}

object WidgetUpdater {

    val Title = stringPreferencesKey("title")
    val Subtitle = stringPreferencesKey("subtitle")
    val Detail = stringPreferencesKey("detail")
    val On = booleanPreferencesKey("on")
    val Playing = booleanPreferencesKey("playing")
    val ArtPath = stringPreferencesKey("art_path")

    /** Interroge les enceintes puis met à jour tous les widgets posés. */
    suspend fun refresh(context: Context) {
        push(context, WidgetSummary.of(SpeakerStore(context).speakers.fetchStates()))
    }

    suspend fun push(context: Context, summary: WidgetSummary) {
        val ids = GlanceAppWidgetManager(context).getGlanceIds(SoundTouchWidget::class.java)
        if (ids.isEmpty()) return
        val artPath = cacheArt(context, summary.artUrl)
        ids.forEach { id ->
            write(context, id, summary, artPath)
            SoundTouchWidget().update(context, id)
        }
    }

    /** Enregistre l'état d'un widget sans le redessiner (appelé depuis provideGlance). */
    suspend fun store(context: Context, id: GlanceId, summary: WidgetSummary) =
        write(context, id, summary, cacheArt(context, summary.artUrl))

    private suspend fun write(context: Context, id: GlanceId, summary: WidgetSummary, artPath: String?) {
        updateAppWidgetState(context, id) { prefs: MutablePreferences ->
            prefs[Title] = summary.title
            prefs[Subtitle] = summary.subtitle
            if (summary.detail != null) prefs[Detail] = summary.detail else prefs.remove(Detail)
            prefs[On] = summary.on
            prefs[Playing] = summary.playing
            if (artPath != null) prefs[ArtPath] = artPath else prefs.remove(ArtPath)
        }
    }

    /** Télécharge la pochette une fois, réduite pour tenir dans un widget. */
    private suspend fun cacheArt(context: Context, url: String?): String? = withContext(Dispatchers.IO) {
        if (url == null) return@withContext null
        val dir = File(context.cacheDir, "widget_art").apply { mkdirs() }
        val file = File(dir, "${url.hashCode().toUInt()}.png")
        if (file.exists()) return@withContext file.path
        try {
            val bytes = URL(url).openConnection().apply {
                connectTimeout = 3000
                readTimeout = 3000
            }.getInputStream().use { it.readBytes() }
            val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
            BitmapFactory.decodeByteArray(bytes, 0, bytes.size, bounds)
            var sample = 1
            while (bounds.outWidth / (sample * 2) >= ART_SIZE) sample *= 2
            val bitmap = BitmapFactory.decodeByteArray(bytes, 0, bytes.size, BitmapFactory.Options().apply { inSampleSize = sample })
                ?: return@withContext null
            dir.listFiles()?.forEach { it.delete() }
            file.outputStream().use { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) }
            file.path
        } catch (e: Exception) {
            null
        }
    }

    private const val ART_SIZE = 192
}
