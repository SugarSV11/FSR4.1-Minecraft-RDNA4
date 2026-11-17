package dev.fsr4mc.core;

import static org.junit.jupiter.api.Assertions.*;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class LegacyMigrationTest {
	@TempDir Path dir;

	private Path config() throws IOException { return Files.createDirectories(dir.resolve("config")); }

	@Test void oldConfigIsCopiedAndConverted() throws IOException {
		Path c = config();
		Files.writeString(c.resolve("fsr4linux.json"), "{\"enabled\": true, \"quality\": \"BALANCED\"}");
		Files.createDirectories(c.resolve("fsr4linux-runtime/lib"));
		Files.write(c.resolve("fsr4linux-runtime/lib/fsr4linux.dll"), new byte[]{1, 2, 3});
		Files.writeString(c.resolve("fsr4linux-runtime/note.txt"), "dir fsr4linux-runtime");
		var r = LegacyMigration.migrate(c, dir.resolve("options.txt"));
		assertEquals(2, r.copied().size());
		assertEquals("{\"enabled\": true, \"quality\": \"BALANCED\"}", Files.readString(c.resolve("fsr4mc.json")));
		assertArrayEquals(new byte[]{1, 2, 3}, Files.readAllBytes(c.resolve("fsr4mc-runtime/lib/fsr4linux.dll")));   // native names are unchanged
		assertEquals("dir fsr4mc-runtime", Files.readString(c.resolve("fsr4mc-runtime/note.txt")));   // identifiers inside text are rewritten
		assertTrue(Files.exists(c.resolve("fsr4linux.json")), "old files are kept");
		assertTrue(Fsr4Config.load(c.resolve("fsr4mc.json")).enabled);
	}

	@Test void existingNewConfigIsNeverOverwritten() throws IOException {
		Path c = config();
		Files.writeString(c.resolve("fsr4linux.json"), "{\"enabled\": true}");
		Files.writeString(c.resolve("fsr4mc.json"), "{\"enabled\": false}");
		var r = LegacyMigration.migrate(c, dir.resolve("options.txt"));
		assertTrue(r.copied().isEmpty());
		assertEquals("{\"enabled\": false}", Files.readString(c.resolve("fsr4mc.json")));
	}

	@Test void secondRunChangesNothing() throws IOException {
		Path c = config(); Path opt = dir.resolve("options.txt");
		Files.writeString(c.resolve("fsr4linux.json"), "{}");
		Files.writeString(opt, "key_key.fsr4linux.toggle:key.keyboard.g\n");
		assertTrue(LegacyMigration.migrate(c, opt).changedAnything());
		String after = Files.readString(opt);
		var again = LegacyMigration.migrate(c, opt);
		assertFalse(again.changedAnything());
		assertEquals(after, Files.readString(opt));
	}

	@Test void keybindsKeepTheKeyAndTheRestOfOptions() throws IOException {
		Path opt = dir.resolve("options.txt");
		Files.writeString(opt, "version:5023\nfov:0.5\nkey_key.fsr4linux.toggle:key.keyboard.g\nkey_key.fsr4linux.framegen:key.keyboard.h\nkey_key.jump:key.keyboard.space\nlang:es_es\n");
		var r = LegacyMigration.migrate(config(), opt);
		assertEquals(2, r.keybindsMigrated());
		assertEquals("version:5023\nfov:0.5\nkey_key.fsr4mc.toggle:key.keyboard.g\nkey_key.fsr4mc.framegen:key.keyboard.h\nkey_key.jump:key.keyboard.space\nlang:es_es\n", Files.readString(opt));
		assertTrue(Files.exists(dir.resolve("options.txt.fsr4linux.bak")));
		assertTrue(Files.readString(dir.resolve("options.txt.fsr4linux.bak")).contains("key_key.fsr4linux.toggle"));
	}

	@Test void existingNewKeybindWins() throws IOException {
		Path opt = dir.resolve("options.txt");
		Files.writeString(opt, "key_key.fsr4linux.toggle:key.keyboard.g\nkey_key.fsr4mc.toggle:key.keyboard.k\n");
		var r = LegacyMigration.migrate(config(), opt);
		assertEquals(0, r.keybindsMigrated());
		assertEquals("key_key.fsr4mc.toggle:key.keyboard.k\n", Files.readString(opt));
	}

	@Test void crlfLineEndingsArePreserved() throws IOException {
		Path opt = dir.resolve("options.txt");
		Files.write(opt, "fov:0.5\r\nkey_key.fsr4linux.settings:key.keyboard.f6\r\n".getBytes(StandardCharsets.UTF_8));
		LegacyMigration.migrate(config(), opt);
		assertEquals("fov:0.5\r\nkey_key.fsr4mc.settings:key.keyboard.f6\r\n", Files.readString(opt));
	}

	@Test void missingFilesAreHarmless() throws IOException {
		var r = LegacyMigration.migrate(dir.resolve("nope"), dir.resolve("nope.txt"));
		assertFalse(r.changedAnything());
		assertFalse(LegacyMigration.migrate(null, null).changedAnything());
	}
}
