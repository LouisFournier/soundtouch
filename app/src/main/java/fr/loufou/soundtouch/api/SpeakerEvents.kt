package fr.loufou.soundtouch.api

import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.callbackFlow
import kotlinx.coroutines.flow.filter
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.retryWhen
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import okhttp3.WebSocket
import okhttp3.WebSocketListener
import java.io.IOException
import java.util.concurrent.TimeUnit

/** Notifications temps réel d'une enceinte (WebSocket sur le port 8080, sous-protocole « gabbo »). */
object SpeakerEvents {

    private val http = OkHttpClient.Builder()
        .pingInterval(30, TimeUnit.SECONDS)
        .build()

    /** Émet à chaque changement (lecture, volume, groupe…) et se reconnecte tout seul. */
    fun updates(ip: String): Flow<Unit> =
        messages(ip)
            .filter { it.startsWith("<updates") }
            .map { }
            .retryWhen { _, _ ->
                delay(5000)
                true
            }

    private fun messages(ip: String): Flow<String> = callbackFlow {
        val request = Request.Builder()
            .url("ws://$ip:8080")
            .header("Sec-WebSocket-Protocol", "gabbo")
            .build()
        val socket = http.newWebSocket(request, object : WebSocketListener() {
            override fun onMessage(webSocket: WebSocket, text: String) {
                trySend(text)
            }

            override fun onClosing(webSocket: WebSocket, code: Int, reason: String) {
                close(IOException("WebSocket fermé par $ip"))
            }

            override fun onFailure(webSocket: WebSocket, t: Throwable, response: Response?) {
                close(t)
            }
        })
        awaitClose { socket.cancel() }
    }
}
