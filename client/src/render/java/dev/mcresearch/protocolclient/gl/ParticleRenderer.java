package dev.mcresearch.protocolclient.gl;

import static org.lwjgl.opengl.GL33C.*;

import dev.mcresearch.protocolclient.protocol.packet.Clientbound;
import dev.mcresearch.protocolclient.session.ClientSession;
import dev.mcresearch.protocolclient.view.Mat4;
import dev.mcresearch.protocolclient.view.MeshBuilder;
import dev.mcresearch.protocolclient.view.ParticleSystem;
import dev.mcresearch.protocolclient.view.ResourceModels;
import dev.mcresearch.protocolclient.view.TextureAtlas;
import dev.mcresearch.protocolclient.view.TintTable;
import dev.mcresearch.protocolclient.world.BlockRegistry;
import dev.mcresearch.protocolclient.world.WorldState;
import java.io.InputStream;
import java.nio.IntBuffer;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.EnumMap;
import java.util.List;
import java.util.Map;
import java.util.Random;
import java.util.zip.ZipEntry;
import java.util.zip.ZipFile;
import org.lwjgl.system.MemoryUtil;

/**
 * Partikeldarstellung (docs/48 §7): Ereignisse aus der Sitzung ({@code level_particles}, Abbau 2001, kritische Treffer) und
 * Umgebungspartikel ({@code animateTick}-Ausschnitt: Fackel-/Feuer-Flamme und Rauch, Lagerfeuerrauch, Regenspritzer, Tropfen an
 * Decken unter Wasser) speisen das {@link ParticleSystem}; je Bild kamerazugewandte Quads in einem Stream-Puffer. Bilder aus
 * einem eigenen Partikelatlas (Beschreibungen {@code particles/*.json}), Abbaupartikel aus dem Blockatlas (Viertel-Ausschnitt des
 * Partikelbilds). Licht an der Partikelposition über die Lightmap, Flamme mit wachsender Eigenhelligkeit. Durchscheinend,
 * Tiefe geprüft, nicht geschrieben. Nur Grafikthread.
 */
final class ParticleRenderer implements AutoCloseable {

	private final ShaderProgram shader;
	private final TextureAtlas atlas = new TextureAtlas();
	private final TextureAtlasGl atlasGl;
	private final Map<ParticleSystem.Kind, List<TextureAtlas.Sprite>> sprites = new EnumMap<>(ParticleSystem.Kind.class);
	private final ParticleSystem system = new ParticleSystem();
	private final Random random = new Random();
	private final int vao;
	private final int vbo;
	private long capacity;
	private long unsupported;
	private long fromServer;
	private int drawn;

	ParticleRenderer(final Path assets) {
		this.shader = new ShaderProgram(WeatherRenderer.VERTEX, WeatherRenderer.FRAGMENT); // gleiche Licht-/Nebelregeln wie Niederschlag
		if (assets != null) {
			try (ZipFile zip = new ZipFile(assets.toFile())) {
				for (ParticleSystem.Kind k : ParticleSystem.Kind.values()) {
					if (k.spriteSet == null) {
						continue;
					}
					List<TextureAtlas.Sprite> list = new ArrayList<>();
					ZipEntry e = zip.getEntry("assets/minecraft/particles/" + k.spriteSet + ".json");
					if (e != null) {
						try (InputStream in = zip.getInputStream(e)) {
							Object json = dev.mcresearch.protocolclient.protocol.json.MiniJson.parse(new String(in.readAllBytes(), StandardCharsets.UTF_8));
							if (json instanceof Map<?, ?> m && m.get("textures") instanceof List<?> tex) {
								for (Object t : tex) {
									String name = String.valueOf(t);
									name = name.startsWith("minecraft:") ? name.substring(10) : name;
									ZipEntry png = zip.getEntry("assets/minecraft/textures/particle/" + name + ".png");
									if (png != null) {
										try (InputStream pin = zip.getInputStream(png)) {
											list.add(this.atlas.add(name, dev.mcresearch.protocolclient.view.PngImages.read(pin)));
										}
									}
								}
							}
						}
					}
					this.sprites.put(k, list);
				}
			} catch (java.io.IOException e) {
				System.err.println("[GL] particle sprites: " + e);
			}
		}
		this.atlasGl = new TextureAtlasGl(this.atlas);
		this.vao = glGenVertexArrays();
		this.vbo = glGenBuffers();
		GlResources.created("vao");
		GlResources.created("buffer");
		glBindVertexArray(this.vao);
		glBindBuffer(GL_ARRAY_BUFFER, this.vbo);
		int stride = MeshBuilder.BYTES_PER_VERTEX;
		glVertexAttribPointer(0, 3, GL_FLOAT, false, stride, 0L);
		glEnableVertexAttribArray(0);
		glVertexAttribPointer(1, 2, GL_FLOAT, false, stride, 12L);
		glEnableVertexAttribArray(1);
		glVertexAttribPointer(2, 4, GL_UNSIGNED_BYTE, true, stride, 20L);
		glEnableVertexAttribArray(2);
		glVertexAttribIPointer(3, 2, GL_UNSIGNED_SHORT, stride, 24L);
		glEnableVertexAttribArray(3);
		glBindVertexArray(0);
	}

	ParticleSystem system() {
		return this.system;
	}

	long unsupported() {
		return this.unsupported;
	}

	long fromServer() {
		return this.fromServer;
	}

	int drawn() {
		return this.drawn;
	}

	private static ParticleSystem.Kind kindOf(final String name) {
		return switch (name) {
			case "minecraft:block", "minecraft:block_crumble", "minecraft:dust_pillar" -> ParticleSystem.Kind.TERRAIN;
			case "minecraft:crit" -> ParticleSystem.Kind.CRIT;
			case "minecraft:enchanted_hit" -> ParticleSystem.Kind.ENCHANTED_HIT;
			case "minecraft:smoke" -> ParticleSystem.Kind.SMOKE;
			case "minecraft:large_smoke" -> ParticleSystem.Kind.LARGE_SMOKE;
			case "minecraft:flame" -> ParticleSystem.Kind.FLAME;
			case "minecraft:poof" -> ParticleSystem.Kind.POOF;
			case "minecraft:rain" -> ParticleSystem.Kind.RAIN;
			case "minecraft:splash" -> ParticleSystem.Kind.SPLASH;
			case "minecraft:dripping_water" -> ParticleSystem.Kind.DRIPPING_WATER;
			case "minecraft:falling_water" -> ParticleSystem.Kind.FALLING_WATER;
			case "minecraft:campfire_cosy_smoke" -> ParticleSystem.Kind.CAMPFIRE_SMOKE;
			case "minecraft:bubble" -> ParticleSystem.Kind.BUBBLE;
			default -> null;
		};
	}

	/** Ereignisse und Umgebungspartikel eines Client-Ticks, dann Simulation. */
	void tick(final ClientSession session, final WorldState world, final double camX, final double camY, final double camZ, final float rain,
		final WeatherRenderer.Columns columns) {
		if (world == null) {
			return;
		}
		for (ClientSession.VisualEvent e; (e = session.pollVisual()) != null; ) {
			switch (e) {
				case ClientSession.VisualEvent.Particles p -> serverParticles(p.packet());
				case ClientSession.VisualEvent.BlockBroken b -> {
					var shape = dev.mcresearch.protocolclient.physics.ShapeRegistry.get().outlineShape(b.state(), b.x(), b.z());
					double[] bounds = null;
					if (shape != null && !shape.isEmpty()) {
						bounds = new double[]{1, 1, 1, 0, 0, 0};
						for (var bb : shape.boxes()) {
							bounds[0] = Math.min(bounds[0], bb.minX());
							bounds[1] = Math.min(bounds[1], bb.minY());
							bounds[2] = Math.min(bounds[2], bb.minZ());
							bounds[3] = Math.max(bounds[3], bb.maxX());
							bounds[4] = Math.max(bounds[4], bb.maxY());
							bounds[5] = Math.max(bounds[5], bb.maxZ());
						}
					}
					this.system.destroy(b.x(), b.y(), b.z(), b.state(), terrainTint(b.state()), bounds);
				}
				case ClientSession.VisualEvent.Crit c -> {
					for (var v : session.entities().snapshot(1f, session.gameMode())) {
						if (v.id() == c.entityId()) {
							for (int i = 0; i < 3; i++) {
								this.system.critAt(v.x(), v.y(), v.z(), v.width(), v.height(), c.magic());
							}
						}
					}
				}
			}
		}
		ambient(world, camX, camY, camZ, rain, columns);
		this.system.tick((x, y, z) -> {
			int s = world.stateId((int) Math.floor(x), (int) Math.floor(y), (int) Math.floor(z));
			return s > 0 && dev.mcresearch.protocolclient.physics.ShapeRegistry.get().isFullBlock(s);
		});
	}

	private int terrainTint(final int state) {
		TintTable.Source s = TintTable.get().source(state, 0);
		String name = BlockRegistry.get().state(state).name();
		// colorAsTerrainParticle: Grasblock ungetönt; Biomfarben hier mit der Ebenen-Vorgabe (Partikel ohne Biommischung)
		if (name.equals("minecraft:grass_block") || s.kind() == TintTable.Kind.NONE) {
			return 0xFFFFFF;
		}
		return s.kind() == TintTable.Kind.CONSTANT ? s.rgb() : switch (s.kind()) {
			case FOLIAGE -> 0x77AB2F;
			case WATER -> 0x3F76E4;
			default -> 0x91BD59;
		};
	}

	/** {@code ClientPacketListener#handleParticleEvent}: Anzahl 0 → eine Partikel mit Geschwindigkeit = Tempo × Streuung. */
	private void serverParticles(final Clientbound.LevelParticles p) {
		ParticleSystem.Kind k = kindOf(p.typeName());
		if (k == null) {
			this.unsupported++;
			return;
		}
		this.fromServer++;
		if (p.count() == 0) {
			this.system.spawn(k, p.x(), p.y(), p.z(), p.xSpeed() * p.xDist(), p.ySpeed() * p.yDist(), p.zSpeed() * p.zDist(), p.blockState(),
				k == ParticleSystem.Kind.TERRAIN ? terrainTint(p.blockState()) : 0xFFFFFF);
			return;
		}
		for (int i = 0; i < Math.min(p.count(), 2000); i++) {
			double ox;
			double oy;
			double oz;
			double xa;
			double ya;
			double za;
			if (p.randomization() != 0) {
				ox = this.random.nextDouble() * p.xDist();
				oy = this.random.nextDouble() * p.yDist();
				oz = this.random.nextDouble() * p.zDist();
				xa = p.xSpeed();
				ya = p.ySpeed();
				za = p.zSpeed();
				if (p.randomization() == 2) {
					xa *= this.random.nextDouble();
					ya *= this.random.nextDouble();
					za *= this.random.nextDouble();
				}
			} else {
				ox = this.random.nextGaussian() * p.xDist();
				oy = this.random.nextGaussian() * p.yDist();
				oz = this.random.nextGaussian() * p.zDist();
				xa = this.random.nextGaussian() * p.xSpeed();
				ya = this.random.nextGaussian() * p.ySpeed();
				za = this.random.nextGaussian() * p.zSpeed();
			}
			this.system.spawn(k, p.x() + ox, p.y() + oy, p.z() + oz, xa, ya, za, p.blockState(),
				k == ParticleSystem.Kind.TERRAIN ? terrainTint(p.blockState()) : 0xFFFFFF);
		}
	}

	/**
	 * Umgebungspartikel ({@code ClientLevel#animateTick}: je Tick 667 Zufallsblöcke im Umkreis 16 und 667 im Umkreis 32; Ausschnitt
	 * der Blockarten) und Regenspritzer ({@code tickRainParticles}: 100·Regen² Versuche im Radius 10).
	 */
	private void ambient(final WorldState world, final double camX, final double camY, final double camZ, final float rain,
		final WeatherRenderer.Columns columns) {
		BlockRegistry reg = BlockRegistry.get();
		int cx = (int) Math.floor(camX);
		int cy = (int) Math.floor(camY);
		int cz = (int) Math.floor(camZ);
		for (int i = 0; i < 667; i++) {
			animate(world, reg, cx, cy, cz, 16);
			animate(world, reg, cx, cy, cz, 32);
		}
		if (rain > 0f && columns != null) {
			int count = (int) (100.0f * rain * rain);
			for (int i = 0; i < count; i++) {
				int x = cx + this.random.nextInt(21) - 10;
				int z = cz + this.random.nextInt(21) - 10;
				int top = columns.motionBlockingHeight(x, z);
				if (top > cy + 10 || top < cy - 10) {
					continue;
				}
				if (columns.precipitation(x, cy, z) != dev.mcresearch.protocolclient.view.env.Precipitation.Kind.RAIN) {
					continue;
				}
				this.system.spawn(ParticleSystem.Kind.RAIN, x + this.random.nextDouble(), top + 0.1, z + this.random.nextDouble(), 0, 0, 0, -1, 0xFFFFFF);
			}
		}
	}

	private void animate(final WorldState world, final BlockRegistry reg, final int cx, final int cy, final int cz, final int range) {
		int x = cx + this.random.nextInt(range) - this.random.nextInt(range);
		int y = cy + this.random.nextInt(range) - this.random.nextInt(range);
		int z = cz + this.random.nextInt(range) - this.random.nextInt(range);
		int s = world.stateId(x, y, z);
		if (s <= 0) {
			return;
		}
		String n = reg.state(s).name();
		switch (n) {
			case "minecraft:torch", "minecraft:wall_torch" -> {
				this.system.spawn(ParticleSystem.Kind.SMOKE, x + 0.5, y + 0.7, z + 0.5, 0, 0, 0, -1, 0xFFFFFF);
				this.system.spawn(ParticleSystem.Kind.FLAME, x + 0.5, y + 0.7, z + 0.5, 0, 0, 0, -1, 0xFFFFFF);
			}
			case "minecraft:fire" -> {
				if (this.random.nextInt(24) == 0) {
					this.system.spawn(ParticleSystem.Kind.LARGE_SMOKE, x + this.random.nextDouble(), y + this.random.nextDouble() * 0.5 + 0.5,
						z + this.random.nextDouble(), 0, 0, 0, -1, 0xFFFFFF);
				}
			}
			case "minecraft:campfire" -> {
				if ("true".equals(reg.state(s).properties().get("lit")) && this.random.nextFloat() < 0.11f) {
					for (int i = 0; i < this.random.nextInt(2) + 2; i++) {
						this.system.spawn(ParticleSystem.Kind.CAMPFIRE_SMOKE, x + 0.5 + this.random.nextDouble() / 3.0 * (this.random.nextBoolean() ? 1 : -1),
							y + this.random.nextDouble() + this.random.nextDouble(), z + 0.5 + this.random.nextDouble() / 3.0 * (this.random.nextBoolean() ? 1 : -1),
							0.0, 0.07, 0.0, -1, 0xFFFFFF);
					}
				}
			}
			default -> {
				// Tropfen unter wasserführenden Decken (Fluid über einem festen Block, darunter Luft)
				if (this.random.nextInt(10) == 0 && dev.mcresearch.protocolclient.physics.ShapeRegistry.get().isFullBlock(s)) {
					int above = world.stateId(x, y + 1, z);
					int below = world.stateId(x, y - 1, z);
					if (above > 0 && reg.state(above).name().equals("minecraft:water") && below >= 0 && reg.state(Math.max(0, below)).isAir()) {
						this.system.spawn(ParticleSystem.Kind.DRIPPING_WATER, x + this.random.nextDouble(), y - 0.05, z + this.random.nextDouble(), 0, 0, 0, -1,
							0xFFFFFF);
					}
				}
			}
		}
	}

	/** Zeichnet alle Partikel (nach dem durchscheinenden Terrain). */
	void draw(final Mat4 viewProj, final float yaw, final float pitch, final double camX, final double camY, final double camZ, final float partial,
		final WorldState world, final ResourceModels models, final TextureAtlasGl blockAtlas, final float[] fogColor, final float[] fog) {
		this.drawn = 0;
		if (this.system.size() == 0 || world == null) {
			return;
		}
		double yr = Math.toRadians(yaw);
		double pr = Math.toRadians(pitch);
		float rx = (float) -Math.cos(yr);
		float rz = (float) -Math.sin(yr);
		float fx = (float) (-Math.sin(yr) * Math.cos(pr));
		float fy = (float) -Math.sin(pr);
		float fz = (float) (Math.cos(yr) * Math.cos(pr));
		// oben = rechts × vorn
		float ux = 0 * fz - rz * fy;
		float uy = rz * fx - rx * fz;
		float uz = rx * fy - 0 * fx;
		MeshBuilder terrain = new MeshBuilder(256, 1 << 18);
		MeshBuilder sprite = new MeshBuilder(256, 1 << 18);
		for (ParticleSystem.Particle p : this.system.particles()) {
			double x = p.xo + (p.x - p.xo) * partial - camX;
			double y = p.yo + (p.y - p.yo) * partial - camY;
			double z = p.zo + (p.z - p.zo) * partial - camZ;
			float s = p.quadSize(partial);
			float u0;
			float v0;
			float u1;
			float v1;
			MeshBuilder target;
			if (p.kind == ParticleSystem.Kind.TERRAIN) {
				if (models == null) {
					continue;
				}
				TextureAtlas.Sprite sp = models.particleSprite(p.blockState);
				u0 = sp.u((p.uo + 1) / 4f * 16f);
				u1 = sp.u(p.uo / 4f * 16f);
				v0 = sp.v(p.vo / 4f * 16f);
				v1 = sp.v((p.vo + 1) / 4f * 16f);
				target = terrain;
			} else {
				List<TextureAtlas.Sprite> set = this.sprites.get(p.kind);
				if (set == null || set.isEmpty()) {
					continue;
				}
				int idx = p.sprite >= 0 ? p.sprite % set.size() : Math.min(set.size() - 1, p.age * (set.size() - 1) / Math.max(1, p.lifetime));
				TextureAtlas.Sprite sp = set.get(idx);
				u0 = sp.u0();
				u1 = sp.u1();
				v0 = sp.v0();
				v1 = sp.v1();
				target = sprite;
			}
			int bx = (int) Math.floor(p.x);
			int by = (int) Math.floor(p.y);
			int bz = (int) Math.floor(p.z);
			int block = world.renderLight(false, bx, by, bz) * 16;
			int sky = world.renderLight(true, bx, by, bz) * 16;
			if (p.emissive) {
				block = Math.min(240, block + (int) (Math.max(0f, Math.min(1f, (p.age + partial) / p.lifetime)) * 240f));
			}
			int light = MeshBuilder.light(block, sky);
			int c = MeshBuilder.rgba((int) (p.r * 255), (int) (p.g * 255), (int) (p.b * 255), (int) (Math.max(0f, Math.min(1f, p.alpha)) * 255));
			float[] pos = {(float) (x - rx * s - ux * s), (float) (y - uy * s), (float) (z - rz * s - uz * s), (float) (x - rx * s + ux * s),
				(float) (y + uy * s), (float) (z - rz * s + uz * s), (float) (x + rx * s + ux * s), (float) (y + uy * s), (float) (z + rz * s + uz * s),
				(float) (x + rx * s - ux * s), (float) (y - uy * s), (float) (z + rz * s - uz * s)};
			target.quad(pos, new float[]{u1, v1, u1, v0, u0, v0, u0, v1}, new int[]{c, c, c, c}, new int[]{light, light, light, light});
			this.drawn++;
		}
		int[] t = terrain.toArray();
		int[] sp = sprite.toArray();
		int tv = t.length / MeshBuilder.INTS_PER_VERTEX;
		int sv = sp.length / MeshBuilder.INTS_PER_VERTEX;
		if (tv + sv == 0) {
			return;
		}
		int[] all = new int[t.length + sp.length];
		System.arraycopy(t, 0, all, 0, t.length);
		System.arraycopy(sp, 0, all, t.length, sp.length);
		glBindBuffer(GL_ARRAY_BUFFER, this.vbo);
		IntBuffer buf = MemoryUtil.memAllocInt(all.length);
		try {
			buf.put(all).flip();
			if (all.length * 4L > this.capacity) {
				glBufferData(GL_ARRAY_BUFFER, buf, GL_STREAM_DRAW);
				this.capacity = all.length * 4L;
			} else {
				glBufferSubData(GL_ARRAY_BUFFER, 0L, buf);
			}
		} finally {
			MemoryUtil.memFree(buf);
		}
		glBindBuffer(GL_ARRAY_BUFFER, 0);
		glEnable(GL_DEPTH_TEST);
		glDepthMask(false);
		glDisable(GL_CULL_FACE);
		glEnable(GL_BLEND);
		glBlendFuncSeparate(GL_SRC_ALPHA, GL_ONE_MINUS_SRC_ALPHA, GL_ONE, GL_ONE_MINUS_SRC_ALPHA);
		this.shader.use();
		this.shader.set("uViewProj", viewProj);
		this.shader.set("uLightmap", LightmapGl.UNIT);
		this.shader.set("uTex", 0);
		this.shader.set("uFogColor", fogColor[0], fogColor[1], fogColor[2], 1f);
		this.shader.set("uFogEnvStart", fog[0]);
		this.shader.set("uFogEnvEnd", fog[1]);
		this.shader.set("uFogRdStart", fog[2]);
		this.shader.set("uFogRdEnd", fog[3]);
		glBindVertexArray(this.vao);
		if (tv > 0 && blockAtlas != null) {
			blockAtlas.bind();
			glDrawArrays(GL_TRIANGLES, 0, tv);
		}
		if (sv > 0) {
			this.atlasGl.bind();
			glDrawArrays(GL_TRIANGLES, tv, sv);
		}
		glBindVertexArray(0);
		glDisable(GL_BLEND);
		glDepthMask(true);
		glEnable(GL_CULL_FACE);
	}

	@Override
	public void close() {
		glDeleteBuffers(this.vbo);
		glDeleteVertexArrays(this.vao);
		GlResources.deleted("buffer");
		GlResources.deleted("vao");
		this.atlasGl.close();
		this.shader.close();
	}
}
