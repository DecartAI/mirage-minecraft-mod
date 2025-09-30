package ai.decart.oasis

import java.nio.ByteBuffer

import org.lwjgl.opengl.GL15

import com.mojang.blaze3d.buffers.GpuBuffer
import com.mojang.blaze3d.opengl.GlConst
import com.mojang.blaze3d.opengl.GlStateManager
import com.mojang.blaze3d.systems.RenderSystem
import com.mojang.blaze3d.textures.GpuTexture
import com.mojang.blaze3d.textures.TextureFormat

import net.minecraft.client.gl.Framebuffer
import net.minecraft.client.gl.GlBackend
import net.minecraft.client.gl.GlGpuBuffer
import net.minecraft.client.gl.SimpleFramebuffer
import net.minecraft.client.MinecraftClient
import net.minecraft.client.texture.GlTexture

import ai.decart.oasis.mixin.client.BufferManagerAccessor
import ai.decart.oasis.mixin.client.GlGpuBufferAccessor

object Graphics {
	val device = RenderSystem.getDevice() as GlBackend
	val bufferManager = device.bufferManager

	val window = MinecraftClient.getInstance().window
	val windowWidth get() = window.framebufferWidth
	val windowHeight get() = window.framebufferHeight

	var windowFBOHandle = -1

	fun init() {
		windowFBOHandle = Graphics.getOrCreateColorFBO(MinecraftClient.getInstance().framebuffer)
	}

	fun getOrCreateColorFBO(framebuffer: Framebuffer) = (framebuffer.colorAttachment as GlTexture).getOrCreateFramebuffer(bufferManager, /* depthTexture */ null)

	// this is the same as `device.createBuffer` when the gpu buffer manager is "direct" and not ARB
	// the ARB buffer manager only creates immutable buffers, so we implement the direct method ourselves
	fun createMutableBuffer(usage: Int, size: Int): GlGpuBuffer {
		GlStateManager.clearGlErrors()
		val bufferId: Int = GlStateManager._glGenBuffers()
		GlStateManager._glBindBuffer(GlConst.GL_COPY_WRITE_BUFFER, bufferId)
		GlStateManager._glBufferData(GlConst.GL_COPY_WRITE_BUFFER, size.toLong(), GlConst.bufferUsageToGlEnum(usage))
		GlStateManager._glBindBuffer(GlConst.GL_COPY_WRITE_BUFFER, 0)
		val error = GlStateManager._getError()
		if (error != 0) {
			throw Exception("Could not allocate buffer: $error")
		}
		return GlGpuBufferAccessor.create(null, Graphics.bufferManager, usage, size, bufferId, null)
	}

	// copy one FBO to another, scaled
	fun blitFBO(
		srcFBOHandle: Int, srcWidth: Int, srcHeight: Int,
		dstFBOHandle: Int, dstWidth: Int, dstHeight: Int,
	) {
		GlStateManager._disableScissorTest()
		GlStateManager.clearGlErrors()
		(Graphics.bufferManager as BufferManagerAccessor).setupBlitFramebuffer(
			srcFBOHandle, dstFBOHandle,
			0, 0, srcWidth, srcHeight,
			0, 0, dstWidth, dstHeight,
			GlConst.GL_COLOR_BUFFER_BIT, GlConst.GL_NEAREST,
		)
		val glError = GlStateManager._getError()
		if (glError != 0) {
			throw IllegalStateException("Failed to blit FBO $srcFBOHandle to FBO $dstFBOHandle: $glError")
		}
	}

	// updates a texture from an RGBA buffer using a PBO for passing the data to the GPU
	// this updates the region from `(0, 0)` to `(width, height)`, the texture can be bigger than it
	// the `pbo` must have been created using the `createMutableBuffer` function
	fun updateTextureUsingPBO(texture: GpuTexture, pbo: GpuBuffer, bufferRGBA: ByteBuffer, width: Int, height: Int) {
		require(!texture.isClosed)
		require(0 < width && width <= texture.getWidth(0))
		require(0 < height && height <= texture.getHeight(0))
		require(texture.format == TextureFormat.RGBA8)
		require(!pbo.isClosed)
		require(pbo.size >= width * height * 4)
		require(pbo.usage() and GpuBuffer.USAGE_COPY_SRC != 0)
		require(pbo.usage() and GpuBuffer.USAGE_MAP_WRITE != 0)
		require(bufferRGBA.limit() == width * height * 4)

		// bind the PBO
		GlStateManager._glBindBuffer(GlConst.GL_PIXEL_UNPACK_BUFFER, (pbo as GlGpuBufferAccessor).id)

		// reallocate the PBO to let the GPU know we don't care about the previous contents, should be faster
		GlStateManager._glBufferData(GlConst.GL_PIXEL_UNPACK_BUFFER, bufferRGBA.capacity().toLong(), GlConst.GL_STREAM_DRAW)

		// map the PBO to memory
		val pboBuffer = GL15.glMapBuffer(GlConst.GL_PIXEL_UNPACK_BUFFER, GlConst.GL_WRITE_ONLY, width * height * 4L, null)!!

		// update the PBO
		bufferRGBA.rewind()
		pboBuffer.put(bufferRGBA)
		bufferRGBA.rewind()

		// unmap the PBO
		GlStateManager._glUnmapBuffer(GlConst.GL_PIXEL_UNPACK_BUFFER)

		// bind the texture
		GlStateManager._bindTexture((texture as GlTexture).glId)

		// copy the PBO to the texture
		GlStateManager._pixelStore(GlConst.GL_UNPACK_SKIP_ROWS, 0)
		GlStateManager._pixelStore(GlConst.GL_UNPACK_ROW_LENGTH, 0)
		GlStateManager._pixelStore(GlConst.GL_UNPACK_SKIP_PIXELS, 0)
		GlStateManager._pixelStore(GlConst.GL_UNPACK_ALIGNMENT, 4)
		GlStateManager._texSubImage2D(GlConst.GL_TEXTURE_2D, 0, 0, 0, width, height, GlConst.GL_RGBA, GlConst.GL_UNSIGNED_BYTE, 0)

		// unbind the PBO
		GlStateManager._glBindBuffer(GlConst.GL_PIXEL_UNPACK_BUFFER, 0)

		// unbind the texture
		GlStateManager._bindTexture(0)
	}

	class WindowDownloader(val width: Int, val height: Int) : AutoCloseable {
		// create an FBO and a PBO for reading
		val fbo = SimpleFramebuffer(/* name */ null, width, height, /* useDepthAttachment */ false)
		val fboHandle = Graphics.getOrCreateColorFBO(fbo)
		val pbo = Graphics.device.createBuffer(null, GpuBuffer.USAGE_COPY_DST or GpuBuffer.USAGE_MAP_READ, width * height * 4)

		fun captureWindow(width: Int, height: Int, callback: (bufferRGBA: ByteBuffer) -> Unit) {
			// copy the window FBO to our FBO, scaled
			Graphics.blitFBO(
				Graphics.windowFBOHandle, Graphics.windowWidth, Graphics.windowHeight,
				fboHandle, width, height,
			)

			val commandEncoder = Graphics.device.createCommandEncoder()

			// copy our FBO to our PBO
			// NOTE: calling `mapBuffer` inside the `Runnable` breaks on Windows
			commandEncoder.copyTextureToBuffer(fbo.colorAttachment, pbo, 0, Runnable {}, 0, 0, 0, width, height)

			// map our PBO to the CPU for reading
			commandEncoder.mapBuffer(pbo, /* read */ true, /* write */ false).use {
				val bufferBGRA = it.data()
				callback(bufferBGRA)
			}
		}

		override fun close() {
			fbo.delete()
			pbo.close()
		}
	}

	class WindowUploader(val maxWidth: Int, val maxHeight: Int) : AutoCloseable {
		// create an FBO and a PBO for writing
		val fbo = SimpleFramebuffer(/* name */ null, maxWidth, maxHeight, /* useDepthAttachment */ false)
		val fboHandle = Graphics.getOrCreateColorFBO(fbo)
		val pbo = Graphics.createMutableBuffer(GpuBuffer.USAGE_COPY_SRC or GpuBuffer.USAGE_MAP_WRITE, maxWidth * maxHeight * 4)

		fun drawImageToWindow(bufferRGBA: ByteBuffer, width: Int, height: Int) {
			require(0 < width && width <= maxWidth)
			require(0 < height && height <= maxHeight)
			require(bufferRGBA.limit() == width * height * 4)

			// update our FBO's texture with the image from the buffer
			Graphics.updateTextureUsingPBO(fbo.colorAttachment!!, pbo, bufferRGBA, width, height)

			// copy our FBO to the window FBO, scaled
			Graphics.blitFBO(
				fboHandle, width, height,
				Graphics.windowFBOHandle, Graphics.windowWidth, Graphics.windowHeight,
			)
		}

		override fun close() {
			fbo.delete()
			pbo.close()
		}
	}
}
