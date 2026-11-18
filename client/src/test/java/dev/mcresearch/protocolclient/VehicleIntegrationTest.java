package dev.mcresearch.protocolclient;

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
import java.util.HashMap;
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
 * M14 Phase L (docs/58 §4): Fahrzeugszenarien ({@code m14-vehicle-course.txt}) mit dem eigenen Client gegen den lokalen
 * Vanilla-Server. Je Szenario: Fahrzeug beschwören, aufsitzen, Aufsitz-Tick + 40 Ruheticks, Eingaben; Spur je Tick, Vergleich mit der
 * Vanilla-Referenzspur (falls angegeben) und mit der Serverposition des Fahrzeugs am Ende; Absteigen über die Schleichtaste.
 */
@Tag("integration")
class VehicleIntegrationTest {
	private static final int PORT = 25565;
	private static final String NAME = "ProtoBotVeh";
	private static final int SETTLE = 40;
	private static InetAddress address;
	private static ServerConsole console;
	private static Path course;

	record Scenario(String name, String type, double x, double y, double z, float yaw, List<String> inputs) {
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
		course = Path.of(System.getProperty("mcclient.course", "../analysis/tools/m14-vehicle-course.txt"));
		assumeTrue(Files.isRegularFile(course) && course.getFileName().toString().contains("vehicle"), "vehicle course file " + course);
	}

	static List<Scenario> scenarios(final List<String> lines) {
		List<Scenario> out = new ArrayList<>();
		for (String line : lines) {
			String[] t = line.trim().split("\\s+");
			if (t.length >= 8 && t[0].equals("vehicle")) {
				List<String> inputs = new ArrayList<>();
				for (int i = 7; i < t.length; i++) {
					String[] seg = t[i].split(":");
					for (int n = 0; n < Integer.parseInt(seg[1]); n++) {
						inputs.add(seg[0]);
					}
				}
				out.add(new Scenario(t[1], t[2], Double.parseDouble(t[3]), Double.parseDouble(t[4]), Double.parseDouble(t[5]), Float.parseFloat(t[6]),
					inputs));
			}
		}
		return out;
	}

	static Map<String, String> nbt(final List<String> lines) {
		Map<String, String> m = new HashMap<>();
		for (String line : lines) {
			if (line.startsWith("vehicle-nbt ")) {
				String[] t = line.split("\\s+", 3);
				m.put(t[1], t[2]);
			}
		}
		return m;
	}

	private static PlayerPhysics.Input input(final String keys) {
		return new PlayerPhysics.Input(keys.contains("f"), keys.contains("b"), keys.contains("l"), keys.contains("r"), keys.contains("j"),
			keys.contains("s"), false);
	}

	private static Map<String, List<String[]>> loadReference() throws Exception {
		String p = System.getProperty("mcclient.physicsReference", "");
		Map<String, List<String[]>> m = new HashMap<>();
		if (p.isEmpty() || !Files.isRegularFile(Path.of(p))) {
			return m;
		}
		for (String l : Files.readAllLines(Path.of(p), StandardCharsets.UTF_8)) {
			if (l.startsWith("#") || l.isBlank()) {
				continue;
			}
			String[] f = l.split(" ");
			m.computeIfAbsent(f[0], k -> new ArrayList<>()).add(f);
		}
		return m;
	}

	private static void waitFor(final BooleanSupplier c, final int seconds, final String what) throws InterruptedException {
		long end = System.nanoTime() + seconds * 1_000_000_000L;
		while (!c.getAsBoolean()) {
			if (System.nanoTime() > end) {
				throw new AssertionError("timeout waiting for " + what);
			}
			Thread.sleep(20);
		}
	}

	@Test
	void vehicleCourseMatchesServerAndVanillaReference() throws Exception {
		List<String> lines = Files.readAllLines(course, StandardCharsets.UTF_8);
		List<String> build = lines.stream().filter(l -> l.startsWith("build ")).map(l -> l.substring(6)).toList();
		Map<String, String> nbt = nbt(lines);
		List<Scenario> scenarios = scenarios(lines);
		Map<String, List<String[]>> reference = loadReference();
		List<String> trace = new ArrayList<>();
		List<String> report = new ArrayList<>();
		SessionConfig c = new SessionConfig("127.0.0.1", PORT, NAME, 2, Duration.ZERO, false, "mcresearch-protocol-client-it", 9f, Duration.ofSeconds(5),
			SessionConfig.DEFAULT_READ_TIMEOUT, Path.of("build", "integration-vehicle-packets.log"));
		int compared = 0;
		int exact = 0;
		double worst = 0;
		int serverMismatch = 0;
		try (PacketLog log = PacketLog.open(c.packetLog())) {
			ClientSession session = new ClientSession(Connection.open(address, PORT, 5000, 30000, log), c);
			CompletableFuture<ClientSession.Result> result = CompletableFuture.supplyAsync(session::run);
			waitFor(() -> session.movement().ready() && session.movement().physics() != null, 20, "ready with physics");
			console.send(build, 2500);
			console.send(List.of("gamemode creative " + NAME, "kill @e[tag=m14v]"), 500);
			for (Scenario s : scenarios) {
				console.send(List.of(String.format(Locale.ROOT, "tp %s %s %s %s", NAME, s.x(), s.y() + 2, s.z() - 3)), 800);
				List<PlayerPhysics.Input> inputs = s.inputs().stream().map(VehicleIntegrationTest::input).toList();
				CompletableFuture<List<String>> f = session.movement().scheduleRideScript(s.yaw(), SETTLE, inputs, s.inputs());
				String data = nbt.getOrDefault(s.type(), "{Tags:[m14v]}");
				data = data.replaceFirst("\\{",String.format(Locale.ROOT, "{Rotation:[%sf,0f],", s.yaw()));
				console.send(List.of(String.format(Locale.ROOT, "summon %s %s %s %s %s", s.type(), s.x(), s.y(), s.z(), data),
					"ride " + NAME + " mount @e[tag=m14v,limit=1]"), 0);
				List<String> got = f.get(60, TimeUnit.SECONDS);
				for (String g : got) {
					trace.add(s.name() + " " + g);
				}
				// Serverposition des Fahrzeugs am Ende (move_vehicle angenommen?)
				Thread.sleep(300);
				double[] server = console.dataGet("@e[tag=m14v,limit=1]", "Pos");
				String[] last = got.getLast().split(" ");
				double dx = Double.parseDouble(last[2]) - server[0];
				double dy = Double.parseDouble(last[3]) - server[1];
				double dz = Double.parseDouble(last[4]) - server[2];
				double serverDiff = Math.sqrt(dx * dx + dy * dy + dz * dz);
				if (serverDiff > 0.01) {
					serverMismatch++;
				}
				// Vergleich mit der Vanilla-Referenz
				List<String[]> ref = reference.get(s.name());
				String cmp = "no reference";
				if (ref != null) {
					int n = Math.min(ref.size(), got.size());
					double maxD = 0;
					int firstDiff = -1;
					for (int k = 0; k < n; k++) {
						String[] o = got.get(k).split(" ");
						String[] r = ref.get(k);
						double d = Math.max(Math.abs(Double.parseDouble(o[2]) - Double.parseDouble(r[3])), Math.max(Math.abs(Double.parseDouble(o[3])
							- Double.parseDouble(r[4])), Math.abs(Double.parseDouble(o[4]) - Double.parseDouble(r[5]))));
						if (d > 0 && firstDiff < 0) {
							firstDiff = k + 1;
						}
						maxD = Math.max(maxD, d);
						compared++;
					}
					worst = Math.max(worst, maxD);
					if (maxD == 0 && n == got.size()) {
						exact++;
					}
					cmp = String.format(Locale.ROOT, "vanilla ticks %d/%d max|Δpos| %.3e first diff tick %d", n, ref.size(), maxD, firstDiff);
				}
				report.add(String.format(Locale.ROOT, "%-20s ticks %3d end (%.4f %.4f %.4f) server |Δ| %.2e corrections %d; %s", s.name(), got.size(),
					Double.parseDouble(last[2]), Double.parseDouble(last[3]), Double.parseDouble(last[4]), serverDiff, session.movement().vehicleCorrections(),
					cmp));
				// Absteigen wie ein Spieler: Schleichtaste (player_input shift) → Server setzt ab
				session.movement().setManualInput(new PlayerPhysics.Input(false, false, false, false, false, true, false));
				waitFor(() -> session.movement().ridingVehicle() < 0, 10, "dismount via sneak");
				session.movement().setManualInput(PlayerPhysics.Input.NONE);
				console.send(List.of("kill @e[tag=m14v]"), 600);
			}
			report.add(String.format(Locale.ROOT, "summary: %d scenarios, vanilla ticks compared %d, bit-exact %d, worst |Δpos| %.3e, server mismatches %d,"
				+ " own mounts %d dismounts %d", scenarios.size(), compared, exact, worst, serverMismatch, session.entities().stats().ownMounts,
				session.entities().stats().ownDismounts));
			session.requestStop("vehicle course done");
			ClientSession.Result r = result.get(30, TimeUnit.SECONDS);
			assertNull(r.serverDisconnectReason());
		} finally {
			Files.write(Path.of("build", "m14-vehicle-own-trace.txt"), trace, StandardCharsets.UTF_8);
			Files.write(Path.of("build", "m14-vehicle-report.txt"), report, StandardCharsets.UTF_8);
		}
		assertTrue(serverMismatch == 0, "server accepted vehicle positions: " + report);
	}
}
