package dev.fsr4mc.client;

import com.mojang.renderpearl.api.textures.GpuTexture;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import net.fabricmc.loader.api.FabricLoader;

/**
 * Read-only access to Aetherium's render-scale state (Aetherium 0.1.x, shader packs on the Vulkan renderer). Everything is reflective: Aetherium is NOT a compile-time dependency and
 * its jar is never modified. When Aetherium scales the world (renderScale &lt; 100) the whole shader pipeline runs at the reduced size into its own textures and, at the end of the world,
 * upscales with its spatial FSR 1 (EASU + RCAS). {@code AetheriumRenderScaleMixin} replaces that last step with FSR 4.1 through {@link FsrRuntime#aetheriumUpscale}.
 */
public final class AetheriumBridge {
	public static final boolean PRESENT = FabricLoader.getInstance().isModLoaded("aetherium");
	private static boolean inited, ok;
	private static Class<?> renderScale;
	private static Method mPercent, mEngineGet, mEngineRunning;
	private static Field fScaledColor, fScaledDepth, fScaledW, fScaledH, fFullW, fFullH;

	private AetheriumBridge() { }

	private static synchronized boolean init() {
		if (inited) return ok;
		inited = true;
		if (!PRESENT) return false;
		try {
			ClassLoader cl = AetheriumBridge.class.getClassLoader();
			renderScale = Class.forName("dev.spacebod.aetherium.shaders.engine.RenderScale", true, cl);
			Class<?> engine = Class.forName("dev.spacebod.aetherium.shaders.engine.ShaderPackEngine", true, cl);
			mPercent = renderScale.getMethod("percent");
			mEngineGet = engine.getMethod("get");
			mEngineRunning = engine.getMethod("running");
			fScaledColor = field("scaledColor"); fScaledDepth = field("scaledDepth"); fScaledW = field("scaledWidth"); fScaledH = field("scaledHeight"); fFullW = field("fullWidth"); fFullH = field("fullHeight");
			ok = true;
			Fsr4McClient.LOGGER.info("Aetherium detected: FSR 4.1 can replace its FSR 1 upscale (render scale < 100)");
		} catch (Throwable t) {
			Fsr4McClient.LOGGER.warn("Aetherium integration unavailable: {}", t.toString());
		}
		return ok;
	}

	private static Field field(String name) throws NoSuchFieldException {
		Field f = renderScale.getDeclaredField(name); f.setAccessible(true); return f;
	}

	/** True when a shader pack is running in Aetherium AND its render scale is below 100 % (so the world is rendered reduced and upscaled at the end). */
	public static boolean scaling() {
		if (!init()) return false;
		try { return ((Integer) mPercent.invoke(null)) < 100 && (Boolean) mEngineRunning.invoke(mEngineGet.invoke(null)); }
		catch (Throwable t) { return false; }
	}

	/** Aetherium's reduced-size world textures (valid between its beginWorld and endWorld; still referenced right after its restore()). */
	public static GpuTexture scaledColor() { return get(fScaledColor); }
	public static GpuTexture scaledDepth() { return get(fScaledDepth); }
	public static int scaledWidth() { return geti(fScaledW); }
	public static int scaledHeight() { return geti(fScaledH); }
	/** The real (full-size) main target dimensions while Aetherium has the reduced-size textures swapped in (the RenderTarget itself then reports the reduced size). */
	public static int fullWidth() { return geti(fFullW); }
	public static int fullHeight() { return geti(fFullH); }

	private static GpuTexture get(Field f) { try { return init() ? (GpuTexture) f.get(null) : null; } catch (Throwable t) { return null; } }
	private static int geti(Field f) { try { return init() ? f.getInt(null) : 0; } catch (Throwable t) { return 0; } }
}
