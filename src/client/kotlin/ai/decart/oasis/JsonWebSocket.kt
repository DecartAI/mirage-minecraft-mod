package ai.decart.oasis

import java.net.http.HttpClient
import java.net.http.WebSocket
import java.net.URI
import java.time.Duration
import java.util.concurrent.CompletableFuture
import java.util.concurrent.CompletionStage

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.KSerializer
import kotlinx.serialization.serializer

class JsonWebSocket<T>(private val incomingSerializer: KSerializer<T>, uri: URI, connectTimeout: Duration, listener: Listener<T>) : AutoCloseable {
	interface Listener<T> {
		fun onOpen()
		fun onClose(status: Int, reason: String)
		fun onError(error: Throwable)
		fun onMessage(message: T)
	}

	var innerWebSocket: WebSocket? = null

	init {
		val innerListener = object : WebSocket.Listener {
			var message = StringBuilder()

			override fun onOpen(webSocket: WebSocket) {
				Utils.log("WebSocket opened")
				innerWebSocket = webSocket
				webSocket.request(1)
				listener.onOpen()
			}

			override fun onClose(webSocket: WebSocket, status: Int, reason: String): CompletionStage<Void> {
				Utils.log("WebSocket closed for reason: $reason")
				innerWebSocket = webSocket
				close()
				listener.onClose(status, reason)
				return CompletableFuture.completedFuture(null)
			}

			override fun onError(webSocket: WebSocket, error: Throwable) {
				Utils.exception("WebSocket error", error)
				innerWebSocket = webSocket
				close()
				listener.onError(error)
			}

			override fun onText(webSocket: WebSocket, message: CharSequence, last: Boolean): CompletionStage<Void> {
				this.message.append(message)
				if (last) {
					val rawMessage = this.message.toString()
					Utils.log("Received WebSocket message: ${rawMessage.length} | $rawMessage")
					val message = try {
						Json.decodeFromString(incomingSerializer, rawMessage)
					} catch (error: Exception) {
						Utils.exception("Error decoding WebSocket message", error)
						innerWebSocket = webSocket
						close()
						listener.onError(error)
						return CompletableFuture.completedFuture(null)
					}
					this.message = StringBuilder()
					listener.onMessage(message)
				} else {
					Utils.log("Received WebSocket partial message: ${message.length} | $message")
				}
				webSocket.request(1)
				return CompletableFuture.completedFuture(null)
			}
		}

		HttpClient.newHttpClient()
			.newWebSocketBuilder()
			.connectTimeout(connectTimeout)
			.buildAsync(uri, innerListener)
			.whenComplete { webSocket, error ->
				if (error != null) {
					Utils.log("WebSocket connection failed")
					innerWebSocket = webSocket
					close()
					listener.onError(error)
				} else {
					require(webSocket == innerWebSocket)
				}
			}
	}

	inline fun <reified T> sendMessage(message: T) {
		val rawMessage = Json.encodeToString(message)
		Utils.log("Sending WebSocket message: $rawMessage")
		innerWebSocket?.sendText(rawMessage, true)
	}

	override fun close() {
		Utils.log("Closing WebSocket")
		innerWebSocket?.sendClose(1_000, "Normal Closure")
		innerWebSocket = null
	}
}

inline fun <reified T> JsonWebSocket(uri: URI, connectTimeout: Duration, listener: JsonWebSocket.Listener<T>): JsonWebSocket<T> = JsonWebSocket(serializer(), uri, connectTimeout, listener)
