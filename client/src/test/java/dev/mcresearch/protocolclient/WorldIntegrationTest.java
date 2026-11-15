package dev.mcresearch.protocolclient;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

import dev.mcresearch.protocolclient.net.Connection;
import dev.mcresearch.protocolclient.net.LoopbackGuard;
import dev.mcresearch.protocolclient.net.PacketLog;
import dev.mcresearch.protocolclient.session.ClientSession;
import dev.mcresearch.protocolclient.session.SessionConfig;
import dev.mcresearch.protocolclient.world.BlockState;
import dev.mcresearch.protocolclient.world.WorldState;
import java.net.InetAddress;
import java.net.Socket;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;
import java.util.function.BooleanSupplier;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

/**
 * M4-Laufzeittest (docs/20): Die eigene Weltverwaltung muss Block für Block mit dem Server übereinstimmen.
 * Serverseitige Referenz: {@code execute if block x y z <vollständiger Zustand>} über die Konsole, mit dem vom Client
 * dekodierten Zustand als Prädikat. Benötigt {@code -PserverDir}. Alle Änderungen werden am Ende zurückgesetzt.
 */
@Tag("integration")
class WorldIntegrationTest {
	private static final int PORT = 25565;
	private static final String NAME = "ProtoBotM4";
	private static final Path REPORT = Path.of("build", "m4-world-report.txt");
	private static InetAddress address;
	private static ServerConsole console;

	@BeforeAll
	static void requireLocalServerAndConsole() throws Exception {
		address = LoopbackGuard.resolveLoopback("127.0.0.1");
		boolean reachable;
		try (Socket s = new Socket()) {
			s.connect(new java.net.InetSocketAddress(address, PORT), 1000);
			reachable = true;
		} catch (java.io.IOException e) {
			reachable = false;
		}
		assumeTrue(reachable, "Local server on 127.0.0.1:25565 not running");
		Optional<ServerConsole> c = ServerConsole.fromSystemProperty();
		assumeTrue(c.isPresent(), "server console not available (-PserverDir)");
		console = c.get();
	}

	private record Pos(int x, int y, int z) {
		@Override
		public String toString() {
			return this.x + " " + this.y + " " + this.z;
		}
	}

	/** Gezielte Einzeländerungen (block_update): Stufen/Treppen, Chunkgrenzen, negative Koordinaten, Höhen. */
	private static final Map<Pos, String> SINGLE = new LinkedHashMap<>();
	static {
		SINGLE.put(new Pos(-1, 201, -1), "minecraft:oak_stairs[facing=east,half=top,shape=straight,waterlogged=false]");
		SINGLE.put(new Pos(0, 201, 0), "minecraft:oak_slab[type=top,waterlogged=false]");
		SINGLE.put(new Pos(1, 201, 0), "minecraft:oak_slab[type=bottom,waterlogged=false]");
		SINGLE.put(new Pos(15, 201, 15), "minecraft:dirt");
		SINGLE.put(new Pos(16, 201, 16), "minecraft:glass");
		SINGLE.put(new Pos(-16, 202, -16), "minecraft:stone");
		SINGLE.put(new Pos(-17, 202, -17), "minecraft:dirt");
		SINGLE.put(new Pos(5, -64, 5), "minecraft:stone");
		SINGLE.put(new Pos(5, 319, 5), "minecraft:glass");
		SINGLE.put(new Pos(5, -1, 5), "minecraft:oak_planks");
		SINGLE.put(new Pos(5, 0, 5), "minecraft:glass");
		SINGLE.put(new Pos(5, 15, 5), "minecraft:oak_planks");
		SINGLE.put(new Pos(5, 16, 5), "minecraft:glass");
	}

	/** Mehrfachänderungen (section_blocks_update): Plattform über 4 Chunks, Block in einer Sektion. */
	private static final List<int[]> FILLS = List.of(
		new int[]{-3, 200, -3, 2, 200, 2}, // stone, Chunks -1/-1, -1/0, 0/-1, 0/0
		new int[]{8, 210, 8, 11, 211, 11}); // oak_planks, eine Sektion (y 208–223) in Chunk 0,0
	private static final List<String> FILL_STATES = List.of("minecraft:stone", "minecraft:oak_planks");

	@Test
	void clientWorldMatchesServerBlocks() throws Exception {
		List<String> report = new ArrayList<>();
		SessionConfig c = new SessionConfig("127.0.0.1", PORT, NAME, 2, Duration.ZERO, false, "mcresearch-protocol-client-it",
			9f, Duration.ofSeconds(5), SessionConfig.DEFAULT_READ_TIMEOUT, Path.of("build", "integration-world-packets.log"));
		try (PacketLog log = PacketLog.open(c.packetLog())) {
			ClientSession session = new ClientSession(Connection.open(address, PORT, 5000, 30000, log), c);
			CompletableFuture<ClientSession.Result> result = CompletableFuture.supplyAsync(session::run);
			waitFor(() -> session.movement().ready() && session.world() != null, 20, "ready with world model");
			double ox = session.player().x();
			double oy = session.player().y();
			double oz = session.player().z();
			report.add(String.format(Locale.ROOT, "start %s at %.3f %.3f %.3f, dimension %s", NAME, ox, oy, oz, session.world().dimension()));

			// 1) Bereich um den Ursprung laden und Spieler dorthin bringen (kreativ: kein Floating-Kick)
			console.send(List.of("gamemode creative " + NAME, "forceload add -48 -48 47 47"), 3000);
			console.send(List.of("tp " + NAME + " 0.5 205 0.5"), 500);
			waitFor(() -> chunksLoaded(session, -2, 1), 30, "chunks -2..1 around origin");

			// 2) Originalzustände merken (zum Zurücksetzen), dann ändern
			List<Pos> modified = new ArrayList<>(SINGLE.keySet());
			Map<Pos, String> expected = new LinkedHashMap<>(SINGLE);
			for (int f = 0; f < FILLS.size(); f++) {
				int[] b = FILLS.get(f);
				for (int y = b[1]; y <= b[4]; y++) {
					for (int z = b[2]; z <= b[5]; z++) {
						for (int x = b[0]; x <= b[3]; x++) {
							Pos p = new Pos(x, y, z);
							if (!expected.containsKey(p)) {
								modified.add(p);
							}
							expected.putIfAbsent(p, FILL_STATES.get(f));
						}
					}
				}
			}
			Map<Pos, String> original = new LinkedHashMap<>();
			for (Pos p : modified) {
				original.put(p, state(session, p).orElseThrow(() -> new AssertionError("not loaded: " + p)).toString());
			}
			// zuerst die Fills (Mehrfachänderungen), dann die Einzelblöcke, die teils in den Fill-Bereichen liegen
			List<String> changes = new ArrayList<>();
			for (int f = 0; f < FILLS.size(); f++) {
				int[] b = FILLS.get(f);
				changes.add(String.format(Locale.ROOT, "fill %d %d %d %d %d %d %s", b[0], b[1], b[2], b[3], b[4], b[5], FILL_STATES.get(f)));
			}
			SINGLE.forEach((p, s) -> changes.add("setblock " + p + " " + s));
			console.send(changes, 1000);
			waitFor(() -> expected.entrySet().stream().allMatch(e -> state(session, e.getKey()).map(BlockState::toString).orElse("").equals(e.getValue())),
				15, "client sees all changes");
			WorldState.Stats afterChanges = session.world().stats();
			report.add("after changes: block_update=" + afterChanges.blockUpdates + " section_blocks_update=" + afterChanges.sectionUpdates
				+ " (" + afterChanges.sectionUpdateEntries + " entries)");
			// Änderungen derselben Sektion im selben Tick fasst der Server zu einem section_blocks_update zusammen
			long changedBlocks = expected.entrySet().stream().filter(e -> !e.getValue().equals(original.get(e.getKey()))).count();
			assertTrue(afterChanges.blockUpdates >= 1 && afterChanges.sectionUpdates >= 1, "both update kinds expected");
			assertTrue(afterChanges.blockUpdates + afterChanges.sectionUpdateEntries >= changedBlocks,
				"updates " + afterChanges.blockUpdates + " + " + afterChanges.sectionUpdateEntries + " < " + changedBlocks);

			// 3) Server bestätigt jeden geänderten Block und eine Stichprobe des natürlichen Terrains
			List<Pos> sample = new ArrayList<>(expected.keySet());
			for (int x : new int[]{-17, -16, -1, 0, 15, 16}) {
				for (int z : new int[]{-17, -1, 0, 16}) {
					for (int y : new int[]{-64, -63, -50, -20, -1, 0, 20, 40, 50, 55, 60, 62, 63, 64, 65, 66, 68, 70, 72, 75, 80, 90, 120, 319}) {
						sample.add(new Pos(x, y, z));
					}
				}
			}
			int passed = verify(session, sample, report, "initial");
			assertEquals(sample.size(), passed, "server confirms every client block state");

			// Gegenprobe: falscher Zustand muss "Test failed" liefern (die Prüfung unterscheidet wirklich)
			List<String> wrong = new ArrayList<>();
			for (Pos p : List.of(new Pos(-1, 201, -1), new Pos(0, 201, 0), new Pos(5, -64, 5))) {
				wrong.add("execute if block " + p + " " + (p.y() == 201 && p.x() == 0
					? "minecraft:oak_slab[type=bottom,waterlogged=false]" : "minecraft:oak_stairs[facing=west,half=top,shape=straight,waterlogged=false]"));
			}
			List<String> wrongResults = console.check(wrong);
			report.add("negative control: " + wrongResults);
			assertTrue(wrongResults.stream().allMatch("Test failed"::equals), wrongResults.toString());

			// 4) Chunks entladen (weit weg teleportieren) und neu laden
			long unloadsBefore = session.world().stats().chunksUnloaded;
			console.send(List.of("tp " + NAME + " 3000.5 250 3000.5"), 500);
			waitFor(() -> session.world().chunk(0, 0) == null && session.world().chunk(-1, -1) == null, 60, "origin chunks unloaded");
			waitFor(() -> session.world().chunk(187, 187) != null, 60, "chunks at 3000,3000 loaded");
			long unloaded = session.world().stats().chunksUnloaded - unloadsBefore;
			report.add("unloaded after teleport: " + unloaded + " chunks, loaded now " + session.world().loadedChunks());
			assertTrue(unloaded >= 16, "unloaded " + unloaded);
			for (Pos p : expected.keySet()) {
				assertTrue(state(session, p).isEmpty(), "still loaded: " + p);
			}
			console.send(List.of("tp " + NAME + " 0.5 205 0.5"), 500);
			waitFor(() -> chunksLoaded(session, -2, 1), 60, "origin chunks reloaded");
			waitFor(() -> expected.entrySet().stream().allMatch(e -> state(session, e.getKey()).map(BlockState::toString).orElse("").equals(e.getValue())),
				15, "reloaded chunks contain the changes");
			passed = verify(session, new ArrayList<>(expected.keySet()), report, "reloaded");
			assertEquals(expected.size(), passed);

			// 5) zurücksetzen: jede geänderte Position erhält ihren zuvor vom Client gelesenen (und vom Server bestätigten) Zustand
			List<String> restore = new ArrayList<>();
			original.forEach((p, s) -> restore.add("setblock " + p + " " + s));
			console.send(restore, 1500);
			waitFor(() -> original.entrySet().stream().allMatch(e -> state(session, e.getKey()).map(BlockState::toString).orElse("").equals(e.getValue())),
				15, "client sees restored blocks");
			passed = verify(session, new ArrayList<>(original.keySet()), report, "restored");
			assertEquals(original.size(), passed);
			// erst zurück und landen lassen, dann Überleben: die im Kreativmodus angesammelte Fallstrecke (Stürze bei y 250/205)
			// wird beim Aufsetzen ohne Schaden zurückgesetzt; gleichzeitiges Umschalten führte zu "fell from a high place" (docs/23 §5)
			console.send(List.of(String.format(Locale.ROOT, "tp %s %.3f %.3f %.3f", NAME, ox, oy, oz)), 0);
			waitFor(() -> Math.abs(session.player().x() - ox) < 0.01 && Math.abs(session.player().z() - oz) < 0.01 && session.player().onGround(), 15,
				"back at the start position on the ground");
			console.send(List.of("gamemode survival " + NAME, "forceload remove all"), 1500);

			// 6) Strukturprüfungen über den gesamten Lauf
			WorldState.Stats s = session.world().stats();
			report.add(String.format(Locale.ROOT,
				"stats: chunks loaded %d (replaced %d), unloaded %d, sections %d, section bytes %d, trailing-byte checks %d, nonAir mismatches %d,"
					+ " block_update %d, section_blocks_update %d (%d entries), light_update %d, block_entity_data %d, biome updates %d, ignored %d,"
					+ " block palettes %s, biome palettes %s",
				s.chunksLoaded, s.chunksReplaced, s.chunksUnloaded, s.sectionsDecoded, s.chunkBytesDecoded, s.trailingByteChecks,
				s.nonEmptyCountMismatches, s.blockUpdates, s.sectionUpdates, s.sectionUpdateEntries, s.lightUpdates, s.blockEntityUpdates,
				s.biomeUpdates, s.updatesForUnloadedChunks, s.blockPalettes, s.biomePalettes));
			report.add("transport: " + session.transportStats());
			assertEquals(s.chunksLoaded, s.trailingByteChecks, "every chunk buffer consumed exactly");
			assertEquals(24L * s.chunksLoaded, s.sectionsDecoded);
			assertEquals(0, s.nonEmptyCountMismatches, "server nonEmptyBlockCount equals decoded non-air count");
			session.requestStop("world integration test done");
			ClientSession.Result r = result.get(30, TimeUnit.SECONDS);
			assertNull(r.error(), () -> "error: " + r.error());
			assertNull(r.serverDisconnectReason());
		} finally {
			Files.createDirectories(REPORT.getParent());
			Files.write(REPORT, report, StandardCharsets.UTF_8);
		}
	}

	/** Prüft serverseitig, dass an jeder Position genau der vom Client dekodierte Zustand steht. */
	private static int verify(final ClientSession session, final List<Pos> positions, final List<String> report, final String label) throws Exception {
		List<String> commands = new ArrayList<>();
		List<String> states = new ArrayList<>();
		for (Pos p : positions) {
			BlockState s = state(session, p).orElseThrow(() -> new AssertionError("not loaded in client: " + p));
			states.add(s.toString());
			commands.add("execute if block " + p + " " + s);
		}
		List<String> results = console.check(commands);
		int passed = 0;
		for (int i = 0; i < positions.size(); i++) {
			boolean ok = results.get(i).equals("Test passed");
			passed += ok ? 1 : 0;
			report.add(String.format(Locale.ROOT, "%-8s %-16s client=%-70s server=%s", label, positions.get(i), states.get(i), results.get(i)));
		}
		report.add(label + ": " + passed + "/" + positions.size() + " confirmed");
		return passed;
	}

	private static Optional<BlockState> state(final ClientSession session, final Pos p) {
		WorldState w = session.world();
		return w == null ? Optional.empty() : w.block(p.x(), p.y(), p.z());
	}

	private static boolean chunksLoaded(final ClientSession session, final int min, final int max) {
		WorldState w = session.world();
		for (int cx = min; cx <= max; cx++) {
			for (int cz = min; cz <= max; cz++) {
				if (w == null || w.chunk(cx, cz) == null) {
					return false;
				}
			}
		}
		return true;
	}

	private static void waitFor(final BooleanSupplier condition, final int seconds, final String what) throws InterruptedException {
		long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(seconds);
		while (!condition.getAsBoolean()) {
			if (System.nanoTime() > deadline) {
				throw new AssertionError("timeout waiting for " + what);
			}
			Thread.sleep(100);
		}
		assertNotNull(what);
	}
}
