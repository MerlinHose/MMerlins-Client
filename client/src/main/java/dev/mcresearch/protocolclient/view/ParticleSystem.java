package dev.mcresearch.protocolclient.view;

import java.util.ArrayList;
import java.util.Iterator;
import java.util.List;
import java.util.Random;

/**
 * Partikel (docs/48 §7): Simulation je Client-Tick nach dem Muster von 26.3 ({@code Particle#tick}: Schwerkraft 0,04·g,
 * Bewegung mit einfacher Blockkollision, Reibung, Bodenreibung 0,7), begrenzt auf {@value #MAX} Partikel (älteste fallen weg).
 * Arten und Parameter je Vorlage (Lebensdauer, Schwerkraft, Reibung, Größe, Farbe, Bildfolge nach Alter). Reine Daten,
 * kein OpenGL; der Grafikteil baut je Bild kamerazugewandte Quads.
 */
public final class ParticleSystem {
	public static final int MAX = 4000;

	/** Unterstützte Arten (Bildfolge: Name der Partikelbeschreibung in {@code assets/minecraft/particles}). */
	public enum Kind {
		TERRAIN(null), CRIT("crit"), ENCHANTED_HIT("enchanted_hit"), SMOKE("smoke"), LARGE_SMOKE("large_smoke"), FLAME("flame"), POOF("poof"),
		RAIN("rain"), SPLASH("splash"), DRIPPING_WATER("dripping_water"), FALLING_WATER("falling_water"), CAMPFIRE_SMOKE("campfire_cosy_smoke"),
		BUBBLE("bubble");

		public final String spriteSet;

		Kind(final String spriteSet) {
			this.spriteSet = spriteSet;
		}
	}

	/** Ein Partikel; öffentlich lesbar für den Renderer. */
	public static final class Particle {
		public final Kind kind;
		public double x;
		public double y;
		public double z;
		public double xo;
		public double yo;
		public double zo;
		double xd;
		double yd;
		double zd;
		public int age;
		public int lifetime;
		float gravity;
		float friction = 0.98f;
		boolean physics = true;
		boolean onGround;
		public float size;
		public float r = 1f;
		public float g = 1f;
		public float b = 1f;
		public float alpha = 1f;
		/** Terrain: Blockzustand und Ausschnitt (uo, vo ∈ 0…3 Viertel-Raster wie TerrainParticle) */
		public int blockState = -1;
		public float uo;
		public float vo;
		/** feste Bildnummer (−1: nach Alter) */
		public int sprite = -1;
		/** Blocklicht-Aufhellung nach Alter (Flamme: addSmoothBlockEmission) */
		public boolean emissive;

		Particle(final Kind kind, final double x, final double y, final double z) {
			this.kind = kind;
			this.x = this.xo = x;
			this.y = this.yo = y;
			this.z = this.zo = z;
		}

		/** Größe zum Zeitpunkt {@code a} (Teiltick) – je Art wie {@code getQuadSize}. */
		public float quadSize(final float a) {
			float t = (this.age + a) / Math.max(1, this.lifetime);
			return switch (this.kind) {
				case CRIT, ENCHANTED_HIT -> this.size * Math.max(0f, Math.min(1f, t * 32f));
				case FLAME -> this.size * (1f - t * t * 0.5f);
				case SMOKE, LARGE_SMOKE, POOF -> this.size * Math.max(0f, Math.min(1f, t * 32f));
				default -> this.size;
			};
		}
	}

	/** Blockabfrage für die Kollision (true = fest an der Position). */
	@FunctionalInterface
	public interface Solid {
		boolean blocked(double x, double y, double z);
	}

	private final List<Particle> particles = new ArrayList<>();
	private final Random random = new Random();
	private long spawned;
	private long dropped;

	public List<Particle> particles() {
		return this.particles;
	}

	public long spawned() {
		return this.spawned;
	}

	public long dropped() {
		return this.dropped;
	}

	private Particle add(final Particle p) {
		if (this.particles.size() >= MAX) {
			this.particles.removeFirst();
			this.dropped++;
		}
		this.particles.add(p);
		this.spawned++;
		return p;
	}

	private float rnd() {
		return this.random.nextFloat();
	}

	/** Grundbewegung von {@code Particle(level, x, y, z, xa, ya, za)}: zufällige Richtung, Geschwindigkeit ~0,06. */
	private void baseMotion(final Particle p, final double xa, final double ya, final double za) {
		p.xd = xa + (rnd() * 2f - 1f) * 0.4f;
		p.yd = ya + (rnd() * 2f - 1f) * 0.4f;
		p.zd = za + (rnd() * 2f - 1f) * 0.4f;
		double speed = (rnd() + rnd() + 1f) * 0.15f;
		double dd = Math.sqrt(p.xd * p.xd + p.yd * p.yd + p.zd * p.zd);
		p.xd = p.xd / dd * speed * 0.4f;
		p.yd = p.yd / dd * speed * 0.4f + 0.1f;
		p.zd = p.zd / dd * speed * 0.4f;
	}

	private void baseDefaults(final Particle p) {
		p.lifetime = (int) (4.0f / (rnd() * 0.9f + 0.1f));
		p.size = 0.1f * (rnd() * 0.5f + 0.5f) * 2.0f;
	}

	/** Eine Partikel der Art {@code kind} mit Geschwindigkeit (xa, ya, za) wie die Spielvorlage. */
	public Particle spawn(final Kind kind, final double x, final double y, final double z, final double xa, final double ya, final double za,
		final int blockState, final int tint) {
		Particle p = new Particle(kind, x, y, z);
		baseDefaults(p);
		switch (kind) {
			case TERRAIN -> {
				baseMotion(p, xa, ya, za);
				p.gravity = 1.0f;
				p.r = p.g = p.b = 0.6f;
				p.r *= (tint >> 16 & 255) / 255f;
				p.g *= (tint >> 8 & 255) / 255f;
				p.b *= (tint & 255) / 255f;
				p.size /= 2.0f;
				p.uo = rnd() * 3f;
				p.vo = rnd() * 3f;
				p.blockState = blockState;
			}
			case CRIT, ENCHANTED_HIT -> {
				baseMotion(p, 0, 0, 0);
				p.friction = 0.7f;
				p.gravity = 0.5f;
				p.xd = p.xd * 0.1f + xa * 0.4;
				p.yd = p.yd * 0.1f + ya * 0.4;
				p.zd = p.zd * 0.1f + za * 0.4;
				float c = rnd() * 0.3f + 0.6f;
				p.r = p.g = p.b = c;
				if (kind == Kind.ENCHANTED_HIT) {
					p.r *= 0.3f;
					p.g *= 0.8f;
				}
				p.size *= 0.75f;
				p.lifetime = Math.max((int) (6.0 / (rnd() * 0.8 + 0.6)), 1);
				p.physics = false;
				p.sprite = this.random.nextInt(1 << 20);
			}
			case SMOKE, LARGE_SMOKE -> {
				float scale = kind == Kind.LARGE_SMOKE ? 2.5f : 1.0f;
				p.friction = 0.96f;
				p.gravity = -0.1f;
				p.xd = xa + p.xd * 0.1f;
				p.yd = ya + p.yd * 0.1f;
				p.zd = za + p.zd * 0.1f;
				float c = rnd() * 0.3f;
				p.r = p.g = p.b = c;
				p.size *= 0.75f * scale;
				p.lifetime = Math.max(1, (int) (8 / (rnd() * 0.8f + 0.2f) * scale));
			}
			case FLAME -> {
				p.xd = xa;
				p.yd = ya;
				p.zd = za;
				p.lifetime = (int) (8.0 / (rnd() * 0.8 + 0.2)) + 4;
				p.friction = 0.96f;
				p.emissive = true;
				p.sprite = this.random.nextInt(1 << 20);
			}
			case POOF -> {
				p.gravity = -0.1f;
				p.friction = 0.9f;
				p.xd = xa + (rnd() * 2f - 1f) * 0.05f;
				p.yd = ya + (rnd() * 2f - 1f) * 0.05f;
				p.zd = za + (rnd() * 2f - 1f) * 0.05f;
				float c = rnd() * 0.3f + 0.7f;
				p.r = p.g = p.b = c;
				p.size = 0.1f * (rnd() * rnd() * 6.0f + 1.0f);
				p.lifetime = (int) (16.0 / (rnd() * 0.8 + 0.2)) + 2;
			}
			case RAIN, SPLASH -> {
				baseMotion(p, 0, 0, 0);
				p.xd *= 0.3f;
				p.yd = rnd() * 0.2f + 0.1f;
				p.zd *= 0.3f;
				p.gravity = 0.06f;
				p.lifetime = (int) (8.0 / (rnd() * 0.8 + 0.2));
				p.sprite = this.random.nextInt(1 << 20);
				if (kind == Kind.SPLASH && (xa != 0 || za != 0)) {
					p.xd = xa;
					p.yd = 0.1;
					p.zd = za;
				}
			}
			case DRIPPING_WATER -> {
				p.xd = p.yd = p.zd = 0;
				p.size *= 0.5f;
				p.gravity = 0.0006f;
				p.lifetime = 40;
				p.r = 0.2f;
				p.g = 0.3f;
				p.b = 1.0f;
				p.sprite = 0;
			}
			case FALLING_WATER -> {
				p.xd = p.yd = p.zd = 0;
				p.size *= 0.5f;
				p.gravity = 0.06f;
				p.lifetime = (int) (64.0 / (rnd() * 0.8 + 0.2));
				p.r = 0.2f;
				p.g = 0.3f;
				p.b = 1.0f;
				p.sprite = 0;
			}
			case CAMPFIRE_SMOKE -> {
				p.size *= 3.0f;
				p.lifetime = this.random.nextInt(50) + 280;
				p.gravity = 3.0e-6f;
				p.xd = xa;
				p.yd = ya + rnd() / 500.0f;
				p.zd = za;
				p.sprite = this.random.nextInt(1 << 20);
				p.alpha = 0.95f;
			}
			case BUBBLE -> {
				p.r = p.g = p.b = 1f;
				p.size *= rnd() * 0.6f + 0.2f;
				p.xd = xa * 0.2f + (rnd() * 2f - 1f) * 0.02f;
				p.yd = ya * 0.2f + (rnd() * 2f - 1f) * 0.02f;
				p.zd = za * 0.2f + (rnd() * 2f - 1f) * 0.02f;
				p.lifetime = (int) (8.0 / (rnd() * 0.8 + 0.2));
				p.sprite = 0;
			}
		}
		return add(p);
	}

	/**
	 * Abbaupartikel eines Blocks ({@code ParticleEngine#destroy}): 4 × 4 × 4 Gitter, Geschwindigkeit aus der Lage im Block,
	 * {@code TerrainParticle} mit dem Partikelbild des Blocks.
	 */
	public void destroy(final int x, final int y, final int z, final int blockState, final int tint, final double[] box) {
		double x0 = box == null ? 0 : box[0];
		double y0 = box == null ? 0 : box[1];
		double z0 = box == null ? 0 : box[2];
		double x1 = box == null ? 1 : box[3];
		double y1 = box == null ? 1 : box[4];
		double z1 = box == null ? 1 : box[5];
		double wx = Math.min(1.0, x1 - x0);
		double wy = Math.min(1.0, y1 - y0);
		double wz = Math.min(1.0, z1 - z0);
		int cx = Math.max(2, (int) Math.ceil(wx / 0.25));
		int cy = Math.max(2, (int) Math.ceil(wy / 0.25));
		int cz = Math.max(2, (int) Math.ceil(wz / 0.25));
		for (int xx = 0; xx < cx; xx++) {
			for (int yy = 0; yy < cy; yy++) {
				for (int zz = 0; zz < cz; zz++) {
					double rx = (xx + 0.5) / cx;
					double ry = (yy + 0.5) / cy;
					double rz = (zz + 0.5) / cz;
					double px = rx * wx + x0;
					double py = ry * wy + y0;
					double pz = rz * wz + z0;
					spawn(Kind.TERRAIN, x + px, y + py, z + pz, rx - 0.5, ry - 0.5, rz - 0.5, blockState, tint);
				}
			}
		}
	}

	/** Kritischer Treffer ({@code TrackingEmitter}): 16 Partikel je Tick über 3 Ticks am Körper. */
	public void critAt(final double x, final double y, final double z, final double width, final double height, final boolean magic) {
		for (int i = 0; i < 16; i++) {
			double xa = rnd() * 2f - 1f;
			double ya = rnd() * 2f - 1f;
			double za = rnd() * 2f - 1f;
			if (xa * xa + ya * ya + za * za <= 1.0) {
				spawn(magic ? Kind.ENCHANTED_HIT : Kind.CRIT, x + width / 4 * xa, y + height / 2 + height / 4 * ya, z + width / 4 * za, xa, ya + 0.2, za, -1,
					0xFFFFFF);
			}
		}
	}

	/** Ein Tick aller Partikel. */
	public void tick(final Solid solid) {
		List<double[]> drops = new ArrayList<>();
		for (Iterator<Particle> it = this.particles.iterator(); it.hasNext(); ) {
			Particle p = it.next();
			p.xo = p.x;
			p.yo = p.y;
			p.zo = p.z;
			if (p.age++ >= p.lifetime) {
				it.remove();
				continue;
			}
			if (p.kind == Kind.CAMPFIRE_SMOKE) {
				p.xd += rnd() / 5000.0f * (this.random.nextBoolean() ? 1 : -1);
				p.zd += rnd() / 5000.0f * (this.random.nextBoolean() ? 1 : -1);
				if (p.age >= p.lifetime - 60 && p.alpha > 0.01f) {
					p.alpha -= 0.015f;
				}
			}
			p.yd -= 0.04 * p.gravity;
			move(p, solid);
			if ((p.kind == Kind.SMOKE || p.kind == Kind.LARGE_SMOKE) && p.y == p.yo) {
				p.xd *= 1.1;
				p.zd *= 1.1;
			}
			p.xd *= p.friction;
			p.yd *= p.friction;
			p.zd *= p.friction;
			if (p.onGround) {
				p.xd *= 0.7f;
				p.zd *= 0.7f;
				if (p.kind == Kind.RAIN || p.kind == Kind.SPLASH || p.kind == Kind.FALLING_WATER) {
					if (rnd() < 0.5f || p.kind == Kind.FALLING_WATER) {
						it.remove();
						continue;
					}
				}
			}
			if (p.kind == Kind.DRIPPING_WATER && p.age >= p.lifetime - 1) {
				drops.add(new double[]{p.x, p.y, p.z, p.size}); // erst nach der Iteration einfügen
			}
		}
		for (double[] d : drops) {
			Particle f = spawn(Kind.FALLING_WATER, d[0], d[1], d[2], 0, 0, 0, -1, 0xFFFFFF);
			f.size = (float) d[3];
		}
	}

	/** Bewegung mit einfacher Blockkollision (Punkt statt Partikelbox); Abwärtsblockade setzt {@code onGround}. */
	private static void move(final Particle p, final Solid solid) {
		if (!p.physics || solid == null) {
			p.x += p.xd;
			p.y += p.yd;
			p.z += p.zd;
			return;
		}
		double ny = p.y + p.yd;
		p.onGround = false;
		if (solid.blocked(p.x, ny, p.z)) {
			if (p.yd < 0) {
				p.onGround = true;
				ny = Math.floor(p.y) + 1e-3 > ny ? Math.floor(p.y) + 1e-3 : ny;
			}
			p.yd = 0;
			ny = p.y;
		}
		p.y = ny;
		double nx = p.x + p.xd;
		if (solid.blocked(nx, p.y, p.z)) {
			p.xd = 0;
		} else {
			p.x = nx;
		}
		double nz = p.z + p.zd;
		if (solid.blocked(p.x, p.y, nz)) {
			p.zd = 0;
		} else {
			p.z = nz;
		}
	}

	public int size() {
		return this.particles.size();
	}

	public void clear() {
		this.particles.clear();
	}
}
