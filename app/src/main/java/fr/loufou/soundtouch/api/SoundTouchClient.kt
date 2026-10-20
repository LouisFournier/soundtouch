package fr.loufou.soundtouch.api

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.w3c.dom.Element
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL
import javax.xml.parsers.DocumentBuilderFactory

data class SpeakerInfo(val id: String, val name: String, val type: String, val ip: String)

data class NowPlaying(
    val source: String,
    val itemName: String?,
    val track: String?,
    val artist: String?,
    val album: String?,
    val station: String?,
    val playStatus: String?,
    val artUrl: String?,
) {
    val isStandby get() = source == "STANDBY"
    val isPlaying get() = playStatus == "PLAY_STATE" || playStatus == "BUFFERING_STATE"
}

data class Volume(val target: Int, val muted: Boolean)

data class ZoneMember(val id: String, val ip: String)

data class Zone(val masterId: String, val members: List<ZoneMember>)

enum class Key { POWER, PLAY_PAUSE, NEXT_TRACK, PREV_TRACK, MUTE }

fun SpeakerInfo.asMember() = ZoneMember(id, ip)

/** API locale d'une enceinte SoundTouch (HTTP + XML sur le port 8090). */
class SoundTouchClient(private val ip: String, private val timeoutMs: Int = 3000) {

    suspend fun info(): SpeakerInfo {
        val root = get("info")
        if (root.tagName != "info") throw IOException("$ip n'est pas une enceinte SoundTouch")
        return SpeakerInfo(
            id = root.getAttribute("deviceID"),
            name = root.text("name") ?: ip,
            type = root.text("type") ?: "SoundTouch",
            ip = ip,
        )
    }

    suspend fun nowPlaying(): NowPlaying {
        val root = get("now_playing")
        return NowPlaying(
            source = root.getAttribute("source"),
            itemName = root.text("itemName"),
            track = root.text("track"),
            artist = root.text("artist"),
            album = root.text("album"),
            station = root.text("stationName"),
            playStatus = root.text("playStatus"),
            artUrl = (root.getElementsByTagName("art").item(0) as Element?)
                ?.takeIf { it.getAttribute("artImageStatus") == "IMAGE_PRESENT" }
                ?.textContent?.trim()?.takeIf { it.startsWith("http") },
        )
    }

    suspend fun volume(): Volume {
        val root = get("volume")
        return Volume(
            target = root.text("targetvolume")?.toIntOrNull() ?: 0,
            muted = root.text("muteenabled") == "true",
        )
    }

    /** Le groupe multiroom dont fait partie l'enceinte, ou null si elle est seule. */
    suspend fun zone(): Zone? {
        val root = get("getZone")
        val master = root.getAttribute("master")
        if (master.isEmpty()) return null
        val nodes = root.getElementsByTagName("member")
        val members = (0 until nodes.length).map {
            val e = nodes.item(it) as Element
            ZoneMember(id = e.textContent.trim(), ip = e.getAttribute("ipaddress"))
        }
        return Zone(master, members)
    }

    suspend fun setVolume(value: Int) = post("volume", "<volume>${value.coerceIn(0, 100)}</volume>")

    suspend fun pressKey(key: Key) {
        post("key", """<key state="press" sender="Gabbo">${key.name}</key>""")
        post("key", """<key state="release" sender="Gabbo">${key.name}</key>""")
    }

    /** À appeler sur le maître : les esclaves jouent alors la même chose que lui. */
    suspend fun setZone(master: ZoneMember, slaves: List<ZoneMember>) =
        post("setZone", zoneXml(master, slaves, """ senderIPAddress="${master.ip}""""))

    /** À appeler sur le maître ; retirer tous les esclaves dissout le groupe. */
    suspend fun removeZoneSlaves(master: ZoneMember, slaves: List<ZoneMember>) =
        post("removeZoneSlave", zoneXml(master, slaves))

    private fun zoneXml(master: ZoneMember, slaves: List<ZoneMember>, extraAttrs: String = "") =
        buildString {
            append("""<zone master="${master.id}"$extraAttrs>""")
            slaves.forEach { append("""<member ipaddress="${it.ip}">${it.id}</member>""") }
            append("</zone>")
        }

    private suspend fun get(path: String): Element = withContext(Dispatchers.IO) {
        val bytes = request(path, body = null)
        documentBuilders.newDocumentBuilder().parse(bytes.inputStream()).documentElement
    }

    private suspend fun post(path: String, body: String) {
        withContext(Dispatchers.IO) { request(path, body) }
    }

    private fun request(path: String, body: String?): ByteArray {
        val conn = URL("http://$ip:8090/$path").openConnection() as HttpURLConnection
        conn.connectTimeout = timeoutMs
        conn.readTimeout = timeoutMs
        try {
            if (body != null) {
                conn.requestMethod = "POST"
                conn.doOutput = true
                conn.setRequestProperty("Content-Type", "text/xml; charset=utf-8")
                conn.outputStream.use { it.write(body.toByteArray()) }
            }
            if (conn.responseCode !in 200..299) throw IOException("HTTP ${conn.responseCode} sur /$path")
            return conn.inputStream.use { it.readBytes() }
        } finally {
            conn.disconnect()
        }
    }

    private fun Element.text(tag: String): String? =
        getElementsByTagName(tag).item(0)?.textContent?.trim()?.takeIf { it.isNotEmpty() }

    private companion object {
        val documentBuilders: DocumentBuilderFactory = DocumentBuilderFactory.newInstance()
    }
}
