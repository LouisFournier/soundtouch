package fr.loufou.soundtouch.data

import android.content.Context
import fr.loufou.soundtouch.api.SpeakerInfo

/** Ce qu'on retient entre deux lancements, partagé par l'appli et le widget. */
class SpeakerStore(context: Context) {

    private val prefs = context.getSharedPreferences("speakers", Context.MODE_PRIVATE)

    var speakers: List<SpeakerInfo>
        get() = prefs.getStringSet(KEY_SPEAKERS, emptySet()).orEmpty().mapNotNull { line ->
            val parts = line.split('\t')
            if (parts.size == 4) SpeakerInfo(id = parts[0], name = parts[1], type = parts[2], ip = parts[3]) else null
        }.sortedBy { it.name.lowercase() }
        set(value) = prefs.edit()
            .putStringSet(KEY_SPEAKERS, value.map { listOf(it.id, it.name, it.type, it.ip).joinToString("\t") }.toSet())
            .apply()

    var autoGroup: Boolean
        get() = prefs.getBoolean(KEY_AUTO_GROUP, true)
        set(value) = prefs.edit().putBoolean(KEY_AUTO_GROUP, value).apply()

    /** Mis à vrai quand l'utilisateur dégroupe à la main ; remis à faux quand tout est en veille. */
    var autoGroupPaused: Boolean
        get() = prefs.getBoolean(KEY_AUTO_GROUP_PAUSED, false)
        set(value) = prefs.edit().putBoolean(KEY_AUTO_GROUP_PAUSED, value).apply()

    /** Le dernier maître choisi à la main, utilisé pour les regroupements automatiques. */
    var preferredMaster: String?
        get() = prefs.getString(KEY_MASTER, null)
        set(value) = prefs.edit().putString(KEY_MASTER, value).apply()

    private companion object {
        const val KEY_SPEAKERS = "speakers"
        const val KEY_AUTO_GROUP = "auto_group"
        const val KEY_AUTO_GROUP_PAUSED = "auto_group_paused"
        const val KEY_MASTER = "preferred_master"
    }
}
