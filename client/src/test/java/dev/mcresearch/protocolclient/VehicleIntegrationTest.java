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
 * Vanilla-Server. Je Szenario: Hindernisse (Tag m14o) und Fahrzeug (Tag m14v) beschwören, aufsitzen, Aufsitz-Tick + 40 Ruheticks,
 * Eingaben (Absteigen per Schleichtaste ist Teil der Folge); {@code walk}-Szenarien laufen ohne Fahrzeug. Spur je Tick, Vergleich
 * aller Felder mit der Vanilla-Referenzspur (Position, Geschwindigkeit, Gier, Bodenkontakt, horizontale Kollision, Blickrichtung,
 * Absteigetick und -position) und mit der Serverposition am Ende.
 */
@Tag("integration")
class VehicleIntegrationTest {
	private static final int PORT = 25565;
	private static final String NAME = "ProtoBotVeh";
	private static final int SETTLE = 40;
	private static InetAddress address;
	private static ServerConsole console;
	private static Path course;

	record Scenario(String name, String type, boolean walk, double x, double y, double z, float yaw, List<String> inputs) {
	}

	record Obstacle(String scenario, String type, double x, double y, double z, float yaw) {
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
			boolean walk = t.length >= 7 && t[0].equals("walk");
			if (walk || t.length >= 8 && t[0].equals("vehicle")) {
				int o = walk ? 0 : 1;
				List<String> inputs = new ArrayList<>();
				for (int i = 6 + o; i < t.length; i++) {
					String[] seg = t[i].split(":");
					for (int n = 0; n < Integer.parseInt(seg[1]); n++) {
						inputs.add(seg[0]);
					}
				}
				out.add(new Scenario(t[1], walk ? "walk" : t[2], walk, Double.parseDouble(t[2 + o]), Double.parseDouble(t[3 + o]),
					Double.parseDouble(t[4 + o]), Float.parseFloat(t[5 + o]), inputs));
			}
		}
		return out;
	}

	static List<Obstacle> obstacles(final List<String> lines) {
		List<Obstacle> out = new ArrayList<>();
		for (String line : lines) {
			String[] t = line.trim().split("\\s+");
			if (t.length == 7 && t[0].equals("obstacle")) {
				out.add(new Obstacle(t[1], t[2], Double.parseDouble(t[3]), Double.parseDouble(t[4]), Double.parseDouble(t[5]), Float.parseFloat(t[6])));
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

	/** Feldweiser Vergleich einer eigenen Spur (ohne Szenariospalte) mit der Referenz (mit Szenariospalte). */
	static final class Comparison {
		int n;
		int exactTicks;
		int firstDiff = -1;
		double pos;
		double vel;
		double yRot;
		double playerYRot;
		int groundMismatch;
		int collisionMismatch;
		String dismount = "-";
		boolean dismountExact = true;

		void add(final String[] o, final String[] r) {
			double dp = 0;
			double dv = 0;
			for (int i = 0; i < 3; i++) {
				dp = Math.max(dp, Math.abs(Double.parseDouble(o[2 + i]) - Double.parseDouble(r[3 + i])));
				dv = Math.max(dv, Math.abs(Double.parseDouble(o[5 + i]) - Double.parseDouble(r[6 + i])));
			}
			double dy = Math.abs(Float.parseFloat(o[8]) - Float.parseFloat(r[9]));
			double dpy = Math.abs(Float.parseFloat(o[11]) - Float.parseFloat(r[12]));
			boolean g = o[9].equals(r[10]);
			boolean h = o[10].equals(r[11]);
			this.pos = Math.max(this.pos, dp);
			this.vel = Math.max(this.vel, dv);
			this.yRot = Math.max(this.yRot, dy);
			this.playerYRot = Math.max(this.playerYRot, dpy);
			this.groundMismatch += g ? 0 : 1;
			this.collisionMismatch += h ? 0 : 1;
			this.n++;
			if (dp == 0 && dv == 0 && dy == 0 && dpy == 0 && g && h) {
				this.exactTicks++;
			} else if (this.firstDiff < 0) {
				this.firstDiff = this.n;
			}
		}

		@Override
		public String toString() {
			return String.format(Locale.ROOT, "vanilla ticks %d exact %d first diff %d max|Δpos| %.3e |Δv| %.3e |ΔyRot| %.3e |ΔplayerYRot| %.3e"
				+ " ground≠ %d hc≠ %d dismount %s", this.n, this.exactTicks, this.firstDiff, this.pos, this.vel, this.yRot, this.playerYRot,
				this.groundMismatch, this.collisionMismatch, this.dismount);
		}
	}

	@Test
	void vehicleCourseMatchesServerAndVanillaReference() throws Exception {
		List<String> lines = Files.readAllLines(course, StandardCharsets.UTF_8);
		List<String> build = lines.stream().filter(l -> l.startsWith("build ")).map(l -> l.substring(6)).toList();
		Map<String, String> nbt = nbt(lines);
		List<Scenario> scenarios = scenarios(lines);
		List<Obstacle> obstacles = obstacles(lines);
		Map<String, List<String[]>> reference = loadReference();
		List<String> trace = new ArrayList<>();
		List<String> report = new ArrayList<>();
		SessionConfig c = new SessionConfig("127.0.0.1", PORT, NAME, 2, Duration.ZERO, false, "mcresearch-protocol-client-it", 9f, Duration.ofSeconds(5),
			SessionConfig.DEFAULT_READ_TIMEOUT, Path.of("build", "integration-vehicle-packets.log"));
		int compared = 0;
		int exactTicks = 0;
		int exactScenarios = 0;
		int serverMismatch = 0;
		try (PacketLog log = PacketLog.open(c.packetLog())) {
			ClientSession session = new ClientSession(Connection.open(address, PORT, 5000, 30000, log), c);
			CompletableFuture<ClientSession.Result> result = CompletableFuture.supplyAsync(session::run);
			waitFor(() -> session.movement().ready() && session.movement().physics() != null, 20, "ready with physics");
			console.send(build, 2500);
			console.send(List.of("gamemode creative " + NAME, "kill @e[tag=m14v]", "kill @e[tag=m14o]"), 500);
			for (Scenario s : scenarios) {
				long corrBefore = session.movement().vehicleCorrections();
				List<String> summon = new ArrayList<>();
				for (Obstacle o : obstacles) {
					if (o.scenario().equals(s.name())) {
						summon.add(String.format(Locale.ROOT, "summon %s %s %s %s {Rotation:[%sf,0f],Tags:[m14o]}", o.type(), o.x(), o.y(), o.z(), o.yaw()));
					}
				}
				List<String> got;
				if (s.walk()) {
					summon.add(String.format(Locale.ROOT, "tp %s %s %s %s %s 0", NAME, s.x(), s.y(), s.z(), s.yaw()));
					console.send(summon, 1500);
					List<PlayerPhysics.Input> inputs = new ArrayList<>();
					for (int i = 0; i < SETTLE; i++) {
						inputs.add(PlayerPhysics.Input.NONE);
					}
					s.inputs().forEach(k -> inputs.add(input(k)));
					List<PlayerPhysics.TickResult> rs = session.movement().script(inputs).get(60, TimeUnit.SECONDS);
					got = new ArrayList<>();
					float yaw = session.movement().renderState().yaw();
					for (int k = SETTLE; k < rs.size(); k++) {
						PlayerPhysics.TickResult r = rs.get(k);
						got.add(String.join(" ", Integer.toString(k - SETTLE + 1), s.inputs().get(k - SETTLE), Double.toString(r.x()), Double.toString(r.y()),
							Double.toString(r.z()), Double.toString(r.vx()), Double.toString(r.vy()), Double.toString(r.vz()), Float.toString(yaw),
							Boolean.toString(r.onGround()), Boolean.toString(r.horizontalCollision()), Float.toString(yaw)));
					}
				} else {
					summon.add(0, String.format(Locale.ROOT, "tp %s %s %s %s", NAME, s.x(), s.y() + 2, s.z() - 3));
					console.send(summon, 800);
					List<PlayerPhysics.Input> inputs = s.inputs().stream().map(VehicleIntegrationTest::input).toList();
					CompletableFuture<List<String>> f = session.movement().scheduleRideScript(s.yaw(), SETTLE, inputs, s.inputs());
					String data = nbt.getOrDefault(s.type(), "{Tags:[m14v]}");
					data = data.replaceFirst("\\{", String.format(Locale.ROOT, "{Rotation:[%sf,0f],", s.yaw()));
					console.send(List.of(String.format(Locale.ROOT, "summon %s %s %s %s %s", s.type(), s.x(), s.y(), s.z(), data),
						"ride " + NAME + " mount @e[tag=m14v,limit=1]"), 0);
					got = f.get(60, TimeUnit.SECONDS);
				}
				for (String g : got) {
					trace.add(s.name() + " " + g);
				}
				List<String> ticks = got.stream().filter(g -> !g.startsWith("dismount ")).toList();
				String dismountLine = got.stream().filter(g -> g.startsWith("dismount ")).findFirst().orElse(null);
				// Serverposition am Ende (Fahrzeug bzw. Spieler beim Laufen)
				Thread.sleep(300);
				double[] server = console.dataGet(s.walk() ? NAME : "@e[tag=m14v,limit=1]", "Pos");
				String[] last = ticks.getLast().split(" ");
				double dx = Double.parseDouble(last[2]) - server[0];
				double dy = Double.parseDouble(last[3]) - server[1];
				double dz = Double.parseDouble(last[4]) - server[2];
				double serverDiff = Math.sqrt(dx * dx + dy * dy + dz * dz);
				boolean serverDriven = s.type().endsWith("minecart");
				if (serverDiff > 0.01 && !serverDriven) {
					serverMismatch++;
				}
				// Vergleich mit der Vanilla-Referenz
				List<String[]> ref = reference.get(s.name());
				String cmp = "no reference";
				if (ref != null) {
					List<String[]> refTicks = ref.stream().filter(r -> !r[1].equals("dismount")).toList();
					String[] refDismount = ref.stream().filter(r -> r[1].equals("dismount")).findFirst().orElse(null);
					Comparison cmpr = new Comparison();
					int n = Math.min(refTicks.size(), ticks.size());
					for (int k = 0; k < n; k++) {
						cmpr.add(ticks.get(k).split(" "), refTicks.get(k));
					}
					if (refDismount != null || dismountLine != null) {
						if (refDismount == null || dismountLine == null) {
							cmpr.dismount = "only " + (refDismount == null ? "own" : "vanilla");
							cmpr.dismountExact = false;
						} else {
							String[] o = dismountLine.split(" ");
							double d = 0;
							for (int i = 0; i < 3; i++) {
								d = Math.max(d, Math.abs(Double.parseDouble(o[2 + i]) - Double.parseDouble(refDismount[3 + i])));
							}
							cmpr.dismountExact = d == 0 && o[1].equals(refDismount[2]);
							cmpr.dismount = String.format(Locale.ROOT, "tick %s/%s |Δpos| %.3e", o[1], refDismount[2], d);
						}
					}
					compared += cmpr.n;
					exactTicks += cmpr.exactTicks;
					if (cmpr.exactTicks == cmpr.n && n == ticks.size() && n == refTicks.size() && cmpr.dismountExact) {
						exactScenarios++;
					}
					cmp = cmpr + String.format(Locale.ROOT, " (ticks own %d / vanilla %d)", ticks.size(), refTicks.size());
				}
				report.add(String.format(Locale.ROOT, "%-20s %-16s end (%.4f %.4f %.4f) server |Δ| %.2e%s corrections %d; %s", s.name(), s.type(),
					Double.parseDouble(last[2]), Double.parseDouble(last[3]), Double.parseDouble(last[4]), serverDiff, serverDriven ? " (serverseitig)" : "",
					session.movement().vehicleCorrections() - corrBefore, cmp));
				if (!s.walk() && session.movement().ridingVehicle() >= 0) {
					// nicht während der Folge abgestiegen: Schleichtaste wie ein Spieler
					session.movement().setManualInput(new PlayerPhysics.Input(false, false, false, false, false, true, false));
					waitFor(() -> session.movement().ridingVehicle() < 0, 10, "dismount via sneak");
					session.movement().setManualInput(PlayerPhysics.Input.NONE);
				}
				console.send(List.of("kill @e[tag=m14v]", "kill @e[tag=m14o]"), 600);
			}
			report.add(String.format(Locale.ROOT, "summary: %d scenarios, vanilla ticks compared %d, bit-exact ticks %d, bit-exact scenarios %d,"
				+ " server mismatches %d, own mounts %d dismounts %d, corrections %d", scenarios.size(), compared, exactTicks, exactScenarios, serverMismatch,
				session.entities().stats().ownMounts, session.entities().stats().ownDismounts, session.movement().vehicleCorrections()));
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
