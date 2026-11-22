package dev.mcresearch.protocolclient.gl;

import dev.mcresearch.protocolclient.view.BlockModelProvider;
import dev.mcresearch.protocolclient.view.ColorModels;
import dev.mcresearch.protocolclient.world.BlockRegistry;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Locale;

/**
 * GPU- und Ressourcenbestand mit Anwendungslebensdauer (docs/59 §5): Modelle, Atlas, Shader und alle Renderer werden einmal beim
 * Start geladen und über mehrere Verbindungen hinweg verwendet. Sitzungsbezogene Daten (Chunk-Meshes, Entity-Zustände, Partikel)
 * gibt {@link GameScene#close()} frei; dieser Bestand wird erst beim Beenden geschlossen.
 */
final class ClientResources implements AutoCloseable {
	final BlockModelProvider models;
	final TextureAtlasGl atlas;
	final dev.mcresearch.protocolclient.view.env.EnvironmentData envData;
	final ShaderProgram shader;
	final Overlay overlay;
	final HudRenderer hud;
	final Hud gameHud;
	final InventoryScreen invScreen;
	final EntityRenderer entityRenderer;
	final NameTags nameTags;
	final SurvivalHud survivalHud;
	final FrameProfiler profiler;
	final LightmapGl lightmap;
	final SkyRenderer sky;
	final CloudRenderer clouds;
	final WeatherRenderer weather;
	final ParticleRenderer particles;
	final DestroyOverlay destroy;
	final UiLayer ui;

	ClientResources(final GraphicalOptions o) throws java.io.IOException {
		Path assets = o.textures() ? o.assets() : null;
		if (o.textures() && o.assets() != null && Files.isRegularFile(o.assets())) {
			long t0 = System.nanoTime();
			var res = dev.mcresearch.protocolclient.view.ResourceModels.load(o.assets(), BlockRegistry.get());
			this.atlas = new TextureAtlasGl(res.atlas());
			this.models = res;
			System.out.printf(Locale.ROOT, "[GL] resource models: %s (%.0f ms)%n", res.summary(), (System.nanoTime() - t0) / 1e6);
			long t1 = System.nanoTime();
			this.envData = dev.mcresearch.protocolclient.view.env.EnvironmentData.load(o.assets());
			System.out.printf(Locale.ROOT, "[GL] environment data: %s (%.0f ms)%n", this.envData.summary(), (System.nanoTime() - t1) / 1e6);
		} else {
			if (o.textures()) {
				System.out.println("[GL] no local Minecraft assets (--assets) – colour mode");
			}
			this.atlas = null;
			this.models = new ColorModels(BlockRegistry.get());
			this.envData = dev.mcresearch.protocolclient.view.env.EnvironmentData.empty();
		}
		this.shader = new ShaderProgram(Shaders.WORLD_VERTEX, Shaders.WORLD_FRAGMENT);
		this.overlay = new Overlay();
		this.hud = new HudRenderer();
		this.gameHud = new Hud();
		this.invScreen = new InventoryScreen();
		this.entityRenderer = new EntityRenderer(assets, o.skins());
		this.nameTags = new NameTags();
		this.survivalHud = new SurvivalHud(assets);
		this.profiler = new FrameProfiler();
		this.lightmap = new LightmapGl();
		this.sky = new SkyRenderer(assets);
		this.clouds = new CloudRenderer(assets);
		this.weather = new WeatherRenderer(assets);
		this.particles = new ParticleRenderer(assets);
		this.destroy = new DestroyOverlay(assets);
		this.ui = new UiLayer();
		GameView.PARTICLES = this.particles;
		GameView.DESTROY = this.destroy;
		GameView.PROFILER = this.profiler;
		GameView.SKY_RENDERER = this.sky;
		GameView.CLOUDS = this.clouds;
		GameView.WEATHER = this.weather;
		this.entityRenderer.setBlockAtlas(this.atlas, this.models instanceof dev.mcresearch.protocolclient.view.ResourceModels rm ? rm.atlas() : null);
	}

	@Override
	public void close() {
		// Reihenfolge wie bisher (try-with-resources: zuletzt erzeugt, zuerst geschlossen), dann Atlas
		closeQuietly(this.ui);
		closeQuietly(this.destroy);
		closeQuietly(this.particles);
		closeQuietly(this.weather);
		closeQuietly(this.clouds);
		closeQuietly(this.sky);
		closeQuietly(this.lightmap);
		closeQuietly(this.profiler);
		closeQuietly(this.survivalHud);
		closeQuietly(this.nameTags);
		closeQuietly(this.entityRenderer);
		closeQuietly(this.invScreen);
		closeQuietly(this.gameHud);
		closeQuietly(this.hud);
		closeQuietly(this.overlay);
		closeQuietly(this.shader);
		if (this.atlas != null) {
			this.atlas.close();
		}
	}

	private static void closeQuietly(final AutoCloseable c) {
		try {
			c.close();
		} catch (Exception e) {
			System.out.println("[GL] close failed: " + c.getClass().getSimpleName() + " " + e);
		}
	}
}
