package dev.mcresearch.protocolclient.view;

import java.awt.image.BufferedImage;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Textur-Atlas im Hauptspeicher (docs/25 §8, M12: docs/48 §4). Jede Textur behält ihre Originalgröße (Regalpackung in einem
 * {@value #SIZE}²-Atlas); animierte Texturen ({@code .png.mcmeta}) liegen mit ihrem ersten Bild im Atlas, alle Einzelbilder
 * zusätzlich im Speicher. {@link #animationTick()} rechnet je Client-Tick die geänderten Bilder (inklusive Überblendung bei
 * {@code interpolate}) – der Grafikteil lädt nur diese Rechtecke hoch, Meshes bleiben unverändert.
 *
 * <p>Transparenzklasse je Fläche wie in 26.3 ({@code SpriteContents#computeTransparency}): über den UV-Ausschnitt der Fläche
 * und alle verwendeten Einzelbilder; Alpha 0 → ausgestanzt, Alpha 1…254 → durchscheinend. Kachel {@code #debug} =
 * magenta/schwarz für unbekannte Modelle. Nur Pixeldaten, kein OpenGL.
 */
public final class TextureAtlas {
	public static final int SIZE = 2048;
	public static final String DEBUG = "#debug";

	/** Transparenzklasse (Vanilla: {@code Transparency} → {@code ChunkSectionLayer#byTransparency}). */
	public enum Transparency {
		OPAQUE, CUTOUT, TRANSLUCENT;

		Transparency or(final Transparency o) {
			return this.ordinal() >= o.ordinal() ? this : o;
		}
	}

	/** Animationsbeschreibung: Bildfolge (Index, Dauer in Ticks), Überblendung. */
	public record Animation(int[] frameIndex, int[] frameTime, boolean interpolate) {
	}

	/**
	 * Lage einer Textur im Atlas (normierte UV, Pixelrechteck), Transparenz des ganzen Bildes, Mittelwertfarbe (RGBA) und – bei
	 * Animation – alle Einzelbilder ({@code frames[i]} ARGB, Zeilen von oben).
	 */
	public static final class Sprite {
		private final String name;
		private final int x;
		private final int y;
		private final int width;
		private final int height;
		private final float u0;
		private final float v0;
		private final float u1;
		private final float v1;
		private final int averageColor;
		final int[][] frames;
		final Animation animation;
		// Animationszustand (wie SpriteContents.AnimationState): Position in der Bildfolge, Teilschritt
		int frame;
		int subFrame;

		Sprite(final String name, final int x, final int y, final int width, final int height, final int averageColor, final int[][] frames,
			final Animation animation) {
			this.name = name;
			this.x = x;
			this.y = y;
			this.width = width;
			this.height = height;
			float inv = 1f / SIZE;
			float e = 0.01f * inv; // kleiner Abstand gegen Ausbluten benachbarter Kacheln (nächste Abtastung, keine Mipmaps)
			this.u0 = x * inv + e;
			this.v0 = y * inv + e;
			this.u1 = (x + width) * inv - e;
			this.v1 = (y + height) * inv - e;
			this.averageColor = averageColor;
			this.frames = frames;
			this.animation = animation;
		}

		public String name() {
			return this.name;
		}

		/** u im Atlas zu Pixel {@code px} (0…16 Modellkoordinaten). */
		public float u(final float px) {
			return this.u0 + (this.u1 - this.u0) * px / 16f;
		}

		public float v(final float px) {
			return this.v0 + (this.v1 - this.v0) * px / 16f;
		}

		public float u0() {
			return this.u0;
		}

		public float v0() {
			return this.v0;
		}

		public float u1() {
			return this.u1;
		}

		public float v1() {
			return this.v1;
		}

		public int x() {
			return this.x;
		}

		public int y() {
			return this.y;
		}

		public int width() {
			return this.width;
		}

		public int height() {
			return this.height;
		}

		public int averageColor() {
			return this.averageColor;
		}

		public boolean animated() {
			return this.animation != null;
		}

		public Animation animation() {
			return this.animation;
		}

		public int frameCount() {
			return this.frames.length;
		}

		/** Aktuelles Einzelbild (Index in {@code frames}) – für Tests und Bericht. */
		public int currentFrameIndex() {
			return this.animation == null ? 0 : this.animation.frameIndex()[this.frame];
		}

		/** Transparenz über das ganze Bild (alle verwendeten Einzelbilder). */
		public Transparency transparency() {
			return transparency(0, 0, 16, 16);
		}

		/** Transparenz im UV-Ausschnitt (Modellpixel 0…16) über alle verwendeten Einzelbilder. */
		public Transparency transparency(final float pu0, final float pv0, final float pu1, final float pv1) {
			int x0 = (int) Math.floor(Math.min(pu0, pu1) / 16f * this.width);
			int y0 = (int) Math.floor(Math.min(pv0, pv1) / 16f * this.height);
			int x1 = (int) Math.ceil(Math.max(pu0, pu1) / 16f * this.width);
			int y1 = (int) Math.ceil(Math.max(pv0, pv1) / 16f * this.height);
			x0 = Math.max(0, Math.min(this.width, x0));
			x1 = Math.max(0, Math.min(this.width, x1));
			y0 = Math.max(0, Math.min(this.height, y0));
			y1 = Math.max(0, Math.min(this.height, y1));
			boolean transparent = false;
			boolean translucent = false;
			List<Integer> used = new ArrayList<>();
			if (this.animation == null) {
				used.add(0);
			} else {
				for (int i : this.animation.frameIndex()) {
					if (!used.contains(i)) {
						used.add(i);
					}
				}
			}
			for (int f : used) {
				int[] px = this.frames[f];
				for (int yy = y0; yy < y1; yy++) {
					for (int xx = x0; xx < x1; xx++) {
						int a = px[yy * this.width + xx] >>> 24;
						if (a == 0) {
							transparent = true;
						} else if (a != 255) {
							translucent = true;
						}
					}
				}
			}
			return translucent ? Transparency.TRANSLUCENT : transparent ? Transparency.CUTOUT : Transparency.OPAQUE;
		}
	}

	/** Ein geändertes Atlasrechteck nach einem Animationstick (ARGB, Zeilen von oben). */
	public record Update(int x, int y, int width, int height, int[] argb) {
	}

	private final int[] argb = new int[SIZE * SIZE];
	private final Map<String, Sprite> sprites = new LinkedHashMap<>();
	private final List<Sprite> animated = new ArrayList<>();
	// Regalpackung
	private int shelfX;
	private int shelfY;
	private int shelfHeight;

	public TextureAtlas() {
		BufferedImage debug = new BufferedImage(16, 16, BufferedImage.TYPE_INT_ARGB);
		for (int y = 0; y < 16; y++) {
			for (int x = 0; x < 16; x++) {
				debug.setRGB(x, y, ((x / 8 + y / 8) & 1) == 0 ? 0xFFF800F8 : 0xFF000000);
			}
		}
		add(DEBUG, debug, null);
	}

	/** Kompatibilität (M6): Größenangabe wird ignoriert, der Atlas ist fest {@value #SIZE}². */
	public TextureAtlas(final int ignoredMaxTextures) {
		this();
	}

	public Sprite get(final String name) {
		return this.sprites.get(name);
	}

	public Sprite debug() {
		return this.sprites.get(DEBUG);
	}

	public boolean contains(final String name) {
		return this.sprites.containsKey(name);
	}

	/** Ohne Animation (wie bisher). */
	public Sprite add(final String name, final BufferedImage image) {
		return add(name, image, null);
	}

	/**
	 * Fügt eine Textur hinzu. {@code animation} ≠ null: Bildstreifen mit quadratischen Einzelbildern (Breite = Kantenlänge,
	 * {@code .png.mcmeta} ohne width/height), Bildfolge laut Metadaten; leere Folge = alle Bilder in Reihenfolge.
	 */
	public Sprite add(final String name, final BufferedImage image, final AnimationMeta animation) {
		Sprite existing = this.sprites.get(name);
		if (existing != null) {
			return existing;
		}
		int fw = image.getWidth();
		int fh = animation != null ? (animation.frameHeight() > 0 ? animation.frameHeight() : Math.min(image.getWidth(), image.getHeight()))
			: image.getHeight();
		if (animation != null && animation.frameWidth() > 0) {
			fw = animation.frameWidth();
		}
		int cols = Math.max(1, image.getWidth() / fw);
		int rows = Math.max(1, image.getHeight() / fh);
		int count = animation == null ? 1 : cols * rows;
		int[][] frames = new int[count][];
		for (int f = 0; f < count; f++) {
			int fx = (f % cols) * fw;
			int fy = (f / cols) * fh;
			frames[f] = image.getRGB(fx, fy, fw, fh, null, 0, fw);
		}
		Animation anim = null;
		if (animation != null) {
			anim = animation.resolve(count);
		}
		int[] pos = place(fw, fh);
		long r = 0;
		long g = 0;
		long b = 0;
		long a = 0;
		int visible = 0;
		int[] first = frames[anim == null ? 0 : anim.frameIndex()[0]];
		for (int i = 0; i < first.length; i++) {
			int c = first[i];
			int alpha = c >>> 24;
			if (alpha > 0) {
				r += c >> 16 & 255;
				g += c >> 8 & 255;
				b += c & 255;
				visible++;
			}
			a += alpha;
		}
		blit(pos[0], pos[1], fw, fh, first);
		int avg = visible == 0 ? 0 : MeshBuilder.rgba((int) (r / visible), (int) (g / visible), (int) (b / visible), (int) (a / first.length));
		Sprite s = new Sprite(name, pos[0], pos[1], fw, fh, avg, frames, anim != null && anim.frameIndex().length > 1 ? anim : null);
		this.sprites.put(name, s);
		if (s.animated()) {
			this.animated.add(s);
		}
		return s;
	}

	private int[] place(final int w, final int h) {
		if (this.shelfX + w > SIZE) {
			this.shelfX = 0;
			this.shelfY += this.shelfHeight;
			this.shelfHeight = 0;
		}
		if (this.shelfY + h > SIZE || w > SIZE) {
			throw new IllegalStateException("texture atlas full (" + this.sprites.size() + " textures)");
		}
		int[] p = {this.shelfX, this.shelfY};
		this.shelfX += w;
		this.shelfHeight = Math.max(this.shelfHeight, h);
		return p;
	}

	private void blit(final int x, final int y, final int w, final int h, final int[] px) {
		for (int yy = 0; yy < h; yy++) {
			System.arraycopy(px, yy * w, this.argb, (y + yy) * SIZE + x, w);
		}
	}

	/**
	 * Ein Client-Tick aller Animationen (Ablauf wie {@code SpriteContents.AnimationState#tick} + {@code drawToAtlas}):
	 * Teilschritt +1; erreicht er die Bilddauer, nächstes Bild der Folge. Geliefert werden Rechtecke, deren Inhalt sich geändert
	 * hat – bei Überblendung jeden Tick {@code mix(aktuell, nächstes, Teilschritt / Dauer)} (Fortschritt auf 1/1000 gerundet).
	 */
	public List<Update> animationTick() {
		List<Update> out = new ArrayList<>();
		for (Sprite s : this.animated) {
			Animation an = s.animation;
			s.subFrame++;
			boolean dirty = false;
			int old = an.frameIndex()[s.frame];
			if (s.subFrame >= an.frameTime()[s.frame]) {
				s.frame = (s.frame + 1) % an.frameIndex().length;
				s.subFrame = 0;
				dirty = an.frameIndex()[s.frame] != old;
			}
			if (an.interpolate()) {
				int cur = an.frameIndex()[s.frame];
				int next = an.frameIndex()[(s.frame + 1) % an.frameIndex().length];
				float progress = (int) ((float) s.subFrame / an.frameTime()[s.frame] * 1000.0F) / 1000.0F;
				int[] mixed = mix(s.frames[cur], s.frames[next], progress);
				blit(s.x(), s.y(), s.width(), s.height(), mixed);
				out.add(new Update(s.x(), s.y(), s.width(), s.height(), mixed));
			} else if (dirty) {
				int[] px = s.frames[an.frameIndex()[s.frame]];
				blit(s.x(), s.y(), s.width(), s.height(), px);
				out.add(new Update(s.x(), s.y(), s.width(), s.height(), px));
			}
		}
		return out;
	}

	/** Komponentenweise Überblendung zweier ARGB-Bilder (wie {@code mix} im Interpolationsshader, 8 Bit gerundet). */
	static int[] mix(final int[] a, final int[] b, final float t) {
		int[] out = new int[a.length];
		for (int i = 0; i < a.length; i++) {
			int ca = a[i];
			int cb = b[i];
			int r = 0;
			for (int sh = 0; sh < 32; sh += 8) {
				float va = (ca >>> sh & 255) / 255f;
				float vb = (cb >>> sh & 255) / 255f;
				int v = Math.round((va + (vb - va) * t) * 255f);
				r |= (v & 255) << sh;
			}
			out[i] = r;
		}
		return out;
	}

	public int size() {
		return SIZE;
	}

	public int spriteCount() {
		return this.sprites.size();
	}

	public List<Sprite> animatedSprites() {
		return List.copyOf(this.animated);
	}

	/** ARGB-Pixel (Zeilen von oben), Kantenlänge {@link #size()}. */
	public int[] pixels() {
		return this.argb.clone();
	}

	/** Animationsmetadaten aus {@code .png.mcmeta}: Standard-Bilddauer, optionale Bildfolge, Überblendung, Bildgröße. */
	public record AnimationMeta(int defaultFrameTime, int[] frames, int[] times, boolean interpolate, int frameWidth, int frameHeight) {
		/** Bildfolge wie {@code SpriteContents#createAnimatedTexture}: ungültige Einträge (Dauer ≤ 0, Index außerhalb) entfallen. */
		Animation resolve(final int frameCount) {
			List<int[]> seq = new ArrayList<>();
			if (this.frames == null || this.frames.length == 0) {
				for (int i = 0; i < frameCount; i++) {
					seq.add(new int[]{i, this.defaultFrameTime});
				}
			} else {
				for (int i = 0; i < this.frames.length; i++) {
					int t = this.times[i] >= 0 ? this.times[i] : this.defaultFrameTime; // timeOr(Standarddauer)
					if (t > 0 && this.frames[i] >= 0 && this.frames[i] < frameCount) {
						seq.add(new int[]{this.frames[i], t});
					}
				}
			}
			int[] idx = new int[seq.size()];
			int[] tm = new int[seq.size()];
			for (int i = 0; i < idx.length; i++) {
				idx[i] = seq.get(i)[0];
				tm[i] = seq.get(i)[1];
			}
			return new Animation(idx, tm, this.interpolate);
		}

		/** Liest den Abschnitt {@code animation} (Map aus MiniJson); {@code null}, wenn keiner vorhanden ist. */
		@SuppressWarnings("unchecked")
		public static AnimationMeta parse(final Object mcmeta) {
			if (!(mcmeta instanceof Map<?, ?> root) || !(root.get("animation") instanceof Map<?, ?> an)) {
				return null;
			}
			int frametime = an.get("frametime") instanceof Number n ? n.intValue() : 1;
			boolean interpolate = Boolean.TRUE.equals(an.get("interpolate"));
			int w = an.get("width") instanceof Number n ? n.intValue() : -1;
			int h = an.get("height") instanceof Number n ? n.intValue() : -1;
			int[] frames = null;
			int[] times = null;
			if (an.get("frames") instanceof List<?> list) {
				frames = new int[list.size()];
				times = new int[list.size()];
				for (int i = 0; i < list.size(); i++) {
					Object f = list.get(i);
					if (f instanceof Number n) {
						frames[i] = n.intValue();
						times[i] = -1; // Standarddauer
					} else if (f instanceof Map<?, ?> fm) {
						frames[i] = ((Number) fm.get("index")).intValue();
						times[i] = fm.get("time") instanceof Number t ? t.intValue() : -1;
					}
				}
			}
			return new AnimationMeta(frametime, frames, times, interpolate, w, h);
		}
	}
}
