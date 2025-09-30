package ai.decart.oasis.mixin.client;

import org.spongepowered.asm.mixin.gen.Invoker;
import org.spongepowered.asm.mixin.Mixin;

import net.minecraft.client.gl.BufferManager;

@Mixin(BufferManager.class)
public interface BufferManagerAccessor {
	@Invoker("setupBlitFramebuffer")
	void setupBlitFramebuffer(
		int readFramebuffer, int writeFramebuffer,
		int srcX0, int srcY0, int srcX1, int srcY1,
		int dstX0, int dstY0, int dstX1, int dstY1,
		int mask, int filter
	);
}
