package ai.decart.oasis

import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.net.URI
import java.time.Duration

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.future.await
import kotlinx.coroutines.withContext
import kotlinx.serialization.decodeFromString
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.Serializable
import kotlinx.serialization.SerialName

@Serializable
sealed interface CreateSessionResponse

@Serializable
@SerialName("session-config")
data class SessionConfig(
	val sessionId: String,
	val websocketUrl: String,
	val totalConnectTimeoutMs: Long,
	val websocketConnectTimeoutMs: Long,
	val fpsNumerator: Int,
	val fpsDenominator: Int,
	val fpsStableMinimum: Double,
	val inputVideoWidth: Int,
	val inputVideoHeight: Int,
	val maxOutputVideoWidth: Int,
	val maxOutputVideoHeight: Int,
	val recommendedPrompts: LinkedHashMap<String, String>,
	val chatMessages: List<String>,
) : CreateSessionResponse

@Serializable
@SerialName("error")
data class CreateSessionError(val errorMessages: List<String>) : CreateSessionResponse

object Http {
	const val CONFIG_URL = "https://oasis2.decart.ai/api/create-session"

	const val CONFIG_CONNECT_TIMEOUT_MS = 10_000L

	suspend fun createSession(userAgent: JsonObject): CreateSessionResponse = withContext(Dispatchers.IO) {
		val requestBody = Json.encodeToString(JsonObject.serializer(), userAgent)
		val request = HttpRequest.newBuilder()
			.uri(URI.create(CONFIG_URL))
			.header("Content-Type", "application/json")
			.POST(HttpRequest.BodyPublishers.ofString(requestBody))
			.build()
		val client = HttpClient.newBuilder()
			.version(HttpClient.Version.HTTP_1_1)
			.connectTimeout(Duration.ofMillis(CONFIG_CONNECT_TIMEOUT_MS))
			.build()
		val response = client.sendAsync(request, HttpResponse.BodyHandlers.ofString()).await()
		val responseBody = Json.decodeFromString<CreateSessionResponse>(response.body())
		responseBody
	}
}
