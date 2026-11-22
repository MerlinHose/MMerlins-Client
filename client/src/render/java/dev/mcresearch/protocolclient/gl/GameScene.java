package dev.mcresearch.protocolclient.gl;

import static org.lwjgl.glfw.GLFW.GLFW_KEY_ESCAPE;
import static org.lwjgl.opengl.GL33C.*;

import dev.mcresearch.protocolclient.input.GameAction;
import dev.mcresearch.protocolclient.input.InputManager;
import dev.mcresearch.protocolclient.physics.BlockRaycast;
import dev.mcresearch.protocolclient.physics.PlayerPhysics;
import dev.mcresearch.protocolclient.physics.ShapeRegistry;
import dev.mcresearch.protocolclient.session.ClientSession;
import dev.mcresearch.protocolclient.session.MovementController;
import dev.mcresearch.protocolclient.view.Frustum;
import dev.mcresearch.protocolclient.view.Mat4;
import dev.mcresearch.protocolclient.world.WorldState;
import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.CompletableFuture;

/**
 * Spielwelt einer Verbindung (docs/59 §5): Kamera, Welt- und Entity-Rendering, HUD, Inventar- und Todesbildschirm, Autotest –
 * der bisherige Inhalt der GameView-Schleife, jetzt mit Sitzungslebensdauer. Eingaben kommen über das Merlin Input System
 * (Aktionen statt Tastencodes); der Autotest-Weg ist unverändert. {@link #close()} gibt alle sitzungsbezogenen Daten frei
 * (Chunk-Meshes, Entity-Zustände, Partikel), die Renderer selbst bleiben für die nächste Verbindung erhalten.
 */
final class GameScene {
	private final GraphicalOptions o;
	private final Window w;
	private final ClientResources r;
	final ClientSession session;
	final CompletableFuture<ClientSession.Result> result;
	private final Autotest auto;
	private final InputManager input;
	private final boolean direct;
	private final dev.mcresearch.protocolclient.view.env.EnvironmentState env;
	private final dev.mcresearch.protocolclient.view.env.FogModel fogModel = new dev.mcresearch.protocolclient.view.env.FogModel();
	private final dev.mcresearch.protocolclient.view.TextureAtlas cpuAtlas;
	private final ShapeRegistry shapes = ShapeRegistry.get();
	private final MovementController m;
	private final java.util.Random flickerRandom = new java.util.Random();
	private final List<String> lines = new ArrayList<>();
	private final String host;
	private final int port;
	private long lastEnvTick = -1;
	private long lastFrameNanos = System.nanoTime();
	private float flicker;
	private boolean inventoryOpen;
	private boolean screenWasOpen;
	private BlockRaycast.Hit target;
	private ChunkRenderer renderer;
	private WorldState rendered;
	private boolean captureRequested;
	private String banner = "";
	private String lastBanner = "";
	private long overlayBuilt;
	private boolean closed;
	/** Einstellungen (live): Sichtfeld, Sichtweite, Helligkeit, Wolken, Debug-Anzeige. */
	float fov;
	int renderDistance;
	float brightness = 0.5f;
	boolean clouds = true;
	boolean debug = true;
	/** Escape wurde in diesem Bild vom Inventar/Container verbraucht (kein Pausenmenü öffnen). */
	boolean escapeUsed;
	/** Spiel-Screens dieses Bildes (für den ScreenManager). */
	boolean inventoryVisible;
	boolean deathVisible;

	GameScene(final GraphicalOptions o, final Window w, final ClientResources r, final ClientSession session,
		final CompletableFuture<ClientSession.Result> result, final Autotest auto, final InputManager input, final boolean direct, final String host,
		final int port) {
		this.o = o;
		this.w = w;
		this.r = r;
		this.session = session;
		this.result = result;
		this.auto = auto;
		this.input = input;
		this.direct = direct;
		this.host = host;
		this.port = port;
		this.env = new dev.mcresearch.protocolclient.view.env.EnvironmentState(r.envData);
		this.cpuAtlas = r.models instanceof dev.mcresearch.protocolclient.view.ResourceModels rm ? rm.atlas() : null;
		this.m = session.movement();
		this.captureRequested = auto == null; // im Autotest bleibt die Maus frei
		this.fov = o.fov();
		this.renderDistance = o.renderDistance();
	}

	Autotest autotest() {
		return this.auto;
	}

	ChunkRenderer renderer() {
		return this.renderer;
	}

	/** Nach dem Schließen eines Menüs die Maus wieder fangen (wie Vanilla {@code Minecraft#setScreen(null)} → grabMouse). */
	void requestCapture() {
		this.captureRequested = this.auto == null;
	}

	/** Chunk-Meshes neu bauen (Biomübergang/weiche Beleuchtung geändert). */
	void rebuildMeshes() {
		if (this.renderer != null) {
			this.renderer.close();
			this.renderer = null;
		}
		this.rendered = null;
	}

	/**
	 * Ein Bild.
	 *
	 * @param uiBlocking ein Menü (Pause, Einstellungen) liegt über der Welt: keine Weltsteuerung, Maus gehört dem Menü
	 */
	void frame(final long now, final GameView.FrameStats fps, final boolean uiBlocking) throws IOException {
		ClientSession session = this.session;
		Window w = this.w;
		Autotest auto = this.auto;
		MovementController m = this.m;
		this.escapeUsed = false;
		// ---- Eingabe ----
		if (this.captureRequested && !uiBlocking && session.playing() && w.focused()) {
			w.setMouseCaptured(true);
			this.captureRequested = false;
		}
		// ---- Inventar- und Containerfenster (M8): Inventartaste öffnet das Spielerinventar, Inventartaste/Esc schließen; Container öffnet der Server ----
		int invReq = auto == null ? -1 : auto.consumeInventoryRequest();
		boolean serverContainer = session.inventory().openContainer() != null;
		boolean screenOpen = this.inventoryOpen || serverContainer;
		boolean escPressed = w.wasPressed(GLFW_KEY_ESCAPE);
		boolean closeKey = this.input.rawPresses(GameAction.INVENTORY) > 0 || escPressed || invReq == 0;
		List<Window.MouseEvent> mouseEvents = new ArrayList<>(uiBlocking ? List.of() : w.consumeMouseEvents());
		for (Window.MouseEvent me; (me = GameView.SYNTHETIC_MOUSE.poll()) != null;) {
			mouseEvents.add(me); // M10: Autotest-Klicks auf denselben Weg
		}
		if (uiBlocking) {
			// Menü über der Welt: Inventar bleibt, wie es ist; keine Taste wirkt auf die Welt
		} else if (!screenOpen && session.playing() && (this.input.presses(GameAction.INVENTORY) > 0 || invReq == 1)) {
			this.inventoryOpen = true;
			screenOpen = true;
			w.setMouseCaptured(false);
		} else if (screenOpen && closeKey) {
			session.closeContainer(session.inventory().currentId()); // AbstractContainerScreen#onClose → container_close
			this.inventoryOpen = false;
			screenOpen = false;
			this.captureRequested = auto == null;
			this.escapeUsed = escPressed;
		} else if (screenOpen) {
			if (!this.screenWasOpen) {
				w.setMouseCaptured(false); // vom Server geöffneter Container
			}
			if (auto == null || auto.externalInput()) {
				this.r.invScreen.input(session, w, mouseEvents, false, this.input);
			}
		}
		if (this.screenWasOpen && !screenOpen && !this.inventoryOpen) {
			this.captureRequested = auto == null; // Server hat den Container geschlossen
		}
		this.screenWasOpen = screenOpen;
		final boolean screenNow = screenOpen || uiBlocking;
		this.inventoryVisible = screenOpen;
		w.setScreenOpen(screenNow);
		// ---- Blockinteraktion und Hotbar (M7): wie Minecraft#handleKeybinds, ausgewertet im nächsten Client-Tick ----
		dev.mcresearch.protocolclient.session.BlockInteraction bi = session.interaction();
		// M10: Todesbildschirm – keine Weltsteuerung, Maus frei, Klick auf den Knopf fordert die Wiederbelebung an
		boolean deathScreen = this.r.survivalHud.deathScreenVisible();
		if (deathScreen) {
			if (w.mouseCaptured()) {
				w.setMouseCaptured(false);
			}
			for (Window.MouseEvent me : mouseEvents) {
				if (me.press() && me.button() == 0 && this.r.survivalHud.overRespawnButton(me.x(), me.y())) {
					session.requestRespawn();
					this.captureRequested = auto == null;
				}
			}
		}
		this.deathVisible = deathScreen;
		GameView.DEATH_SCREEN = deathScreen;
		GameView.RESPAWN_BUTTON = this.r.survivalHud.respawnButton();
		boolean interactive = !screenNow && !deathScreen && (auto != null || w.mouseCaptured());
		bi.setInputAllowed(interactive);
		if (auto == null) {
			if (interactive) {
				for (int i = 0; i < this.input.presses(GameAction.ATTACK); i++) {
					bi.attackClick();
				}
				for (int i = 0; i < this.input.presses(GameAction.USE); i++) {
					bi.useClick();
				}
				bi.setAttackHeld(this.input.down(GameAction.ATTACK));
				bi.setUseHeld(this.input.down(GameAction.USE));
				int slot = this.input.hotbarSlotPressed();
				if (slot >= 0) {
					bi.selectSlot(slot);
				}
				// Gegenstand der Haupthand fallen lassen (Strg: ganzer Stapel), LocalPlayer#drop
				if (this.input.presses(GameAction.DROP_ITEM) > 0) {
					session.inventoryController().submit(new dev.mcresearch.protocolclient.session.InventoryController.DropSelected(0, w.controlDown()));
				}
				// Mausrad: nach oben = Platz links (ScrollWheelHandler#getNextScrollWheelSelection)
				int steps = this.input.hotbarSteps();
				if (steps != 0) {
					bi.selectSlot(Math.floorMod(bi.selectedSlot() - steps, 9));
				}
			} else {
				bi.setAttackHeld(false);
				bi.setUseHeld(false);
			}
		}
		PlayerPhysics.Input in = PlayerPhysics.Input.NONE;
		if (auto != null) {
			in = auto.input();
		} else if (w.mouseCaptured()) {
			in = this.input.movement();
			float[] d = this.input.lookDelta();
			if (d[0] != 0 || d[1] != 0) {
				MovementController.RenderState rs = m.renderState();
				float yaw = rs.yaw() + d[0];
				float pitch = Math.max(-90f, Math.min(90f, rs.pitch() + d[1]));
				m.look(yaw, pitch);
			}
		}
		// M11: bei geöffnetem Inventar/Container, Menü oder Todesbildschirm keine Bewegung (Vanilla: Tasten ohne Wirkung, KeyMapping#releaseAll)
		GameView.SCREEN_OPEN = screenNow || GameView.DEATH_SCREEN;
		if (GameView.SCREEN_OPEN) {
			in = PlayerPhysics.Input.NONE;
		}
		m.setManualInput(in);
		render(now, fps, screenNow);
	}

	private void render(final long now, final GameView.FrameStats fps, final boolean screenNow) throws IOException {
		ClientSession session = this.session;
		Window w = this.w;
		MovementController m = this.m;
		ClientResources res = this.r;
		var envData = res.envData;
		var env = this.env;
		// ---- Welt / Renderer (Dimensionswechsel erzeugt neue Welt) ----
		WorldState world = session.world();
		if (world != this.rendered) {
			if (this.renderer != null) {
				this.renderer.close();
				this.renderer = null;
			}
			this.rendered = world;
			if (world != null) {
				// M12: Mesh-Kontext je Welt (Biome der Registry, Richtungsschattierung der Dimension)
				env.setDimension(world.dimension().id());
				EntityRenderer.netherLighting = env.dimension() != null && env.dimension().netherLighting();
				this.renderer = new ChunkRenderer(world, GameView.meshContext(res.models, envData, world, env));
				this.lastEnvTick = -1;
			}
		}
		ChunkRenderer renderer = this.renderer;
		int rd = this.renderDistance;
		// ---- Kamera aus dem Physikzustand ----
		MovementController.RenderState rs = m.renderState();
		double alpha = Math.max(0, Math.min(1, (now - rs.tickNanos()) / 50e6));
		double cx = rs.prevX() + (rs.x() - rs.prevX()) * alpha;
		double cy = rs.prevY() + (rs.y() - rs.prevY()) * alpha + rs.eyeHeightAt(alpha); // M11: Augenhöhe je Pose, geglättet
		double cz = rs.prevZ() + (rs.z() - rs.prevZ()) * alpha;
		// M12: ferne Ebene wie Camera#depthFar = max(Sichtweite·64, Wolkenweite·16) (Wolken bis 2048 Blöcke)
		Mat4 proj = Mat4.perspective(this.fov, (float) w.width() / w.height(), 0.05f, Math.max(rd * 64f, GameView.CLOUD_RANGE * 16f));
		Mat4 vp = proj.multiply(Mat4.minecraftView(cx, cy, cz, rs.yaw(), rs.pitch()));
		int camChunkX = Math.floorDiv((int) Math.floor(cx), 16);
		int camChunkZ = Math.floorDiv((int) Math.floor(cz), 16);
		int camSectionY = Math.floorDiv((int) Math.floor(cy), 16);
		// ---- M11: Fluid an der Kamera (Camera#getFluidInCamera) ----
		GameView.CAMERA_FLUID = 0;
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
					GameView.CAMERA_FLUID = cf.type();
				}
				int sl = world.light(true, fbx, fby, fbz);
				skyHere = sl < 0 ? 15 : sl;
			}
		}
		// ---- M12: Umgebung je Client-Tick (Uhren/Wetter vom Server), Atlas-Animation, Lightmap, Nebel ----
		dev.mcresearch.protocolclient.session.LevelEnvironment le = session.levelEnvironment();
		long envTick = le.ticks();
		if (world != null && rs.ready() && envTick != this.lastEnvTick) {
			long steps = this.lastEnvTick < 0 ? 1 : Math.min(5, envTick - this.lastEnvTick);
			for (long s = 0; s < steps; s++) {
				if (this.cpuAtlas != null && res.atlas != null) {
					GameView.ANIMATION_BYTES += res.atlas.upload(this.cpuAtlas.animationTick());
				}
				this.flicker += (this.flickerRandom.nextFloat() - this.flickerRandom.nextFloat()) * this.flickerRandom.nextFloat() * this.flickerRandom.nextFloat()
					* 0.1f;
				this.flicker *= 0.9f;
			}
			this.lastEnvTick = envTick;
			final WorldState fw = world;
			for (long s = 0; s < steps; s++) {
				GameView.PARTICLES.tick(session, fw, cx, cy, cz, le.rainLevel(), GameView.weatherColumns(fw, envData));
			}
			env.tick(new dev.mcresearch.protocolclient.view.env.EnvironmentState.Inputs(cx, cy, cz, fw::noiseBiome, fw.biomeNames(), le.clockTicks(),
				le.rainLevel(), le.thunderLevel()));
			var bd = envData.biome(GameView.biomeAt(fw, cx, cy, cz));
			rainsHere = bd == null || bd.hasPrecipitation();
		}
		float partial = (float) alpha;
		GameView.ENV = env;
		float nightVision = session.survival().effectAmplifier(15) >= 0 ? 1f : 0f; // night_vision (Registry-ID 15)
		res.lightmap.update(new dev.mcresearch.protocolclient.view.Lightmap.Params(env.get(dev.mcresearch.protocolclient.view.env.EnvAttr.SKY_LIGHT_FACTOR,
			partial)[0], 1.4f + this.flicker, nightVision, 0f, 0f, this.brightness, env.get(dev.mcresearch.protocolclient.view.env.EnvAttr.BLOCK_LIGHT_TINT,
			partial), env.get(dev.mcresearch.protocolclient.view.env.EnvAttr.SKY_LIGHT_COLOR, partial),
			env.get(dev.mcresearch.protocolclient.view.env.EnvAttr.AMBIENT_LIGHT_COLOR, partial),
			env.get(dev.mcresearch.protocolclient.view.env.EnvAttr.NIGHT_VISION_COLOR, partial)));
		double yawRad = Math.toRadians(rs.yaw());
		double pitchRad = Math.toRadians(rs.pitch());
		float forwardX = (float) (-Math.sin(yawRad) * Math.cos(pitchRad));
		float deltaTicks = (now - this.lastFrameNanos) / 50e6f;
		this.lastFrameNanos = now;
		boolean fireRes = session.survival().effectAmplifier(11) >= 0; // fire_resistance
		dev.mcresearch.protocolclient.view.env.FogModel.Fog fog = this.fogModel.compute(env, partial, rd,
			GameView.CAMERA_FLUID == dev.mcresearch.protocolclient.physics.BlockMovementFacts.FLUID_WATER ? dev.mcresearch.protocolclient.view.env.FogModel.WATER
				: GameView.CAMERA_FLUID == dev.mcresearch.protocolclient.physics.BlockMovementFacts.FLUID_LAVA ? dev.mcresearch.protocolclient.view.env.FogModel.LAVA
					: dev.mcresearch.protocolclient.view.env.FogModel.NONE,
			forwardX, le.rainLevel(), le.thunderLevel(), skyHere, rainsHere, deltaTicks, m.waterVision(), fireRes, nightVision);
		GameView.FOG = fog;
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
			boolean below = cy - 63.0 < 0.0 && GameView.CAMERA_FLUID != dev.mcresearch.protocolclient.physics.BlockMovementFacts.FLUID_WATER;
			GameView.SKY_RENDERER.draw(proj, viewRot, env, partial, env.dimension().skybox(), le.rainLevel(), fogColor, fog.skyEnd(), below);
		}
		ShaderProgram shader = res.shader;
		TextureAtlasGl atlas = res.atlas;
		if (renderer != null && rs.ready()) {
			renderer.frame(camChunkX, camSectionY, camChunkZ, rd);
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
			res.lightmap.bind();
			if (atlas != null) {
				atlas.bind();
			}
			renderer.drawOpaque(shader, new Frustum(vp), cx, cy, cz, rd, camChunkX, camChunkZ);
		}
		// ---- Entities (M9, docs/36): Momentaufnahme mit Interpolation zwischen zwei Client-Ticks ----
		EntityRenderer entityRenderer = res.entityRenderer;
		List<dev.mcresearch.protocolclient.entity.EntityManager.View> eviews = List.of();
		if (rs.ready() && session.playing()) {
			eviews = session.entities().snapshot(session.entities().partialTick(now), session.gameMode());
			entityRenderer.setCameraYaw(rs.yaw());
			entityRenderer.draw(vp, new Frustum(vp), cx, cy, cz, eviews, world, fogColor, fogStart, fogEnd, now, session.entities().variants(),
				session.levelEnvironment().gameTime());
			GameView.ENTITY_FRAME = entityRenderer.lastFrame();
			GameView.ENTITY_LOOKS = entityRenderer.looks();
			GameView.ENTITY_GPU = String.format(java.util.Locale.ROOT, "meshes=%d meshKB=%d textures=%d skins=%d", entityRenderer.meshCount(),
				entityRenderer.meshBytes() >> 10, entityRenderer.textureCount(), entityRenderer.skins().cacheSize());
		}
		// ---- M12: durchscheinendes Terrain, Wolken, Niederschlag (Reihenfolge wie LevelRenderer#executeClassicTransparency) ----
		if (renderer != null && rs.ready()) {
			shader.use();
			shader.set("uViewProj", vp);
			res.lightmap.bind();
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
				GameView.DESTROY.draw(vp, sites, res.models, cx, cy, cz);
				GameView.PARTICLES.draw(vp, rs.yaw(), rs.pitch(), cx, cy, cz, session.entities().partialTick(now), world,
					res.models instanceof dev.mcresearch.protocolclient.view.ResourceModels rmp ? rmp : null, atlas, fogColor,
					new float[]{fog.envStart(), fog.envEnd(), fog.rdStart(), fog.rdEnd()});
			}
			if (this.clouds && env.dimension() != null && !env.dimension().skybox().equals("none") && !env.dimension().skybox().equals("end")) {
				GameView.CLOUDS.draw(proj, viewRot, env.get(dev.mcresearch.protocolclient.view.env.EnvAttr.CLOUD_COLOR, partial),
					env.get(dev.mcresearch.protocolclient.view.env.EnvAttr.CLOUD_HEIGHT, partial)[0], GameView.CLOUD_RANGE, cx, cy, cz, le.gameTime(), partial,
					Math.min(GameView.CLOUD_RANGE * 16f, fog.cloudEnd()));
			}
			if (world != null && le.rainLevel() > 0f) {
				GameView.WEATHER.draw(vp, cx, cy, cz, le.rainLevel(), le.gameTime(), partial, GameView.weatherColumns(world, envData), fogColor,
					new float[]{fog.envStart(), fog.envEnd(), fog.rdStart(), fog.rdEnd()});
			}
		}
		// ---- Blockauswahl (M7, docs/27 §1): je Bild wie GameRenderer#pick, Augenposition = interpolierte Kamera ----
		this.target = null;
		dev.mcresearch.protocolclient.entity.EntityPick.Target entityTarget = null;
		HudRenderer hud = res.hud;
		if (world != null && rs.ready() && session.playing()) {
			dev.mcresearch.protocolclient.physics.Shape outline = null;
			synchronized (world) {
				this.target = BlockRaycast.pick(world::stateId, this.shapes, cx, cy, cz, rs.yaw(), rs.pitch(), session.blockInteractionRange(),
					session.entityInteractionRange());
				if (!this.target.miss()) {
					outline = this.shapes.outlineShape(world.stateId(this.target.blockX(), this.target.blockY(), this.target.blockZ()), this.target.blockX(),
						this.target.blockZ());
				}
			}
			// M9 Phase F: Entity vor Block (EntityPick, gleiche Regeln wie LocalPlayer#pick)
			synchronized (world) {
				entityTarget = dev.mcresearch.protocolclient.entity.EntityPick.pick(world::stateId, this.shapes, cx, cy, cz, rs.yaw(), rs.pitch(),
					session.blockInteractionRange(), session.entityInteractionRange(), eviews, -1);
			}
			GameView.ENTITY_TARGET = entityTarget;
			if (entityTarget.kind() == dev.mcresearch.protocolclient.entity.EntityPick.Kind.ENTITY) {
				dev.mcresearch.protocolclient.entity.EntityManager.View ev = entityTarget.entity();
				double hw = ev.width() / 2.0;
				if (!GameView.HIDE_GUI) {
					hud.outlineBox(vp, ev.rx() - hw, ev.ry(), ev.rz() - hw, ev.rx() + hw, ev.ry() + ev.height(), ev.rz() + hw, cx, cy, cz);
				}
			} else if (outline != null && !GameView.HIDE_GUI) {
				hud.outline(vp, outline, this.target.blockX(), this.target.blockY(), this.target.blockZ(), cx, cy, cz);
			}
		}
		boolean inventoryShown = this.inventoryVisible;
		if (rs.ready() && session.playing() && !GameView.HIDE_GUI) {
			res.nameTags.draw(vp, eviews, cx, cy, cz, w.width(), w.height());
			if (!screenNow) {
				hud.crosshair(w.width(), w.height(), Math.max(1, w.height() / 360));
			}
			res.gameHud.draw(hud, session, w.width(), w.height(), false);
			res.survivalHud.draw(hud, session, w.width(), w.height(), entityTarget != null
				&& entityTarget.kind() == dev.mcresearch.protocolclient.entity.EntityPick.Kind.ENTITY, session.entities().partialTick(now));
			if (inventoryShown) {
				res.invScreen.draw(hud, session, w.width(), w.height());
			}
		}
		// ---- Overlay / Status ----
		if (this.result.isDone() || session.stopRequested()) {
			ClientSession.Result rr = this.result.getNow(null);
			this.banner = "Disconnected: " + (rr == null ? "stopping" : rr.outcome());
		} else if (!session.playing()) {
			this.banner = "Connecting to " + this.host + ":" + this.port + " …";
		} else {
			this.banner = "";
		}
		// Overlay-Text höchstens 4×/s neu aufbauen (Java2D + Textur-Upload kostet sonst Bildrate), Meldung sofort
		if (now - this.overlayBuilt >= 250_000_000L || !this.banner.equals(this.lastBanner)) {
			this.overlayBuilt = now;
			this.lastBanner = this.banner;
			List<String> lines = this.lines;
			lines.clear();
			if (this.direct || this.debug) {
				lines.add(String.format(Locale.ROOT, "Minecraft Research – M12   %.0f FPS (%.1f ms, 1%% low %.0f)%s", fps.fps(), fps.avgMs(), fps.low1(),
					w.mouseCaptured() ? "" : "   [Klick: Maus fangen]"));
			}
			if (this.debug && !inventoryShown) { // M8: bei offenem Fenster würde der F3-Text das Fenster verdecken
				GameView.debugLines(lines, session, m, rs, renderer, cx, cy, cz, rd, this.host, this.port, this.input);
				lines.add(GameView.targetLine(session, this.target));
				lines.add(GameView.entityLine(session, entityRenderer.lastFrame(), entityTarget));
				dev.mcresearch.protocolclient.session.BlockInteraction.Status ist = session.interaction().status();
				String[] modes = {"Überleben", "Kreativ", "Abenteuer", "Zuschauer"};
				lines.add(String.format(Locale.ROOT, "Interaktion: %s, Abbau %s%s, Sequenz %d / Ack %d, offen %d, Hotbar %d, Inventar %s%s",
					modes[Math.max(0, Math.min(3, session.gameMode()))], ist.destroying() ? String.format(Locale.ROOT, "%.0f %%", ist.progress() * 100) : "–",
					ist.destroying() ? " bei " + ist.destroyX() + " " + ist.destroyY() + " " + ist.destroyZ() : "", ist.sequence(), ist.lastAck(),
					ist.pendingPredictions(), session.interaction().selectedSlot() + 1, session.inventory().synced() ? "synchron" : "–",
					session.inventory().unsupportedPackets() > 0 ? " (" + session.inventory().unsupportedPackets() + " nicht dekodierbar)" : ""));
			}
			if (inventoryShown) {
				String st = res.invScreen.status(session);
				lines.add("Inventar: Linksklick nehmen/ablegen, Rechts teilen, Shift schnell, Ziehen verteilen, 1–9/F tauschen, Q werfen, E/Esc schließen"
					+ (st.isEmpty() ? "" : "  |  " + st));
			}
			if (this.auto != null) {
				lines.add("AUTOTEST: " + this.auto.status());
			}
		}
		if (!GameView.HIDE_GUI) {
			res.overlay.draw(this.lines, this.banner, w.width(), w.height());
			GameView.HUD_INFO = "window=" + w.width() + "x" + w.height() + " lines=" + res.overlay.lineCount() + " wrapped=" + res.overlay.wrappedLines()
				+ " widestPx=" + res.overlay.widestLine();
		}
		// ---- Autotest ----
		if (this.auto != null) {
			this.auto.step(now, w, session, renderer, fps, rs, res.invScreen);
			if (this.auto.finished()) {
				w.requestClose();
			}
		}
		ChunkRenderer.Stats pst = renderer == null ? null : renderer.stats();
		GameView.PROFILER.frameEnd(pst == null ? 0 : pst.drawCalls() + GameView.ENTITY_FRAME.drawCalls(), pst == null ? 0 : pst.translucentSections(),
			pst == null ? 0 : pst.translucentVertices());
	}

	/**
	 * Sitzungsdaten freigeben (vor dem Trennen der Sitzung aufrufen, wie bisher im {@code finally} der Schleife): Eingabe zurücksetzen,
	 * Chunk-Meshes löschen, Autotest-Abschlusszeile schreiben, Entity- und Partikelzustand leeren.
	 */
	void dispose(final GameView.FrameStats fps) {
		if (this.closed) {
			return;
		}
		this.closed = true;
		this.m.setManualInput(PlayerPhysics.Input.NONE);
		if (this.renderer != null) {
			this.renderer.close();
			this.renderer = null;
		}
		this.rendered = null;
		if (this.auto != null) {
			this.auto.close(this.session, fps);
		}
		this.r.entityRenderer.resetSession();
		GameView.PARTICLES.system().clear();
		GameView.SCREEN_OPEN = false;
		GameView.DEATH_SCREEN = false;
		GameView.ENTITY_TARGET = null;
		this.w.setScreenOpen(false);
	}

	boolean disposed() {
		return this.closed;
	}
}
