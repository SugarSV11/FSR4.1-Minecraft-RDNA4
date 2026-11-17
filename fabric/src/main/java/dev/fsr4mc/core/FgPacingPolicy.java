package dev.fsr4mc.core;

/**
 * Chooses whether frame-generation pacing runs and how far after the generated frame the real one is held (K, as a fraction of the average real-frame interval).
 * The automatic choice is taken from measurements on an RX 9060 XT (Better Voxels high, Aetherium renderScale 65, FSR 4.1 + AMD FG x2, 1080p, 2026-11-14):
 * <ul>
 *   <li>VSync (FIFO), static camera: no pacing 5.8-6.2 ms added latency / present-interval std 4.5-4.9 ms; K=0.5 9.3 ms / 1.4-1.5 ms; K=1.0 13.5 ms / 2.9 ms.</li>
 *   <li>VSync (FIFO), camera moving through new chunks: no pacing std 5.6-6.7 ms, p99 20-25 ms; K=0.5 std 2.1-2.7 ms, p99 14-19 ms, at +3.5 ms latency.</li>
 *   <li>No VSync, GPU-bound: K=1.0 measured best earlier (144-150 presented FPS, present-interval p99 9.1-10.1 ms).</li>
 * </ul>
 * FSR4_PACE_K (environment) still overrides K for experiments.
 */
public final class FgPacingPolicy {
	public enum Mode { AUTO, ON, OFF }

	public record Decision(boolean pace, double k, String reason) { }

	public static final double K_FIFO = 0.5, K_FREE = 1.0;

	private FgPacingPolicy() { }

	/** Manual on wins, then the automatic mode, else off. Matches the config fields: {@code fgPacing} (manual on) and {@code fgPacingAuto}. */
	public static Mode mode(boolean manualOn, boolean auto) { return manualOn ? Mode.ON : auto ? Mode.AUTO : Mode.OFF; }

	public static Decision decide(Mode mode, boolean vsyncFifo, Double envK) {
		if (mode == Mode.OFF) return new Decision(false, 0, "off");
		if (envK != null) return new Decision(true, envK, "FSR4_PACE_K=" + envK);
		if (mode == Mode.ON) return new Decision(true, K_FREE, "manual");   // the manual setting keeps its historical K
		return vsyncFifo ? new Decision(true, K_FIFO, "auto: vsync") : new Decision(true, K_FREE, "auto: no vsync");
	}

	/** Parses FSR4_PACE_K; null when unset or invalid, clamped to [0.1, 1.5] otherwise. */
	public static Double parseEnvK(String s) {
		if (s == null) return null;
		try { return Math.max(0.1, Math.min(1.5, Double.parseDouble(s))); } catch (NumberFormatException e) { return null; }
	}
}
