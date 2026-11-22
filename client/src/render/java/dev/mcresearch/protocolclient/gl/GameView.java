package dev.mcresearch.protocolclient.gl;

import static org.lwjgl.glfw.GLFW.*;
import static org.lwjgl.opengl.GL33C.*;

import dev.mcresearch.protocolclient.net.Connection;
import dev.mcresearch.protocolclient.net.LoopbackGuard;
import dev.mcresearch.protocolclient.net.PacketLog;
import dev.mcresearch.protocolclient.physics.BlockRaycast;
import dev.mcresearch.protocolclient.physics.PlayerPhysics;
import dev.mcresearch.protocolclient.physics.ShapeRegistry;
import dev.mcresearch.protocolclient.session.ClientSession;
import dev.mcresearch.protocolclient.session.MovementController;
import dev.mcresearch.protocolclient.session.SessionConfig;
import dev.mcresearch.protocolclient.view.BlockModelProvider;
import dev.mcresearch.protocolclient.view.ColorModels;
import dev.mcresearch.protocolclient.view.Frustum;
import dev.mcresearch.protocolclient.view.Mat4;
import dev.mcresearch.protocolclient.world.BlockRegistry;
import dev.mcresearch.protocolclient.world.WorldState;
import java.io.IOException;
import java.net.InetAddress;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;

/**
 * Grafischer Spielbetrieb (docs/24 §4–§7): Sitzung (Netzwerk + 20-Hz-Physik wie im Terminalclient) auf eigenen Threads,
 * Fenster, Eingabe, Kamera und Chunk-Rendering auf dem Grafikthread.
 */
final class GameView {
	static final float EYE_HEIGHT = 1.62F;
	static final float MOUSE_SENSITIVITY = 0.15f;
	private static final float[] SKY = {0.53f, 0.70f, 0.98f};
	/** M11: Wassernebel (Standard {@code visual/water_fog_color} 0x050533) und Lavanebel (0x991A00). */
	private static final float[] WATER_FOG = {5 / 255f, 5 / 255f, 51 / 255f};
	private static final float[] LAVA_FOG = {153 / 255f, 26 / 255f, 0f};
	/** Fluid an der Kamera im letzten Bild (0 keins, 1 Wasser, 2 Lava) – für Autotest/Bericht. */
	static volatile int CAMERA_FLUID;

	private GameView() {
	}

	/** Start des grafischen Clients (Lebenszyklus in {@link MerlinApp}). */
	static void run(final GraphicalOptions o) throws Exception {
		MerlinApp.run(o);
	}

	/** Für Autotest und Leistungsmessung: Zähler des letzten Bildes und die aktuelle Auswahl (nur Grafikthread schreibt). */
	static volatile EntityRenderer.FrameInfo ENTITY_FRAME = new EntityRenderer.FrameInfo(0, 0, 0, 0, 0, 0);
	static volatile dev.mcresearch.protocolclient.entity.EntityPick.Target ENTITY_TARGET;
	/** M14: Darstellungsplan je Entity des letzten Bildes (look-dump) und GPU-Bestand des Entity-Renderers. */
	static volatile java.util.Map<Integer, String> ENTITY_LOOKS = java.util.Map.of();
	static volatile String ENTITY_GPU = "";
	/** M10: Todesbildschirm sichtbar und Lage des Knopfs „Wiederbeleben“ (für Autotest-Klicks über denselben Eingabeweg). */
	static volatile boolean DEATH_SCREEN;
	/** M11: Bildschirm offen (Inventar, Container, Tod) – Bewegungstasten wirkungslos. */
	static volatile boolean SCREEN_OPEN;
	/** M12: Umgebung und Nebel des letzten Bildes (Autotest-Bericht). */
	static volatile dev.mcresearch.protocolclient.view.env.EnvironmentState ENV;
	static volatile dev.mcresearch.protocolclient.view.env.FogModel.Fog FOG;
	/** M12: insgesamt hochgeladene Animationsbytes (Atlas). */
	static long ANIMATION_BYTES;

	/** M12: Mesh-Kontext (Fakten, Tönung, Biome, AO, Richtungsschattierung der Dimension) für eine Welt. */
	static dev.mcresearch.protocolclient.view.SectionMesher.Context meshContext(final BlockModelProvider models,
		final dev.mcresearch.protocolclient.view.env.EnvironmentData envData, final WorldState world,
		final dev.mcresearch.protocolclient.view.env.EnvironmentState env) {
		if (!(models instanceof dev.mcresearch.protocolclient.view.ResourceModels)) {
			return dev.mcresearch.protocolclient.view.SectionMesher.Context.simple(models);
		}
		boolean nether = env.dimension() != null && env.dimension().netherLighting();
		return new dev.mcresearch.protocolclient.view.SectionMesher.Context(models, dev.mcresearch.protocolclient.view.BlockRenderFacts.get(),
			dev.mcresearch.protocolclient.view.TintTable.get(), new dev.mcresearch.protocolclient.view.env.BiomeColors(envData), envData, world.biomeNames(),
			AMBIENT_OCCLUSION, BIOME_BLEND_RADIUS, nether ? dev.mcresearch.protocolclient.view.BlockLighter.CARDINAL_NETHER
				: dev.mcresearch.protocolclient.view.BlockLighter.CARDINAL_DEFAULT, ShapeRegistry.get());
	}

	/** M12: Himmel, Wolken, Niederschlag (Grafikthread). */
	static SkyRenderer SKY_RENDERER;
	static CloudRenderer CLOUDS;
	static WeatherRenderer WEATHER;
	static ParticleRenderer PARTICLES;
	static DestroyOverlay DESTROY;

	/** Spaltenabfragen für Niederschlag und Regenpartikel (Gelände, Niederschlagsart, Licht). */
	static WeatherRenderer.Columns weatherColumns(final WorldState ww, final dev.mcresearch.protocolclient.view.env.EnvironmentData envData) {
		final dev.mcresearch.protocolclient.view.BlockRenderFacts facts = dev.mcresearch.protocolclient.view.BlockRenderFacts.get();
		return new WeatherRenderer.Columns() {
			@Override
			public int motionBlockingHeight(final int x, final int z) {
				return ww.motionBlockingHeight(x, z, facts::motionBlocking);
			}

			@Override
			public dev.mcresearch.protocolclient.view.env.Precipitation.Kind precipitation(final int x, final int y, final int z) {
				var b = envData.biome(biomeAt(ww, x, y, z));
				return dev.mcresearch.protocolclient.view.env.Precipitation.at(b, x, y, z, 63);
			}

			@Override
			public int lightCoords(final int x, final int y, final int z) {
				return ww.renderLight(false, x, y, z) << 4 | ww.renderLight(true, x, y, z) << 20;
			}
		};
	}

	/** Wolkenweite in Chunks (Vanilla-Vorgabe cloudRange 128). */
	static final int CLOUD_RANGE = 128;

	/** M12: Grafikoptionen wie Vanilla-Vorgaben (ao true, biomeBlendRadius 2). */
	static volatile boolean AMBIENT_OCCLUSION = true;
	static volatile int BIOME_BLEND_RADIUS = 2;

	/** Biom (zoomt wie {@code BiomeManager#getBiome}) an der Kameraposition, Registry-Name. */
	static String biomeAt(final WorldState world, final double x, final double y, final double z) {
		int id = dev.mcresearch.protocolclient.world.BiomeZoom.biome(world.hashedSeed(), (int) Math.floor(x), (int) Math.floor(y), (int) Math.floor(z),
			world::noiseBiome);
		return id >= 0 && id < world.biomeNames().size() ? world.biomeNames().get(id) : "minecraft:plains";
	}

	/** M12: wie F1 in Vanilla (Hud#isHidden) – keine HUD-Elemente, kein Auswahlrahmen, keine Namensschilder (Vergleichsbilder). */
	static volatile boolean HIDE_GUI;
	/** M12: HUD-Zustand des letzten Bildes (Autotest hud-dump, docs/49 §4). */
	static volatile String HUD_INFO = "-";
	/** M12: Leistungsmessung des Grafikthreads (Autotest perf-begin/perf-end). */
	static FrameProfiler PROFILER;
	/** Synthetische Mausereignisse des Autotests; werden wie echte Ereignisse verarbeitet. */
	static final java.util.concurrent.ConcurrentLinkedQueue<Window.MouseEvent> SYNTHETIC_MOUSE = new java.util.concurrent.ConcurrentLinkedQueue<>();
	static volatile float[] RESPAWN_BUTTON = new float[4];

	static String entityLine(final ClientSession session, final EntityRenderer.FrameInfo f,
		final dev.mcresearch.protocolclient.entity.EntityPick.Target t) {
		dev.mcresearch.protocolclient.entity.EntityManager.Stats st = session.entities().stats();
		String target = t != null && t.kind() == dev.mcresearch.protocolclient.entity.EntityPick.Kind.ENTITY
			? String.format(Locale.ROOT, "%s #%d %s (%.2f m)", t.entity().type().shortName(), t.entity().id(),
				t.entity().playerName() != null ? t.entity().playerName() : t.entity().uuid().toString().substring(0, 8), t.distance()) : "–";
		return String.format(Locale.ROOT, "Entities %d (gezeichnet %d, verworfen %d, Debug-Box %d, %d Draws, %.2f ms) Pakete %d, unbekannt %d | Entity-Ziel %s",
			f.total(), f.drawn(), f.culled(), f.debugBoxes(), f.drawCalls(), f.cpuNanos() / 1e6, st.packetsHandled, st.unknownIdPackets, target);
	}

	/** F3-Zeile zum Auswahlziel: Block, Seite, Trefferpunkt, Zustand (docs/27 §5). */
	static String targetLine(final ClientSession session, final BlockRaycast.Hit t) {
		if (t == null || t.miss()) {
			return String.format(Locale.ROOT, "Ziel: –   Reichweite Block %.2f / Entity %.2f", session.blockInteractionRange(), session.entityInteractionRange());
		}
		WorldState world = session.world();
		String state = "?";
		if (world != null) {
			synchronized (world) {
				int id = world.stateId(t.blockX(), t.blockY(), t.blockZ());
				state = id >= 0 && BlockRegistry.get().isValid(id) ? BlockRegistry.get().state(id).toString() : "?";
			}
		}
		return String.format(Locale.ROOT, "Ziel: %d %d %d  Seite %s  Punkt %.3f %.3f %.3f%s  %s", t.blockX(), t.blockY(), t.blockZ(),
			t.face().name().toLowerCase(Locale.ROOT), t.x(), t.y(), t.z(), t.inside() ? " (innen)" : "", state);
	}

	static void debugLines(final List<String> lines, final ClientSession session, final MovementController m,
		final MovementController.RenderState rs, final ChunkRenderer renderer, final double cx, final double cy, final double cz,
		final int renderDistance, final String host, final int port, final dev.mcresearch.protocolclient.input.InputManager input) {
		float yaw = ((rs.yaw() % 360) + 360) % 360;
		String facing = yaw >= 315 || yaw < 45 ? "Süden (+Z)" : yaw < 135 ? "Westen (−X)" : yaw < 225 ? "Norden (−Z)" : "Osten (+X)";
		lines.add(String.format(Locale.ROOT, "XYZ %.3f / %.3f / %.3f   Block %d %d %d   Chunk %d %d", rs.x(), rs.y(), rs.z(),
			(int) Math.floor(rs.x()), (int) Math.floor(rs.y()), (int) Math.floor(rs.z()), Math.floorDiv((int) Math.floor(rs.x()), 16),
			Math.floorDiv((int) Math.floor(rs.z()), 16)));
		lines.add(String.format(Locale.ROOT, "Blick Yaw %.1f Pitch %.1f  %s   onGround %s   Korrekturen %d", rs.yaw(), rs.pitch(), facing,
			rs.onGround(), m.corrections()));
		WorldState world = session.world();
		lines.add(String.format(Locale.ROOT, "Welt: %d Chunks geladen, Sichtweite %d   Verbindung %s %s:%d   Keep-Alive %d (max. Lücke %d ms)",
			world == null ? 0 : world.loadedChunks(), renderDistance, session.playing() ? "PLAY" : session.ended() ? "GETRENNT" : session.state(), host, port,
			session.stats().keepAlivesAnswered(), session.stats().maxKeepAliveGapMillis()));
		if (renderer != null) {
			ChunkRenderer.Stats s = renderer.stats();
			lines.add(String.format(Locale.ROOT, "Render: %d Meshes, %d sichtbar, %d Draw Calls, %,d Vertices, GPU %.1f MB", s.meshes(), s.drawnSections(),
				s.drawCalls(), s.drawnVertices(), s.gpuBytes() / 1048576.0));
			lines.add(String.format(Locale.ROOT, "Meshing: %d gebaut, Ø %.2f ms, max %.1f ms, offen %d, Debug-Blöcke %d", s.builds(), s.avgBuildMs(),
				s.maxBuildMs(), s.dirty() + s.building(), s.debugBlocks()));
		}
		lines.add(envLine(session));
		Runtime rt = Runtime.getRuntime();
		lines.add(String.format(Locale.ROOT, "Heap %d / %d MB   %s", (rt.totalMemory() - rt.freeMemory()) >> 20, rt.maxMemory() >> 20,
			session.transportStats()));
		dev.mcresearch.protocolclient.physics.PlayerPhysics pp = session.movement().physics();
		if (pp != null) {
			dev.mcresearch.protocolclient.physics.PlayerPhysics.MotionState ms = pp.motionState();
			lines.add(String.format(Locale.ROOT, "Bewegung: Pose %s%s%s%s  Wasser %.2f%s  Lava %.2f%s", ms.pose(), ms.sprinting() ? " Sprint" : "",
				ms.swimming() ? " Schwimmen" : "", ms.onClimbable() ? " Klettern" : "", ms.waterHeight(), ms.underWater() ? " (unter Wasser)" : "", ms.lavaHeight(),
				CAMERA_FLUID == 0 ? "" : CAMERA_FLUID == 1 ? "  Kamera im Wasser" : "  Kamera in Lava"));
		}
		lines.add(helpLine(input));
	}

	/** Hilfezeile der F3-Anzeige; bei geänderter Belegung mit den tatsächlichen Tasten (M15). */
	static String helpLine(final dev.mcresearch.protocolclient.input.InputManager input) {
		dev.mcresearch.protocolclient.input.KeyBindings b = input == null ? null : input.bindings();
		boolean defaults = b == null || java.util.Arrays.stream(dev.mcresearch.protocolclient.input.GameAction.values()).allMatch(b::isDefault);
		if (defaults) {
			return "WASD gehen, Leertaste springen, Umschalt schleichen, Strg sprinten, Maus schauen, Links abbauen, Rechts benutzen, 1–9/Mausrad Hotbar, E Inventar, Esc, F3, F2";
		}
		java.util.function.Function<dev.mcresearch.protocolclient.input.GameAction, String> k = a -> b.get(a).displayName();
		return String.format(Locale.ROOT, "%s/%s/%s/%s gehen, %s springen, %s schleichen, %s sprinten, %s abbauen, %s benutzen, %s Inventar, Esc Menü",
			k.apply(dev.mcresearch.protocolclient.input.GameAction.MOVE_FORWARD), k.apply(dev.mcresearch.protocolclient.input.GameAction.MOVE_LEFT), k.apply(dev.mcresearch.protocolclient.input.GameAction.MOVE_BACKWARD), k.apply(dev.mcresearch.protocolclient.input.GameAction.MOVE_RIGHT), k.apply(dev.mcresearch.protocolclient.input.GameAction.JUMP), k.apply(dev.mcresearch.protocolclient.input.GameAction.SNEAK),
			k.apply(dev.mcresearch.protocolclient.input.GameAction.SPRINT), k.apply(dev.mcresearch.protocolclient.input.GameAction.ATTACK), k.apply(dev.mcresearch.protocolclient.input.GameAction.USE), k.apply(dev.mcresearch.protocolclient.input.GameAction.INVENTORY));
	}

	/** M12: Umgebung (Server-Uhr, Wetter, Attribute an der Kamera), Partikel, Wolken, Niederschlag – F3 und Autotest env-dump. */
	static String envLine(final ClientSession session) {
		dev.mcresearch.protocolclient.view.env.EnvironmentState e = ENV;
		dev.mcresearch.protocolclient.view.env.FogModel.Fog f = FOG;
		if (e == null || f == null) {
			return "Umgebung: –";
		}
		float[] sky = e.value(dev.mcresearch.protocolclient.view.env.EnvAttr.SKY_COLOR);
		return String.format(Locale.ROOT,
			"Umgebung: %s  Sonne %.1f°  Mond %d  Sterne %.3f  Himmel #%02x%02x%02x  Himmelslicht %.3f  Nebel #%02x%02x%02x %.0f–%.0f/%.0f–%.0f  "
				+ "Partikel %d (Server %d, nicht unterstützt %d)  Wolkenflächen %d  Niederschlag %d Spalten (Höhe %d, ohne %d, Gelände %d)",
			session.levelEnvironment().describe(), e.value(dev.mcresearch.protocolclient.view.env.EnvAttr.SUN_ANGLE)[0],
			(int) e.value(dev.mcresearch.protocolclient.view.env.EnvAttr.MOON_PHASE)[0], e.value(dev.mcresearch.protocolclient.view.env.EnvAttr.STAR_BRIGHTNESS)[0],
			(int) (sky[0] * 255), (int) (sky[1] * 255), (int) (sky[2] * 255), e.value(dev.mcresearch.protocolclient.view.env.EnvAttr.SKY_LIGHT_FACTOR)[0],
			(int) (f.r() * 255), (int) (f.g() * 255), (int) (f.b() * 255), f.envStart(), f.envEnd(), f.rdStart(), f.rdEnd(),
			PARTICLES == null ? 0 : PARTICLES.system().size(), PARTICLES == null ? 0 : PARTICLES.fromServer(), PARTICLES == null ? 0 : PARTICLES.unsupported(),
			CLOUDS == null ? 0 : CLOUDS.faces(), WEATHER == null ? 0 : WEATHER.columns(), WEATHER == null ? 0 : WEATHER.skippedHeight,
			WEATHER == null ? 0 : WEATHER.skippedNone, WEATHER == null ? 0 : WEATHER.lastTerrainSample);
	}

	private static void configureLogging() {
		System.setProperty("java.util.logging.SimpleFormatter.format", "[%1$tT] [%4$s] %3$s: %5$s%6$s%n");
	}

	/** Bildraten-Statistik (gleitend über 2 s). */
	static final class FrameStats {
		private final long[] times = new long[512];
		private int count;
		private long last;
		private double minFps = Double.MAX_VALUE;
		private long frames;

		void frame(final long now) {
			if (this.last != 0) {
				this.times[this.count++ % this.times.length] = now - this.last;
			}
			this.last = now;
			this.frames++;
			if (this.frames > 120) {
				this.minFps = Math.min(this.minFps, fps());
			}
		}

		double avgMs() {
			int n = Math.min(this.count, this.times.length);
			if (n == 0) {
				return 0;
			}
			long sum = 0;
			for (int i = 0; i < n; i++) {
				sum += this.times[i];
			}
			return sum / 1e6 / n;
		}

		double fps() {
			double ms = avgMs();
			return ms == 0 ? 0 : 1000.0 / ms;
		}

		/** Bildrate der langsamsten 1 % Frames im Fenster. */
		double low1() {
			int n = Math.min(this.count, this.times.length);
			if (n < 10) {
				return fps();
			}
			long[] sorted = java.util.Arrays.copyOf(this.times, n);
			java.util.Arrays.sort(sorted);
			long p99 = sorted[Math.min(n - 1, (int) (n * 0.99))];
			return 1e9 / p99;
		}

		double minFps() {
			return this.minFps == Double.MAX_VALUE ? 0 : this.minFps;
		}

		long frames() {
			return this.frames;
		}
	}

	static void appendReport(final Path report, final String line) {
		if (report == null) {
			return;
		}
		try {
			Files.createDirectories(report.toAbsolutePath().getParent());
			Files.writeString(report, line + System.lineSeparator(), StandardCharsets.UTF_8, StandardOpenOption.CREATE, StandardOpenOption.APPEND);
		} catch (IOException e) {
			System.err.println("[GL] report: " + e);
		}
	}
}
