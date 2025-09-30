package ai.decart.oasis

import dev.onvoid.webrtc.logging.Logging
import dev.onvoid.webrtc.logging.LogSink
import dev.onvoid.webrtc.media.MediaStream
import dev.onvoid.webrtc.media.MediaType
import dev.onvoid.webrtc.PeerConnectionFactory
import dev.onvoid.webrtc.PeerConnectionObserver
import dev.onvoid.webrtc.RTCConfiguration
import dev.onvoid.webrtc.RTCDataChannel
import dev.onvoid.webrtc.RTCIceCandidate
import dev.onvoid.webrtc.RTCIceConnectionState
import dev.onvoid.webrtc.RTCIceGatheringState
import dev.onvoid.webrtc.RTCIceServer
import dev.onvoid.webrtc.RTCPeerConnection
import dev.onvoid.webrtc.RTCPeerConnectionIceErrorEvent
import dev.onvoid.webrtc.RTCPeerConnectionState
import dev.onvoid.webrtc.RTCRtpCodecCapability
import dev.onvoid.webrtc.RTCRtpReceiver
import dev.onvoid.webrtc.RTCRtpTransceiver
import dev.onvoid.webrtc.RTCSignalingState

import ai.decart.oasis.Utils

object WebRTC {
	val peerConnectionFactory = PeerConnectionFactory()

	fun enableLogging() {
		Logging.logToDebug(Logging.Severity.INFO)
		Logging.logThreads(true)
		Logging.logTimestamps(true)
		Logging.addLogSink(Logging.Severity.INFO, object : LogSink {
			override fun onLogMessage(severity: Logging.Severity, message: String) {
				println("[WebRTC] [$severity] ${message.trim()}")
			}
		})
	}

	fun createPeerConnection(observer: PeerConnectionObserver): RTCPeerConnection {
		val config = RTCConfiguration()
		val iceServer = RTCIceServer()
		iceServer.urls.add("stun:stun.l.google.com:19302")
		config.iceServers.add(iceServer)

		return peerConnectionFactory.createPeerConnection(config, object : PeerConnectionObserver {
			override fun onSignalingChange(state: RTCSignalingState) {
				Utils.log("onSignalingChange: $state")
				observer.onSignalingChange(state)
			}
			override fun onConnectionChange(state: RTCPeerConnectionState) {
				Utils.log("onConnectionChange: $state")
				observer.onConnectionChange(state)
			}
			override fun onIceConnectionChange(state: RTCIceConnectionState) {
				Utils.log("onIceConnectionChange: $state")
				observer.onIceConnectionChange(state)
			}
			override fun onStandardizedIceConnectionChange(state: RTCIceConnectionState) {
				Utils.log("onStandardizedIceConnectionChange: $state")
				observer.onStandardizedIceConnectionChange(state)
			}
			override fun onIceConnectionReceivingChange(receiving: Boolean) {
				Utils.log("onIceConnectionReceivingChange: $receiving")
				observer.onIceConnectionReceivingChange(receiving)
			}
			override fun onIceGatheringChange(state: RTCIceGatheringState) {
				Utils.log("onIceGatheringChange: $state")
				observer.onIceGatheringChange(state)
			}
			override fun onIceCandidate(candidate: RTCIceCandidate) {
				Utils.log("Received ICE candidate: $candidate")
				observer.onIceCandidate(candidate)
			}
			override fun onIceCandidateError(event: RTCPeerConnectionIceErrorEvent) {
				Utils.log("onIceCandidateError: $event")
				observer.onIceCandidateError(event)
			}
			override fun onIceCandidatesRemoved(candidates: Array<RTCIceCandidate>) {
				Utils.log("onIceCandidatesRemoved: $candidates")
				observer.onIceCandidatesRemoved(candidates)
			}
			override fun onAddStream(stream: MediaStream) {
				Utils.log("onAddStream: $stream")
				observer.onAddStream(stream)
			}
			override fun onRemoveStream(stream: MediaStream) {
				Utils.log("onRemoveStream: $stream")
				observer.onRemoveStream(stream)
			}
			override fun onDataChannel(dataChannel: RTCDataChannel) {
				Utils.log("onDataChannel: $dataChannel")
				observer.onDataChannel(dataChannel)
			}
			override fun onRenegotiationNeeded() {
				Utils.log("onRenegotiationNeeded")
				observer.onRenegotiationNeeded()
			}
			override fun onAddTrack(receiver: RTCRtpReceiver, mediaStreams: Array<MediaStream>) {
				Utils.log("onAddTrack: $receiver, $mediaStreams")
				observer.onAddTrack(receiver, mediaStreams)
			}
			override fun onRemoveTrack(receiver: RTCRtpReceiver) {
				Utils.log("onRemoveTrack: $receiver")
				observer.onRemoveTrack(receiver)
			}
			override fun onTrack(transceiver: RTCRtpTransceiver) {
				Utils.log("onTrack: $transceiver, ${transceiver.direction}, ${transceiver.sender.track?.kind}, ${transceiver.receiver.track?.kind}")
				observer.onTrack(transceiver)
			}
		})
	}

	fun preferVideoCodec(transceiver: RTCRtpTransceiver, preferredCodecName: String) {
		val capabilities = peerConnectionFactory.getRtpSenderCapabilities(MediaType.VIDEO)
		val preferredCodecs = mutableListOf<RTCRtpCodecCapability>()
		val otherCodecs = mutableListOf<RTCRtpCodecCapability>()

		capabilities.codecs.forEach { codec ->
			if (codec.name.contains(preferredCodecName, ignoreCase = true)) {
				preferredCodecs.add(codec)
			} else {
				otherCodecs.add(codec)
			}
		}

		transceiver.setCodecPreferences(preferredCodecs + otherCodecs)
	}
}
