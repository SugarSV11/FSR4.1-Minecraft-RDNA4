package dev.fsr4mc.client;

import com.mojang.blaze3d.pipeline.RenderTarget;
import com.mojang.blaze3d.pipeline.TextureTarget;
import com.mojang.blaze3d.systems.RenderSystem;
import com.mojang.renderpearl.api.GpuFormat;
import com.mojang.renderpearl.api.device.GpuSurface;
import com.mojang.renderpearl.api.device.SurfaceException;
import com.mojang.renderpearl.backend.vulkan.VulkanCommandEncoder;
import com.mojang.renderpearl.backend.vulkan.VulkanDevice;
import com.mojang.renderpearl.backend.vulkan.VulkanGpuTexture;
import com.mojang.renderpearl.frontend.FrontendCommandEncoder;
import dev.fsr4mc.client.nativ.Fsr4Native;
import dev.fsr4mc.core.FgStats;
import dev.fsr4mc.core.BackendException;
import dev.fsr4mc.core.BackendId;
import dev.fsr4mc.core.GpuSync;
import dev.fsr4mc.core.Fsr4Config;
import dev.fsr4mc.core.Notification;
import dev.fsr4mc.core.QualityMode;
import dev.fsr4mc.core.UpscaleController;
import java.io.File;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Arrays;
import java.util.Comparator;
import java.util.Optional;
import java.util.function.Consumer;
import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.GameRenderer;
import net.minecraft.client.renderer.state.level.CameraRenderState;
import org.joml.Matrix4f;
import org.lwjgl.vulkan.VK10;
import org.lwjgl.vulkan.VK12;
import org.lwjgl.vulkan.VkCommandBuffer;
import org.lwjgl.vulkan.VkDevice;

/**
 * Per-process state of the upscaler: the external Wine helper (official AMD FSR), the internal-resolution world target and the
 * frame hooks called from {@link dev.fsr4mc.client.mixin.GameRendererMixin}. Everything except {@link #startBridgeAsync}
 * runs on the render thread.
 */
public final class FsrRuntime {
	public enum BridgeState { NOT_STARTED, STARTING, RUNNING, FAILED }

	private static final FsrRuntime INSTANCE = new FsrRuntime();
	public static FsrRuntime get() { return INSTANCE; }

	public volatile BridgeState bridgeState = BridgeState.NOT_STARTED;
	public volatile String bridgeFailure = "bridge not started";
	public volatile String offeredProviders = "";
	private volatile boolean bridgeStartPending;
	private final boolean[] fgModeFailed = new boolean[5];
	public boolean fgModeCandidate(int multiplier) {
		boolean anr = Fsr4McClient.controller() != null && Fsr4McClient.controller().config().anrSelected();
		return multiplier >= 2 && multiplier <= 4 && !fgModeFailed[multiplier]
			&& (multiplier == 2 && !anr ? VulkanCaps.gpu.mayTryAmdFg() : VulkanCaps.gpu.mayTryOwnFg());
	}
	/** x3/x4 own backend, or ANR FG at any multiplier: generated inside Minecraft's Vulkan device and presented by x3PresentHook. */
	static boolean inProcessFg(Fsr4Config cfg) { return cfg.fgMultiplier >= 3 || cfg.anrSelected(); }
	private boolean x3Anr;
	public boolean x3Anr() { return x3Active && x3Anr; }
	/** ANR FG provider: FSR4_ANR_DLL, else <runtime>/anr/dlssg/nvngx_dlssg.dll (the package's "Apex Neural Rendering FG" folder renamed to anr). */
	static String anrDll() {
		String env = System.getenv("FSR4_ANR_DLL"); if (env != null && !env.isBlank()) return env;
		String rt = System.getenv("FSR4_RUNTIME_DIR");
		Path base = rt != null ? Path.of(rt) : net.fabricmc.loader.api.FabricLoader.getInstance().getConfigDir().resolve("fsr4mc-runtime");
		return base.resolve("anr").resolve("dlssg").resolve("nvngx_dlssg.dll").toString();
	}
	public int firstFgCandidate() {
		for (int n = 2; n <= 4; n++) if (fgModeCandidate(n)) return n;
		return 0;
	}
	public int nextFgCandidate(int current) {
		for (int step = 1; step <= 3; step++) {
			int n = 2 + Math.floorMod(current - 2 + step, 3);
			if (fgModeCandidate(n)) return n;
		}
		return 0;
	}

	private Fsr4Native nat;
	// Runtime directory is now provided by FsrBootstrap (auto-installed on first run)
	// Fallback to env var or legacy path for backwards compatibility
	private Path getRuntimeDir() {
		Path bootDir = FsrBootstrap.get().getRuntimeDir();
		if (FsrBootstrap.get().isComplete()) return bootDir;
		// Legacy fallback
		String env = System.getenv("FSR4_RUNTIME_DIR");
		if (env != null) return Path.of(env);
		return Path.of(System.getProperty("user.home"), ".local/share/fsr4linux");
	}

	private Path getLibDir() { return FsrBootstrap.get().isComplete() ? FsrBootstrap.get().getLibDir() : getRuntimeDir().resolve("lib"); }
	private Path getBridgeDir() { return FsrBootstrap.get().isComplete() ? FsrBootstrap.get().getBridgeDir() : getRuntimeDir().resolve("bridge"); }
	private Path getPrefixDir() { return FsrBootstrap.get().isComplete() ? FsrBootstrap.get().getPrefixDir() : getRuntimeDir().resolve("prefix"); }
	private Path getProtonDir() { return FsrBootstrap.get().isComplete() ? FsrBootstrap.get().getProtonDir() : null; }

	// GPU / frame state (render thread)
	private boolean gpuInit;
	/** true = GPU-to-GPU synchronisation through the shared timeline fence (no CPU waits on the render thread); false = legacy CPU-wait path (FSR4_SYNC=cpu forces it). */
	public volatile boolean fenceSync;
	private long sharedSem;
	private final long[] fenceVals = new long[2];
	private volatile long lastIssuedFence;   // highest value any queue may be waiting for: signalling it frees every pending GPU wait
	private static final long ALL_COMMANDS = 65536L;
	private VulkanDevice vk;
	private TextureTarget worldTarget;
	private int rw, rh, ow, oh;
	private boolean allocated;
	private BackendId activeId = BackendId.NATIVE;
	private QualityMode activeQuality = QualityMode.QUALITY;
	private boolean resetNext = true, hasPrev;
	private final Matrix4f prevVp = new Matrix4f(), curVp = new Matrix4f(), curVpJit = new Matrix4f(), invJit = new Matrix4f();
	private double prevX, prevY, prevZ;
	private long frameIndex, timelineValue;
	private float jitterX, jitterY;
	private boolean worldPhaseActive;
	/** This frame's world is rendered by Aetherium at reduced size (see AetheriumBridge); FSR runs from {@link #aetheriumUpscale}, not from {@link #endWorld}. */
	private boolean aeWorld;
	private long aeDepthImage;
	private RenderTarget savedMain;
	private final Matrix4f capturedProj = new Matrix4f();

	// statistics for the overlay (all real measurements)
	public volatile float lastGpuDispatchMs, lastHelperMs, lastBridgeFrameMs; // GPU dispatch in helper, helper total, whole bridge cost on the render thread
	// per-step render-thread timings of the last frame (ms): record+submit, CPU wait for the GPU (timeline), IPC round trip, helper CPU record, helper submit+fence wait
	/** TIMING EXPERIMENT ONLY (FSR4_NOWAIT=1): the GPU does not wait for the upscaler output, so the displayed image is wrong; used to measure how much overlap would be available. */
	private static final boolean NOWAIT_EXPERIMENT = System.getenv("FSR4_NOWAIT") != null;
	public float[] nativeFgGpuTimes() { return nat == null ? null : nat.fgGpuTimes(); }
	public float[] nativeGpuTimes() { return nat == null || !allocated ? null : nat.gpuTimes(); }
	public volatile float tSubmitMs, tGpuWaitMs, tIpcMs, tHelperRecMs, tHelperWaitMs;
	public volatile String lastExecutedProvider = "";
	public volatile String lastError = "";

	private FsrRuntime() { }

	// ---------------------------------------------------------------- bridge lifecycle (any thread)
	public Path runtimeDir() { return getRuntimeDir(); }

	private String findProton() {
		// First try bootstrap-provided GE-Proton
		Path bootProton = getProtonDir();
		if (bootProton != null && Files.isRegularFile(bootProton.resolve("proton"))) {
			return bootProton.toString();
		}
		// Fallback to environment variable
		String env = System.getenv("FSR4_PROTON");
		if (env != null && new File(env, "proton").exists()) return env;
		// Fallback to Steam compatibility tools
		File dir = new File(System.getProperty("user.home"), ".steam/steam/compatibilitytools.d");
		File[] ge = dir.listFiles((d, n) -> n.startsWith("GE-Proton"));
		if (ge == null || ge.length == 0) return null;
		return Arrays.stream(ge).max(Comparator.comparing(File::getName)).map(File::getAbsolutePath).orElse(null);
	}

	/** Starts the helper in the background (takes ~2 s). Safe to call repeatedly. */
	public synchronized void startBridgeAsync() {
		if (!IS_LINUX && !IS_WINDOWS) {
			bridgeState = BridgeState.FAILED; bridgeFailure = "Linux or Windows required"; return;
		}
		if (!VulkanCaps.deviceProbed) return; // onTick retries after Minecraft chooses its Vulkan GPU
		if (!VulkanCaps.gpu.bridgeCapable()) {
			bridgeState = BridgeState.FAILED; bridgeFailure = VulkanCaps.gpu.reason(); return;
		}
		if (bridgeState == BridgeState.STARTING || bridgeState == BridgeState.RUNNING) return;
		bridgeState = BridgeState.STARTING; bridgeFailure = "bridge starting";
		Thread t = new Thread(() -> {
			try {
				// Wait for bootstrap to complete (max 5 minutes)
				FsrBootstrap boot = FsrBootstrap.get();
				if (!boot.isComplete()) {
					if (boot.getState() == FsrBootstrap.BootstrapState.FAILED) {
						throw new IllegalStateException("Bootstrap failed: " + boot.getError());
					}
					// Wait for bootstrap
					boot.join();
					if (!boot.isComplete()) {
						throw new IllegalStateException("Bootstrap did not complete");
					}
				}

				Path lib = getLibDir().resolve(IS_WINDOWS ? "fsr4linux.dll" : "libfsr4linux.so");
				Path exe = getBridgeDir().resolve("fsr4_bridge.exe");
				if (!Files.isRegularFile(lib)) throw new IllegalStateException("missing " + lib + " (bootstrap should have extracted it)");
				if (!Files.isRegularFile(exe)) throw new IllegalStateException("missing " + exe + " (bootstrap should have extracted it)");
				// Native Windows: the frame-generation DLL is optional (upscaling works without it).
				for (String dll : IS_WINDOWS ? new String[]{"amd_fidelityfx_loader_dx12.dll", "amd_fidelityfx_upscaler_dx12.dll"}
						: new String[]{"amd_fidelityfx_loader_dx12.dll", "amd_fidelityfx_upscaler_dx12.dll", "amd_fidelityfx_framegeneration_dx12.dll"}) {
					if (!Files.isRegularFile(getBridgeDir().resolve(dll))) throw new IllegalStateException("missing AMD DLL " + dll + " in " + getBridgeDir());
				}
				String proton = IS_WINDOWS ? "" : findProton();   // native Windows: no Wine/Proton layer
				if (proton == null) throw new IllegalStateException("no GE-Proton found (bootstrap should have downloaded it)");

				// Log environment for diagnostics
				boolean flatpak = Files.exists(Path.of("/.flatpak-info")) || System.getenv("FLATPAK_ID") != null;
				Fsr4McClient.LOGGER.info("FSR bridge start: flatpak={}, proton={}, lib={}, exe={}, prefix={}",
					flatpak, proton, lib, exe, getPrefixDir());
				if (flatpak) {
					String gpuDevices = System.getenv("VK_DRIVER_FILES");
					String icdFiles = System.getenv("VK_ICD_FILENAMES");
					String vkd3dConfig = System.getenv("VKD3D_CONFIG");
					Fsr4McClient.LOGGER.info("Flatpak env: VK_DRIVER_FILES={}, VK_ICD_FILENAMES={}, VKD3D_CONFIG={}",
						gpuDevices != null ? gpuDevices : "unset",
						icdFiles != null ? icdFiles : "unset",
						vkd3dConfig != null ? vkd3dConfig : "unset");
				}

				if (nat == null) {
					nat = new Fsr4Native(lib);
					nat.create(proton, IS_WINDOWS ? "" : getPrefixDir().toString(), IS_WINDOWS ? "" : System.getenv().getOrDefault("FSR4_STEAM_DIR", System.getProperty("user.home") + "/.local/share/Steam"), exe.toString(),
						getBridgeDir().resolve("bridge.log").toString(), 7680, 4320, 7680, 4320);   // up to 8K (a 4K cap made 5K/8K windows fail with "dimensions invalid")
				}
				if (!IS_WINDOWS) Files.createDirectories(getPrefixDir());
				int rc = nat.bridgeStart(IS_WINDOWS ? "" : "3.1.5");   // Windows: "" = best provider offered by the official SDK DLLs
				if (rc != 0) throw new IllegalStateException(nat.lastError());
				offeredProviders = nat.offeredProviders();
				bridgeState = BridgeState.RUNNING; bridgeFailure = ""; startStallWatchdog();
				Fsr4McClient.LOGGER.info("FSR bridge running with {}; AMD runtime offers providers: {}", proton, offeredProviders);
			} catch (Throwable e) {
				bridgeState = BridgeState.FAILED; bridgeFailure = String.valueOf(e.getMessage());
				Fsr4McClient.LOGGER.error("FSR bridge failed to start: {}", e.toString());
			}
		}, "fsr4mc-bridge-start");
		t.setDaemon(true); t.start();
	}

	public boolean bridgeAlive() { return nat != null && bridgeState == BridgeState.RUNNING && nat.bridgeRunning(); }
	public boolean providerOffered(String version) {
		for (String s : offeredProviders.split(" ")) if (sameProvider(s, version)) return true;
		return false;
	}
	static final boolean IS_WINDOWS = System.getProperty("os.name", "").toLowerCase(java.util.Locale.ROOT).contains("win");
	static final boolean IS_LINUX = System.getProperty("os.name", "").toLowerCase(java.util.Locale.ROOT).contains("linux");
	/** Linux builds need the exact provider name; native Windows uses the newer official SDK whose providers are the same family ("4.1.1" ~ "4.1.2"). */
	public static boolean sameProvider(String offered, String wanted) {
		if (offered == null || wanted == null || offered.isEmpty()) return false;
		if (offered.equals(wanted)) return true;
		if (!IS_WINDOWS) return false;
		int a = offered.lastIndexOf('.'), b = wanted.lastIndexOf('.');
		return a > 0 && b > 0 && offered.substring(0, a).equals(wanted.substring(0, b));
	}
	public void setBridgeStartPending(boolean p) { bridgeStartPending = p; }
	public boolean bridgeStartPending() { return bridgeStartPending; }

	public synchronized void shutdown() {
		try { fgCollectPending(); } catch (Throwable e) { Fsr4McClient.LOGGER.warn("FG collection during shutdown failed", e); }
		try { releaseGpuNow(); } catch (Throwable e) { Fsr4McClient.LOGGER.error("GPU release during shutdown failed", e); }
		if (nat != null) { try { nat.bridgeStop(); nat.close(); } catch (Throwable e) { Fsr4McClient.LOGGER.error("Bridge close during shutdown failed", e); } nat = null; }
		bridgeState = BridgeState.NOT_STARTED;
	}

	// ---------------------------------------------------------------- Vulkan access
	private static VulkanCommandEncoder encoder() {
		return (VulkanCommandEncoder) ((FrontendCommandEncoder) RenderSystem.getDevice().createCommandEncoder()).backend();
	}

	private void ensureGpuInit() throws BackendException {
		if (gpuInit) return;
		if (nat == null) throw new BackendException("native library not loaded");
		vk = ((VulkanCommandEncoder) ((FrontendCommandEncoder) RenderSystem.getDevice().createCommandEncoder()).backend()).device;
		VkDevice d = vk.vkDevice();
		boolean flatpak = Files.exists(Path.of("/.flatpak-info")) || System.getenv("FLATPAK_ID") != null;
		Fsr4McClient.LOGGER.info("FSR gpuInit: flatpak={}, device={}, queueFamily={}, externalSemaphore={}, int64Atomics={}",
			flatpak, d.getPhysicalDevice().address(), vk.graphicsQueue().queueFamilyIndex(), VulkanCaps.externalSemaphoreEnabled, VulkanCaps.int64Atomics);
		nat.x3SetInt64(VulkanCaps.int64Atomics);
		int rc = nat.gpuInit(d.getPhysicalDevice().getInstance().address(), d.getPhysicalDevice().address(), d.address(), vk.graphicsQueue().vkQueue().address(), vk.graphicsQueue().queueFamilyIndex());
		if (rc != 0) throw new BackendException("gpu init: " + nat.lastError());
		gpuInit = true;
		fenceSync = false;
		
		// Probe the actual Vulkan and fd capabilities even inside Flatpak.
		// Importing a syncobj found by scanning Wine fds can signal the wrong object.
		// Keep the GPU-fence path available for explicit diagnostics until fd identity is reliable.
		if ((IS_WINDOWS ? !"cpu".equalsIgnoreCase(System.getenv("FSR4_SYNC")) : "gpu".equalsIgnoreCase(System.getenv("FSR4_SYNC"))) && VulkanCaps.externalSemaphoreEnabled) {   // native Windows: D3D12 fence <-> Vulkan timeline semaphore is the default (validated on RDNA 4)
			Fsr4McClient.LOGGER.info("FSR bridge: attempting shared fence init (timeline semaphore)...");
			if (nat.syncInit() == 0) { sharedSem = nat.sharedSemaphore(); fenceSync = true; Fsr4McClient.LOGGER.info("FSR bridge: GPU-to-GPU synchronisation through a shared timeline fence (OK)"); }
			else Fsr4McClient.LOGGER.warn("FSR bridge: shared fence unavailable ({}), using CPU-wait synchronisation", nat.lastError());
		} else Fsr4McClient.LOGGER.info("FSR bridge: CPU-wait synchronisation (FSR4_SYNC={}, external semaphore ext={})", System.getenv("FSR4_SYNC"), VulkanCaps.externalSemaphoreEnabled);
	}

	public GpuSync gpuSync() {
		return () -> { if (vk != null) VK10.vkQueueWaitIdle(vk.graphicsQueue().vkQueue()); };
	}

	// ---------------------------------------------------------------- (re)allocation, called by the backend
	public void allocate(BackendId id, int outW, int outH, QualityMode q) throws BackendException {
		fgCollectPending();
		if (!bridgeAlive()) throw new BackendException("bridge process not running: " + bridgeFailure);
		if (!providerOffered(id.label.replace("FSR ", ""))) throw new BackendException(id.label + " not offered by the AMD runtime (offered: " + offeredProviders + ")");
		ensureGpuInit();
		int nrw = q.renderSize(outW), nrh = q.renderSize(outH);
		if (nrw > outW || nrh > outH) throw new BackendException("invalid internal size");
		allocated = false; fgAllocated = false; x3Active = false; fgOnlyPlanes = false; fgFrames = 0;   // re-allocating the planes frees the helper's FG context too
		try {
			if (worldTarget == null) worldTarget = new TextureTarget("FSR world", nrw, nrh, GpuFormat.RGBA8_UNORM, GpuFormat.D32_FLOAT);
			else if (worldTarget.width != nrw || worldTarget.height != nrh) worldTarget.resize(nrw, nrh);
		} catch (RuntimeException e) {
			throw new BackendException("cannot allocate the internal-resolution target " + nrw + "x" + nrh + ": " + e.getMessage(), e);
		}
		long depth = ((VulkanGpuTexture) worldTarget.getDepthTexture()).vkImage();
		boolean flatpak = Files.exists(Path.of("/.flatpak-info")) || System.getenv("FLATPAK_ID") != null;
		Fsr4McClient.LOGGER.info("FSR gpuAlloc: flatpak={}, internal={}x{}, output={}x{}, depthImage=0x{}",
			flatpak, nrw, nrh, outW, outH, Long.toHexString(depth));
		int rc = nat.gpuAlloc(nrw, nrh, outW, outH, depth);
		if (rc != 0) {
			String err = nat.lastError();
			Fsr4McClient.LOGGER.error("FSR gpuAlloc FAILED: flatpak={}, error={}", flatpak, err);
			throw new BackendException("shared texture setup failed: " + err);
		}
		rw = nrw; rh = nrh; ow = outW; oh = outH; activeId = id; activeQuality = q; allocated = true; resetNext = true; hasPrev = false; lastExecutedProvider = "";
		Fsr4McClient.LOGGER.info("FSR {} allocated: internal {}x{} -> output {}x{} ({})", id.label, rw, rh, ow, oh, q.label);
	}

	public void resetHistory() { resetNext = true; hasPrev = false; }

	public void releaseGpuNow() {
		try { fgCollectPending(); } catch (Throwable ignored) { }
		allocated = false; worldPhaseActive = false; fgAllocated = false; x3Active = false; fgOnlyPlanes = false; fgFrames = 0;
		if (System.getenv("FSR4_CLEANUP_DIAG") != null)
			Fsr4McClient.LOGGER.info("FSR cleanup target images: gen={} gen2={} gen3={} world={}", targetImage(genTarget), targetImage(genTarget2), targetImage(genTarget3), targetImage(worldTarget));
		if (vk != null && BatchPresent.hasSemaphores()) { gpuSync().waitForGpuIdle(); BatchPresent.release(vk.vkDevice()); }
		if (genTarget != null) { gpuSync().waitForGpuIdle(); genTarget.destroyBuffers(); genTarget = null; }
		if (genTarget2 != null) { gpuSync().waitForGpuIdle(); genTarget2.destroyBuffers(); genTarget2 = null; }
		if (genTarget3 != null) { gpuSync().waitForGpuIdle(); genTarget3.destroyBuffers(); genTarget3 = null; }
		if (gpuInit && nat != null) { gpuSync().waitForGpuIdle(); nat.gpuRelease(); gpuInit = false; }
		if (worldTarget != null) { worldTarget.destroyBuffers(); worldTarget = null; }
	}

	private static String targetImage(TextureTarget t) {
		return t == null || t.getColorTexture() == null ? "none" : Long.toHexString(((VulkanGpuTexture) t.getColorTexture()).vkImage());
	}

	public boolean isAllocated() { return allocated; }
	public int renderWidth() { return rw; } public int renderHeight() { return rh; } public int outputWidth() { return ow; } public int outputHeight() { return oh; }
	public BackendId activeBackend() { return activeId; }
	public String executedProvider() { return nat == null ? "" : nat.activeProvider(); }

	// ---------------------------------------------------------------- frame hooks
	private static float halton(int index, int base) {
		float f = 1, r = 0; int i = index;
		while (i > 0) { f /= base; r += f * (i % base); i /= base; }
		return r;
	}

	/** Start of the world phase: world goes to the internal-resolution target. Returns false when upscaling is not applied this frame. */
	public boolean beginWorld(GameRenderer gr, UpscaleController controller) {
		worldPhaseActive = false;
		if (!controller.isActive() || !allocated || worldTarget == null) { if (aeDiag < 3 && AetheriumBridge.PRESENT && controller.config().enabled) { aeDiag++; Fsr4McClient.LOGGER.info("FSR beginWorld skipped: active={} allocated={} worldTarget={}", controller.isActive(), allocated, worldTarget != null); } return false; }
		savedMain = gr.mainRenderTarget;
		boolean aeScaling = AetheriumBridge.scaling();   // Aetherium already swapped in its reduced textures (the RenderTarget reports the reduced size): the real size comes from Aetherium
		int mainW = aeScaling && AetheriumBridge.fullWidth() > 0 ? AetheriumBridge.fullWidth() : savedMain.width, mainH = aeScaling && AetheriumBridge.fullHeight() > 0 ? AetheriumBridge.fullHeight() : savedMain.height;
		if (mainW != ow || mainH != oh) { if (aeDiag < 3) { aeDiag++; Fsr4McClient.LOGGER.info("FSR beginWorld skipped: main target {}x{} != output {}x{}", mainW, mainH, ow, oh); } return false; }   // window size changed this frame: the controller will resize at the next boundary
		int phases = Math.max(1, (int) Math.ceil(8.0 * Math.pow((double) ow / rw, 2)));
		int idx = (int) (frameIndex % phases);
		jitterX = halton(idx + 1, 2) - 0.5f; jitterY = halton(idx + 1, 3) - 0.5f;
		aeWorld = false;
		if (aeScaling) {   // Aetherium (shader pack) renders the world at reduced size into its own textures: do NOT redirect to worldTarget; FSR 4.1 replaces its upscale at the end of its world
			aeWorld = true; worldPhaseActive = true;
			return true;
		}
		gr.mainRenderTarget = worldTarget;
		RenderSystem.getDevice().createCommandEncoder().clearColorAndDepthTextures(worldTarget.getColorTexture(), gr.gameRenderState().guiRenderState.clearColorOverride, worldTarget.getDepthTexture(), 0.0);
		worldPhaseActive = true;
		return true;
	}

	public boolean worldPhaseActive() { return worldPhaseActive; }
	/** FG without upscaling: the projection is captured un-jittered (the prep pass still needs it for camera-reprojection motion vectors). */
	public void captureProjection(Matrix4f projection) { if (fgOnlyPlanes && fgAllocated) capturedProj.set(projection); }

	/** Sub-pixel jitter applied to the projection of the world pass (called from the projection hook). Also remembers the un-jittered matrix. */
	// Diagnostics for the Aetherium integration (isolate where a slowdown comes from): FSR4_AE_NOJITTER / FSR4_AE_NOPREP / FSR4_AE_PASSTHRU = 1
	private static final boolean AE_NOJITTER = "1".equals(System.getenv("FSR4_AE_NOJITTER")), AE_NOPREP = "1".equals(System.getenv("FSR4_AE_NOPREP")), AE_PASSTHRU = "1".equals(System.getenv("FSR4_AE_PASSTHRU")), AE_FLUSHONLY = "1".equals(System.getenv("FSR4_AE_FLUSHONLY")), AE_HANDMV = "1".equals(System.getenv("FSR4_AE_HANDMV"));
	public Matrix4f jitterProjection(Matrix4f projection) {
		capturedProj.set(projection);
		if (AE_NOJITTER && aeWorld) return projection;
		Matrix4f j = new Matrix4f(projection);
		float ox = fx(1) * 2.0f * jitterX / rw, oy = fx(2) * 2.0f * jitterY / rh;
		// clip' = clip + offset * w with w = -z_view (m23 = -1): column 2 changes. Y: image Y is down, matrix Y is up -> opposite sign.
		j.m20(j.m20() - ox);
		j.m21(j.m21() + oy);
		return j;
	}


	private void worldPrep2(GameRenderer gr) { boolean was = worldPhaseActive; worldPrep(gr); worldPhaseActive = was; }

	/** Set by worldPrep when this frame's depth/motion-vector pass already ran (before the hand). */
	private boolean prepDone;
	/** Test hook: the next world prep pass is flushed and its depth/MV planes are dumped (prepdump_*.bin in the game dir). */
	public static volatile boolean dumpNextPrep;
	private static final boolean PARTICLE_AB = "1".equals(System.getenv("FSR4_PARTICLE_AB"));
	public static volatile String prepDumpDir; public static volatile int prepDumpTag;
	private static final int PREP_DUMP_AT = Integer.parseInt(System.getenv().getOrDefault("FSR4_PREP_DUMP", "0")); private int prepFrames;
	/** Per-entity rigid motion vectors (on by default; FSR4_ENTITY_MV=0 disables). */
	public final EntityMotion entityMotion = new EntityMotion();
	private final boolean entityMvEnv = !"0".equals(System.getenv("FSR4_ENTITY_MV"));   // ON by default since session 8 (FSR 3.1.5 ghosting 13 % -> 5 %); FSR4_ENTITY_MV=0 disables
	private boolean entityMv() { return entityMvEnv || x3Active; }   // x3 always needs them (measured: +5.4 dB on moving entities)

	/**
	 * Mixin hook: right BEFORE GameRenderer.render3dHud. Minecraft clears the depth buffer there (so the first-person hand does not clip into terrain),
	 * which means this is the last moment the world depth exists. Records depth + camera-reprojection motion vectors into the shared planes.
	 */
	private long dbgPrepCalls, dbgFrames;
	private final float[] partBuf = new float[ParticleMotion.MAX * ParticleMotion.FLOATS];
	public void worldPrep(GameRenderer gr) {
		if (System.getenv("FSR4_PREP_COUNT") != null && (++dbgPrepCalls % 200) == 0) Fsr4McClient.LOGGER.info("PREPCOUNT worldPrep calls={} frames={}", dbgPrepCalls, dbgFrames);
		prepDone = false;
		boolean up = worldPhaseActive, fgOnly = fgOnlyPlanes && fgAllocated;
		if (!up && !fgOnly) return;
		if (!up && !fgSizeMatches(gr.mainRenderTarget)) return;   // FG-only planes belong to the previous window size until the next boundary re-creates them
		try {
			if (up && aeWorld && (AE_NOPREP || !aetheriumBindDepth())) { aeWorld = AE_NOPREP; worldPhaseActive = false; return; }   // Aetherium's scaled textures are not usable: its own upscale runs this frame
			CameraRenderState cam = gr.gameRenderState().levelRenderState.cameraRenderState;
			if (x3Active && !hasPrev) nat.x3Invalidate();   // no previous frame to interpolate from (alloc, resize, reset)
			curVp.set(capturedProj).mul(cam.viewRotationMatrix);
			if (up) curVpJit.set(jitterProjectionCached()).mul(cam.viewRotationMatrix); else curVpJit.set(curVp);
			curVpJit.invert(invJit);
			if (!hasPrev) { prevVp.set(curVp); prevX = cam.pos.x; prevY = cam.pos.y; prevZ = cam.pos.z; }
			float[] m = new float[48]; invJit.get(m, 0); prevVp.get(m, 16); curVp.get(m, 32);
			float[] d = {(float) (cam.pos.x - prevX), (float) (cam.pos.y - prevY), (float) (cam.pos.z - prevZ)};
			if (!up && Math.abs(d[0]) + Math.abs(d[1]) + Math.abs(d[2]) > 8f) fgWarmReset = true;   // teleport: camera cut
			if (entityMv()) {
				var mc = net.minecraft.client.Minecraft.getInstance();
				entityMotion.update(mc, gr.gameRenderState().levelRenderState.worldPartialTicks, cam.pos, mc.getCameraEntity(), mc.options.getCameraType().isFirstPerson());
				if (!hasPrev) entityMotion.reset();
			if (x3Active && !hasPrev) nat.x3Invalidate();
				if (nat.setEntities(entityMotion.data, entityMotion.count) != 0) throw new IllegalStateException(nat.lastError());
			} else if (nat.setEntities(entityMotion.data, 0) != 0) throw new IllegalStateException(nat.lastError());
			if (ParticleMotion.ENABLED) {   // per-particle depth + motion vectors (session 10): see docs/PARTICLE_MV.md
				var vr0 = cam.viewRotationMatrix; int pw = up ? rw : fgW, ph = up ? rh : fgH;
				int np = pw > 0 ? ParticleMotion.build(partBuf, -vr0.m02(), -vr0.m12(), -vr0.m22(), capturedProj.m00(), capturedProj.m11(), pw, ph) : 0;
				if (nat.setParticles(partBuf, np) != 0) throw new IllegalStateException(nat.lastError());
			}
			VulkanCommandEncoder enc = encoder();
			if (prepDumpDir != null && PARTICLE_AB && ParticleMotion.ENABLED) {   // test-only A/B recording (FSR4_PARTICLE_AB=1): the SAME frame's planes WITHOUT particle vectors (da_/ma_), then the normal pass with them (d_/m_)
				VkCommandBuffer cb0 = enc.allocateAndBeginTransientCommandBuffer();
				if (nat.setParticles(partBuf, 0) != 0 || nat.recordWorld(cb0.address(), m, d) != 0) throw new IllegalStateException(nat.lastError());
				VK12.vkEndCommandBuffer(cb0); enc.execute(cb0); enc.submit();
				nat.debugDump(1, prepDumpDir + "/da_" + String.format("%04d", prepDumpTag) + ".bin");
				nat.debugDump(2, prepDumpDir + "/ma_" + String.format("%04d", prepDumpTag) + ".bin");
				var vr1 = cam.viewRotationMatrix; int pw1 = up ? rw : fgW, ph1 = up ? rh : fgH;
				int np1 = pw1 > 0 ? ParticleMotion.build(partBuf, -vr1.m02(), -vr1.m12(), -vr1.m22(), capturedProj.m00(), capturedProj.m11(), pw1, ph1) : 0;
				if (nat.setParticles(partBuf, np1) != 0) throw new IllegalStateException(nat.lastError());
			}
			VkCommandBuffer cb = enc.allocateAndBeginTransientCommandBuffer();
			if (nat.recordWorld(cb.address(), m, d) != 0) throw new IllegalStateException(nat.lastError());
			VK12.vkEndCommandBuffer(cb);
			enc.execute(cb);
			if (prepDumpDir != null) {   // test-only dataset recording (FSR4_MOTION_PLANES): every frame's depth + MV planes, synchronously
				enc.submit();
				nat.debugDump(1, prepDumpDir + "/d_" + String.format("%04d", prepDumpTag) + ".bin");
				nat.debugDump(2, prepDumpDir + "/m_" + String.format("%04d", prepDumpTag) + ".bin");
				try { java.nio.file.Files.writeString(java.nio.file.Path.of(prepDumpDir, "cam_" + String.format("%04d", prepDumpTag) + ".txt"), java.util.Arrays.toString(m) + " " + java.util.Arrays.toString(d) + " " + System.nanoTime() + "\n"); } catch (java.io.IOException ignored) { }
			}
			if (captureTag == X3_DUMP_TAG) { enc.submit(); Fsr4McClient.LOGGER.info("X3DUMP right after prep: mv {} / depth {}", nat.debugDump(2, "p_mv.bin"), nat.debugDump(1, "p_d.bin")); }
			if (dumpNextPrep || (PREP_DUMP_AT > 0 && ++prepFrames == PREP_DUMP_AT)) { dumpNextPrep = false;   // test-only: flush this very frame's prep pass and read the planes back (no race with later frames)
				enc.submit();
				Fsr4McClient.LOGGER.info("PREPDUMP depth: {}", nat.debugDump(1, "prepdump_depth.bin"));
				Fsr4McClient.LOGGER.info("PREPDUMP mv: {}", nat.debugDump(2, "prepdump_mv.bin"));
				Fsr4McClient.LOGGER.info("PREPDUMP entities: count={} first={}", entityMotion.count, java.util.Arrays.toString(java.util.Arrays.copyOf(entityMotion.data, 16)));
			}
			prevVp.set(curVp); prevX = cam.pos.x; prevY = cam.pos.y; prevZ = cam.pos.z; hasPrev = true;
			prepDone = true;
		} catch (Throwable t) {
			fgError = String.valueOf(t.getMessage()); lastError = fgError;
			Fsr4McClient.LOGGER.error("FSR world prep pass failed", t);
		}
	}

	// ---------------------------------------------------------------- Mid-frame flush that Minecraft does not count
	private static java.lang.reflect.Method flushEndCb, flushTmEnd, flushTmBegin; private static java.lang.reflect.Field flushSub; private static boolean flushInit, flushOk;
	/**
	 * Sends the work recorded so far (and the semaphore signals already attached to the open submission) to the GPU WITHOUT going through VulkanCommandEncoder.submit().
	 * submit() also advances Minecraft's submit index and waits for the submit two indices earlier (MAX_SUBMITS_IN_FLIGHT = 2, two rotating command pools): one extra submit per frame
	 * therefore cuts the CPU's lead over the GPU from two frames to one and CPU/GPU stop overlapping (measured: 66.6 -> 34 fps with the shader scene, no helper involved).
	 * Here only the queue submission is closed and reopened; the later real submit() of the frame signals the index semaphore AFTER this work on the same queue, so Minecraft's own
	 * completion tracking (pool reset, destruction queue) stays correct. Returns false when unavailable (the caller then uses the normal submit()).
	 * FSR4_NOROTATE_FLUSH=0 disables it.
	 */
	private boolean flushNoRotate(VulkanCommandEncoder enc) {
		if (flushInit && !flushOk) return false;
		try {
			if (!flushInit) {
				flushInit = true;
				if ("0".equals(System.getenv("FSR4_NOROTATE_FLUSH"))) return false;
				flushEndCb = VulkanCommandEncoder.class.getDeclaredMethod("endCommandBuffer"); flushEndCb.setAccessible(true);
				flushSub = VulkanCommandEncoder.class.getDeclaredField("submissionBuilder"); flushSub.setAccessible(true);
				Class<?> tmc = enc.transientMemory().getClass();   // VulkanTransientMemory: beginSubmit() reserves a command buffer for staging copies as the FIRST command buffer of every submission; endSubmit() must end it before the submit
				flushTmEnd = tmc.getDeclaredMethod("endSubmit"); flushTmEnd.setAccessible(true);
				flushTmBegin = tmc.getDeclaredMethod("beginSubmit"); flushTmBegin.setAccessible(true);
				flushOk = true;
				Fsr4McClient.LOGGER.info("FSR: mid-frame flush without advancing Minecraft's submit index is active");
			}
			Object tm = enc.transientMemory();
			flushEndCb.invoke(enc);
			flushTmEnd.invoke(tm);   // ends the staging command buffer (an unended command buffer in a vkQueueSubmit is invalid and the whole submission, incl. our semaphore signal, never ran)
			((com.mojang.renderpearl.backend.vulkan.VulkanQueue.Submission) flushSub.get(enc)).close();
			flushSub.set(enc, vk.graphicsQueue().beginSubmit());
			flushTmBegin.invoke(tm);   // starts the next submission's staging command buffer, exactly as submit() does
			return true;
		} catch (Throwable t) {
			flushOk = false;
			Fsr4McClient.LOGGER.warn("FSR: no-rotate flush unavailable, using Minecraft's submit(): {}", t.toString());
			return false;
		}
	}

	// ---------------------------------------------------------------- Aetherium integration (see AetheriumBridge / AetheriumRenderScaleMixin)
	private boolean aeDone;
	private int aeDiag;

	/** worldPrep, Aetherium mode: sizes the shared planes to Aetherium's reduced world and points the prep pass at its depth. False: not usable this frame (Aetherium's own upscale runs). */
	private boolean aetheriumBindDepth() {
		try {
			com.mojang.renderpearl.api.textures.GpuTexture sd = AetheriumBridge.scaledDepth();
			int sw = AetheriumBridge.scaledWidth(), sh = AetheriumBridge.scaledHeight(), fw = AetheriumBridge.fullWidth(), fh = AetheriumBridge.fullHeight();
			if (!(sd instanceof VulkanGpuTexture vd) || sw <= 0 || sh <= 0 || fw <= 0 || fh <= 0 || !allocated || nat == null) return false;
			long depth = vd.vkImage();
			aeDone = false;
			if (rw != sw || rh != sh || ow != fw || oh != fh) {
				fgCollectPending(); gpuSync().waitForGpuIdle();
				fgAllocated = false; x3Active = false; fgOnlyPlanes = false; fgFrames = 0;   // new planes: the helper's FG context and the own x3 passes are re-created at the next boundary
				if (nat.gpuAlloc(sw, sh, fw, fh, depth) != 0) throw new IllegalStateException("shared planes " + sw + "x" + sh + ": " + nat.lastError());
				rw = sw; rh = sh; ow = fw; oh = fh; resetNext = true; hasPrev = false; aeDepthImage = depth; lastExecutedProvider = "";   // new planes: first-frame grace for the stall watchdog again
				Fsr4McClient.LOGGER.info("FSR 4.1 + Aetherium: Aetherium renders {}x{}, FSR upscales to {}x{}", rw, rh, ow, oh);
			} else if (depth != aeDepthImage) {
				gpuSync().waitForGpuIdle();
				if (nat.setDepth(depth) != 0) throw new IllegalStateException("prep depth rebind: " + nat.lastError());
				aeDepthImage = depth;
			}
			return true;
		} catch (Throwable t) {
			Fsr4McClient.LOGGER.error("FSR + Aetherium: cannot use Aetherium's scaled world, falling back to its own upscale", t);
			return false;
		}
	}

	/**
	 * Called from Aetherium's RenderScale.endWorld right after it restored the full-size textures: upscales Aetherium's reduced world (colour from its scaled texture, depth + motion
	 * vectors from the prep pass that already ran in worldPrep) with FSR 4.1 straight into the real main target. Returns true when FSR did it (Aetherium then skips its EASU/RCAS).
	 */
	public boolean aetheriumUpscale(UpscaleController controller, RenderTarget target, float frameTimeMs, float sharpness) {
		if (AE_PASSTHRU || !aeWorld || aeDone || !allocated || !prepDone || nat == null || !controller.isActive()) return false;
		aeDone = true;
		long t0 = System.nanoTime();
		try {
			if (!(AetheriumBridge.scaledColor() instanceof VulkanGpuTexture vc) || !(target.getColorTexture() instanceof VulkanGpuTexture vo)) return false;
			if (target.width != ow || target.height != oh) return false;
			CameraRenderState cam = Minecraft.getInstance().gameRenderer.gameRenderState().levelRenderState.cameraRenderState;
			VulkanCommandEncoder enc = encoder();
			VkCommandBuffer cb = enc.allocateAndBeginTransientCommandBuffer();
			// Aetherium does not clear its depth before the hand, so the hand overlay ("depth > 0 = hand pixel, zero motion") zeroed the motion vectors of the
			// whole world (measured 2026-11-14: mean 14.9 px after the prep pass, 0.38 px / 2.6 % non-zero after it): FSR 4.1, AMD FG, x3 and ANR all got no motion.
			// Colour copy only; FSR4_AE_HANDMV=1 restores the old behaviour for comparison.
			if ((AE_HANDMV ? nat.recordHandColor(cb.address(), vc.vkImage()) : nat.recordColor(cb.address(), vc.vkImage())) != 0) throw new IllegalStateException(nat.lastError());
			VK12.vkEndCommandBuffer(cb);
			enc.execute(cb);
			prepDone = false;
			boolean async = fenceSync;
			if (async) {
				if (nat.syncValues(fenceVals) != 0) throw new IllegalStateException(nat.lastError());
				lastIssuedFence = fenceVals[1];
				enc.signalSemaphore(sharedSem, fenceVals[0], ALL_COMMANDS);
				if (!flushNoRotate(enc)) enc.submit();
			} else {
				long v = ++timelineValue;
				enc.signalSemaphore(nat.timelineSemaphore(), v, ALL_COMMANDS);
				enc.submit();
				if (nat.waitTimeline(v, 2_000_000_000L) != 0) throw new IllegalStateException("GPU wait: " + nat.lastError());
			}
			if (AE_FLUSHONLY) return false;   // diagnostic: mid-frame flush + copy-in only, no helper round trip (Aetherium's own upscale then runs)
			int flags = (1 << 1) | (1 << 3) | (1 << 4);
			int[] ints = {(int) frameIndex, rw, rh, ow, oh, flags | (resetNext ? 1 : 0), activeQuality.ordinal(), sharpness > 0 ? 1 : 0};
			float fovY = (float) (2.0 * Math.atan(1.0 / capturedProj.m11()));
			float[] floats = {fx(4) * jitterX, fx(8) * jitterY, mvScaleX, mvScaleY, frameTimeMs, 1f, sharpness, 0.05f, cam.depthFar, fovY};
			float[] stats = new float[8];
			String prov = activeId.label.replace("FSR ", "");
			if (async) {
				if (nat.processAsync(ints, floats, prov, stats, fenceVals[0], fenceVals[1]) != 0) { String e = nat.lastError(); nat.signalShared(fenceVals[1]); throw new IllegalStateException(e); }
			} else if (nat.process(ints, floats, prov, stats) != 0) throw new IllegalStateException(nat.lastError());
			lastGpuDispatchMs = stats[0]; lastHelperMs = stats[1]; tHelperRecMs = stats[4]; tHelperWaitMs = stats[5];
			boolean firstConfirmed = lastExecutedProvider.isEmpty();
			if (activeId == BackendId.FSR4_1_1 || activeId == BackendId.FSR3_1_5) {
				lastExecutedProvider = nat.activeProvider();
				if (!sameProvider(lastExecutedProvider, prov)) throw new IllegalStateException("provider mismatch: wanted " + prov + " got " + lastExecutedProvider);
			}
			if (async && !NOWAIT_EXPERIMENT) enc.waitSemaphore(sharedSem, fenceVals[1], ALL_COMMANDS);
			VkCommandBuffer cb2 = enc.allocateAndBeginTransientCommandBuffer();
			if (nat.recordOutput(cb2.address(), vo.vkImage()) != 0) throw new IllegalStateException(nat.lastError());
			if (x3Active) { if (fgSizeMatches(target) && nat.x3RecordHudless(cb2.address(), vo.vkImage()) != 0) throw new IllegalStateException(nat.lastError()); }
			else if (fgAllocated && fgSizeMatches(target) && nat.recordFgHudless(cb2.address(), vo.vkImage()) != 0) throw new IllegalStateException(nat.lastError());
			VK12.vkEndCommandBuffer(cb2);
			enc.execute(cb2);
			if (firstConfirmed) Fsr4McClient.notifyUser(new Notification("FSR ACTIVO | " + activeId.label + " | Aetherium " + rw + "x" + rh + " -> " + ow + "x" + oh, false));
			prevVp.set(curVp); prevX = cam.pos.x; prevY = cam.pos.y; prevZ = cam.pos.z; hasPrev = true; resetNext = false; frameIndex++;
			lastBridgeFrameMs = (System.nanoTime() - t0) / 1e6f;
			return true;
		} catch (Throwable t) {
			lastError = String.valueOf(t.getMessage());
			Fsr4McClient.LOGGER.error("FSR + Aetherium frame failed, disabling FSR (Aetherium's own upscale takes over)", t);
			try { controller.reportRuntimeFailure(gpuSync(), lastError); } catch (Throwable ignored) { }
			String le = String.valueOf(lastError).toLowerCase();
			if (!bridgeAlive() || le.contains("timeout") || le.contains("stalled") || le.contains("died") || le.contains("not running")) {
				try { nat.bridgeStop(); } catch (Throwable ignored) { }
				bridgeState = BridgeState.FAILED; bridgeFailure = lastError; fgTeardown(true);
			}
			return false;
		}
	}

	/** End of the world phase: run the official upscaler and write the result into the real main target. */
	public void endWorld(GameRenderer gr, UpscaleController controller, float frameTimeMs, float sharpness) {
		fgCollectPending();
		if (!worldPhaseActive) { if (fgOnlyPlanes && fgAllocated) fgOnlyEndWorld(gr); return; }
		if (aeWorld) { worldPhaseActive = false; return; }   // Aetherium mode: nothing was redirected; FSR ran (or Aetherium's own upscale did) inside Aetherium's endWorld
		worldPhaseActive = false;
		gr.mainRenderTarget = savedMain;
		long t0 = System.nanoTime(); long tA = 0, tB = 0, tC = 0;
		try {
			CameraRenderState cam = gr.gameRenderState().levelRenderState.cameraRenderState;
			if (!prepDone) worldPrep2(gr);   // the hook did not run (should not happen): best effort, depth is gone by now
			VulkanCommandEncoder enc = encoder();
			VkCommandBuffer cb = enc.allocateAndBeginTransientCommandBuffer();
			long colorImg = ((VulkanGpuTexture) worldTarget.getColorTexture()).vkImage();
			if (nat.recordHandColor(cb.address(), colorImg) != 0) throw new IllegalStateException(nat.lastError());
			VK12.vkEndCommandBuffer(cb);
			enc.execute(cb);
			prepDone = false;
			tA = System.nanoTime();
			boolean async = fenceSync;
			if (async) {
				if (nat.syncValues(fenceVals) != 0) throw new IllegalStateException(nat.lastError());
				lastIssuedFence = fenceVals[1];
				enc.signalSemaphore(sharedSem, fenceVals[0], ALL_COMMANDS);
				if (!flushNoRotate(enc)) enc.submit();   // flush world + prep + copy-in; the GPU signals fenceVals[0], nothing waits on the CPU (not counted by Minecraft's 2-in-flight window)
			} else {
				long v = ++timelineValue;
				enc.signalSemaphore(nat.timelineSemaphore(), v, ALL_COMMANDS);
				enc.submit();   // flush this frame's work so far; the helper needs the planes to be complete
				if (nat.waitTimeline(v, 2_000_000_000L) != 0) throw new IllegalStateException("GPU wait: " + nat.lastError());
			}
			tB = System.nanoTime();

			int flags = (1 << 1) /*depth inverted*/ | (1 << 3) /*auto exposure: FSR 4.1.1 rejects a null exposure resource without it*/ | (1 << 4) /*non-linear sRGB*/;
			int[] ints = {(int) frameIndex, rw, rh, ow, oh, flags | (resetNext ? 1 : 0), activeQuality.ordinal(), sharpness > 0 ? 1 : 0};
			float fovY = (float) (2.0 * Math.atan(1.0 / capturedProj.m11()));
			float[] floats = {fx(4) * jitterX, fx(8) * jitterY, mvScaleX, mvScaleY, frameTimeMs, 1f, sharpness, 0.05f, cam.depthFar, fovY};
			float[] stats = new float[8];
			String prov = activeId.label.replace("FSR ", "");
			if (async) {
				if (nat.processAsync(ints, floats, prov, stats, fenceVals[0], fenceVals[1]) != 0) { String e = nat.lastError(); nat.signalShared(fenceVals[1]); throw new IllegalStateException(e); }
			} else if (nat.process(ints, floats, prov, stats) != 0) throw new IllegalStateException(nat.lastError());
			tC = System.nanoTime();
			lastGpuDispatchMs = stats[0]; lastHelperMs = stats[1]; tHelperRecMs = stats[4]; tHelperWaitMs = stats[5];
			tSubmitMs = (tA - t0) / 1e6f; tGpuWaitMs = (tB - tA) / 1e6f; tIpcMs = (tC - tB) / 1e6f;
			boolean firstConfirmed = lastExecutedProvider.isEmpty();
			if (activeId == BackendId.FSR4_1_1 || activeId == BackendId.FSR3_1_5) {
				lastExecutedProvider = nat.activeProvider();
				if (!sameProvider(lastExecutedProvider, prov))
					throw new IllegalStateException("provider mismatch: wanted " + prov + " got " + lastExecutedProvider);
			}

			if (async && !NOWAIT_EXPERIMENT) enc.waitSemaphore(sharedSem, fenceVals[1], ALL_COMMANDS);   // GPU waits for the helper's copy-out; the CPU does not
			VkCommandBuffer cb2 = enc.allocateAndBeginTransientCommandBuffer();
			if (nat.recordOutput(cb2.address(), ((VulkanGpuTexture) savedMain.getColorTexture()).vkImage()) != 0) throw new IllegalStateException(nat.lastError());
			if (x3Active) { if (fgSizeMatches(savedMain) && nat.x3RecordHudless(cb2.address(), ((VulkanGpuTexture) savedMain.getColorTexture()).vkImage()) != 0) throw new IllegalStateException(nat.lastError()); }   // size guard: during a window-size change the passes are re-created at the next boundary (an out-of-range copy was reported by the validation layer, session 10)
			else if (fgAllocated && fgSizeMatches(savedMain) && nat.recordFgHudless(cb2.address(), ((VulkanGpuTexture) savedMain.getColorTexture()).vkImage()) != 0) throw new IllegalStateException(nat.lastError());   // upscaled world, BEFORE the HUD: lets FG separate the UI
			VK12.vkEndCommandBuffer(cb2);
			enc.execute(cb2);
			if (firstConfirmed) Fsr4McClient.notifyUser(new Notification("FSR ACTIVO | " + activeId.label + " | primer cuadro procesado", false));

			prevVp.set(curVp); prevX = cam.pos.x; prevY = cam.pos.y; prevZ = cam.pos.z; hasPrev = true; resetNext = false; frameIndex++;
			lastBridgeFrameMs = (System.nanoTime() - t0) / 1e6f;
		} catch (Throwable t) {
			lastError = String.valueOf(t.getMessage());
			Fsr4McClient.LOGGER.error("FSR frame failed, disabling upscaling", t);
			// Show the internal image scaled (nearest) for this frame, then switch FSR off safely.
			try { worldTarget.blitAndBlendToTexture(savedMain.getColorTextureView(), null); } catch (Throwable ignored) { }
			controller.reportRuntimeFailure(gpuSync(), lastError);
			releaseGpuNow();
			String le = String.valueOf(lastError).toLowerCase();
			if (!bridgeAlive() || le.contains("timeout") || le.contains("stalled") || le.contains("died") || le.contains("not running")) {   // dead or stuck helper: kill it and clean up so that the next F-key press starts a fresh one
				try { nat.bridgeStop(); } catch (Throwable ignored) { }
				bridgeState = BridgeState.FAILED; bridgeFailure = lastError; fgTeardown(true);
			}
		}
	}

	/** Jitter sign convention (TestHarness `jflip N` overrides it for experiments): bit0/1 = flip projection jitter X/Y, bit2/3 = flip the jitter value passed to FFX X/Y. */
	public static volatile int jitterFlip = 8;   // measured (16 combinations, static converged scene vs native): the jitter Y passed to FFX must be negated relative to the projection offset (+2.7 dB, +7 % sharpness)
	private static float fx(int bit) { return (jitterFlip & bit) != 0 ? -1f : 1f; }
	/** Motion vector scale passed to FFX (1,1 = pixels at render resolution). TestHarness `mvscale X Y` overrides it for experiments. */
	public static volatile float mvScaleX = 1f, mvScaleY = 1f;
	private final Matrix4f jitterCache = new Matrix4f();
	private Matrix4f jitterProjectionCached() {
		jitterCache.set(capturedProj);
		float ox = fx(1) * 2.0f * jitterX / rw, oy = fx(2) * 2.0f * jitterY / rh;
		jitterCache.m20(jitterCache.m20() - ox); jitterCache.m21(jitterCache.m21() + oy);
		return jitterCache;
	}

	public Optional<RenderTarget> worldTarget() { return Optional.ofNullable(worldTarget); }

	// ======================================================================================================================================
	// Frame Generation (AMD FSR FG API 4.0.1 through the same Wine bridge). Independent of upscaling: with FSR off the world is rendered at
	// native size and only the prep pass (depth + camera-reprojection motion vectors) runs ("FG-only"); with FSR on it reuses the upscaler's planes.
	// ======================================================================================================================================
	public final FgStats fg = new FgStats();
	public volatile String fgStatus = "OFF", fgError = "", fgProviderReported = "";
	private boolean fgAllocated, fgOnlyPlanes, fgWarmReset = true;
	/** FG x3 (EXPERIMENTAL own backend, no AMD code): active instead of the AMD FG context when the multiplier is 3. */
	private boolean x3Active;
	public boolean x3Active() { return x3Active; }
	public float x3GpuMs() { return x3Active && nat != null ? nat.x3GpuMs() : -1f; }
	public long x3Bytes() { return x3Active && nat != null ? nat.x3Bytes() : 0; }
	/** Test hook (FSR4_X3_CAPTURE): frame tag set by the harness; each generated frame is read back as x3g_<tag>_<idx>.png. */
	public static volatile int captureTag = -1;
	private int fgW, fgH, fgFrames;
	private long fgFrameId;
	private TextureTarget genTarget, genTarget2, genTarget3;   // genTarget2/3: further generated images of a batched x3/x4 frame (all are blitted from inside one submit)
	private int x3N = 3;   // FG multiplier of the own backend: 3 or 4
	private TextureTarget genFor(int idx) { return idx == 1 ? genTarget : idx == 2 ? genTarget2 : genTarget3; }
	/** Y scale of the vector given to AMD FG. +1 is right (measured against TRUE intermediate frames, scripts/x2-ingame-report.py: 33.7 dB vs 28.7 dB with -1); an earlier session-8 measurement against an average-of-neighbours proxy wrongly preferred -1 and was retracted. Kept as an env knob for measurements only. */
	private static final float FG_MVY = Float.parseFloat(System.getenv().getOrDefault("FSR4_FG_MVY", "1")), FG_UP = Float.parseFloat(System.getenv().getOrDefault("FSR4_FG_UP", "1"));
	private final float[] fgFloats = new float[20], fgStatsArr = new float[8];
	private final int[] fgInts = new int[2];
	private long lastHookEntryNs, avgIntervalNs = 1_000_000, lastRealPresentNs, lastPaceNs;
	private boolean fgFailedNotified;
	private boolean fgOutstanding;
	public boolean fgActive() { return fgAllocated; }
	public String debugDump(int plane, String path) { return nat == null || !gpuInit ? "no GPU bridge" : nat.debugDump(plane, path); }
	public RenderTarget generatedTarget() { return genTarget; }

	/** Collects the status of the asynchronous FG request posted by the previous frame. MUST run before any other bridge call. */
	private void fgCollectPending() {
		if (!fgOutstanding || nat == null) return;
		fgOutstanding = false;
		long t = System.nanoTime();
		int rc = nat.fgCollect(fgStatsArr);
		fg.ipcSumMs += (System.nanoTime() - t) / 1e6;
		fg.gpuFgSumMs += fgStatsArr[0]; fg.gpuN++;
		if (rc != 0) {
			String e = nat.lastError(); fgError = e; Fsr4McClient.LOGGER.error("FG request failed: {}", e);
			unblockGpu();
			String le = e.toLowerCase(); if (le.contains("timeout") || le.contains("stalled") || le.contains("died") || le.contains("not running")) stallSuspect = true;
			Fsr4McClient.requestFgOff(e);
		}
	}
	public boolean fgOnly() { return fgOnlyPlanes; }



	// ---- stall watchdog -------------------------------------------------------------------------------------------------------------------
	private volatile long lastFrameStartNs;
	private volatile boolean stallSuspect;
	private Thread stallThread;
	public void frameStarted() { lastFrameStartNs = System.nanoTime(); dbgFrames++; }

	/** If a GPU wait on the shared fence is pending and no frame started for 3 s, the helper is stuck or gone: free the GPU (the render thread would block forever in the kernel) and flag it for a hard reset. */
	private synchronized void startStallWatchdog() {
		if (stallThread != null && stallThread.isAlive()) return;
		stallThread = new Thread(() -> {
			long lastAct = 0;
			while (!Thread.currentThread().isInterrupted()) {
				try { Thread.sleep(250); } catch (InterruptedException e) { return; }
				try {
					long now = System.nanoTime(); Fsr4Native n = nat;
					// Before the first frame of an allocation has been processed the render thread is legitimately busy for many seconds (the helper creates the FFX context while the shader pack compiles on the same GPU:
					// measured ~10.7 s in a freshly loaded world): 45 s of grace then, 3 s afterwards.
					long grace = lastExecutedProvider.isEmpty() ? 45_000_000_000L : 3_000_000_000L;
					if (n == null || bridgeState != BridgeState.RUNNING || !fenceSync || lastFrameStartNs == 0 || now - lastFrameStartNs < grace || now - lastAct < 3_000_000_000L) continue;
					long counter = n.sharedCounter(), issued = lastIssuedFence;
					if (counter >= 0 && issued > counter) {
						lastAct = now; stallSuspect = true;
						Fsr4McClient.LOGGER.error("FSR stall watchdog: GPU waiting for fence value {} (current {}) and no frame for {} ms: releasing the GPU wait, the helper will be reset", issued, counter, (now - lastFrameStartNs) / 1_000_000);
						n.signalShared(issued);
					}
				} catch (Throwable ignored) { }
			}
		}, "fsr4mc-stall-watchdog");
		stallThread.setDaemon(true); stallThread.start();
	}

	/** Kills the helper and cleans every GPU resource so that the next F-key press starts a fresh bridge. Render thread. */
	public void hardResetBridge(UpscaleController controller, Fsr4Config cfg, Consumer<Notification> notify, String why) {
		Fsr4McClient.LOGGER.error("FSR bridge hard reset: {}", why);
		unblockGpu();
		try { gpuSync().waitForGpuIdle(); } catch (Throwable ignored) { }
		fgOutstanding = false; stallSuspect = false;
		boolean fgWas = cfg.frameGeneration;
		if (controller.isActive() || controller.config().enabled) controller.reportRuntimeFailure(gpuSync(), why);
		fgTeardown(true);
		if (fgWas) { cfg.frameGeneration = false; Fsr4McClient.save(cfg); notify.accept(new Notification("FRAME GENERATION: FALLO (" + why + ") - DESACTIVADO", true)); }
		releaseGpuNow();
		try { if (nat != null) nat.bridgeStop(); } catch (Throwable ignored) { }
		bridgeState = BridgeState.FAILED; bridgeFailure = why; lastError = why; fgError = why;
	}

	// ---- helper failure handling ---------------------------------------------------------------------------------------------------------
	/** Frees every GPU wait on the shared timeline (the helper that should have signalled it is gone or stuck). Harmless if already signalled. */
	public void unblockGpu() { try { if (nat != null && gpuInit && lastIssuedFence != 0) nat.signalShared(lastIssuedFence); } catch (Throwable ignored) { } }

	/** Per-frame check (render thread): the Wine helper died or was killed while the bridge was supposed to be running. */
	public void helperWatchdog(UpscaleController controller, Fsr4Config cfg, Consumer<Notification> notify) {
		frameStarted();
		if (bridgeState != BridgeState.RUNNING || nat == null) return;
		if (stallSuspect) { hardResetBridge(controller, cfg, notify, "el helper de Wine dejo de responder (vigilante de bloqueo)"); return; }
		if (nat.bridgeRunning()) return;
		hardResetBridge(controller, cfg, notify, "el proceso helper de Wine ha terminado");
	}

	/** Called once per frame (render thread, frame start) with the user's intent; (de)allocates FG resources and reports failures. */
	public void fgBoundary(GameRenderer gr, Fsr4Config cfg, Consumer<Notification> notify) {
		fgCollectPending();
		if (!cfg.frameGeneration) {
			if (fgAllocated || fgOnlyPlanes || genTarget != null) fgTeardown(false);
			fgStatus = "OFF"; fgFailedNotified = false; return;
		}
		if (!fgModeCandidate(cfg.fgMultiplier)) {
			fgFail(cfg, notify, "FG x" + cfg.fgMultiplier + " incompatible or failed on this Vulkan device");
			return;
		}
		if (bridgeState != BridgeState.RUNNING) {
			if (bridgeState == BridgeState.NOT_STARTED) startBridgeAsync();
			if (bridgeState == BridgeState.FAILED) fgFail(cfg, notify, "bridge: " + bridgeFailure); else fgStatus = "STARTING BRIDGE";
			return;
		}
		RenderTarget main = gr.mainRenderTarget;
		if (worldPhaseActive || main == null) { if (!fgAllocated) fgStatus = "PENDING (waiting for a frame boundary)"; return; }
		int w = main.width, h = main.height;
		if (fgAllocated && (fgW != w || fgH != h)) { fgTeardown(false); return; }   // window size changed: re-create at the next boundary
		if (fgAllocated) {
			if (allocated == fgOnlyPlanes) fgTeardown(false);   // upscaler switched on/off underneath us
			else if (x3Active != inProcessFg(cfg) || (x3Active && (x3N != cfg.fgMultiplier || x3Anr != cfg.anrSelected()))) fgTeardown(false);   // multiplier or generator changed: re-create at the next boundary
			return;
		}
		try {
			if (allocated) {
				if (ow != w || oh != h) { fgStatus = "PENDING (waiting for the upscaler to resize)"; return; }   // the controller is about to re-allocate the upscaler for the new size
			} else {
				if (!fgOnlyPlanes) allocateFgOnlyPlanes(main);
				else if (fgW != w || fgH != h) { fgTeardown(false); return; }
			}
			String prov;
			if (inProcessFg(cfg)) {   // EXPERIMENTAL own backend or ANR FG, both inside Minecraft's Vulkan device: no AMD frame-generation context is created at all
				boolean flatpak = Files.exists(Path.of("/.flatpak-info")) || System.getenv("FLATPAK_ID") != null;
				if (cfg.anrSelected()) {
					String dll = anrDll();
					int max = nat.anrConfigure(dll);
					if (max < 0) throw new BackendException("ANR FG: " + nat.lastError());
					if (cfg.fgMultiplier - 1 > max) throw new BackendException("ANR FG offers up to " + max + " generated frames per real frame, x" + cfg.fgMultiplier + " needs " + (cfg.fgMultiplier - 1));
					Fsr4McClient.LOGGER.info("ANR FG provider loaded: {} (MultiFrameCountMax {})", dll, max);
				} else nat.anrDisable();
				x3Anr = cfg.anrSelected();
				Fsr4McClient.LOGGER.info("FSR x3Alloc: flatpak={}, display={}x{}", flatpak, w, h);
				if (nat.x3Alloc(w, h) != 0) {
					String err = nat.lastError();
					Fsr4McClient.LOGGER.error("FSR x3Alloc FAILED: flatpak={}, error={}", flatpak, err);
					throw new BackendException("FG x3 setup failed: " + err);
				}
				x3Active = true; x3N = cfg.fgMultiplier; BatchPresent.maxExtra = x3N - 1; prov = "";
				fgProviderReported = "";
			} else {
				boolean flatpak = Files.exists(Path.of("/.flatpak-info")) || System.getenv("FLATPAK_ID") != null;
				Fsr4McClient.LOGGER.info("FSR fgAlloc (AMD FG 4.0.1): flatpak={}, display={}x{}", flatpak, w, h);
				int rc = nat.fgAlloc(w, h, "4.0.1", 1 << 1 /* depth inverted */);
				if (rc != 0) {
					String err = nat.lastError();
					Fsr4McClient.LOGGER.error("FSR fgAlloc (AMD FG) FAILED: flatpak={}, error={}", flatpak, err);
					throw new BackendException("frame generation setup failed: " + err);
				}
				prov = nat.fgProvider();
				if (!prov.startsWith("4.0.1") && !(IS_WINDOWS && prov.startsWith("4.0."))) throw new BackendException("the AMD runtime created frame generation provider '" + prov + "', expected 4.0.1");
				fgProviderReported = prov;
			}
			genTarget = new TextureTarget("FSR generated frame", w, h, GpuFormat.RGBA8_UNORM, null);
			if (x3Active) genTarget2 = new TextureTarget("FSR generated frame 2", w, h, GpuFormat.RGBA8_UNORM, null);
			if (x3Active && x3N >= 4) genTarget3 = new TextureTarget("FSR generated frame 3", w, h, GpuFormat.RGBA8_UNORM, null);
			fgW = w; fgH = h; fgAllocated = true; fgFrames = 0; fgFrameId = 0; fgWarmReset = true; hasPrev = false; fgFailedNotified = false;
			fgModeFailed[cfg.fgMultiplier] = false;
			fgStatus = (x3Active ? "ACTIVE x" + x3N + (x3Anr ? " ANR FG (" : " EXPERIMENTAL, own backend (") : "ACTIVE (") + (allocated ? "with upscaling" : "FG only") + ")";
			if (x3Active && x3Anr) {
				Fsr4McClient.LOGGER.info("FSR FG x{} ANR FG (Apex Neural Rendering, external beta) allocated: display {}x{}, {} MiB, mode {}", x3N, w, h, nat.x3Bytes() / 1048576, allocated ? "with upscaler planes" : "FG-only (native render + prep pass)");
				notify.accept(new Notification("FG x" + x3N + " ANR (Apex Neural Rendering) ACTIVO", false));
			} else if (x3Active) {
				Fsr4McClient.LOGGER.info("FSR FG x{} (EXPERIMENTAL own backend, NOT AMD FG) allocated: display {}x{}, {} MiB, mode {}, single-pass int64 shader: " + VulkanCaps.int64Atomics + ", particle MV: " + ParticleMotion.ENABLED, x3N, w, h, nat.x3Bytes() / 1048576, allocated ? "with upscaler planes" : "FG-only (native render + prep pass)");
				notify.accept(new Notification("FG x" + x3N + " EXPERIMENTAL (backend propio, no AMD) ACTIVO", false));
			} else {
				Fsr4McClient.LOGGER.info("FSR Frame Generation allocated: provider {} (reported by the AMD runtime), display {}x{}, mode {}", prov, w, h, allocated ? "with upscaler planes" : "FG-only (native render + prep pass)");
				notify.accept(new Notification("FRAME GENERATION ACTIVO | " + prov, false));
			}
		} catch (BackendException | RuntimeException e) {
			if (cfg.fgMultiplier >= 2 && cfg.fgMultiplier <= 4) fgModeFailed[cfg.fgMultiplier] = true;
			fgFail(cfg, notify, String.valueOf(e.getMessage()));
		}
	}

	private void allocateFgOnlyPlanes(RenderTarget main) throws BackendException {
		ensureGpuInit();
		long depth = ((VulkanGpuTexture) main.getDepthTexture()).vkImage();
		int w = main.width, h = main.height;
		boolean flatpak = Files.exists(Path.of("/.flatpak-info")) || System.getenv("FLATPAK_ID") != null;
		Fsr4McClient.LOGGER.info("FSR fgAlloc (FG-only): flatpak={}, display={}x{}, depthImage=0x{}",
			flatpak, w, h, Long.toHexString(depth));
		int rc = nat.gpuAlloc(w, h, w, h, depth);
		if (rc != 0) {
			String err = nat.lastError();
			Fsr4McClient.LOGGER.error("FSR fgAlloc (FG-only) FAILED: flatpak={}, error={}", flatpak, err);
			throw new BackendException("shared planes for FG-only mode: " + err);
		}
		fgOnlyPlanes = true; fgW = w; fgH = h; hasPrev = false;
	}

	private void fgFail(Fsr4Config cfg, Consumer<Notification> notify, String why) {
		fgError = why; fgStatus = "FAILED: " + why;
		Fsr4McClient.LOGGER.error("FSR Frame Generation unavailable: {}", why);
		fgTeardown(true);
		if (!fgFailedNotified) { fgFailedNotified = true; notify.accept(new Notification("FRAME GENERATION NO DISPONIBLE: " + why, true)); }
		cfg.frameGeneration = false; Fsr4McClient.save(cfg);
	}

	/** Releases FG resources (GPU idle first). keepStatus leaves fgStatus/fgError for the overlay. */
	/** Dedicated Vulkan command encoder for the generated-frame submission (see presentHook). Lazily created; FSR4_FG_OWN_ENC=0 falls back to Minecraft's encoder. */
	// Pacing on/off and K come from FgPacingPolicy (auto: VSync -> 0.5, no VSync -> 1.0, both measured); FSR4_PACE_K still overrides K.
	private static final Double ENV_PACE_K = dev.fsr4mc.core.FgPacingPolicy.parseEnvK(System.getenv("FSR4_PACE_K"));
	private String lastPacingReason;
	private dev.fsr4mc.core.FgPacingPolicy.Decision pacing(Fsr4Config cfg) {
		var d = dev.fsr4mc.core.FgPacingPolicy.decide(dev.fsr4mc.core.FgPacingPolicy.mode(cfg.fgPacing, cfg.fgPacingAuto), BatchPresent.fifo(), ENV_PACE_K);
		if (!d.reason().equals(lastPacingReason)) { lastPacingReason = d.reason(); Fsr4McClient.LOGGER.info("FG pacing: {} (K={})", d.reason(), d.pace() ? d.k() : 0); }
		return d;
	}   // 1.0: measured best (shader scene, GPU-bound): 144-150 fps, 99th-percentile present interval 9.1-10.1 ms
	private VulkanCommandEncoder fgBackendEnc; private FrontendCommandEncoder fgFrontEnc; private boolean fgEncFailed;
	private boolean ensureFgEncoder() {
		if (fgFrontEnc != null) return true;
		if (fgEncFailed || vk == null || "0".equals(System.getenv("FSR4_FG_OWN_ENC"))) return false;
		try {
			FrontendCommandEncoder base = (FrontendCommandEncoder) RenderSystem.getDevice().createCommandEncoder();
			var fp = FrontendCommandEncoder.class.getDeclaredField("profiler"); fp.setAccessible(true);
			var fd = FrontendCommandEncoder.class.getDeclaredField("device"); fd.setAccessible(true);
			java.lang.reflect.Constructor<?> ctor = null;
			for (var c : FrontendCommandEncoder.class.getConstructors()) if (c.getParameterCount() == 3) { ctor = c; break; }
			if (ctor == null) throw new NoSuchMethodException("FrontendCommandEncoder(profiler, device, backend)");
			fgBackendEnc = new VulkanCommandEncoder(vk);
			fgFrontEnc = (FrontendCommandEncoder) ctor.newInstance(fp.get(base), fd.get(base), fgBackendEnc);
			Fsr4McClient.LOGGER.info("FG: dedicated command encoder created (generated-frame submits no longer count against Minecraft's 2-in-flight window)");
			return true;
		} catch (Throwable t) {
			fgEncFailed = true; fgFrontEnc = null;
			if (fgBackendEnc != null) { try { fgBackendEnc.destroy(); } catch (Throwable ignored) { } fgBackendEnc = null; }
			Fsr4McClient.LOGGER.warn("FG: dedicated encoder unavailable, using Minecraft's encoder: {}", t.toString());
			return false;
		}
	}

	public void fgTeardown(boolean keepStatus) {
		boolean wasOnly = fgOnlyPlanes;
		try { gpuSync().waitForGpuIdle(); } catch (Throwable ignored) { }
		if (fgBackendEnc != null) { try { fgBackendEnc.destroy(); } catch (Throwable ignored) { } fgBackendEnc = null; fgFrontEnc = null; }
		if (nat != null && gpuInit && fgAllocated && !x3Active) { try { nat.fgRelease(); } catch (Throwable ignored) { } }
		if (nat != null && gpuInit && x3Active) { try { nat.x3Release(); } catch (Throwable ignored) { } }
		x3Active = false; fgAllocated = false; fgFrames = 0;
		if (genTarget != null) { try { genTarget.destroyBuffers(); } catch (Throwable ignored) { } genTarget = null; }
		if (genTarget2 != null) { try { genTarget2.destroyBuffers(); } catch (Throwable ignored) { } genTarget2 = null; }
		if (genTarget3 != null) { try { genTarget3.destroyBuffers(); } catch (Throwable ignored) { } genTarget3 = null; }
		if (wasOnly) { fgOnlyPlanes = false; if (gpuInit && nat != null) { try { nat.gpuRelease(); } catch (Throwable ignored) { } gpuInit = false; } }
		if (!keepStatus) fgStatus = "OFF";
	}

	private boolean fgSizeMatches(RenderTarget t) { return fgAllocated && t.width == fgW && t.height == fgH; }

	/** World finished without upscaling: run only the prep pass so depth + motion vectors exist for FG. Recorded into the frame's current batch. */
	private void fgOnlyEndWorld(GameRenderer gr) {
		if (!fgSizeMatches(gr.mainRenderTarget)) { prepDone = false; return; }
		try {
			if (!prepDone) { if (System.getenv("FSR4_PREP_COUNT") != null) Fsr4McClient.LOGGER.info("PREPCOUNT fallback prep (hook did not run before the depth clear)"); worldPrep2(gr); }
			VulkanCommandEncoder enc = encoder();
			VkCommandBuffer cb = enc.allocateAndBeginTransientCommandBuffer();
			long colorImg = ((VulkanGpuTexture) gr.mainRenderTarget.getColorTexture()).vkImage();
			if (nat.recordHandColor(cb.address(), colorImg) != 0) throw new IllegalStateException(nat.lastError());
			if (x3Active) { if (fgSizeMatches(gr.mainRenderTarget) && nat.x3RecordHudless(cb.address(), colorImg) != 0) throw new IllegalStateException(nat.lastError()); }
			else if (fgSizeMatches(gr.mainRenderTarget) && nat.recordFgHudless(cb.address(), colorImg) != 0) throw new IllegalStateException(nat.lastError());   // native world (incl. hand), BEFORE the HUD
			VK12.vkEndCommandBuffer(cb);
			enc.execute(cb);
			if (captureTag == X3_DUMP_TAG) { enc.submit(); Fsr4McClient.LOGGER.info("X3DUMP after hand pass: mv {} / depth {}", nat.debugDump(2, "h_mv.bin"), nat.debugDump(1, "h_d.bin")); }
			prepDone = false;
		} catch (Throwable t) {
			fgError = String.valueOf(t.getMessage()); Fsr4McClient.LOGGER.error("FSR FG prep pass failed, disabling frame generation", t);
			Fsr4McClient.requestFgOff(fgError);
		}
	}

	/** Mixin hook, end of the frame BEFORE Minecraft blits the real frame: runs FG on the final image (with HUD) and presents the generated frame first. */
	/** FG hook CPU time per segment (profiling): 0 input copy+submit, 1 fgPost IPC, 2 output copy record, 3 blit+submit+present, 4 acquireNextTexture; [5] = hooks counted. */
	public final double[] fgSeg = new double[6], fgSegB = new double[3];   // fgSegB: blit record / submit / present
	public void presentHook(Minecraft mc, GpuSurface surface, Fsr4Config cfg) {
		long t0 = System.nanoTime();
		if (lastHookEntryNs != 0) { long iv = Math.max(100_000L, t0 - lastHookEntryNs - lastPaceNs); avgIntervalNs += (iv - avgIntervalNs) / 16; }   // interval WITHOUT our own pacing wait (else the wait feeds back into itself)
		lastPaceNs = 0;
		lastHookEntryNs = t0; fg.realFrames++;
		if (!fgAllocated || !surface.isAcquired() || genTarget == null) return;
		RenderTarget main = mc.gameRenderer.mainRenderTarget();
		if (main.width != fgW || main.height != fgH) return;
		if (x3Active) { x3PresentHook(mc, surface, cfg, main, t0); return; }
		long signalPending = 0;
		try {
			VulkanCommandEncoder enc = encoder();
			// 1) final colour (incl. HUD) -> helper; the GPU signals W when the copy is done
			if (nat.syncValues(fenceVals) != 0) throw new IllegalStateException(nat.lastError());
			lastIssuedFence = fenceVals[1];
			VkCommandBuffer cb = enc.allocateAndBeginTransientCommandBuffer();
			if (nat.recordFgInput(cb.address(), ((VulkanGpuTexture) main.getColorTexture()).vkImage()) != 0) throw new IllegalStateException(nat.lastError());
			VK12.vkEndCommandBuffer(cb);
			enc.execute(cb);
			enc.signalSemaphore(sharedSem, fenceVals[0], ALL_COMMANDS);
			if (!flushNoRotate(enc)) enc.submit();
			// 2) helper: Configure + PrepareV2 + FG dispatch, queue-side wait for W and signal S (no CPU wait on the GPU)
			CameraRenderState cam = mc.gameRenderer.gameRenderState().levelRenderState.cameraRenderState;
			fgInts[0] = (int) ++fgFrameId; fgInts[1] = fgWarmReset ? 1 : 0;
			float fovY = (float) (2.0 * Math.atan(1.0 / capturedProj.m11()));
			var vr = cam.viewRotationMatrix;
			fgFloats[0] = allocated ? fx(4) * jitterX : 0f; fgFloats[1] = allocated ? fx(8) * jitterY : 0f; fgFloats[2] = 1f; fgFloats[3] = FG_MVY; fgFloats[4] = Fsr4McClient.lastFrameMs(); fgFloats[5] = 0.05f; fgFloats[6] = cam.depthFar; fgFloats[7] = fovY;
			fgFloats[8] = (float) cam.pos.x; fgFloats[9] = (float) cam.pos.y; fgFloats[10] = (float) cam.pos.z;
			fgFloats[11] = FG_UP * vr.m01(); fgFloats[12] = FG_UP * vr.m11(); fgFloats[13] = FG_UP * vr.m21();       // up    = row 1 of the world->view rotation
			fgFloats[14] = vr.m00(); fgFloats[15] = vr.m10(); fgFloats[16] = vr.m20();       // right = row 0
			fgFloats[17] = -vr.m02(); fgFloats[18] = -vr.m12(); fgFloats[19] = -vr.m22();    // forward = -row 2
			signalPending = fenceVals[1];
			long tI = System.nanoTime(); fgSeg[0] += (tI - t0) / 1e6;
			fgCollectPending();   // (normally already collected at the frame boundary; keeps the protocol one-request-at-a-time)
			if (nat.fgPost(fgInts, fgFloats, fenceVals[0], fenceVals[1]) != 0) throw new IllegalStateException(nat.lastError());
			fgOutstanding = true; signalPending = 0;   // the helper now owns the signal of S (it signals even on failure)
			fg.ipcSumMs += (System.nanoTime() - tI) / 1e6; fgSeg[1] += (System.nanoTime() - tI) / 1e6;
			fgWarmReset = false; fgFrames++;
			// 3) generated frame back into a Minecraft texture (GPU waits for the helper, the CPU does not)
			long tC = System.nanoTime();
			// The generated-frame submission goes through OUR OWN encoder: Minecraft's submit() waits for the submit issued two calls earlier (MAX_SUBMITS_IN_FLIGHT = 2),
			// so with three submits per frame (input copy, generated blit, final blit) the CPU was stalled on the GPU every frame and CPU/GPU stopped overlapping.
			boolean own = ensureFgEncoder();
			VulkanCommandEncoder genEnc = own ? fgBackendEnc : enc;
			com.mojang.renderpearl.api.commands.CommandEncoder genFront = own ? fgFrontEnc : RenderSystem.getDevice().createCommandEncoder();
			genEnc.waitSemaphore(sharedSem, fenceVals[1], ALL_COMMANDS);
			VkCommandBuffer cb2 = genEnc.allocateAndBeginTransientCommandBuffer();
			if (nat.recordFgOutput(cb2.address(), ((VulkanGpuTexture) genTarget.getColorTexture()).vkImage()) != 0) throw new IllegalStateException(nat.lastError());
			VK12.vkEndCommandBuffer(cb2);
			genEnc.execute(cb2);
			fg.genCopySumMs += (System.nanoTime() - tC) / 1e6; fgSeg[2] += (System.nanoTime() - tC) / 1e6;
			if (own && fgFrames < 3) genFront.submit();   // warm-up frames: flush the copy now, nothing is presented yet
			// 4) present the generated frame (the first frames after (re)start have no valid history: the provider outputs a copy of the real frame)
			if (fgFrames >= 3) {
				long tB = System.nanoTime();
				surface.blitFromTexture(genFront, genTarget.getColorTextureView());
				long tB1 = System.nanoTime(); fgSegB[0] += (tB1 - tB) / 1e6;
				genFront.submit();
				long tB2 = System.nanoTime(); fgSegB[1] += (tB2 - tB1) / 1e6;
				surface.present();
				fgSegB[2] += (System.nanoTime() - tB2) / 1e6;
				long tp = System.nanoTime(); fg.onPresent(tp); fg.generatedPresented++; fgSeg[3] += (tp - tB) / 1e6;
				surface.acquireNextTexture(); fgSeg[4] += (System.nanoTime() - tp) / 1e6;
				var pd = pacing(cfg);
				if (pd.pace()) {   // even pacing: the real frame goes out half an interval after the generated one
					// avgIntervalNs excludes our own wait (see presentHook entry), so when the GPU is the bottleneck (the spin hides behind GPU work) a factor of 0.5 settles at 1/3 of the frame period,
					// not 1/2: intervals of ~5 and ~11 ms instead of ~8 and ~8. PACE_K = 1.0 centres it there; lower it (FSR4_PACE_K=0.5) where the CPU is the bottleneck, because there the wait stretches the frame. Pacing itself stays opt-in (fgPacing).
					long target = tp + (long) (avgIntervalNs * pd.k()); long w0 = System.nanoTime();
					while (System.nanoTime() < target) Thread.onSpinWait();
					lastPaceNs = System.nanoTime() - w0; fg.paceWaitSumMs += lastPaceNs / 1e6;
				}
			}
		} catch (SurfaceException e) {
			Fsr4McClient.LOGGER.warn("FG: swapchain re-acquire failed: {}", e.toString());
		} catch (Throwable t) {
			if (signalPending != 0) { try { nat.signalShared(signalPending); } catch (Throwable ignored) { } }
			fgError = String.valueOf(t.getMessage()); Fsr4McClient.LOGGER.error("FG frame failed, disabling frame generation", t);
			Fsr4McClient.requestFgOff(fgError);
		}
		fg.hookCpuSumMs += (System.nanoTime() - t0) / 1e6; fg.hookN++; fgSeg[5]++;
	}

	/**
	 * FG x3 (EXPERIMENTAL own backend): before Minecraft presents real frame k, generate and present the two intermediate frames between k-1 and k (t=1/3, 2/3), then end the frame
	 * (current becomes previous). Same present sequence as the AMD x2 path (generated frames first, real frame right after); no frame is ever duplicated: when there is no valid
	 * previous frame (first frame, resize, camera cut) nothing extra is presented.
	 */
	private void x3PresentHook(Minecraft mc, GpuSurface surface, Fsr4Config cfg, RenderTarget main, long t0) {
		try {
			VulkanCommandEncoder enc = encoder();
			long finalImg = ((VulkanGpuTexture) main.getColorTexture()).vkImage(), dstImg = ((VulkanGpuTexture) genTarget.getColorTexture()).vkImage();
			if (fgWarmReset) { nat.x3Invalidate(); fgWarmReset = false; }
			final boolean batch = genTarget2 != null && (x3N < 4 || genTarget3 != null) && BatchPresent.supported(surface) && !X3_NOPRESENT && captureTag != X3_DUMP_TAG;
			for (int idx = 1; idx < x3N; idx++) {
				long g0 = System.nanoTime();
				VkCommandBuffer cb = enc.allocateAndBeginTransientCommandBuffer();
				TextureTarget gt = batch ? genFor(idx) : genTarget;
				int ok = nat.x3RecordGenerateN(cb.address(), finalImg, idx, x3N, ((VulkanGpuTexture) gt.getColorTexture()).vkImage());
				VK12.vkEndCommandBuffer(cb);
				if (ok != 1) break;
				enc.execute(cb);
				fgSeg[0] += (System.nanoTime() - g0) / 1e6;   // x3 profile: 0 generate record+execute, 2 end-of-frame record, 3 blit+submit+present, 4 acquire; fgSegB: blit record / submit / present
				if (batch) {   // batched: acquire + blit recorded into the frame's single submit; presented by presentBatch() right after it
					long b0 = System.nanoTime();
					BatchPresent.acquireAndBlit(surface, enc, gt.getColorTextureView());
					fgSegB[0] += (System.nanoTime() - b0) / 1e6;
					continue;
				}
				if (captureTag == X3_DUMP_TAG && idx == 1) { enc.submit(); Fsr4McClient.LOGGER.info("X3DUMP mv at present: {} / depth {}", nat.debugDump(2, "x3_mv.bin"), nat.debugDump(1, "x3_d.bin")); }
				if (captureTag >= 0 && X3_CAPTURE) net.minecraft.client.Screenshot.grab(mc.gameDirectory, "x3g_" + String.format("%03d", captureTag) + "_" + idx + ".png", genTarget, 1, m -> { });
				if (X3_NOPRESENT) continue;   // experiment only (measures compute-only cost; nothing extra is shown)
				long tB = System.nanoTime();
				surface.blitFromTexture(RenderSystem.getDevice().createCommandEncoder(), genTarget.getColorTextureView());
				long tB1 = System.nanoTime(); fgSegB[0] += (tB1 - tB) / 1e6;
				if (!flushNoRotate(enc)) RenderSystem.getDevice().createCommandEncoder().submit();   // per-image submit of the unbatched x3/x4 path (VSync): must not advance Minecraft's submit index either
				long tB2 = System.nanoTime(); fgSegB[1] += (tB2 - tB1) / 1e6;
				surface.present();
				long tp = System.nanoTime(); fgSegB[2] += (tp - tB2) / 1e6; fgSeg[3] += (tp - tB) / 1e6;
				fg.onPresent(tp); fg.generatedPresented++; fgFrames++;
				surface.acquireNextTexture();
				fgSeg[4] += (System.nanoTime() - tp) / 1e6;
				if (pacing(cfg).pace()) { long target = tp + avgIntervalNs / x3N; long w0 = System.nanoTime(); while (System.nanoTime() < target) Thread.onSpinWait(); lastPaceNs += System.nanoTime() - w0; fg.paceWaitSumMs += (System.nanoTime() - w0) / 1e6; }
			}
			long e0 = System.nanoTime();
			VkCommandBuffer cb = enc.allocateAndBeginTransientCommandBuffer();
			nat.x3RecordEnd(cb.address());
			VK12.vkEndCommandBuffer(cb);
			enc.execute(cb);
			fgSeg[2] += (System.nanoTime() - e0) / 1e6;
			if (X3_SYNC) { enc.submit(); gpuSync().waitForGpuIdle(); }   // test only: rules out GPU-behind-CPU effects
		} catch (SurfaceException e) {
			Fsr4McClient.LOGGER.warn("FG x3: swapchain re-acquire failed: {}", e.toString());
		} catch (Throwable t) {
			fgError = String.valueOf(t.getMessage()); Fsr4McClient.LOGGER.error("FG x3 frame failed, disabling frame generation", t);
			Fsr4McClient.requestFgOff(fgError);
		}
		fg.hookCpuSumMs += (System.nanoTime() - t0) / 1e6; fg.hookN++; fgSeg[5]++;
	}
	private static final int X3_DUMP_TAG = Integer.parseInt(System.getenv().getOrDefault("FSR4_X3_DUMP", "-2"));
	private static final boolean X3_NOPRESENT = System.getenv("FSR4_X3_NOPRESENT") != null;
	private static final boolean X3_SYNC = System.getenv("FSR4_X3_SYNC") != null;
	private static final boolean X3_CAPTURE = System.getenv("FSR4_X3_CAPTURE") != null;

	/** Replaces Minecraft's present of the real frame when batched generated images are pending (they go first, in order). */
	public void presentBatch(com.mojang.renderpearl.api.device.GpuSurface surface) {
		long t = System.nanoTime();
		int n = BatchPresent.presentAll(surface);
		long tp = System.nanoTime();
		if (captureTag >= 0 && X3_CAPTURE) { var mc = Minecraft.getInstance(); for (int i = 1; i <= n; i++) net.minecraft.client.Screenshot.grab(mc.gameDirectory, "x3g_" + String.format("%03d", captureTag) + "_" + i + ".png", genFor(i), 1, m -> { }); }
		for (int i = 0; i < n; i++) { fg.onPresent(tp); fg.generatedPresented++; fgFrames++; }
		fgSegB[2] += (tp - t) / 1e6;
	}

	/** Mixin hook right after Minecraft presented the real frame. */
	public void afterRealPresent() {
		long now = System.nanoTime();
		fg.onPresent(now); fg.realPresented++;
		if (lastHookEntryNs != 0) { fg.latencySumMs += (now - lastHookEntryNs) / 1e6; fg.latencyN++; }
	}
}
