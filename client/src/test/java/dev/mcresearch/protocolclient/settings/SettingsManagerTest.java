package dev.mcresearch.protocolclient.settings;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import dev.mcresearch.protocolclient.input.GameAction;
import dev.mcresearch.protocolclient.input.InputCode;
import dev.mcresearch.protocolclient.input.KeyBindings;
import dev.mcresearch.protocolclient.input.Keys;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.stream.Stream;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/** M15 Phase E/K: Laden, Speichern, Robustheit der Einstellungen (docs/60 §3, docs/62 §3). */
class SettingsManagerTest {
	@TempDir
	Path dir;

	@Test
	void missingFileGivesDefaultsAndIsCreatedOnSave() throws Exception {
		Path f = this.dir.resolve("sub/settings.properties");
		SettingsManager m = SettingsManager.load(f);
		assertEquals(SettingsManager.LoadStatus.MISSING, m.status());
		assertEquals(70, m.get(Settings.FOV));
		assertEquals(InputCode.key(Keys.W), m.bindings().get(GameAction.MOVE_FORWARD));
		m.set(Settings.FOV, 90);
		assertTrue(m.save().ok());
		assertTrue(Files.isRegularFile(f));
		try (Stream<Path> s = Files.list(f.getParent())) {
			assertEquals(1, s.count(), "no temp files left");
		}
	}

	@Test
	void saveAndReloadRoundTrip() {
		Path f = this.dir.resolve("settings.properties");
		SettingsManager m = SettingsManager.load(f);
		m.set(Settings.RENDER_DISTANCE, 3);
		m.set(Settings.BRIGHTNESS, 0.75);
		m.set(Settings.PARTICLES, "MINIMAL");
		m.set(Settings.OFFLINE_NAME, "Tester_01");
		m.set(Settings.INVERT_Y, true);
		assertEquals(KeyBindings.Change.OK, m.bind(GameAction.JUMP, InputCode.key('J')));
		assertEquals(KeyBindings.Change.OK, m.bind(GameAction.ATTACK, InputCode.mouse(3)));
		assertTrue(m.save().ok());
		SettingsManager r = SettingsManager.load(f);
		assertEquals(SettingsManager.LoadStatus.LOADED, r.status());
		assertTrue(r.notes().isEmpty(), r.notes().toString());
		assertEquals(3, r.get(Settings.RENDER_DISTANCE));
		assertEquals(0.75, r.get(Settings.BRIGHTNESS), 1e-9);
		assertEquals("MINIMAL", r.get(Settings.PARTICLES));
		assertEquals("Tester_01", r.get(Settings.OFFLINE_NAME));
		assertTrue(r.get(Settings.INVERT_Y));
		assertEquals(InputCode.key('J'), r.bindings().get(GameAction.JUMP));
		assertEquals(InputCode.mouse(3), r.bindings().get(GameAction.ATTACK));
		assertFalse(r.dirty());
		assertEquals(m.snapshot(), r.snapshot());
	}

	@Test
	void invalidValuesFallBackToDefaultsWithNotes() throws Exception {
		Path f = this.dir.resolve("settings.properties");
		Files.writeString(f, """
			settings.version=2
			graphics.fov=500
			graphics.renderDistance=abc
			graphics.vsync=vielleicht
			graphics.particles=LOTS
			account.offlineName=no spaces allowed
			graphics.brightness=NaN
			controls.key.jump=key:99999
			controls.key.sneak=banana
			controls.key.move_forward=key:256
			graphics.maxFps=60
			""", StandardCharsets.UTF_8);
		SettingsManager m = SettingsManager.load(f);
		assertEquals(SettingsManager.LoadStatus.LOADED, m.status());
		assertEquals(70, m.get(Settings.FOV));
		assertEquals(6, m.get(Settings.RENDER_DISTANCE));
		assertTrue(m.get(Settings.VSYNC));
		assertEquals("ALL", m.get(Settings.PARTICLES));
		assertEquals("MerlinPlayer", m.get(Settings.OFFLINE_NAME));
		assertEquals(0.5, m.get(Settings.BRIGHTNESS), 1e-9);
		assertEquals(GameAction.JUMP.defaultCode, m.bindings().get(GameAction.JUMP));
		assertEquals(GameAction.SNEAK.defaultCode, m.bindings().get(GameAction.SNEAK));
		assertEquals(GameAction.MOVE_FORWARD.defaultCode, m.bindings().get(GameAction.MOVE_FORWARD), "Esc is reserved");
		assertEquals(60, m.get(Settings.MAX_FPS), "valid values survive");
		assertEquals(9, m.notes().size(), m.notes().toString());
		assertTrue(m.dirty(), "corrected file is rewritten on next save");
	}

	@Test
	void corruptFileIsBackedUpAndDefaultsUsed() throws Exception {
		Path f = this.dir.resolve("settings.properties");
		Files.write(f, new byte[]{(byte) 0xFF, (byte) 0xFE, 0, 1, 2, 3, (byte) 0x80, 'x'});
		SettingsManager m = SettingsManager.load(f);
		assertEquals(SettingsManager.LoadStatus.RECOVERED, m.status());
		assertEquals(70, m.get(Settings.FOV));
		assertFalse(Files.exists(f), "broken file moved aside");
		try (Stream<Path> s = Files.list(this.dir)) {
			assertTrue(s.anyMatch(p -> p.getFileName().toString().startsWith("settings.properties.broken-")));
		}
		assertTrue(m.save().ok(), "client can start and save normally afterwards");
		assertEquals(SettingsManager.LoadStatus.LOADED, SettingsManager.load(f).status());
	}

	@Test
	void malformedUnicodeEscapeAndGarbageTextAreTreatedAsCorrupt() throws Exception {
		Path f = this.dir.resolve("settings.properties");
		Files.writeString(f, "graphics.fov=\\uZZZZ\n", StandardCharsets.UTF_8);
		assertEquals(SettingsManager.LoadStatus.RECOVERED, SettingsManager.load(f).status());
		Files.writeString(f, "dies ist keine einstellungsdatei\nnur text ohne schlüssel\n", StandardCharsets.UTF_8);
		assertEquals(SettingsManager.LoadStatus.RECOVERED, SettingsManager.load(f).status());
	}

	@Test
	void emptyOrCommentOnlyFileIsFine() throws Exception {
		Path f = this.dir.resolve("settings.properties");
		Files.writeString(f, "# nur ein Kommentar\n\n", StandardCharsets.UTF_8);
		SettingsManager m = SettingsManager.load(f);
		assertEquals(SettingsManager.LoadStatus.LOADED, m.status());
		assertEquals(70, m.get(Settings.FOV));
	}

	@Test
	void outdatedFormatIsMigrated() throws Exception {
		Path f = this.dir.resolve("settings.properties");
		Files.writeString(f, """
			renderDistance=4
			fov=85
			mouseSensitivity=150
			fullscreen=true
			clouds=FAST
			key_jump=key:74
			username=OldName
			""", StandardCharsets.UTF_8);
		SettingsManager m = SettingsManager.load(f);
		assertEquals(4, m.get(Settings.RENDER_DISTANCE));
		assertEquals(85, m.get(Settings.FOV));
		assertEquals(0.75, m.get(Settings.SENSITIVITY), 1e-9);
		assertEquals("FULLSCREEN", m.get(Settings.DISPLAY_MODE));
		assertEquals("FANCY", m.get(Settings.CLOUDS));
		assertEquals(InputCode.key(74), m.bindings().get(GameAction.JUMP));
		assertEquals("OldName", m.get(Settings.OFFLINE_NAME));
		assertTrue(m.notes().stream().anyMatch(n -> n.contains("Format 1")), m.notes().toString());
		assertTrue(m.save().ok());
		String saved = Files.readString(f);
		assertTrue(saved.contains("settings.version=" + Settings.FORMAT_VERSION));
		assertTrue(saved.lines().noneMatch(l -> l.startsWith("renderDistance=")), "old keys are not written back");
	}

	@Test
	void newerFormatAndUnknownKeysAreKept() throws Exception {
		Path f = this.dir.resolve("settings.properties");
		Files.writeString(f, "settings.version=99\ngraphics.fov=95\nfuture.option=blau\n", StandardCharsets.UTF_8);
		SettingsManager m = SettingsManager.load(f);
		assertEquals(95, m.get(Settings.FOV));
		assertTrue(m.notes().getFirst().contains("neueren Version"));
		m.set(Settings.FOV, 96);
		assertTrue(m.save().ok());
		assertTrue(Files.readString(f).contains("future.option=blau"));
	}

	@Test
	void readOnlyFileReportsErrorAndKeepsValues() throws Exception {
		Path f = this.dir.resolve("settings.properties");
		SettingsManager m = SettingsManager.load(f);
		assertTrue(m.save().ok());
		assertTrue(f.toFile().setWritable(false));
		try {
			m.set(Settings.FOV, 100);
			SettingsManager.SaveResult r = m.save();
			assertFalse(r.ok());
			assertTrue(r.message().contains("schreibgeschützt"), r.message());
			assertEquals(100, m.get(Settings.FOV), "value stays active in memory");
			assertTrue(m.dirty());
		} finally {
			f.toFile().setWritable(true);
		}
	}

	@Test
	void failedSaveDoesNotThrow() throws Exception {
		Path f = this.dir.resolve("settings.properties");
		Files.createDirectories(f); // ein Verzeichnis, wo die Datei hin soll
		SettingsManager m = SettingsManager.load(f);
		assertEquals(SettingsManager.LoadStatus.RECOVERED, m.status());
		m.set(Settings.FOV, 80);
		SettingsManager.SaveResult r = m.save();
		assertFalse(r.ok());
		assertTrue(r.message().startsWith("Einstellungen nicht gespeichert"), r.message());
	}

	@Test
	void resetToDefaults() {
		SettingsManager m = SettingsManager.inMemory();
		m.set(Settings.FOV, 100);
		m.set(Settings.VSYNC, false);
		m.bind(GameAction.JUMP, InputCode.key('J'));
		List<String> events = new ArrayList<>();
		m.addListener(events::add);
		m.resetToDefaults(Setting.Category.GRAPHICS);
		assertEquals(70, m.get(Settings.FOV));
		assertTrue(m.get(Settings.VSYNC));
		assertEquals(InputCode.key('J'), m.bindings().get(GameAction.JUMP), "other category untouched");
		m.resetToDefaults(null);
		assertEquals(GameAction.JUMP.defaultCode, m.bindings().get(GameAction.JUMP));
		assertTrue(events.contains("graphics.fov"));
		assertTrue(events.contains("controls.key.*"));
	}

	@Test
	void setRejectsInvalidValuesAndKeepsOld() {
		SettingsManager m = SettingsManager.inMemory();
		assertThrows(IllegalArgumentException.class, () -> m.set(Settings.FOV, 5));
		assertThrows(IllegalArgumentException.class, () -> m.set(Settings.OFFLINE_NAME, "x"));
		assertThrows(IllegalArgumentException.class, () -> m.set(Settings.PARTICLES, "MANY"));
		assertEquals(70, m.get(Settings.FOV));
		assertEquals(SettingsManager.LoadStatus.MEMORY, m.status());
		assertTrue(m.save().ok(), "in-memory never touches disk");
	}

	@Test
	void everySettingHasValidDefaultAndUniqueKey() {
		java.util.Set<String> keys = new java.util.HashSet<>();
		for (Setting<?> s : Settings.ALL) {
			assertTrue(keys.add(s.key()), s.key());
			assertEquals(s.defaultValue(), s.parse(s.formatObject(s.defaultValue())), s.key());
		}
	}
}
