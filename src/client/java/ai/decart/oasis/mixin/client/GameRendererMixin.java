package ai.decart.oasis.mixin.client;

import net.minecraft.client.render.GameRenderer;

import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.Mixin;

import ai.decart.oasis.OasisClient;

@Mixin(GameRenderer.class)
public class GameRendererMixin {
	@Inject(method = "renderWorld", at = @At("RETURN"))
	private void afterRenderWorld(CallbackInfo ci) {
		OasisClient.INSTANCE.afterRenderWorld();
	}
}
