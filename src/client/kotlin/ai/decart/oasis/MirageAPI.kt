package ai.decart.oasis

import kotlinx.serialization.Serializable
import kotlinx.serialization.SerialName

@Serializable
data class IceCandidate(
	val candidate: String,
	val sdpMid: String,
	val sdpMLineIndex: Int,
)

// outgoing messages

@Serializable
sealed interface MirageOutgoingMessage

@Serializable
@SerialName("prompt")
data class MirageOutgoingPromptMessage(
	val prompt: String,
	val should_enrich: Boolean,
) : MirageOutgoingMessage

@Serializable
@SerialName("offer")
data class MirageOutgoingOfferMessage(
	val sdp: String,
) : MirageOutgoingMessage

@Serializable
@SerialName("ice-candidate")
data class MirageOutgoingIceCandidateMessage(
	val candidate: IceCandidate,
) : MirageOutgoingMessage

// incoming messages

@Serializable
sealed interface MirageIncomingMessage

@Serializable
@SerialName("ice-candidate")
data class MirageIncomingIceCandidateMessage(
	val candidate: IceCandidate,
) : MirageIncomingMessage

@Serializable
@SerialName("answer")
data class MirageIncomingAnswerMessage(
	val sdp: String,
) : MirageIncomingMessage

@Serializable
@SerialName("error")
data class MirageIncomingErrorMessage(
	val error: String,
) : MirageIncomingMessage
