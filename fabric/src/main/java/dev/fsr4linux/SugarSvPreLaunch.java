package dev.fsr4linux;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import net.fabricmc.api.EnvType;
import net.fabricmc.loader.api.FabricLoader;
import net.fabricmc.loader.api.entrypoint.PreLaunchEntrypoint;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.List;
import java.util.Locale;

/**
 * SugarSV all-in-one: runs before Minecraft reads options.txt, so the whole setup is in place on the very first launch.
 * <ul>
 *   <li>Installs the native runtime bundled in the all-in-one jar (sugarsv-runtime/, SHA-256 manifest) into config/fsr4linux-runtime (Windows only;
 *       the plain fsr4linux jar has no bundled runtime and this step does nothing).</li>
 *   <li>First launch only (marker config/sugarsv-installed.json): the measured setup. Vulkan backend (the mod does nothing on OpenGL) and VSync in
 *       options.txt; FSR 4.1 QUALITY + AMD FG x2 + automatic pacing in fsr4linux.json; Aetherium with the Better Voxels pack found in shaderpacks/
 *       at renderScale 65. Afterwards the player's own changes are never overwritten.</li>
 *   <li>Every launch (coordination, one owner per optimisation): with FerriteCore loaded, Aetherium's copies of the same three memory optimisations
 *       (block-state cache dedup, PalettedContainer threading-detector removal, data-component patch map) are switched off in aetherium.json.</li>
 * </ul>
 * Every step is best effort: a failure is logged and the game starts as usual.
 */
public final class SugarSvPreLaunch implements PreLaunchEntrypoint {
	private static final Logger LOG = LoggerFactory.getLogger("sugarsv");
	private static final Gson GSON = new GsonBuilder().setPrettyPrinting().create();
	private static final int SETUP_VERSION = 1;

	@Override
	public void onPreLaunch() {
		FabricLoader fl = FabricLoader.getInstance();
		if (fl.getEnvironmentType() != EnvType.CLIENT) return;
		Path config = fl.getConfigDir(), game = fl.getGameDir();
		try { installRuntime(config.resolve("fsr4linux-runtime")); } catch (Throwable t) { LOG.warn("SugarSV: runtime install failed: {}", t.toString()); }
		Path marker = config.resolve("sugarsv-installed.json");
		if (!Files.exists(marker)) {
			try { firstSetup(config, game); Files.writeString(marker, "{\"setupVersion\": " + SETUP_VERSION + "}\n"); }
			catch (Throwable t) { LOG.warn("SugarSV: first-launch setup failed: {}", t.toString()); }
		}
		try { coordinate(fl, config); } catch (Throwable t) { LOG.warn("SugarSV: coordination step failed: {}", t.toString()); }
	}

	/** Extracts sugarsv-runtime/* (listed with SHA-256 in sugarsv-runtime/MANIFEST) where a file is missing or differs. */
	static void installRuntime(Path dir) throws IOException {
		if (!System.getProperty("os.name", "").toLowerCase(Locale.ROOT).contains("win")) return;
		ClassLoader cl = SugarSvPreLaunch.class.getClassLoader();
		try (InputStream m = cl.getResourceAsStream("sugarsv-runtime/MANIFEST")) {
			if (m == null) return;   // plain fsr4linux jar: nothing bundled
			int written = 0;
			for (String line : new String(m.readAllBytes(), StandardCharsets.UTF_8).split("\\R")) {
				line = line.strip(); if (line.isEmpty() || line.startsWith("#")) continue;
				String[] p = line.split("\\s+", 2); String sha = p[0], rel = p[1];
				Path out = dir.resolve(rel).normalize();
				if (!out.startsWith(dir)) continue;
				if (Files.exists(out) && sha.equalsIgnoreCase(sha256(out))) continue;
				try (InputStream in = cl.getResourceAsStream("sugarsv-runtime/" + rel)) {
					if (in == null) { LOG.warn("SugarSV: bundled runtime file missing: {}", rel); continue; }
					Files.createDirectories(out.getParent());
					Path tmp = out.resolveSibling(out.getFileName() + ".tmp");
					Files.copy(in, tmp, StandardCopyOption.REPLACE_EXISTING);
					Files.move(tmp, out, StandardCopyOption.REPLACE_EXISTING);
					written++;
				}
			}
			if (written > 0) LOG.info("SugarSV: installed {} runtime file(s) into {}", written, dir);
		}
	}

	static void firstSetup(Path config, Path game) throws IOException {
		// options.txt: Vulkan + VSync (FG x2 locks to the display refresh through FIFO; measured 50 real + 50 generated at 100 Hz)
		Path opt = game.resolve("options.txt");
		List<String> lines = Files.exists(opt) ? new ArrayList<>(Files.readAllLines(opt, StandardCharsets.UTF_8)) : new ArrayList<>();
		// A new options.txt needs the "version:" (data version) line: without it Minecraft treats the file as pre-1.13, runs the data fixer over it and
		// resets preferredGraphicsBackend to "default" = OpenGL (measured on a clean 26.3 install). Read from the game jar's version.json, not from
		// game classes (loading them in preLaunch would bypass their mixins).
		if (lines.stream().noneMatch(l -> l.startsWith("version:"))) {
			Integer dv = dataVersion();
			if (dv != null) lines.add(0, "version:" + dv); else LOG.warn("SugarSV: game data version unknown: options.txt left without a version line");
		}
		setOption(lines, "preferredGraphicsBackend", "\"vulkan\"");
		setOption(lines, "enableVsync", "true");
		Files.write(opt, lines, StandardCharsets.UTF_8);
		Path fsr = config.resolve("fsr4linux.json");
		if (!Files.exists(fsr)) {
			Files.createDirectories(config);
			Files.writeString(fsr, "{\n  \"enabled\": true,\n  \"backend\": \"FSR4_1_1\",\n  \"quality\": \"QUALITY\",\n  \"sharpness\": 0.5,\n  \"debugOverlay\": false,\n"
				+ "  \"fpsCounter\": true,\n  \"frameGeneration\": true,\n  \"fgPacing\": false,\n  \"fgPacingAuto\": true,\n  \"fgMultiplier\": 2,\n  \"fgGenerator\": \"AUTO\"\n}\n");
		}
		Path ae = config.resolve("aetherium.json");
		String pack = findBetterVoxels(game.resolve("shaderpacks"));
		if (pack != null) {
			JsonObject root = readJson(ae); JsonObject sh = root.has("shaders") && root.get("shaders").isJsonObject() ? root.getAsJsonObject("shaders") : new JsonObject();
			sh.addProperty("pack", pack); sh.addProperty("enabled", true); if (!sh.has("renderScale")) sh.addProperty("renderScale", 65);
			root.add("shaders", sh); Files.writeString(ae, GSON.toJson(root));
			LOG.info("SugarSV: Aetherium set to shader pack {} at renderScale {}", pack, sh.get("renderScale"));
		} else LOG.info("SugarSV: no Better Voxels pack in shaderpacks/: Aetherium left as is");
		LOG.info("SugarSV: first-launch setup written (Vulkan, VSync, FSR 4.1 QUALITY + AMD FG x2 + automatic pacing)");
	}

	static void coordinate(FabricLoader fl, Path config) throws IOException {
		if (!fl.isModLoaded("aetherium") || !fl.isModLoaded("ferritecore")) return;
		Path ae = config.resolve("aetherium.json"); JsonObject root = readJson(ae);
		JsonObject opt = root.has("optimisations") && root.get("optimisations").isJsonObject() ? root.getAsJsonObject("optimisations") : new JsonObject();
		boolean changed = false;
		for (String k : new String[]{"mem.state_cache_dedup", "mem.palette_lock", "mem.component_patch"}) {
			if (!opt.has(k) || opt.get(k).getAsBoolean()) { opt.addProperty(k, false); changed = true; }
		}
		if (changed) { root.add("optimisations", opt); Files.createDirectories(config); Files.writeString(ae, GSON.toJson(root)); LOG.info("SugarSV: FerriteCore owns the block-state/palette/component memory optimisations; Aetherium's copies switched off"); }
	}

	/** world_version from the game jar's version.json (e.g. 5023 for 26.3). */
	private static Integer dataVersion() {
		try (InputStream in = SugarSvPreLaunch.class.getClassLoader().getResourceAsStream("version.json")) {
			if (in == null) return null;
			var o = JsonParser.parseString(new String(in.readAllBytes(), StandardCharsets.UTF_8)).getAsJsonObject();
			return o.has("world_version") ? o.get("world_version").getAsInt() : null;
		} catch (Exception e) { return null; }
	}

	private static void setOption(List<String> lines, String key, String value) {
		for (int i = 0; i < lines.size(); i++) if (lines.get(i).startsWith(key + ":")) { lines.set(i, key + ":" + value); return; }
		lines.add(key + ":" + value);
	}

	private static String findBetterVoxels(Path dir) {
		if (!Files.isDirectory(dir)) return null;
		try (var s = Files.list(dir)) {
			return s.map(p -> p.getFileName().toString())
				.filter(n -> n.toLowerCase(Locale.ROOT).replaceAll("§.", "").replace(" ", "").contains("bettervoxels") && (n.toLowerCase(Locale.ROOT).endsWith(".zip") || Files.isDirectory(dir.resolve(n))))
				.sorted().reduce((a, b) -> b).orElse(null);
		} catch (IOException e) { return null; }
	}

	private static JsonObject readJson(Path p) {
		try { if (Files.exists(p)) { var e = JsonParser.parseString(Files.readString(p)); if (e.isJsonObject()) return e.getAsJsonObject(); } } catch (Exception ignored) { }
		return new JsonObject();
	}

	private static String sha256(Path p) throws IOException {
		try { MessageDigest md = MessageDigest.getInstance("SHA-256"); try (InputStream in = Files.newInputStream(p)) { byte[] b = new byte[1 << 16]; int n; while ((n = in.read(b)) > 0) md.update(b, 0, n); } return HexFormat.of().formatHex(md.digest()); }
		catch (java.security.NoSuchAlgorithmException e) { throw new IOException(e); }
	}
}
