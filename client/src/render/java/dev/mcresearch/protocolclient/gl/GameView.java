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

	static void run(final GraphicalOptions o) throws Exception {
		configureLogging();
		// M13 (docs/52 §8): Identität vor dem Spielfenster bestimmen; Standard bleibt der Offline-Testmodus ohne Netzwerkanmeldung
		dev.mcresearch.protocolclient.auth.LoginIdentity identity;
		if (o.loginUi()) {
			identity = LoginWindow.show(o);
			if (identity == null) {
				System.out.println("[LOGIN] Anmeldung geschlossen, kein Verbindungsaufbau");
				return;
			}
		} else {
			try {
				identity = dev.mcresearch.protocolclient.auth.minecraft.LoginFactory.create(o.auth().options(o.username()),
					s -> System.out.println("[LOGIN] " + s), new dev.mcresearch.protocolclient.auth.AuthCancellation());
			} catch (dev.mcresearch.protocolclient.auth.AuthException e) {
				System.out.println("[LOGIN] FAILED " + e.error() + ": " + e.userMessage());
				return;
			}
		}
		System.out.println("[LOGIN] " + identity.kind() + " " + identity.name() + " " + identity.profileId());
		SessionConfig cfg = new SessionConfig(o.host(), o.port(), identity.name(), o.renderDistance(), Duration.ZERO, false, "mcresearch-graphical",
			9f, Duration.ofSeconds(5), SessionConfig.DEFAULT_READ_TIMEOUT, o.packetLog());
		InetAddress address = LoopbackGuard.resolveLoopback(o.host()); // nur localhost (wie Terminalclient)
		try (PacketLog packets = PacketLog.open(cfg.packetLog());
			Window w = new Window("Minecraft Research – Graphical Client", o.width(), o.height(), o.vsync())) {
			System.out.println("[GL] " + w.glInfo());
			BlockModelProvider models;
			TextureAtlasGl atlas = null;
			dev.mcresearch.protocolclient.view.env.EnvironmentData envData = dev.mcresearch.protocolclient.view.env.EnvironmentData.empty();
			if (o.textures() && o.assets() != null && Files.isRegularFile(o.assets())) {
				long t0 = System.nanoTime();
				var res = dev.mcresearch.protocolclient.view.ResourceModels.load(o.assets(), BlockRegistry.get());
				atlas = new TextureAtlasGl(res.atlas());
				models = res;
				System.out.printf(Locale.ROOT, "[GL] resource models: %s (%.0f ms)%n", res.summary(), (System.nanoTime() - t0) / 1e6);
				long t1 = System.nanoTime();
				envData = dev.mcresearch.protocolclient.view.env.EnvironmentData.load(o.assets());
				System.out.printf(Locale.ROOT, "[GL] environment data: %s (%.0f ms)%n", envData.summary(), (System.nanoTime() - t1) / 1e6);
			} else {
				if (o.textures()) {
					System.out.println("[GL] no local Minecraft assets (--assets) – colour mode");
				}
				models = new ColorModels(BlockRegistry.get());
			}
			ClientSession session = new ClientSession(Connection.open(address, cfg.port(), (int) cfg.connectTimeout().toMillis(),
				(int) cfg.readTimeout().toMillis(), packets), cfg, identity);
			session.setAutoRespawn(o.autoRespawn()); // M10: der Grafikclient wartet auf den Spieler (Todesbildschirm)
			CompletableFuture<ClientSession.Result> result = new CompletableFuture<>();
			Thread.ofPlatform().name("session").daemon(true).start(() -> result.complete(session.run()));
			try (ShaderProgram shader = new ShaderProgram(Shaders.WORLD_VERTEX, Shaders.WORLD_FRAGMENT); Overlay overlay = new Overlay();
				HudRenderer hud = new HudRenderer(); Hud gameHud = new Hud(); InventoryScreen invScreen = new InventoryScreen();
				EntityRenderer entityRenderer = new EntityRenderer(o.textures() ? o.assets() : null, o.skins()); NameTags nameTags = new NameTags();
				SurvivalHud survivalHud = new SurvivalHud(o.textures() ? o.assets() : null); FrameProfiler profiler = new FrameProfiler();
				LightmapGl lightmap = new LightmapGl(); SkyRenderer sky = new SkyRenderer(o.textures() ? o.assets() : null);
				CloudRenderer clouds = new CloudRenderer(o.textures() ? o.assets() : null); WeatherRenderer weather = new WeatherRenderer(o.textures() ? o.assets() : null);
				ParticleRenderer particles = new ParticleRenderer(o.textures() ? o.assets() : null); DestroyOverlay destroyOverlay = new DestroyOverlay(o.textures() ? o.assets() : null)) {
				PARTICLES = particles;
				DESTROY = destroyOverlay;
				PROFILER = profiler;
				SKY_RENDERER = sky;
				CLOUDS = clouds;
				WEATHER = weather;
				loop(o, w, session, result, models, atlas, shader, overlay, hud, gameHud, invScreen, entityRenderer, nameTags, survivalHud, lightmap, envData);
			} finally {
				if (atlas != null) {
					atlas.close();
				}
				// M7-Leckprüfung: nach dem Schließen aller Renderer darf kein GL-Objekt mehr leben
				System.out.println("[GL] live GL objects at exit: " + GlResources.live() + " " + GlResources.summary());
				session.requestStop("window closed");
				try {
					ClientSession.Result r = result.get(10, TimeUnit.SECONDS);
					System.out.println("[GL] session: " + r.outcome());
				} catch (java.util.concurrent.TimeoutException e) {
					System.out.println("[GL] session did not finish within 10 s");
				}
			}
		}
	}

	private static void loop(final GraphicalOptions o, final Window w, final ClientSession session, final CompletableFuture<ClientSession.Result> result,
		final BlockModelProvider models, final TextureAtlasGl atlas, final ShaderProgram shader, final Overlay overlay, final HudRenderer hud,
		final Hud gameHud, final InventoryScreen invScreen, final EntityRenderer entityRenderer, final NameTags nameTags, final SurvivalHud survivalHud,
		final LightmapGl lightmap, final dev.mcresearch.protocolclient.view.env.EnvironmentData envData) throws IOException {
		dev.mcresearch.protocolclient.view.env.EnvironmentState env = new dev.mcresearch.protocolclient.view.env.EnvironmentState(envData);
		dev.mcresearch.protocolclient.view.env.FogModel fogModel = new dev.mcresearch.protocolclient.view.env.FogModel();
		dev.mcresearch.protocolclient.view.TextureAtlas cpuAtlas = models instanceof dev.mcresearch.protocolclient.view.ResourceModels rm ? rm.atlas() : null;
		long lastEnvTick = -1;
		long lastFrameNanos = System.nanoTime();
		float flicker = 0f;
		java.util.Random flickerRandom = new java.util.Random();
		boolean inventoryOpen = false;
		boolean screenWasOpen = false;
		ShapeRegistry shapes = ShapeRegistry.get();
		BlockRaycast.Hit target = null;
		MovementController m = session.movement();
		ChunkRenderer renderer = null;
		WorldState rendered = null;
		Autotest auto = o.autotest() == null ? null : Autotest.load(o.autotest(), o.report(), o.username());
		FrameStats fps = new FrameStats();
		boolean debug = true;
		boolean captureRequested = auto == null; // im Autotest bleibt die Maus frei
		long start = System.nanoTime();
		long frame = 0;
		String banner = "";
		String lastBanner = "";
		long overlayBuilt = 0;
		List<String> lines = new ArrayList<>();
		glEnable(GL_DEPTH_TEST);
		glEnable(GL_CULL_FACE);
		glCullFace(GL_BACK);
		glFrontFace(GL_CCW);
		try {
			while (!w.shouldClose()) {
				long now = System.nanoTime();
				fps.frame(now);
				PROFILER.frameStart(now);
				frame++;
				w.consumeResize();
				// ---- Eingabe ----
				if (captureRequested && session.playing() && glfwGetWindowAttrib(windowHandle(w), GLFW_FOCUSED) == GLFW_TRUE) {
					w.setMouseCaptured(true);
					captureRequested = false;
				}
				// ---- Inventar- und Containerfenster (M8): E öffnet das Spielerinventar, E/Esc schließen; Container öffnet der Server ----
				int invReq = auto == null ? -1 : auto.consumeInventoryRequest();
				boolean serverContainer = session.inventory().openContainer() != null;
				boolean screenOpen = inventoryOpen || serverContainer;
				boolean closeKey = w.wasPressed(GLFW_KEY_E) || w.wasPressed(GLFW_KEY_ESCAPE) || invReq == 0;
				java.util.List<Window.MouseEvent> mouseEvents = new ArrayList<>(w.consumeMouseEvents());
				for (Window.MouseEvent me; (me = SYNTHETIC_MOUSE.poll()) != null;) {
					mouseEvents.add(me); // M10: Autotest-Klicks auf denselben Weg
				}
				if (!screenOpen && session.playing() && (w.wasPressed(GLFW_KEY_E) || invReq == 1)) {
					inventoryOpen = true;
					screenOpen = true;
					w.setMouseCaptured(false);
				} else if (screenOpen && closeKey) {
					session.closeContainer(session.inventory().currentId()); // AbstractContainerScreen#onClose → container_close
					inventoryOpen = false;
					screenOpen = false;
					captureRequested = auto == null;
				} else if (screenOpen) {
					if (!screenWasOpen) {
						w.setMouseCaptured(false); // vom Server geöffneter Container
					}
					if (auto == null || auto.externalInput()) {
						invScreen.input(session, w, mouseEvents, false);
					}
				} else if (w.wasPressed(GLFW_KEY_ESCAPE)) {
					w.setMouseCaptured(false);
				}
				if (screenWasOpen && !screenOpen && !inventoryOpen) {
					captureRequested = auto == null; // Server hat den Container geschlossen
				}
				screenWasOpen = screenOpen;
				final boolean screenNow = screenOpen;
				w.setScreenOpen(screenNow);
				// ---- Blockinteraktion und Hotbar (M7): wie Minecraft#handleKeybinds, ausgewertet im nächsten Client-Tick ----
				dev.mcresearch.protocolclient.session.BlockInteraction bi = session.interaction();
				// M10: Todesbildschirm – keine Weltsteuerung, Maus frei, Klick auf den Knopf fordert die Wiederbelebung an
				boolean deathScreen = survivalHud.deathScreenVisible();
				if (deathScreen) {
					if (w.mouseCaptured()) {
						w.setMouseCaptured(false);
					}
					for (Window.MouseEvent me : mouseEvents) {
						if (me.press() && me.button() == 0 && survivalHud.overRespawnButton(me.x(), me.y())) {
							session.requestRespawn();
							captureRequested = auto == null;
						}
					}
				}
				DEATH_SCREEN = deathScreen;
				RESPAWN_BUTTON = survivalHud.respawnButton();
				boolean interactive = !screenNow && !deathScreen && (auto != null || w.mouseCaptured());
				bi.setInputAllowed(interactive);
				if (auto == null) {
					int left = w.consumeClicks(0);
					int right = w.consumeClicks(1);
					double wheel = w.consumeScroll();
					if (interactive) {
						for (int i = 0; i < left; i++) {
							bi.attackClick();
						}
						for (int i = 0; i < right; i++) {
							bi.useClick();
						}
						bi.setAttackHeld(w.isButtonDown(0));
						bi.setUseHeld(w.isButtonDown(1));
						for (int k = 0; k < 9; k++) {
							if (w.wasPressed(GLFW_KEY_1 + k)) {
								bi.selectSlot(k);
							}
						}
						// Q: Gegenstand der Haupthand fallen lassen (Strg: ganzer Stapel), LocalPlayer#drop
						if (w.wasPressed(GLFW_KEY_Q)) {
							session.inventoryController().submit(new dev.mcresearch.protocolclient.session.InventoryController.DropSelected(0, w.controlDown()));
						}
						// Mausrad: nach oben = Platz links (ScrollWheelHandler#getNextScrollWheelSelection)
						int steps = (int) Math.signum(wheel) * (int) Math.min(9, Math.ceil(Math.abs(wheel)));
						if (steps != 0) {
							bi.selectSlot(Math.floorMod(bi.selectedSlot() - steps, 9));
						}
					} else {
						bi.setAttackHeld(false);
						bi.setUseHeld(false);
					}
				}
				if (w.wasPressed(GLFW_KEY_F3)) {
					debug = !debug;
				}
				if (w.wasPressed(GLFW_KEY_F2)) {
					Path shot = Path.of("build", "screenshots", "screenshot-" + System.currentTimeMillis() + ".png");
					w.screenshot(shot);
					System.out.println("[GL] screenshot " + shot);
				}
				PlayerPhysics.Input input = PlayerPhysics.Input.NONE;
				if (auto != null) {
					input = auto.input();
				} else if (w.mouseCaptured()) {
					// M11: linke Umschalttaste = Schleichen, linke Strg-Taste = Sprinten (Vanilla-Standardbelegung)
					input = new PlayerPhysics.Input(w.isKeyDown(GLFW_KEY_W), w.isKeyDown(GLFW_KEY_S), w.isKeyDown(GLFW_KEY_A), w.isKeyDown(GLFW_KEY_D),
						w.isKeyDown(GLFW_KEY_SPACE), w.isKeyDown(GLFW_KEY_LEFT_SHIFT), w.isKeyDown(GLFW_KEY_LEFT_CONTROL));
					double[] d = w.consumeMouseDelta();
					if (d[0] != 0 || d[1] != 0) {
						MovementController.RenderState rs = m.renderState();
						float yaw = rs.yaw() + (float) d[0] * MOUSE_SENSITIVITY;
						float pitch = Math.max(-90f, Math.min(90f, rs.pitch() + (float) d[1] * MOUSE_SENSITIVITY));
						m.look(yaw, pitch);
					}
				}
				// M11: bei geöffnetem Inventar/Container oder Todesbildschirm keine Bewegung (Vanilla: Tasten ohne Wirkung, KeyMapping#releaseAll)
				SCREEN_OPEN = screenNow || DEATH_SCREEN;
				if (SCREEN_OPEN) {
					input = PlayerPhysics.Input.NONE;
				}
				m.setManualInput(input);
				// ---- Welt / Renderer (Dimensionswechsel erzeugt neue Welt) ----
				WorldState world = session.world();
				if (world != rendered) {
					if (renderer != null) {
						renderer.close();
						renderer = null;
					}
					rendered = world;
					if (world != null) {
						// M12: Mesh-Kontext je Welt (Biome der Registry, Richtungsschattierung der Dimension)
						env.setDimension(world.dimension().id());
						EntityRenderer.netherLighting = env.dimension() != null && env.dimension().netherLighting();
						renderer = new ChunkRenderer(world, meshContext(models, envData, world, env));
						lastEnvTick = -1;
					}
				}
				// ---- Kamera aus dem Physikzustand ----
				MovementController.RenderState rs = m.renderState();
				double alpha = Math.max(0, Math.min(1, (now - rs.tickNanos()) / 50e6));
				double cx = rs.prevX() + (rs.x() - rs.prevX()) * alpha;
				double cy = rs.prevY() + (rs.y() - rs.prevY()) * alpha + rs.eyeHeightAt(alpha); // M11: Augenhöhe je Pose, geglättet
				double cz = rs.prevZ() + (rs.z() - rs.prevZ()) * alpha;
				// M12: ferne Ebene wie Camera#depthFar = max(Sichtweite·64, Wolkenweite·16) (Wolken bis 2048 Blöcke)
				Mat4 proj = Mat4.perspective(o.fov(), (float) w.width() / w.height(), 0.05f, Math.max(o.renderDistance() * 64f, CLOUD_RANGE * 16f));
				Mat4 vp = proj.multiply(Mat4.minecraftView(cx, cy, cz, rs.yaw(), rs.pitch()));
				int camChunkX = Math.floorDiv((int) Math.floor(cx), 16);
				int camChunkZ = Math.floorDiv((int) Math.floor(cz), 16);
				int camSectionY = Math.floorDiv((int) Math.floor(cy), 16);
				// ---- M11: Fluid an der Kamera (Camera#getFluidInCamera) ----
				CAMERA_FLUID = 0;
				int skyHere = 15;
				boolean rainsHere = true;
				if (world != null && rs.ready()) {
					int fbx = (int) Math.floor(cx);
					int fby = (int) Math.floor(cy);
					int fbz = (int) Math.floor(cz);
					dev.mcresearch.protocolclient.physics.FluidModel fm;
					synchronized (world) {
						fm = new dev.mcresearch.protocolclient.physics.FluidModel(world::stateId, dev.mcresearch.protocolclient.physics.BlockMovementFacts.get());
						dev.mcresearch.protocolclient.physics.BlockMovementFacts.Fluid cf = fm.fluid(fbx, fby, fbz);
						if (!cf.isEmpty() && cy < fby + fm.heightForCamera(fbx, fby, fbz)) {
							CAMERA_FLUID = cf.type();
						}
						int sl = world.light(true, fbx, fby, fbz);
						skyHere = sl < 0 ? 15 : sl;
					}
				}
				// ---- M12: Umgebung je Client-Tick (Uhren/Wetter vom Server), Atlas-Animation, Lightmap, Nebel ----
				dev.mcresearch.protocolclient.session.LevelEnvironment le = session.levelEnvironment();
				long envTick = le.ticks();
				if (world != null && rs.ready() && envTick != lastEnvTick) {
					long steps = lastEnvTick < 0 ? 1 : Math.min(5, envTick - lastEnvTick);
					for (long s = 0; s < steps; s++) {
						if (cpuAtlas != null && atlas != null) {
							ANIMATION_BYTES += atlas.upload(cpuAtlas.animationTick());
						}
						flicker += (flickerRandom.nextFloat() - flickerRandom.nextFloat()) * flickerRandom.nextFloat() * flickerRandom.nextFloat() * 0.1f;
						flicker *= 0.9f;
					}
					lastEnvTick = envTick;
					final WorldState fw = world;
					for (long s = 0; s < steps; s++) {
						PARTICLES.tick(session, fw, cx, cy, cz, le.rainLevel(), weatherColumns(fw, envData));
					}
					env.tick(new dev.mcresearch.protocolclient.view.env.EnvironmentState.Inputs(cx, cy, cz, fw::noiseBiome, fw.biomeNames(), le.clockTicks(),
						le.rainLevel(), le.thunderLevel()));
					var bd = envData.biome(biomeAt(fw, cx, cy, cz));
					rainsHere = bd == null || bd.hasPrecipitation();
				}
				float partial = (float) alpha;
				ENV = env;
				float nightVision = session.survival().effectAmplifier(15) >= 0 ? 1f : 0f; // night_vision (Registry-ID 15)
				lightmap.update(new dev.mcresearch.protocolclient.view.Lightmap.Params(env.get(dev.mcresearch.protocolclient.view.env.EnvAttr.SKY_LIGHT_FACTOR, partial)[0],
					1.4f + flicker, nightVision, 0f, 0f, 0.5f, env.get(dev.mcresearch.protocolclient.view.env.EnvAttr.BLOCK_LIGHT_TINT, partial),
					env.get(dev.mcresearch.protocolclient.view.env.EnvAttr.SKY_LIGHT_COLOR, partial), env.get(dev.mcresearch.protocolclient.view.env.EnvAttr.AMBIENT_LIGHT_COLOR, partial),
					env.get(dev.mcresearch.protocolclient.view.env.EnvAttr.NIGHT_VISION_COLOR, partial)));
				double yawRad = Math.toRadians(rs.yaw());
				double pitchRad = Math.toRadians(rs.pitch());
				float forwardX = (float) (-Math.sin(yawRad) * Math.cos(pitchRad));
				float deltaTicks = (now - lastFrameNanos) / 50e6f;
				lastFrameNanos = now;
				boolean fireRes = session.survival().effectAmplifier(11) >= 0; // fire_resistance
				dev.mcresearch.protocolclient.view.env.FogModel.Fog fog = fogModel.compute(env, partial, o.renderDistance(),
					CAMERA_FLUID == dev.mcresearch.protocolclient.physics.BlockMovementFacts.FLUID_WATER ? dev.mcresearch.protocolclient.view.env.FogModel.WATER
						: CAMERA_FLUID == dev.mcresearch.protocolclient.physics.BlockMovementFacts.FLUID_LAVA ? dev.mcresearch.protocolclient.view.env.FogModel.LAVA
							: dev.mcresearch.protocolclient.view.env.FogModel.NONE,
					forwardX, le.rainLevel(), le.thunderLevel(), skyHere, rainsHere, deltaTicks, m.waterVision(), fireRes, nightVision);
				FOG = fog;
				EntityRenderer.fogParams = new float[]{fog.envStart(), fog.envEnd(), fog.rdStart(), fog.rdEnd()};
				float[] fogColor = {fog.r(), fog.g(), fog.b()};
				float fogStart = fog.envStart();
				float fogEnd = Math.min(fog.envEnd(), fog.rdEnd());
				// ---- Zeichnen ----
				glClearColor(fogColor[0], fogColor[1], fogColor[2], 1f);
				glClear(GL_COLOR_BUFFER_BIT | GL_DEPTH_BUFFER_BIT);
				// ---- M12: Himmel (vor dem Terrain, ohne Tiefe) ----
				Mat4 viewRot = Mat4.minecraftView(0, 0, 0, rs.yaw(), rs.pitch());
				if (world != null && rs.ready() && env.dimension() != null) {
					boolean below = cy - 63.0 < 0.0 && CAMERA_FLUID != dev.mcresearch.protocolclient.physics.BlockMovementFacts.FLUID_WATER;
					SKY_RENDERER.draw(proj, viewRot, env, partial, env.dimension().skybox(), le.rainLevel(), fogColor, fog.skyEnd(), below);
				}
				if (renderer != null && rs.ready()) {
					renderer.frame(camChunkX, camSectionY, camChunkZ, o.renderDistance());
					shader.use();
					shader.set("uViewProj", vp);
					shader.set("uTextured", atlas != null ? 1 : 0);
					shader.set("uAtlas", 0);
					shader.set("uLightmap", LightmapGl.UNIT);
					shader.set("uFogColor", fogColor[0], fogColor[1], fogColor[2], 1f);
					shader.set("uFogEnvStart", fog.envStart());
					shader.set("uFogEnvEnd", fog.envEnd());
					shader.set("uFogRdStart", fog.rdStart());
					shader.set("uFogRdEnd", fog.rdEnd());
					lightmap.bind();
					if (atlas != null) {
						atlas.bind();
					}
					renderer.drawOpaque(shader, new Frustum(vp), cx, cy, cz, o.renderDistance(), camChunkX, camChunkZ);
				}
				// ---- Entities (M9, docs/36): Momentaufnahme mit Interpolation zwischen zwei Client-Ticks ----
				List<dev.mcresearch.protocolclient.entity.EntityManager.View> eviews = List.of();
				if (rs.ready() && session.playing()) {
					eviews = session.entities().snapshot(session.entities().partialTick(now), session.gameMode());
					entityRenderer.draw(vp, new Frustum(vp), cx, cy, cz, eviews, world, fogColor, fogStart, fogEnd, now, session.entities().variants(),
						session.levelEnvironment().gameTime());
					ENTITY_FRAME = entityRenderer.lastFrame();
					ENTITY_LOOKS = entityRenderer.looks();
					ENTITY_GPU = String.format(java.util.Locale.ROOT, "meshes=%d meshKB=%d textures=%d skins=%d", entityRenderer.meshCount(),
						entityRenderer.meshBytes() >> 10, entityRenderer.textureCount(), entityRenderer.skins().cacheSize());
				}
				// ---- M12: durchscheinendes Terrain, Wolken, Niederschlag (Reihenfolge wie LevelRenderer#executeClassicTransparency) ----
				if (renderer != null && rs.ready()) {
					shader.use();
					shader.set("uViewProj", vp);
					lightmap.bind();
					if (atlas != null) {
						atlas.bind();
					}
					renderer.drawTranslucent(shader, cx, cy, cz);
					// M12: Abbaufortschritt (eigener + block_destruction anderer) und Partikel
					if (world != null) {
						List<DestroyOverlay.Site> sites = new ArrayList<>();
						dev.mcresearch.protocolclient.session.BlockInteraction.Status ds = session.interaction().status();
						synchronized (world) {
							if (ds.destroying() && ds.progress() > 0f) {
								sites.add(new DestroyOverlay.Site(ds.destroyX(), ds.destroyY(), ds.destroyZ(), world.stateId(ds.destroyX(), ds.destroyY(), ds.destroyZ()),
									(int) (ds.progress() * 10.0f) - 1));
							}
							for (var en : session.destruction().entrySet()) {
								long k = en.getKey();
								int bx = dev.mcresearch.protocolclient.protocol.codec.Positions.blockX(k);
								int by = dev.mcresearch.protocolclient.protocol.codec.Positions.blockY(k);
								int bz = dev.mcresearch.protocolclient.protocol.codec.Positions.blockZ(k);
								sites.add(new DestroyOverlay.Site(bx, by, bz, world.stateId(bx, by, bz), en.getValue()));
							}
						}
						DESTROY.draw(vp, sites, models, cx, cy, cz);
						PARTICLES.draw(vp, rs.yaw(), rs.pitch(), cx, cy, cz, session.entities().partialTick(now), world,
							models instanceof dev.mcresearch.protocolclient.view.ResourceModels rmp ? rmp : null, atlas, fogColor,
							new float[]{fog.envStart(), fog.envEnd(), fog.rdStart(), fog.rdEnd()});
					}
					if (env.dimension() != null && !env.dimension().skybox().equals("none") && !env.dimension().skybox().equals("end")) {
						CLOUDS.draw(proj, viewRot, env.get(dev.mcresearch.protocolclient.view.env.EnvAttr.CLOUD_COLOR, partial),
							env.get(dev.mcresearch.protocolclient.view.env.EnvAttr.CLOUD_HEIGHT, partial)[0], CLOUD_RANGE, cx, cy, cz, le.gameTime(), partial,
							Math.min(CLOUD_RANGE * 16f, fog.cloudEnd()));
					}
					if (world != null && le.rainLevel() > 0f) {
						final WorldState ww = world;
						final dev.mcresearch.protocolclient.view.BlockRenderFacts facts = dev.mcresearch.protocolclient.view.BlockRenderFacts.get();
						WEATHER.draw(vp, cx, cy, cz, le.rainLevel(), le.gameTime(), partial,
							new WeatherRenderer.Columns() {
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
							}, fogColor, new float[]{fog.envStart(), fog.envEnd(), fog.rdStart(), fog.rdEnd()});
					}
				}
				// ---- Blockauswahl (M7, docs/27 §1): je Bild wie GameRenderer#pick, Augenposition = interpolierte Kamera ----
				target = null;
				dev.mcresearch.protocolclient.entity.EntityPick.Target entityTarget = null;
				if (world != null && rs.ready() && session.playing()) {
					dev.mcresearch.protocolclient.physics.Shape outline = null;
					synchronized (world) {
						target = BlockRaycast.pick(world::stateId, shapes, cx, cy, cz, rs.yaw(), rs.pitch(), session.blockInteractionRange(),
							session.entityInteractionRange());
						if (!target.miss()) {
							outline = shapes.outlineShape(world.stateId(target.blockX(), target.blockY(), target.blockZ()), target.blockX(), target.blockZ());
						}
					}
					// M9 Phase F: Entity vor Block (EntityPick, gleiche Regeln wie LocalPlayer#pick)
					synchronized (world) {
						entityTarget = dev.mcresearch.protocolclient.entity.EntityPick.pick(world::stateId, shapes, cx, cy, cz, rs.yaw(), rs.pitch(),
							session.blockInteractionRange(), session.entityInteractionRange(), eviews, -1);
					}
					ENTITY_TARGET = entityTarget;
					if (entityTarget.kind() == dev.mcresearch.protocolclient.entity.EntityPick.Kind.ENTITY) {
						dev.mcresearch.protocolclient.entity.EntityManager.View ev = entityTarget.entity();
						double hw = ev.width() / 2.0;
						if (!HIDE_GUI) hud.outlineBox(vp, ev.rx() - hw, ev.ry(), ev.rz() - hw, ev.rx() + hw, ev.ry() + ev.height(), ev.rz() + hw, cx, cy, cz);
					} else if (outline != null && !HIDE_GUI) {
						hud.outline(vp, outline, target.blockX(), target.blockY(), target.blockZ(), cx, cy, cz);
					}
				}
				if (rs.ready() && session.playing() && !HIDE_GUI) {
					nameTags.draw(vp, eviews, cx, cy, cz, w.width(), w.height());
					if (!screenNow) {
						hud.crosshair(w.width(), w.height(), Math.max(1, w.height() / 360));
					}
					gameHud.draw(hud, session, w.width(), w.height(), false);
					survivalHud.draw(hud, session, w.width(), w.height(), entityTarget != null
						&& entityTarget.kind() == dev.mcresearch.protocolclient.entity.EntityPick.Kind.ENTITY, session.entities().partialTick(now));
					if (screenNow) {
						invScreen.draw(hud, session, w.width(), w.height());
					}
				}
				// ---- Overlay / Status ----
				if (result.isDone() || session.stopRequested()) {
					ClientSession.Result r = result.getNow(null);
					banner = "Disconnected: " + (r == null ? "stopping" : r.outcome());
				} else if (!session.playing()) {
					banner = "Connecting to " + o.host() + ":" + o.port() + " …";
				} else {
					banner = "";
				}
				// Overlay-Text höchstens 4×/s neu aufbauen (Java2D + Textur-Upload kostet sonst Bildrate), Meldung sofort
				if (now - overlayBuilt >= 250_000_000L || !banner.equals(lastBanner)) {
					overlayBuilt = now;
					lastBanner = banner;
					lines.clear();
				lines.add(String.format(Locale.ROOT, "Minecraft Research – M12   %.0f FPS (%.1f ms, 1%% low %.0f)%s", fps.fps(), fps.avgMs(), fps.low1(),
					w.mouseCaptured() ? "" : "   [Klick: Maus fangen]"));
				if (debug && !screenNow) { // M8: bei offenem Fenster würde der F3-Text das Fenster verdecken
					debugLines(lines, session, m, rs, renderer, cx, cy, cz, o);
					lines.add(targetLine(session, target));
					lines.add(entityLine(session, entityRenderer.lastFrame(), entityTarget));
					dev.mcresearch.protocolclient.session.BlockInteraction.Status ist = session.interaction().status();
					String[] modes = {"Überleben", "Kreativ", "Abenteuer", "Zuschauer"};
					lines.add(String.format(Locale.ROOT, "Interaktion: %s, Abbau %s%s, Sequenz %d / Ack %d, offen %d, Hotbar %d, Inventar %s%s",
						modes[Math.max(0, Math.min(3, session.gameMode()))], ist.destroying() ? String.format(Locale.ROOT, "%.0f %%", ist.progress() * 100) : "–",
						ist.destroying() ? " bei " + ist.destroyX() + " " + ist.destroyY() + " " + ist.destroyZ() : "", ist.sequence(), ist.lastAck(),
						ist.pendingPredictions(), session.interaction().selectedSlot() + 1, session.inventory().synced() ? "synchron" : "–",
						session.inventory().unsupportedPackets() > 0 ? " (" + session.inventory().unsupportedPackets() + " nicht dekodierbar)" : ""));
				}
				if (screenNow) {
					String st = invScreen.status(session);
					lines.add("Inventar: Linksklick nehmen/ablegen, Rechts teilen, Shift schnell, Ziehen verteilen, 1–9/F tauschen, Q werfen, E/Esc schließen"
						+ (st.isEmpty() ? "" : "  |  " + st));
				}
				if (auto != null) {
					lines.add("AUTOTEST: " + auto.status());
				}
				}
				if (!HIDE_GUI) {
					overlay.draw(lines, banner, w.width(), w.height());
					HUD_INFO = "window=" + w.width() + "x" + w.height() + " lines=" + overlay.lineCount() + " wrapped=" + overlay.wrappedLines() + " widestPx=" + overlay.widestLine();
				}
				// ---- Autotest ----
				if (auto != null) {
					auto.step(now, w, session, renderer, fps, rs, invScreen);
					if (auto.finished()) {
						w.requestClose();
					}
				}
				if (o.screenshot() != null && frame == o.screenshotFrame()) {
					w.screenshot(o.screenshot());
				}
				if (o.exitAfterSeconds() > 0 && (now - start) / 1e9 >= o.exitAfterSeconds()) {
					w.requestClose();
				}
				ChunkRenderer.Stats pst = renderer == null ? null : renderer.stats();
				PROFILER.frameEnd(pst == null ? 0 : pst.drawCalls() + ENTITY_FRAME.drawCalls(), pst == null ? 0 : pst.translucentSections(),
					pst == null ? 0 : pst.translucentVertices());
				w.endFrame();
			}
		} finally {
			m.setManualInput(PlayerPhysics.Input.NONE);
			if (renderer != null) {
				renderer.close();
			}
			if (auto != null) {
				auto.close(session, fps);
			}
		}
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

	private static String entityLine(final ClientSession session, final EntityRenderer.FrameInfo f,
		final dev.mcresearch.protocolclient.entity.EntityPick.Target t) {
		dev.mcresearch.protocolclient.entity.EntityManager.Stats st = session.entities().stats();
		String target = t != null && t.kind() == dev.mcresearch.protocolclient.entity.EntityPick.Kind.ENTITY
			? String.format(Locale.ROOT, "%s #%d %s (%.2f m)", t.entity().type().shortName(), t.entity().id(),
				t.entity().playerName() != null ? t.entity().playerName() : t.entity().uuid().toString().substring(0, 8), t.distance()) : "–";
		return String.format(Locale.ROOT, "Entities %d (gezeichnet %d, verworfen %d, Debug-Box %d, %d Draws, %.2f ms) Pakete %d, unbekannt %d | Entity-Ziel %s",
			f.total(), f.drawn(), f.culled(), f.debugBoxes(), f.drawCalls(), f.cpuNanos() / 1e6, st.packetsHandled, st.unknownIdPackets, target);
	}

	/** F3-Zeile zum Auswahlziel: Block, Seite, Trefferpunkt, Zustand (docs/27 §5). */
	private static String targetLine(final ClientSession session, final BlockRaycast.Hit t) {
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

	private static long windowHandle(final Window w) {
		return glfwGetCurrentContext();
	}

	private static void debugLines(final List<String> lines, final ClientSession session, final MovementController m,
		final MovementController.RenderState rs, final ChunkRenderer renderer, final double cx, final double cy, final double cz,
		final GraphicalOptions o) {
		float yaw = ((rs.yaw() % 360) + 360) % 360;
		String facing = yaw >= 315 || yaw < 45 ? "Süden (+Z)" : yaw < 135 ? "Westen (−X)" : yaw < 225 ? "Norden (−Z)" : "Osten (+X)";
		lines.add(String.format(Locale.ROOT, "XYZ %.3f / %.3f / %.3f   Block %d %d %d   Chunk %d %d", rs.x(), rs.y(), rs.z(),
			(int) Math.floor(rs.x()), (int) Math.floor(rs.y()), (int) Math.floor(rs.z()), Math.floorDiv((int) Math.floor(rs.x()), 16),
			Math.floorDiv((int) Math.floor(rs.z()), 16)));
		lines.add(String.format(Locale.ROOT, "Blick Yaw %.1f Pitch %.1f  %s   onGround %s   Korrekturen %d", rs.yaw(), rs.pitch(), facing,
			rs.onGround(), m.corrections()));
		WorldState world = session.world();
		lines.add(String.format(Locale.ROOT, "Welt: %d Chunks geladen, Sichtweite %d   Verbindung %s %s:%d   Keep-Alive %d (max. Lücke %d ms)",
			world == null ? 0 : world.loadedChunks(), o.renderDistance(), session.playing() ? "PLAY" : session.ended() ? "GETRENNT" : session.state(), o.host(), o.port(),
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
		lines.add("WASD gehen, Leertaste springen, Umschalt schleichen, Strg sprinten, Maus schauen, Links abbauen, Rechts benutzen, 1–9/Mausrad Hotbar, E Inventar, Esc, F3, F2");
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
