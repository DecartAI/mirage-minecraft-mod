package ai.decart.oasis.mixin.client;

import net.minecraft.client.render.WorldRenderer;

import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.Mixin;

import ai.decart.oasis.OasisClient;

@Mixin(WorldRenderer.class)
public class WorldRendererMixin {
	@Inject(method = "renderClouds", at = @At("HEAD"), cancellable = true)
	private void killClouds(CallbackInfo ci){
		if (!OasisClient.INSTANCE.shouldRenderClouds()) {
			ci.cancel();
		}
	}
}
