package ai.decart.oasis

import java.nio.ByteBuffer

import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonPrimitive

import com.mojang.blaze3d.platform.GLX
import com.mojang.brigadier.context.CommandContext

import net.minecraft.client.gui.DrawContext
import net.minecraft.client.MinecraftClient
import net.minecraft.client.option.KeyBinding
import net.minecraft.SharedConstants
import net.minecraft.text.Text
import net.minecraft.util.Formatting

import net.fabricmc.fabric.api.client.command.v2.FabricClientCommandSource
import net.fabricmc.fabric.api.client.keybinding.v1.KeyBindingHelper
import net.fabricmc.loader.api.FabricLoader

object Utils {
	const val MOD_PROD_BUILD = true
	const val DEBUG = false
	val modMetadata = FabricLoader.getInstance().getModContainer("mirage-minecraft-mod").orElse(null)!!.metadata

	fun log(message: String) {
		if (DEBUG) {
			println("[${modMetadata.name}] $message")
		}
	}

	fun exception(message: String, error: Throwable) {
		if (DEBUG) {
			println("[${modMetadata.name}] $message: ${error.message}")
			error.printStackTrace()
		}
	}

	private fun formatChatMessage(message: Text): Text {
		return Text.empty()
			.append(Text.literal("[").formatted(Formatting.DARK_GRAY, Formatting.BOLD))
			.append(Text.literal(Utils.modMetadata.name).formatted(Formatting.AQUA, Formatting.BOLD))
			.append(Text.literal("] ").formatted(Formatting.DARK_GRAY, Formatting.BOLD))
			.append(message)
	}

	fun sendChatMessage(context: CommandContext<FabricClientCommandSource>, message: Text) {
		context.source.sendFeedback(formatChatMessage(message))
	}

	fun sendChatMessage(message: Text) {
		MinecraftClient.getInstance().inGameHud.chatHud.addMessage(formatChatMessage(message))
	}

	fun registerKeyBinding(description: String, defaultKeyCode: Int): KeyBinding {
		return KeyBindingHelper.registerKeyBinding(KeyBinding(description, defaultKeyCode, modMetadata.name))
	}

	fun drawMultilineText(drawContext: DrawContext, lines: List<Text>, x: Int, y: Int, lineGap: Int, color: Int) {
		val textRenderer = MinecraftClient.getInstance().textRenderer
		var height = y
		for (text in lines) {
			val textWidth = textRenderer.getWidth(text)
			val textHeight = textRenderer.getWrappedLinesHeight(text, textWidth)
			drawContext.drawTextWithBackground(textRenderer, text, x, height, textWidth, color)
			height += lineGap + textHeight
		}
	}

	fun shortString(string: String, maxLength: Int): String {
		return if (string.length > maxLength) string.take(maxLength) + "..." else string
	}

	fun flipImageBufferVertically(buffer: ByteBuffer, width: Int, height: Int) {
		val rowSize = width * 4
		val rowBufferTop = ByteArray(rowSize)
		val rowBufferBottom = ByteArray(rowSize)

		for (y in 0 until height / 2) {
			val topOffset = y * rowSize
			val bottomOffset = (height - y - 1) * rowSize

			buffer.position(topOffset)
			buffer.get(rowBufferTop)

			buffer.position(bottomOffset)
			buffer.get(rowBufferBottom)

			buffer.position(topOffset)
			buffer.put(rowBufferBottom)

			buffer.position(bottomOffset)
			buffer.put(rowBufferTop)
		}
	}

	fun buildUserAgent(apiKey: String): Map<String, JsonElement> {
		return mapOf(
			"javaVersion" to JsonPrimitive(System.getProperty("java.version")),
			"minecraftVersion" to JsonPrimitive(SharedConstants.getGameVersion().name()),
			"modId" to JsonPrimitive(modMetadata.id),
			"modVersion" to JsonPrimitive(modMetadata.version.friendlyString),
			"osName" to JsonPrimitive(System.getProperty("os.name")),
			"osArch" to JsonPrimitive(System.getProperty("os.arch")),
			"cpu" to JsonPrimitive(GLX._getCpuInfo()),
			"gpuRenderer" to JsonPrimitive(Graphics.device.getRenderer()),
			"gpuVendor" to JsonPrimitive(Graphics.device.getVendor()),
			"gpuBackend" to JsonPrimitive(Graphics.device.getBackendName()),
			"gpuVersion" to JsonPrimitive(Graphics.device.getVersion()),
			"apiKey" to JsonPrimitive(apiKey),
			"width" to JsonPrimitive(Graphics.windowWidth),
			"height" to JsonPrimitive(Graphics.windowHeight),
		)
	}
}
