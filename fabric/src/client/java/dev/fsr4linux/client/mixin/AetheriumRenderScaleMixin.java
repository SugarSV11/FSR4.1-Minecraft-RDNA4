package dev.fsr4linux.client.mixin;

import com.mojang.blaze3d.pipeline.RenderTarget;
import dev.fsr4linux.client.Fsr4LinuxClient;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Pseudo;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * Aetherium's render scale: the shader pipeline renders the world into reduced-size textures swapped into the main target; {@code endWorld} then restores the full-size textures and
 * runs its spatial FSR 1 (EASU + RCAS) into the main target. We hook right AFTER {@code restore} (so Aetherium's state is already consistent) and, when FSR 4.1 is active and has
 * everything it needs, upscale with it instead and cancel the rest of the method. Otherwise nothing changes and Aetherium's own upscale runs.
 * {@code @Pseudo}: Aetherium is optional and not a compile-time dependency.
 */
@Pseudo
@Mixin(targets = "dev.spacebod.aetherium.shaders.engine.RenderScale", remap = false)
public abstract class AetheriumRenderScaleMixin {
	@Inject(method = "endWorld", remap = false, cancellable = true, require = 0,   // an Aetherium update that moves this call must not crash the game: Aetherium's own upscale just keeps running
		at = @At(value = "INVOKE", remap = false, shift = At.Shift.AFTER,
			target = "Ldev/spacebod/aetherium/shaders/engine/RenderScale;restore(Lcom/mojang/blaze3d/pipeline/RenderTarget;)V"))
	private static void fsr4linux$upscaleWithFsr(RenderTarget target, CallbackInfo ci) {
		if (Fsr4LinuxClient.aetheriumUpscale(target)) ci.cancel();
	}
}
