package dev.fsr4mc.client.mixin;

import com.mojang.renderpearl.backend.vulkan.VulkanInstance;
import dev.fsr4mc.client.Fsr4McClient;
import org.lwjgl.vulkan.VK13;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.ModifyArg;

/**
 * Minecraft 26.3 creates its VkInstance with apiVersion 1.2. ANR FG (Apex Neural Rendering) refuses to run on an application whose Vulkan
 * version is below 1.3 ("the game's Vulkan device is older than Vulkan 1.3"), so the requested version is raised to 1.3. apiVersion is only
 * the highest version the application may use: nothing else changes unless 1.3 features are enabled at device creation. FSR4_VK12=1 keeps 1.2.
 */
@Mixin(VulkanInstance.class)
public abstract class VulkanInstanceMixin {
	@ModifyArg(method = "<init>", at = @At(value = "INVOKE", target = "Lorg/lwjgl/vulkan/VkApplicationInfo;apiVersion(I)Lorg/lwjgl/vulkan/VkApplicationInfo;"), index = 0, require = 0)
	private int fsr4mc$vulkan13(int requested) {
		if ("1".equals(System.getenv("FSR4_VK12")) || requested >= VK13.VK_API_VERSION_1_3) return requested;
		Fsr4McClient.LOGGER.info("Vulkan instance apiVersion raised from {}.{} to 1.3 (needed by ANR FG)", requested >>> 22, (requested >>> 12) & 0x3ff);
		return VK13.VK_API_VERSION_1_3;
	}
}
