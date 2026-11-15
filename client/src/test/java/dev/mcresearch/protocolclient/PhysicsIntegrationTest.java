package dev.mcresearch.protocolclient;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

import dev.mcresearch.protocolclient.net.Connection;
import dev.mcresearch.protocolclient.net.LoopbackGuard;
import dev.mcresearch.protocolclient.net.PacketLog;
import dev.mcresearch.protocolclient.physics.PlayerPhysics;
import dev.mcresearch.protocolclient.session.ClientSession;
import dev.mcresearch.protocolclient.session.SessionConfig;
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
 * M5-Laufzeittest (docs/23): Szenarien der Teststrecke {@code analysis/tools/m5-course.txt} mit dem eigenen Client.
 * <ul>
 *   <li>Serverseitig: Endposition per {@code data get entity … Pos} muss der Physikposition entsprechen; keine Korrektur
 *       während eines Szenarios.</li>
 *   <li>Gegen Vanilla: Liegt die Spur des Referenzclients vor ({@code -Pphysref=…}), wird jeder Tick verglichen
 *       (Position, Geschwindigkeit, Boden- und Kollisionsflags).</li>
 * </ul>
 */
@Tag("integration")
class PhysicsIntegrationTest {
	private static final int PORT = 25565;
	private static final String NAME = "ProtoBotM5";
	/** M11: Kurslabel (m5, m11-move …) bestimmt die Ausgabedateien. */
	private static final String LABEL = System.getProperty("mcclient.physicsLabel", "m5");
	private static final Path TRACE = Path.of("build", LABEL + "-own-trace.txt");
	private static final Path REPORT = Path.of("build", LABEL + "-physics-report.txt");
	private static InetAddress address;
	private static ServerConsole console;
	private static Path course;

	/** M11: Startneigung {@code pitch} (Kurs: yaw/neigung) und Neigung je Tick ({@code pitches}, Segment tasten:ticks@neigung). */
	record Scenario(String name, double x, double y, double z, float yaw, List<String> inputs, float pitch, List<Float> pitches) {
	}

	@BeforeAll
	static void requireServer() throws Exception {
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
		course = Path.of(System.getProperty("mcclient.course", "../analysis/tools/m5-course.txt"));
		assumeTrue(Files.isRegularFile(course), "course file " + course);
	}

	static List<Scenario> scenarios(final List<String> lines) {
		List<Scenario> out = new ArrayList<>();
		for (String line : lines) {
			String[] t = line.trim().split("\\s+");
			if (t.length >= 7 && t[0].equals("scenario")) {
				List<String> inputs = new ArrayList<>();
				List<Float> pitches = new ArrayList<>();
				for (int i = 6; i < t.length; i++) {
					String[] at = t[i].split("@");
					String[] seg = at[0].split(":");
					for (int n = 0; n < Integer.parseInt(seg[1]); n++) {
						inputs.add(seg[0]);
						pitches.add(n == 0 && at.length > 1 ? Float.valueOf(at[1]) : null);
					}
				}
				String[] yp = t[5].split("/");
				out.add(new Scenario(t[1], Double.parseDouble(t[2]), Double.parseDouble(t[3]), Double.parseDouble(t[4]), Float.parseFloat(yp[0]), inputs,
					yp.length > 1 ? Float.parseFloat(yp[1]) : 0.0F, pitches));
			}
		}
		return out;
	}

	static PlayerPhysics.Input input(final String keys) {
		return PlayerPhysics.Input.parse(keys);
	}

	/** M11-Zustandsfelder der eigenen Spur (gleiche Reihenfolge wie die Vanilla-Spur ab Spalte 13). */
	static List<String> stateColumns(final PlayerPhysics.TickResult r) {
		PlayerPhysics.MotionState m = r.motion();
		return List.of(m.pose().name(), Boolean.toString(m.sprinting()), Boolean.toString(m.crouching()), Boolean.toString(m.shift()),
			Boolean.toString(m.minorHorizontalCollision()), Double.toString(m.fallDistance()), Float.toString(m.pose().height()),
			Float.toString(m.pose().eyeHeight()), Boolean.toString(m.inWater()), Boolean.toString(m.underWater()), Boolean.toString(m.swimming()),
			Double.toString(m.waterHeight()), Double.toString(m.lavaHeight()), Boolean.toString(m.inLava()), Boolean.toString(m.onClimbable()));
	}

	/** Vergleich der Zustandsfelder; Rückgabe: Anzahl abweichender Felder dieses Ticks (0 = gleich). */
	static int stateMismatches(final String[] v, final PlayerPhysics.TickResult r, final List<String> diffs, final String where) {
		if (v.length < 21) {
			return 0;
		}
		List<String> own = stateColumns(r);
		String[] names = {"pose", "sprinting", "crouching", "shift", "minorHorizontalCollision", "fallDistance", "bbHeight", "eyeHeight", "inWater",
			"underWater", "swimming", "waterHeight", "lavaHeight", "inLava", "onClimbable"};
		int n = 0;
		for (int i = 0; i < names.length && 13 + i < v.length; i++) {
			String ref = v[13 + i];
			boolean numeric = i == 5 || i == 11 || i == 12;
			boolean equal = numeric ? Math.abs(Double.parseDouble(ref) - Double.parseDouble(own.get(i))) <= 1.0E-6 : ref.equals(own.get(i));
			if (!equal) {
				n++;
				if (diffs.size() < 40) {
					diffs.add(where + " " + names[i] + " vanilla=" + ref + " own=" + own.get(i));
				}
			}
		}
		return n;
	}

	@Test
	void courseMatchesServerAndVanillaReference() throws Exception {
		List<String> lines = Files.readAllLines(course, StandardCharsets.UTF_8);
		List<String> build = new ArrayList<>();
		for (String l : lines) {
			if (l.startsWith("build ")) {
				build.add(l.substring(6));
			}
		}
		List<Scenario> scenarios = scenarios(lines);
		List<String> traceLines = new ArrayList<>();
		List<String> report = new ArrayList<>();
		Map<String, List<String[]>> reference = loadReference();
		SessionConfig c = new SessionConfig("127.0.0.1", PORT, NAME, 2, Duration.ZERO, false, "mcresearch-protocol-client-it",
			9f, Duration.ofSeconds(5), SessionConfig.DEFAULT_READ_TIMEOUT, Path.of("build", "integration-physics-packets.log"));
		try (PacketLog log = PacketLog.open(c.packetLog())) {
			ClientSession session = new ClientSession(Connection.open(address, PORT, 5000, 30000, log), c);
			CompletableFuture<ClientSession.Result> result = CompletableFuture.supplyAsync(session::run);
			waitFor(() -> session.movement().ready() && session.movement().physics() != null, 20, "ready with physics");
			double ox = session.player().x();
			double oy = session.player().y();
			double oz = session.player().z();
			console.send(build, 2000);
			console.send(List.of("gamemode survival " + NAME), 300);
			if (!LABEL.equals("m5")) {
				// M11: volle Nahrung (Sprinten erst über 6), keine Effekte
				console.send(List.of("effect clear " + NAME, "effect give " + NAME + " minecraft:saturation 1 20 true",
					"effect give " + NAME + " minecraft:fire_resistance 900 0 true"), 1500); // Lava-Szenarien ohne Schaden und Rückstoß
			}
			List<String> stateDiffs = new ArrayList<>();
			int stateMismatchTotal = 0;
			int bitExactScenarios = 0;
			int inMotionAtEnd = 0;
			int totalTicks = 0;
			int serverMismatches = 0;
			double worstVanilla = 0;
			int flagMismatches = 0;
			int compared = 0;
			for (Scenario s : scenarios) {
				// zweistufig wie beim Referenzclient: Zwischenpunkt 1 Block hinter dem Start, dann Start
				// Nachweis über die Anzahl empfangener player_position-Pakete (unabhängig vom Abfragezeitpunkt)
				int teleportsBefore = session.player().serverPositionUpdates();
				console.send(List.of(String.format(Locale.ROOT, "tp %s %s %s %s %s %s", NAME, s.x(), s.y(), s.z() - 1.0, s.yaw(), s.pitch())), 0);
				waitFor(() -> session.player().serverPositionUpdates() >= teleportsBefore + 1, 30, "staging teleport for " + s.name());
				console.send(List.of(String.format(Locale.ROOT, "tp %s %s %s %s %s %s", NAME, s.x(), s.y(), s.z(), s.yaw(), s.pitch())), 0);
				waitFor(() -> session.player().serverPositionUpdates() >= teleportsBefore + 2, 30, "start teleport for " + s.name());
				waitFor(() -> Math.abs(session.player().x() - s.x()) < 1e-9 && Math.abs(session.player().z() - s.z()) < 1e-9
					&& Math.abs(session.player().y() - s.y()) < 1e-9 && session.player().yRot() == s.yaw() && session.player().xRot() == s.pitch(), 30,
					"teleport to " + s.name());
				Thread.sleep(1100); // ≥ 20 Ticks Ruhe wie der Referenzclient
				waitFor(() -> {
					PlayerPhysics p = session.movement().physics();
					return p.onGround() && p.velocity().horizontalDistanceSqr() == 0.0;
				}, 10, "at rest before " + s.name());
				int correctionsBefore = session.movement().corrections();
				int motionsBefore = session.movement().serverMotions();
				long tickBefore = session.movement().ticks();
				List<PlayerPhysics.Input> inputs = s.inputs().stream().map(PhysicsIntegrationTest::input).toList();
				List<PlayerPhysics.TickResult> trace = session.movement().script(inputs, s.pitches()).get(60, TimeUnit.SECONDS);
				totalTicks += trace.size();
				for (int k = 0; k < trace.size(); k++) {
					PlayerPhysics.TickResult r = trace.get(k);
					traceLines.add(String.join(" ", s.name(), Integer.toString(k + 1), s.inputs().get(k), Double.toString(r.x()), Double.toString(r.y()),
						Double.toString(r.z()), Double.toString(r.vx()), Double.toString(r.vy()), Double.toString(r.vz()), Boolean.toString(r.onGround()),
						Boolean.toString(r.horizontalCollision()), Boolean.toString(r.verticalCollision()), Float.toString(session.player().yRot()))
						+ " " + String.join(" ", stateColumns(r)));
				}
				Thread.sleep(600);
				PlayerPhysics.TickResult last = trace.getLast();
				double[] server = console.dataGet(NAME, "Pos");
				double serverDiff = Math.max(Math.abs(server[0] - session.player().x()),
					Math.max(Math.abs(server[1] - session.player().y()), Math.abs(server[2] - session.player().z())));
				boolean corrected = session.movement().corrections() != correctionsBefore;
				// M11: Szenarien, die im Fluid treibend enden, bewegen sich beim Serverabruf noch (Messaufbau): dort zählt nur eine Korrektur
				// in Ruhe: am Boden und die letzten beiden Ticks an derselben Position (ein Prall- oder Schwebetick zählt nicht)
				PlayerPhysics.TickResult prev = trace.size() > 1 ? trace.get(trace.size() - 2) : last;
				boolean atRest = last.onGround() && prev.x() == last.x() && prev.y() == last.y() && prev.z() == last.z();
				if (corrected || atRest && serverDiff > 1e-6) {
					serverMismatches++;
				} else if (!atRest && serverDiff > 1e-6) {
					inMotionAtEnd++;
				}
				// Vergleich mit der Vanilla-Spur
				String vanilla = "no reference";
				List<String[]> ref = reference.get(s.name());
				if (ref != null) {
					double maxPos = 0;
					double maxVel = 0;
					int flags = 0;
					int n = Math.min(ref.size(), trace.size());
					for (int k = 0; k < n; k++) {
						String[] v = ref.get(k);
						PlayerPhysics.TickResult r = trace.get(k);
						maxPos = Math.max(maxPos, Math.max(Math.abs(Double.parseDouble(v[3]) - r.x()),
							Math.max(Math.abs(Double.parseDouble(v[4]) - r.y()), Math.abs(Double.parseDouble(v[5]) - r.z()))));
						maxVel = Math.max(maxVel, Math.max(Math.abs(Double.parseDouble(v[6]) - r.vx()),
							Math.max(Math.abs(Double.parseDouble(v[7]) - r.vy()), Math.abs(Double.parseDouble(v[8]) - r.vz()))));
						if (Boolean.parseBoolean(v[9]) != r.onGround() || Boolean.parseBoolean(v[10]) != r.horizontalCollision()
							|| Boolean.parseBoolean(v[11]) != r.verticalCollision()) {
							flags++;
						}
						stateMismatchTotal += stateMismatches(v, r, stateDiffs, s.name() + "#" + (k + 1));
					}
					if (n != trace.size() || ref.size() != trace.size()) {
						flags++;
					}
					compared += n;
					if (maxPos == 0.0 && maxVel == 0.0 && flags == 0) {
						bitExactScenarios++;
					}
					worstVanilla = Math.max(worstVanilla, maxPos);
					flagMismatches += flags;
					vanilla = String.format(Locale.ROOT, "vanilla ticks %d max|Δpos| %.3e max|Δv| %.3e flag mismatches %d", n, maxPos, maxVel, flags);
				}
				double maxY = trace.stream().mapToDouble(PlayerPhysics.TickResult::y).max().orElse(Double.NaN);
				double minY = trace.stream().mapToDouble(PlayerPhysics.TickResult::y).min().orElse(Double.NaN);
				long collisions = trace.stream().filter(PlayerPhysics.TickResult::horizontalCollision).count();
				long steps = trace.stream().filter(PlayerPhysics.TickResult::stepped).count();
				report.add(String.format(Locale.ROOT,
					"%-14s ticks %3d end (%.6f, %.6f, %.6f) server (%.6f, %.6f, %.6f) |Δ| %.1e corrected %s | y %.4f..%.4f hcol %d steps %d ground %s | %s | server motions %d (last at script tick %d)",
					s.name(), trace.size(), last.x(), last.y(), last.z(), server[0], server[1], server[2], serverDiff, corrected, minY, maxY, collisions,
					steps, last.onGround(), vanilla, session.movement().serverMotions() - motionsBefore,
					session.movement().serverMotions() > motionsBefore ? session.movement().lastServerMotionTick() - tickBefore : -1));
			}
			report.add(String.format(Locale.ROOT, "summary: %d scenarios, %d ticks, server mismatches/corrections %d, vanilla ticks compared %d,"
				+ " worst |Δpos| %.3e, flag mismatches %d, corrections total %d", scenarios.size(), totalTicks, serverMismatches, compared, worstVanilla,
				flagMismatches, session.movement().corrections()));
			report.add(String.format(Locale.ROOT, "state: bit-exact scenarios %d/%d, state field mismatches %d, sprint commands sent %d, still moving at server read %d",
				bitExactScenarios, scenarios.size(), stateMismatchTotal, session.movement().sprintCommands(), inMotionAtEnd));
			stateDiffs.forEach(d -> report.add("  STATE " + d));
			console.send(List.of(String.format(Locale.ROOT, "tp %s %.3f %.3f %.3f", NAME, ox, oy, oz)), 1000);
			assertEquals(0, serverMismatches, "server-accepted end positions must equal the client physics, without corrections");
			if (!reference.isEmpty()) {
				assertEquals(scenarios.size() * 1L, reference.keySet().stream().filter(k -> scenarios.stream().anyMatch(s -> s.name().equals(k))).count(),
					"reference trace covers all scenarios");
				assertTrue(worstVanilla <= 1e-6, "position deviation from Vanilla " + worstVanilla);
				assertEquals(0, flagMismatches, "onGround/collision flags equal Vanilla in every tick");
				assertEquals(0, stateMismatchTotal, "pose/sprint/sneak state equals Vanilla in every tick");
			}
			session.requestStop("physics integration test done");
			ClientSession.Result r = result.get(30, TimeUnit.SECONDS);
			assertNull(r.error(), () -> "error: " + r.error());
			assertNull(r.serverDisconnectReason());
		} finally {
			Files.createDirectories(TRACE.getParent());
			Files.write(TRACE, traceLines, StandardCharsets.UTF_8);
			Files.write(REPORT, report, StandardCharsets.UTF_8);
		}
	}

	private static Map<String, List<String[]>> loadReference() throws Exception {
		Map<String, List<String[]>> ref = new LinkedHashMap<>();
		String p = System.getProperty("mcclient.physicsReference", "");
		if (p.isBlank() || !Files.isRegularFile(Path.of(p))) {
			return ref;
		}
		for (String line : Files.readAllLines(Path.of(p), StandardCharsets.UTF_8)) {
			if (line.startsWith("#") || line.isBlank()) {
				continue;
			}
			String[] t = line.split(" ");
			ref.computeIfAbsent(t[0], k -> new ArrayList<>()).add(t);
		}
		return ref;
	}

	private static void waitFor(final BooleanSupplier condition, final int seconds, final String what) throws InterruptedException {
		long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(seconds);
		while (!condition.getAsBoolean()) {
			if (System.nanoTime() > deadline) {
				throw new AssertionError("timeout waiting for " + what);
			}
			Thread.sleep(20);
		}
	}
}
