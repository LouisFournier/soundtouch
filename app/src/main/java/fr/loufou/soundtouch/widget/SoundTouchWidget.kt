package fr.loufou.soundtouch.widget

import android.content.Context
import android.graphics.BitmapFactory
import androidx.compose.runtime.Composable
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.datastore.preferences.core.Preferences
import androidx.glance.ColorFilter
import androidx.glance.GlanceId
import androidx.glance.GlanceModifier
import androidx.glance.GlanceTheme
import androidx.glance.Image
import androidx.glance.ImageProvider
import androidx.glance.action.ActionParameters
import androidx.glance.action.actionParametersOf
import androidx.glance.action.actionStartActivity
import androidx.glance.action.clickable
import androidx.glance.appwidget.GlanceAppWidget
import androidx.glance.appwidget.GlanceAppWidgetReceiver
import androidx.glance.appwidget.action.ActionCallback
import androidx.glance.appwidget.action.actionRunCallback
import androidx.glance.appwidget.cornerRadius
import androidx.glance.appwidget.provideContent
import androidx.glance.background
import androidx.glance.currentState
import androidx.glance.layout.Alignment
import androidx.glance.layout.Box
import androidx.glance.layout.Column
import androidx.glance.layout.Row
import androidx.glance.layout.RowScope
import androidx.glance.layout.Spacer
import androidx.glance.layout.fillMaxSize
import androidx.glance.layout.fillMaxWidth
import androidx.glance.layout.height
import androidx.glance.layout.padding
import androidx.glance.layout.size
import androidx.glance.layout.width
import androidx.glance.state.PreferencesGlanceStateDefinition
import androidx.glance.text.FontWeight
import androidx.glance.text.Text
import androidx.glance.text.TextStyle
import fr.loufou.soundtouch.MainActivity
import fr.loufou.soundtouch.R
import fr.loufou.soundtouch.api.Key
import fr.loufou.soundtouch.data.GroupCommands
import fr.loufou.soundtouch.data.SpeakerStore
import fr.loufou.soundtouch.data.fetchStates
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.delay

class SoundTouchWidgetReceiver : GlanceAppWidgetReceiver() {
    override val glanceAppWidget = SoundTouchWidget()
}

class SoundTouchWidget : GlanceAppWidget() {

    override val stateDefinition = PreferencesGlanceStateDefinition

    override suspend fun provideGlance(context: Context, id: GlanceId) {
        try {
            WidgetUpdater.store(context, id, WidgetSummary.of(SpeakerStore(context).speakers.fetchStates()))
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            // Pas de Wi-Fi : on garde le dernier état connu
        }
        provideContent {
            GlanceTheme { Content(currentState()) }
        }
    }

    @Composable
    private fun Content(prefs: Preferences) {
        val on = prefs[WidgetUpdater.On] ?: false
        val art = prefs[WidgetUpdater.ArtPath]?.let { BitmapFactory.decodeFile(it) }
        Column(
            GlanceModifier.fillMaxSize()
                .background(GlanceTheme.colors.widgetBackground)
                .cornerRadius(20.dp)
                .padding(12.dp)
                .clickable(actionStartActivity<MainActivity>()),
        ) {
            Row(GlanceModifier.fillMaxWidth().defaultWeight(), verticalAlignment = Alignment.CenterVertically) {
                if (art != null) {
                    Image(ImageProvider(art), contentDescription = null, modifier = GlanceModifier.size(56.dp).cornerRadius(10.dp))
                    Spacer(GlanceModifier.width(12.dp))
                }
                Column(GlanceModifier.defaultWeight()) {
                    Text(
                        prefs[WidgetUpdater.Title] ?: "SoundTouch",
                        style = TextStyle(color = GlanceTheme.colors.onSurfaceVariant, fontSize = 12.sp),
                        maxLines = 1,
                    )
                    Text(
                        prefs[WidgetUpdater.Subtitle] ?: "Chargement…",
                        style = TextStyle(color = GlanceTheme.colors.onSurface, fontSize = 16.sp, fontWeight = FontWeight.Medium),
                        maxLines = 1,
                    )
                    prefs[WidgetUpdater.Detail]?.let {
                        Text(it, style = TextStyle(color = GlanceTheme.colors.onSurfaceVariant, fontSize = 13.sp), maxLines = 1)
                    }
                }
            }
            Row(GlanceModifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                Button(R.drawable.ic_power, if (on) "Éteindre" else "Allumer", WidgetAction.POWER, highlighted = on)
                if (on) {
                    Button(R.drawable.ic_previous, "Précédent", WidgetAction.PREVIOUS)
                    if (prefs[WidgetUpdater.Playing] == true) Button(R.drawable.ic_pause, "Pause", WidgetAction.PLAY_PAUSE)
                    else Button(R.drawable.ic_play, "Lecture", WidgetAction.PLAY_PAUSE)
                    Button(R.drawable.ic_next, "Suivant", WidgetAction.NEXT)
                    Button(R.drawable.ic_volume_down, "Baisser le volume", WidgetAction.VOLUME_DOWN)
                    Button(R.drawable.ic_volume_up, "Monter le volume", WidgetAction.VOLUME_UP)
                }
            }
        }
    }

    @Composable
    private fun RowScope.Button(icon: Int, label: String, command: String, highlighted: Boolean = false) {
        Box(
            GlanceModifier.defaultWeight()
                .height(44.dp)
                .cornerRadius(22.dp)
                .clickable(actionRunCallback<WidgetAction>(actionParametersOf(WidgetAction.Command to command))),
            contentAlignment = Alignment.Center,
        ) {
            Image(
                ImageProvider(icon),
                contentDescription = label,
                modifier = GlanceModifier.size(26.dp),
                colorFilter = ColorFilter.tint(if (highlighted) GlanceTheme.colors.primary else GlanceTheme.colors.onSurface),
            )
        }
    }
}

class WidgetAction : ActionCallback {

    override suspend fun onAction(context: Context, glanceId: GlanceId, parameters: ActionParameters) {
        val store = SpeakerStore(context)
        val states = store.speakers.fetchStates()
        try {
            when (parameters[Command]) {
                POWER -> GroupCommands.togglePower(store, states)
                PLAY_PAUSE -> GroupCommands.press(states, Key.PLAY_PAUSE)
                NEXT -> GroupCommands.press(states, Key.NEXT_TRACK)
                PREVIOUS -> GroupCommands.press(states, Key.PREV_TRACK)
                VOLUME_DOWN -> GroupCommands.stepVolume(states, -VOLUME_STEP)
                VOLUME_UP -> GroupCommands.stepVolume(states, VOLUME_STEP)
            }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            // Enceinte injoignable : le rafraîchissement ci-dessous montrera l'état réel
        }
        delay(800) // le temps que les enceintes prennent en compte la commande
        WidgetUpdater.refresh(context)
    }

    companion object {
        val Command = ActionParameters.Key<String>("command")
        const val POWER = "power"
        const val PLAY_PAUSE = "play_pause"
        const val NEXT = "next"
        const val PREVIOUS = "previous"
        const val VOLUME_DOWN = "volume_down"
        const val VOLUME_UP = "volume_up"
        const val VOLUME_STEP = 4
    }
}
