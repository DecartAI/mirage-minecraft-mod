package ai.decart.oasis.mixin.client;

import net.minecraft.client.gui.DrawContext;
import net.minecraft.client.gui.hud.InGameHud;
import net.minecraft.client.render.RenderTickCounter;

import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.Mixin;

import ai.decart.oasis.OasisClient;

@Mixin(InGameHud.class)
public class InGameHudMixin {
	@Inject(method = "render", at = @At("HEAD"))
	private void beforeRender(DrawContext context, RenderTickCounter tickCounter, CallbackInfo ci) {
		OasisClient.INSTANCE.beforeInGameHudRender(context);
	}
}
