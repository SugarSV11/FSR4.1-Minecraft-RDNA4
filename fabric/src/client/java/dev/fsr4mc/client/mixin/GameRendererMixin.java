package dev.fsr4mc.client.mixin;

import dev.fsr4mc.client.Fsr4McClient;
import net.minecraft.client.renderer.GameRenderer;
import org.joml.Matrix4f;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.ModifyArg;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * Splits the frame into: world at internal resolution (into a private target, with jitter) -> official FSR upscale into the real
 * main target -> HUD/GUI rendered afterwards at native resolution (unchanged vanilla code path).
 */
@Mixin(GameRenderer.class)
public abstract class GameRendererMixin {
	@Inject(method = "render", at = @At("HEAD"))
	private void fsr4mc$frameStart(CallbackInfo ci) {
		Fsr4McClient.onFrameStart((GameRenderer) (Object) this);
	}

	@Inject(method = "render", at = @At(value = "INVOKE", target = "Lnet/minecraft/client/renderer/GameRenderer;preparePostEffects(Ljava/util/List;)V", ordinal = 0))
	private void fsr4mc$beginWorld(CallbackInfo ci) {
		Fsr4McClient.beginWorld((GameRenderer) (Object) this);
	}

	@Inject(method = "render", at = @At(value = "INVOKE", target = "Lnet/minecraft/client/renderer/GameRenderer;applyPostEffects()V", shift = At.Shift.AFTER))
	private void fsr4mc$endWorld(CallbackInfo ci) {
		Fsr4McClient.endWorld((GameRenderer) (Object) this);
	}

	/** Last moment the world depth buffer exists: render3dHud clears it before drawing the first-person hand. */
	@Inject(method = "renderLevel",
		at = @At(value = "INVOKE", target = "Lnet/minecraft/client/renderer/GameRenderer;render3dHud(Lnet/minecraft/client/renderer/state/level/CameraRenderState;Lnet/minecraft/client/renderer/state/level/PlayerRenderState;Lnet/minecraft/client/renderer/state/OptionsRenderState;Z)V"))
	private void fsr4mc$worldPrep(CallbackInfo ci) {
		Fsr4McClient.worldPrep((GameRenderer) (Object) this);
	}

	@ModifyArg(method = "renderLevel",
		at = @At(value = "INVOKE", target = "Lnet/minecraft/client/renderer/ProjectionMatrixBuffer;getBuffer(Lorg/joml/Matrix4f;)Lcom/mojang/renderpearl/api/buffers/GpuBufferSlice;", ordinal = 0),
		index = 0)
	private Matrix4f fsr4mc$jitter(Matrix4f projection) {
		return Fsr4McClient.jitter(projection);
	}
}
