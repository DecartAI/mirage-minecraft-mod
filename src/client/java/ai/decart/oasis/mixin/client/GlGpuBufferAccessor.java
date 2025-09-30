package ai.decart.oasis.mixin.client;

import net.minecraft.client.gl.BufferManager;
import net.minecraft.client.gl.GlGpuBuffer;

import org.jetbrains.annotations.Nullable;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Accessor;
import org.spongepowered.asm.mixin.gen.Invoker;

import java.nio.ByteBuffer;
import java.util.function.Supplier;

@Mixin(GlGpuBuffer.class)
public interface GlGpuBufferAccessor {
	@Invoker("<init>")
	static GlGpuBuffer create(@Nullable Supplier<String> debugLabelSupplier, BufferManager bufferManager, int usage, int size, int id, @Nullable ByteBuffer backingBuffer) {
		throw new AssertionError();
	}

	@Accessor("id")
	int getId();
}
