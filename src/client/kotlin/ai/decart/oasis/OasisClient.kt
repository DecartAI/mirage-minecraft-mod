package ai.decart.oasis

import java.net.URI
import java.nio.ByteBuffer
import java.time.Duration

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.serialization.json.double
import kotlinx.serialization.json.int
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.long

import org.lwjgl.glfw.GLFW

import com.mojang.brigadier.arguments.StringArgumentType
import com.mojang.brigadier.context.CommandContext

import net.minecraft.client.gui.DrawContext
import net.minecraft.client.MinecraftClient
import net.minecraft.SharedConstants
import net.minecraft.text.Text
import net.minecraft.text.TranslatableTextContent
import net.minecraft.util.Formatting
import net.minecraft.util.Util

import net.fabricmc.api.ClientModInitializer
import net.fabricmc.fabric.api.client.command.v2.ClientCommandManager
import net.fabricmc.fabric.api.client.command.v2.ClientCommandRegistrationCallback
import net.fabricmc.fabric.api.client.command.v2.FabricClientCommandSource
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientLifecycleEvents
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents
import net.fabricmc.fabric.api.client.message.v1.ClientReceiveMessageEvents
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerLifecycleEvents

import dev.onvoid.webrtc.CreateSessionDescriptionObserver
import dev.onvoid.webrtc.media.FourCC
import dev.onvoid.webrtc.media.video.CustomVideoSource
import dev.onvoid.webrtc.media.video.NativeI420Buffer
import dev.onvoid.webrtc.media.video.VideoBufferConverter
import dev.onvoid.webrtc.media.video.VideoFrame
import dev.onvoid.webrtc.media.video.VideoTrack
import dev.onvoid.webrtc.PeerConnectionFactory
import dev.onvoid.webrtc.PeerConnectionObserver
import dev.onvoid.webrtc.RTCIceCandidate
import dev.onvoid.webrtc.RTCOfferOptions
import dev.onvoid.webrtc.RTCPeerConnection
import dev.onvoid.webrtc.RTCPeerConnectionState
import dev.onvoid.webrtc.RTCRtpEncodingParameters
import dev.onvoid.webrtc.RTCRtpParameters
import dev.onvoid.webrtc.RTCRtpSender
import dev.onvoid.webrtc.RTCRtpTransceiver
import dev.onvoid.webrtc.RTCRtpTransceiverInit
import dev.onvoid.webrtc.RTCSdpType
import dev.onvoid.webrtc.RTCSessionDescription
import dev.onvoid.webrtc.RTCStatsType
import dev.onvoid.webrtc.SetSessionDescriptionObserver

object OasisClient : ClientModInitializer {
	const val INITIAL_CONNECT_TIMEOUT_MS = 10_000L

	var isRunning = false
	var isConnected = false
	var hasReceivedFrameBack = false
	var modelIsPrimaryView = true
	var showModelOutput = true

	var currentConfig: SessionConfig? = null
	var currentRecommendedPrompts: List<Map.Entry<String, String>> = mapOf("" to "").entries.toList()

	var webSocket: JsonWebSocket<MirageIncomingMessage>? = null
	var peerConnection: RTCPeerConnection? = null
	var videoSource: CustomVideoSource? = null

	var windowDownloader: Graphics.WindowDownloader? = null
	var windowUploader: Graphics.WindowUploader? = null

	var currentOutputWidth = 0
	var currentOutputHeight = 0
	var currentOutputFrameRGBA = ByteBuffer.allocate(currentOutputWidth * currentOutputHeight * 4)

	var connectionTimeoutTimeMs = 0L
	var lastFrameTimeMs = 0L
	var lastStatsTimeMs = 0L
	var currentSendFPS = 0.0
	var currentReceiveFPS = 0.0
	var currentPromptIndex = 0
	var currentPrompt = ""

	override fun onInitializeClient() {
		Utils.log("onInitializeClient")

		ClientLifecycleEvents.CLIENT_STARTED.register {
			Utils.log("Client started")

			// must be called as soon as possible, otherwise the game crashes when resizing during a session
			Graphics.init()
		}
		ClientLifecycleEvents.CLIENT_STOPPING.register {
			Utils.log("Client stopping")
		}
		ServerLifecycleEvents.SERVER_STOPPING.register {
			Utils.log("Server stopping")
			stop()
		}
		ClientReceiveMessageEvents.CHAT.register { message, _, _, params, receptionTimestamp ->
			onReceivedChatMessage(message)
		}

		// keybindings
		val keyNextPrompt = Utils.registerKeyBinding("Switch to the next prompt", GLFW.GLFW_KEY_RIGHT_BRACKET)
		val keyPreviousPrompt = Utils.registerKeyBinding("Switch to the previous prompt", GLFW.GLFW_KEY_LEFT_BRACKET)
		val keyToggleModel = Utils.registerKeyBinding("Toggle AI/normal view", GLFW.GLFW_KEY_V)
		val keyPeekModel = Utils.registerKeyBinding("Peek AI/normal view", GLFW.GLFW_KEY_R)

		ClientTickEvents.END_CLIENT_TICK.register { client ->
			if (!isConnected) {
				return@register
			}

			if (keyNextPrompt.wasPressed()) {
				currentPromptIndex++
				if (currentPromptIndex == currentRecommendedPrompts.size) {
					currentPromptIndex = 0
				}
				sendPrompt(currentRecommendedPrompts[currentPromptIndex].value, enhance = false)
				currentPrompt = currentRecommendedPrompts[currentPromptIndex].key
			}

			if (keyPreviousPrompt.wasPressed()) {
				if (currentPromptIndex == 0) {
					currentPromptIndex = currentRecommendedPrompts.size
				}
				currentPromptIndex--
				sendPrompt(currentRecommendedPrompts[currentPromptIndex].value, enhance = false)
				currentPrompt = currentRecommendedPrompts[currentPromptIndex].key
			}

			if (keyToggleModel.wasPressed()) {
				modelIsPrimaryView = !modelIsPrimaryView
			}

			showModelOutput = (modelIsPrimaryView != keyPeekModel.isPressed())
		}

		val oasisCommand = ClientCommandManager.literal("oasis")
		ClientCommandRegistrationCallback.EVENT.register { dispatcher, registryAccess ->
			dispatcher.register(oasisCommand)
		}

		val oasisStatusCommand = ClientCommandManager.literal("status")
		oasisStatusCommand.executes { context ->
			Utils.sendChatMessage(context, Text.literal("Minecraft v${SharedConstants.getGameVersion().name()}"))
			Utils.sendChatMessage(context, Text.literal("Mod v${Utils.modMetadata.version.friendlyString}"))
			Utils.sendChatMessage(context, Text.literal("Window size ${Graphics.windowWidth} x ${Graphics.windowHeight}"))
			Utils.sendChatMessage(context, Text.literal(if (isRunning) "Running" else "Stopped").formatted(if (isRunning) Formatting.GREEN else Formatting.RED))
			Utils.sendChatMessage(context, Text.literal(if (isConnected) "Connected" else "Disconnected").formatted(if (isConnected) Formatting.GREEN else Formatting.RED))
			val sessionId = currentConfig?.sessionId
			if (sessionId != null) {
				Utils.sendChatMessage(context, Text.literal("Session ID: $sessionId"))
			}
			return@executes 1
		}
		oasisCommand.then(oasisStatusCommand)

		val oasisStartCommand =
			ClientCommandManager.literal("start")
				.then(
					ClientCommandManager.argument("API Key", StringArgumentType.greedyString())
						.executes { context -> onStartCommand(context, StringArgumentType.getString(context, "API Key").trim()); 1 }
				)
		oasisCommand.then(oasisStartCommand)

		val oasisStopCommand = ClientCommandManager.literal("stop")
		oasisStopCommand.executes { context ->
			if (isRunning) {
				Utils.sendChatMessage(context, Text.literal("Stopping").formatted(Formatting.RED))
				stop()
			} else {
				Utils.sendChatMessage(context, Text.literal("Not running").formatted(Formatting.YELLOW))
			}
			return@executes 1
		}
		oasisCommand.then(oasisStopCommand)

		val oasisSwitchPromptCommand =
			ClientCommandManager.literal("prompt")
				.then(
					ClientCommandManager.argument("prompt", StringArgumentType.greedyString())
						.executes { context ->
							if (isConnected) {
								currentPrompt = StringArgumentType.getString(context, "prompt")
								sendPrompt(currentPrompt, enhance = true)
								Utils.sendChatMessage(context, Text.literal("Prompt sent: \"${Utils.shortString(currentPrompt, 50)}\"").formatted(Formatting.GREEN))
							} else {
								Utils.sendChatMessage(context, Text.literal("Not connected yet! Use \"/oasis start\" to start a new session.").formatted(Formatting.YELLOW))
							}
							return@executes 1
						}
				)
		oasisCommand.then(oasisSwitchPromptCommand)

		val oasisHelpCommand = ClientCommandManager.literal("help")
		oasisHelpCommand.executes { context ->
			Utils.sendChatMessage(context, Text.literal("Use \"/oasis start\" to turn on the stream.").formatted(Formatting.GREEN))
			Utils.sendChatMessage(context, Text.literal("Use \"/oasis status\" to see the stream's status.").formatted(Formatting.GREEN))
			Utils.sendChatMessage(context, Text.literal("Use '[' and ']' to flip through default prompts, and \"/oasis prompt PROMPT\" to use a custom prompt!").formatted(Formatting.GREEN))
			Utils.sendChatMessage(context, Text.literal("Use R to show/hide ${Utils.modMetadata.name}, and V to toggle show/hide!").formatted(Formatting.GREEN))
			Utils.sendChatMessage(context, Text.literal("Use \"/oasis stop\" to turn off the stream.").formatted(Formatting.GREEN))
			return@executes 1
		}
		oasisCommand.then(oasisHelpCommand)

		if (Utils.DEBUG) {
			WebRTC.enableLogging()
		}
	}

	fun onReceivedChatMessage(message: Text) {
		val content = message.content
		if (!(content is TranslatableTextContent && content.key == "chat.type.announcement")) {
			return
		}

		// the text looks like "PlayerName Oasis$prompt underwater"
		val chatMessage = (content.args.getOrNull(1) as? Text)?.string?.trim().orEmpty().substringAfter(" ").trim()

		val commandPrefix = "Oasis$"
		if (!chatMessage.startsWith(commandPrefix)) {
			return
		}
		val chatCommand = chatMessage.substring(commandPrefix.length).trim()

		if (!isConnected) {
			Utils.sendChatMessage(Text.literal("Not connected yet! Use \"/oasis start\" first."))
			return
		}

		when {
			chatCommand.equals("show", ignoreCase = true) -> modelIsPrimaryView = true
			chatCommand.equals("hide", ignoreCase = true) -> modelIsPrimaryView = false
			chatCommand.equals("toggle", ignoreCase = true) -> modelIsPrimaryView = !modelIsPrimaryView
			chatCommand.startsWith("prompt") -> {
				val prompt = chatCommand.removePrefix("prompt").trim()
				if (prompt.isEmpty()) {
					Utils.sendChatMessage(Text.literal("Usage: /say Oasis\$prompt <your prompt>"))
					return
				}
				sendPrompt(prompt, enhance = true)
				currentPrompt = prompt
			}
			else -> Utils.sendChatMessage(Text.literal("Unknown ${Utils.modMetadata.name} command. Try one of: show, hide, toggle, prompt."))
		}
	}

	fun sendPrompt(prompt: String, enhance: Boolean) {
		if (!isConnected) {
			return
		}
		webSocket?.sendMessage<MirageOutgoingMessage>(MirageOutgoingPromptMessage(prompt = prompt, enhance_prompt = enhance))
	}

	fun afterRenderWorld() {
		val frameTimeMs = Util.getMeasuringTimeMs()

		// check if we waited too long for the session to start
		if (isRunning && !hasReceivedFrameBack && connectionTimeoutTimeMs <= frameTimeMs) {
			Utils.sendChatMessage(Text.literal("Our servers may be at capacity at the moment or something went wrong. Please try again later.").formatted(Formatting.YELLOW))
			stop()
			return
		}

		if (!isConnected) {
			return
		}

		// send only FPS frames per second
		val msPerFrame = 1_000 * currentConfig!!.fpsDenominator / currentConfig!!.fpsNumerator
		if (frameTimeMs / msPerFrame != lastFrameTimeMs / msPerFrame) {
			lastFrameTimeMs = frameTimeMs
			sendInputFrame()
		}

		// fetch stats every second
		if (frameTimeMs / 1_000 != lastStatsTimeMs / 1_000) {
			lastStatsTimeMs = frameTimeMs
			peerConnection?.getStats { report ->
				for ((id, stat) in report.stats) {
					val fps = stat.attributes["framesPerSecond"] as Double?
					if (fps != null) {
						when (stat.type) {
							RTCStatsType.OUTBOUND_RTP -> currentSendFPS = fps
							RTCStatsType.INBOUND_RTP -> currentReceiveFPS = fps
							else -> {}
						}
					}
					if (Utils.DEBUG) {
						Utils.log("----- RTCStat -----")
						Utils.log("ID: $id")
						Utils.log("Type: ${stat.type}")
						Utils.log("Timestamp: ${stat.timestamp} µs")
						for ((key, value) in stat.attributes) {
							Utils.log("  $key: $value")
						}
					}
				}
			}
		}

		if (hasReceivedFrameBack && showModelOutput) {
			windowUploader?.drawImageToWindow(currentOutputFrameRGBA, currentOutputWidth, currentOutputHeight)
		}
	}

	fun beforeInGameHudRender(drawContext: DrawContext) {
		if (!isRunning) {
			return
		}

		val showFps = Utils.DEBUG
		val loadingEllipsisHz = 4.0
		val textXOffset = 5
		val textYOffset = 8
		val textYGap = 3
		val textColor = 0xFFFFFFFF.toInt()

		val textLines = mutableListOf<Text>()
		textLines.add(Text.literal("${Utils.modMetadata.name} by ${Utils.modMetadata.authors.first().name}"))

		if (isConnected && isRunning && hasReceivedFrameBack) {
			if (showModelOutput) {
				textLines.add(Text.literal("Prompt: \"${Utils.shortString(currentPrompt, 50)}\""))
			} else if (!modelIsPrimaryView) {
				textLines.add(Text.literal("Model output hidden, press V to toggle it back on"))
			}
		} else {
			val seconds = (Util.getMeasuringTimeMs() * loadingEllipsisHz / 1_000).toInt()
			textLines.add(Text.literal("In queue${".".repeat(seconds % 3 + 1)}"))
		}

		if (showFps) {
			textLines.add(Text.literal("FPS: send ${currentSendFPS.toInt()} / recv ${currentReceiveFPS.toInt()}"))
		}

		Utils.drawMultilineText(drawContext, textLines, textXOffset, textYOffset, textYGap, textColor)
	}

	fun shouldRenderClouds(): Boolean = !(isRunning && isConnected && hasReceivedFrameBack && showModelOutput)

	fun sendInputFrame() {
		windowDownloader?.captureWindow(currentConfig!!.inputVideoWidth, currentConfig!!.inputVideoHeight) { bufferBGRA ->
			if (!isConnected) {
				return@captureWindow
			}
			Utils.flipImageBufferVertically(bufferBGRA, currentConfig!!.inputVideoWidth, currentConfig!!.inputVideoHeight)
			bufferBGRA.rewind()

			val i420Buffer = NativeI420Buffer.allocate(currentConfig!!.inputVideoWidth, currentConfig!!.inputVideoHeight)
			VideoBufferConverter.convertToI420(bufferBGRA, i420Buffer, FourCC.ARGB)
			val videoFrame = VideoFrame(i420Buffer, System.nanoTime())
			videoSource?.pushFrame(videoFrame)
			videoFrame.release()
		}
	}

	fun onStartCommand(context: CommandContext<FabricClientCommandSource>, apiKey: String) {
		if (isRunning) {
			Utils.sendChatMessage(context, Text.literal("Already running").formatted(Formatting.YELLOW))
			return
		}

		isRunning = true
		connectionTimeoutTimeMs = Util.getMeasuringTimeMs() + INITIAL_CONNECT_TIMEOUT_MS

		val userAgent = Utils.buildUserAgent(apiKey)

		CoroutineScope(Dispatchers.IO).launch {
			// create a session and get the params
			val config = try {
				Http.createSession(JsonObject(userAgent))
			} catch (error: Exception) {
				Utils.exception("Error creating session", error)
				Utils.sendChatMessage(context, Text.literal("Error contacting the server, please try again later.").formatted(Formatting.RED))
				stop()
				return@launch
			}
			Utils.log("Config: $config")

			// display the error message if there is one
			when (config) {
				is CreateSessionError -> {
					for (errorMessage in config.errorMessages) {
						Utils.sendChatMessage(context, Text.literal(errorMessage).formatted(Formatting.RED))
					}
					stop()
					return@launch
				}
				is SessionConfig -> {}
			}

			// save the config and the default prompts
			Utils.log("Session ID: ${config.sessionId}")
			currentConfig = config
			currentRecommendedPrompts = (if (config.recommendedPrompts.isEmpty()) mapOf("" to "") else config.recommendedPrompts).entries.toList()

			MinecraftClient.getInstance().execute {
				Utils.log("Creating buffers")
				windowDownloader = Graphics.WindowDownloader(config.inputVideoWidth, config.inputVideoHeight)
				windowUploader = Graphics.WindowUploader(config.maxOutputVideoWidth, config.maxOutputVideoHeight)
			}

			// start the session
			connectionTimeoutTimeMs = Util.getMeasuringTimeMs() + config.totalConnectTimeoutMs
			currentPromptIndex = 0
			currentPrompt = currentRecommendedPrompts[currentPromptIndex].key
			Utils.sendChatMessage(context, Text.literal("Starting with prompt: \"${Utils.shortString(currentPrompt, 50)}\"").formatted(Formatting.GREEN))
			Utils.sendChatMessage(context, Text.literal("Starting your session timer now. When the session ends, you can use \"/oasis start\" again to restart the session.").formatted(Formatting.GREEN))
			Utils.sendChatMessage(context, Text.literal("Try using \"/oasis help\" to learn how to use ${Utils.modMetadata.name}!").formatted(Formatting.GREEN))
			for (chatMessage in config.chatMessages) {
				Utils.sendChatMessage(context, Text.literal(chatMessage).formatted(Formatting.YELLOW))
			}
			connectToWebSocketServer()
		}
	}

	fun connectToWebSocketServer() {
		webSocket = JsonWebSocket(URI.create(currentConfig!!.websocketUrl), Duration.ofMillis(currentConfig!!.websocketConnectTimeoutMs), object : JsonWebSocket.Listener<MirageIncomingMessage> {
			override fun onOpen() {
				setupPeerConnection()
				sendOffer()
			}
			override fun onClose(status: Int, reason: String) {
				onUnexpectedError(if (hasReceivedFrameBack) "Lost connection to the server, please try again later" else null)
			}
			override fun onError(error: Throwable) = onUnexpectedError()
			override fun onMessage(message: MirageIncomingMessage) = handleMessage(message)
		})
	}

	fun handleMessage(message: MirageIncomingMessage) {
		when (message) {
			is MirageIncomingIceCandidateMessage -> {
				Utils.log("Received ice-candidate message, adding candidate")
				peerConnection?.addIceCandidate(RTCIceCandidate(message.candidate.sdpMid, message.candidate.sdpMLineIndex, message.candidate.candidate))
			}
			is MirageIncomingAnswerMessage -> {
				Utils.log("Received answer message, setting remote description")
				peerConnection?.setRemoteDescription(RTCSessionDescription(RTCSdpType.ANSWER, message.sdp), object : SetSessionDescriptionObserver {
					override fun onSuccess() {
						Utils.log("Set remote description")
					}
					override fun onFailure(error: String) {
						Utils.log("Failed to set remote description: $error")
						onUnexpectedError()
					}
				})
			}
			is MirageIncomingErrorMessage -> {
				Utils.log("Received error message: ${message.error}")
				onUnexpectedError(if (message.error == "401: Invalid API key") "Invalid API key" else null)
			}
			is MirageIncomingSessionIdMessage -> {}
			is MirageIncomingPromptAckMessage -> {}
			is MirageIncomingGenerationStartedMessage -> {}
		}
	}

	fun sendOffer() {
		Utils.log("Sending offer")

		peerConnection?.createOffer(RTCOfferOptions(), object : CreateSessionDescriptionObserver {
			override fun onSuccess(description: RTCSessionDescription) {
				Utils.log("Created offer: $description")
				peerConnection?.setLocalDescription(description, object : SetSessionDescriptionObserver {
					override fun onSuccess() {
						Utils.log("Set local description")
						webSocket?.sendMessage<MirageOutgoingMessage>(MirageOutgoingOfferMessage(sdp = description.sdp))
					}
					override fun onFailure(error: String) {
						Utils.log("Failed to set local description: $error")
						onUnexpectedError()
					}
				})
			}
			override fun onFailure(error: String) {
				Utils.log("Failed to create offer: $error")
				onUnexpectedError()
			}
		})
	}

	fun setupPeerConnection() {
		Utils.log("Setting up peer connection")

		peerConnection = WebRTC.createPeerConnection(object : PeerConnectionObserver {
			override fun onConnectionChange(state: RTCPeerConnectionState) {
				when (state) {
					RTCPeerConnectionState.CONNECTED -> {
						isConnected = true

						// send the initial prompt
						sendPrompt(currentRecommendedPrompts[currentPromptIndex].value, enhance = false)
					}
					RTCPeerConnectionState.FAILED, RTCPeerConnectionState.DISCONNECTED, RTCPeerConnectionState.CLOSED -> {
						onUnexpectedError()
					}
					else -> {}
				}
			}

			override fun onIceCandidate(candidate: RTCIceCandidate) {
				webSocket?.sendMessage<MirageOutgoingMessage>(MirageOutgoingIceCandidateMessage(candidate = IceCandidate(candidate = candidate.sdp, sdpMid = candidate.sdpMid, sdpMLineIndex = candidate.sdpMLineIndex)))
			}

			override fun onTrack(transceiver: RTCRtpTransceiver) {
				val incomingTrack = transceiver.receiver.track
				if (incomingTrack !is VideoTrack) {
					return
				}

				var nextOutputFrameRGBA = ByteBuffer.allocate(currentOutputWidth * currentOutputHeight * 4)

				incomingTrack.addSink { frame ->
					if (!(currentOutputWidth == frame.buffer.width && currentOutputHeight == frame.buffer.height && nextOutputFrameRGBA.capacity() == currentOutputWidth * currentOutputHeight * 4)) {
						currentOutputWidth = frame.buffer.width
						currentOutputHeight = frame.buffer.height
						Utils.log("Resizing buffer to ${currentOutputWidth}x${currentOutputHeight}")
						nextOutputFrameRGBA = ByteBuffer.allocate(currentOutputWidth * currentOutputHeight * 4)
					}

					try {
						VideoBufferConverter.convertFromI420(frame.buffer, nextOutputFrameRGBA, FourCC.ABGR)
						Utils.flipImageBufferVertically(nextOutputFrameRGBA, currentOutputWidth, currentOutputHeight)
						nextOutputFrameRGBA.rewind()
					} catch (error: Exception) {
						Utils.log("Error converting video frame: $error")
					}

					frame.release()

					// start showing the model output once we have a stable FPS
					if (!hasReceivedFrameBack && currentReceiveFPS >= currentConfig!!.fpsStableMinimum) {
						hasReceivedFrameBack = true
						// force enable the model view
						modelIsPrimaryView = true
					}

					// atomically swap the buffers
					val tmp = nextOutputFrameRGBA
					nextOutputFrameRGBA = currentOutputFrameRGBA
					currentOutputFrameRGBA = tmp
				}
			}
		})

		videoSource = CustomVideoSource()
		val videoTrack = WebRTC.peerConnectionFactory.createVideoTrack("videoTrack", videoSource)
		val transceiver = peerConnection!!.addTransceiver(videoTrack, RTCRtpTransceiverInit())
		WebRTC.preferVideoCodec(transceiver, "VP8")

		val sender = transceiver.sender
		val parameters = sender.parameters
		if (parameters.encodings.isNotEmpty()) {
			val encoding = parameters.encodings[0]
			encoding.minBitrate = 200 * 1000
			encoding.maxBitrate = 2500 * 1000
			sender.setParameters(parameters)
			Utils.log("Configured VP8 sender bitrate: min=200kbps, max=2.5mbps")
		}
	}

	fun onUnexpectedError(message: String? = null) {
		if (isRunning) {
			val message = if (message != null) message else if (hasReceivedFrameBack) "Disconnected due to an unexpected error, please try again later." else "Failed to connect! Our servers may be at capacity at the moment, please try again later."
			Utils.sendChatMessage(Text.literal(message).formatted(Formatting.RED, Formatting.BOLD))
			stop()
		}
	}

	fun stop() {
		if (!isRunning) {
			return
		}
		Utils.log("Stopping")

		// first let everything know we're no longer running
		isRunning = false

		// now shut everything down
		videoSource?.dispose()
		videoSource = null
		peerConnection?.close()
		peerConnection = null
		webSocket?.close()
		webSocket = null

		MinecraftClient.getInstance().execute {
			Utils.log("Deleting buffers")
			windowDownloader?.close()
			windowDownloader = null
			windowUploader?.close()
			windowUploader = null
		}

		// finally, reset all the state

		isConnected = false
		hasReceivedFrameBack = false
		modelIsPrimaryView = true
		showModelOutput = true

		currentConfig = null
		currentRecommendedPrompts = mapOf("" to "").entries.toList()

		currentOutputWidth = 0
		currentOutputHeight = 0
		currentOutputFrameRGBA = ByteBuffer.allocate(currentOutputWidth * currentOutputHeight * 4)

		connectionTimeoutTimeMs = 0L
		lastFrameTimeMs = 0L
		lastStatsTimeMs = 0L
		currentSendFPS = 0.0
		currentReceiveFPS = 0.0
		currentPromptIndex = 0
		currentPrompt = ""
	}
}
