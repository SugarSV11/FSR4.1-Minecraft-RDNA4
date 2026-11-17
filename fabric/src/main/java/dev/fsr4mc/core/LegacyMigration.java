package dev.fsr4mc.core;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.FileVisitResult;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.SimpleFileVisitor;
import java.nio.file.StandardCopyOption;
import java.nio.file.attribute.BasicFileAttributes;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.stream.Stream;

/**
 * One-time migration from the old mod id {@code fsr4linux} to {@code fsr4mc}. Pure file logic (no game classes) so it is unit tested.
 * <ul>
 *   <li>{@code config/fsr4linux*} is copied to {@code config/fsr4mc*} (same suffix) when the target does not exist; text files
 *       ({@code .json}, {@code .properties}, {@code .txt}) get their {@code fsr4linux} identifiers rewritten. Old files are never deleted or
 *       overwritten, and an existing {@code fsr4mc*} entry is never touched.</li>
 *   <li>{@code key_key.fsr4linux.*} lines of options.txt become {@code key_key.fsr4mc.*} keeping the player's key. If the new key already has a
 *       line, it wins. The first rewrite saves {@code options.txt.fsr4linux.bak}.</li>
 * </ul>
 * Running it again changes nothing.
 */
public final class LegacyMigration {
	public static final String OLD = "fsr4linux", NEW = "fsr4mc";
	private static final String OLD_KEY = "key_key." + OLD + ".", NEW_KEY = "key_key." + NEW + ".";

	public record Result(List<String> copied, int keybindsMigrated) {
		public boolean changedAnything() { return !copied.isEmpty() || keybindsMigrated > 0; }
	}

	private LegacyMigration() {}

	public static Result migrate(Path configDir, Path optionsTxt) throws IOException {
		List<String> copied = copyConfig(configDir);
		int keys = migrateKeybinds(optionsTxt);
		return new Result(copied, keys);
	}

	static List<String> copyConfig(Path configDir) throws IOException {
		List<String> copied = new ArrayList<>();
		if (configDir == null || !Files.isDirectory(configDir)) return copied;
		List<Path> olds = new ArrayList<>();
		try (Stream<Path> s = Files.list(configDir)) {
			s.filter(p -> p.getFileName().toString().startsWith(OLD)).sorted().forEach(olds::add);
		}
		for (Path old : olds) {
			String name = old.getFileName().toString();
			Path target = configDir.resolve(NEW + name.substring(OLD.length()));
			if (Files.exists(target)) continue;   // a new config always wins
			copyTree(old, target);
			copied.add(name + " -> " + target.getFileName());
		}
		return copied;
	}

	private static void copyTree(Path from, Path to) throws IOException {
		if (!Files.isDirectory(from)) { copyFile(from, to); return; }
		Files.walkFileTree(from, new SimpleFileVisitor<>() {
			@Override public FileVisitResult preVisitDirectory(Path dir, BasicFileAttributes a) throws IOException {
				Files.createDirectories(to.resolve(from.relativize(dir).toString())); return FileVisitResult.CONTINUE;
			}
			@Override public FileVisitResult visitFile(Path f, BasicFileAttributes a) throws IOException {
				copyFile(f, to.resolve(from.relativize(f).toString())); return FileVisitResult.CONTINUE;
			}
		});
	}

	private static void copyFile(Path from, Path to) throws IOException {
		String n = from.getFileName().toString().toLowerCase(Locale.ROOT);
		if (n.endsWith(".json") || n.endsWith(".properties") || n.endsWith(".txt")) {
			String text = Files.readString(from, StandardCharsets.UTF_8);
			Files.writeString(to, text.replace(OLD, NEW), StandardCharsets.UTF_8);
		} else {
			Files.copy(from, to, StandardCopyOption.COPY_ATTRIBUTES);
		}
	}

	static int migrateKeybinds(Path options) throws IOException {
		if (options == null || !Files.isRegularFile(options)) return 0;
		String text = Files.readString(options, StandardCharsets.UTF_8);
		if (!text.contains(OLD_KEY)) return 0;
		String eol = text.contains("\r\n") ? "\r\n" : "\n";
		String[] lines = text.split("\\R", -1);
		List<String> out = new ArrayList<>();
		int migrated = 0;
		for (String line : lines) {
			if (line.startsWith(OLD_KEY)) {
				String renamed = NEW_KEY + line.substring(OLD_KEY.length());
				int colon = renamed.indexOf(':');
				String key = colon < 0 ? renamed : renamed.substring(0, colon + 1);
				boolean exists = false;
				for (String l : lines) if (l.startsWith(key)) { exists = true; break; }
				if (exists) continue;   // the new key already has a value: keep it, drop the stale one
				out.add(renamed); migrated++;
			} else out.add(line);
		}
		Path bak = options.resolveSibling(options.getFileName() + "." + OLD + ".bak");
		if (!Files.exists(bak)) Files.copy(options, bak);
		Files.writeString(options, String.join(eol, out), StandardCharsets.UTF_8);
		return migrated;
	}
}
