package dev.mcresearch.protocolclient.view.skin;

import dev.mcresearch.protocolclient.view.PngImages;
import dev.mcresearch.protocolclient.view.model.EntityLooks;
import java.awt.image.BufferedImage;
import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Base64;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;
import java.util.regex.Pattern;
import java.util.zip.ZipEntry;
import java.util.zip.ZipFile;

/**
 * M14 Phase E (docs/56): eigene Skin-Verwaltung ohne Netzwerk.
 * <ul>
 * <li><b>Quellen:</b> lokal bereitgestellte PNG-Skins ({@code --skins <ordner>}: {@code <uuid>.png} oder {@code <name>.png}, schmales
 * Modell mit Markierungsdatei {@code <uuid|name>.slim}) und die neun Vanilla-Standardskins aus dem lokalen JAR (Auswahl wie
 * {@code DefaultPlayerSkin#get}: {@code floorMod(uuid.hashCode(), 18)}, erst 9 schmal, dann 9 breit).</li>
 * <li><b>Sicherheit:</b> Dateigröße ≤ {@value #MAX_FILE_BYTES} Byte, PNG-Signatur und Bildmaße aus dem IHDR-Kopf werden
 * <em>vor</em> dem Dekodieren geprüft; erlaubt sind nur 64×64 und das alte 64×32-Format (wird wie
 * {@code SkinTextureDownloader#processLegacySkin} auf 64×64 umgerechnet). Ungültige Daten → Standardskin.</li>
 * <li><b>Cache:</b> dekodierte Bilder je Schlüssel, höchstens {@value #MAX_CACHE} Einträge (LRU).</li>
 * <li><b>Online-Skins:</b> {@link #profileTextures} wertet die {@code textures}-Eigenschaft eines Profils (Base64-JSON: URL, Modell)
 * aus – vorbereitet für den letzten Authentifizierungsmeilenstein. Es wird <b>nichts heruntergeladen</b>; ohne lokale Kopie gilt der
 * Standardskin (nur das Modell schmal/breit wird übernommen).</li>
 * </ul>
 * Ohne LWJGL; die Textur lädt der Renderer über {@link #image}.
 */
public final class SkinManager implements EntityLooks.SkinLookup {
	public static final int MAX_FILE_BYTES = 64 * 1024;
	public static final int MAX_CACHE = 128;
	private static final String[] DEFAULT_NAMES = {"alex", "ari", "efe", "kai", "makena", "noor", "steve", "sunny", "zuri"};
	private static final Pattern NAME = Pattern.compile("[A-Za-z0-9_]{1,16}");
	private static final byte[] PNG_SIGNATURE = {(byte) 0x89, 'P', 'N', 'G', '\r', '\n', 0x1A, '\n'};

	/** Dekodierter Skin: immer 64×64 ARGB. */
	public record SkinImage(int[] argb, String source) {
		public int width() {
			return 64;
		}

		public int height() {
			return 64;
		}
	}

	/** Ergebnis der Auswertung einer {@code textures}-Profileigenschaft. */
	public record ProfileTextures(String skinUrl, boolean slim) {
	}

	private final Path assetsJar;
	private final Path skinDir;
	private final Map<String, SkinImage> cache = new LinkedHashMap<>(16, 0.75F, true) {
		@Override
		protected boolean removeEldestEntry(final Map.Entry<String, SkinImage> e) {
			return size() > MAX_CACHE;
		}
	};
	/** Modell aus einer Profileigenschaft (UUID → schmal), falls bekannt. */
	private final Map<UUID, Boolean> profileModels = new java.util.concurrent.ConcurrentHashMap<>();
	private long decoded;
	private long rejected;

	public SkinManager(final Path assetsJar, final Path skinDir) {
		this.assetsJar = assetsJar;
		this.skinDir = skinDir;
	}

	/** Standardskin wie {@code DefaultPlayerSkin#get(UUID)}: Index 0–8 schmal, 9–17 breit. */
	public static Skin defaultSkin(final UUID uuid) {
		int i = Math.floorMod(uuid.hashCode(), DEFAULT_NAMES.length * 2);
		boolean slim = i < DEFAULT_NAMES.length;
		String name = DEFAULT_NAMES[i % DEFAULT_NAMES.length];
		return new Skin("skin:default/" + (slim ? "slim/" : "wide/") + name, slim);
	}

	@Override
	public synchronized Skin skin(final UUID uuid, final String name) {
		if (this.skinDir != null) {
			for (String key : new String[]{uuid == null ? null : uuid.toString(), name}) {
				if (key == null || (!key.equals(String.valueOf(uuid)) && !NAME.matcher(key).matches())) {
					continue;
				}
				Path png = this.skinDir.resolve(key + ".png");
				if (Files.isRegularFile(png) && image("skin:local/" + key) != null) {
					return new Skin("skin:local/" + key, Files.exists(this.skinDir.resolve(key + ".slim")));
				}
			}
		}
		if (uuid == null) {
			return defaultSkin(new UUID(0, 0));
		}
		Skin d = defaultSkin(uuid);
		Boolean slim = this.profileModels.get(uuid);
		if (slim != null && slim != d.slim()) {
			// Modell aus dem Profil, Bild bleibt der Standardskin (keine Downloads); passender Standardskin des anderen Modells
			String n = d.texture().substring(d.texture().lastIndexOf('/') + 1);
			return new Skin("skin:default/" + (slim ? "slim/" : "wide/") + n, slim);
		}
		return d;
	}

	/**
	 * M14: Mannequin ({@code ClientMannequin#getSkin}): Standardskin des Profils (UUID, sonst Offline-UUID des Namens, sonst Null-UUID
	 * wie {@code Mannequin.DEFAULT_PROFILE}), danach die Skin-Vorgabe des Profils: Textur (Bezeichner im lokalen JAR, z. B.
	 * {@code minecraft:entity/player/wide/steve}) und Modell. Keine Profilauflösung über das Netzwerk.
	 */
	@Override
	public synchronized Skin mannequin(final dev.mcresearch.protocolclient.protocol.packet.GameProfile.Resolvable p) {
		Skin base;
		if (p == null || (p.id() == null && p.name() == null)) {
			base = defaultSkin(new UUID(0, 0));
		} else if (p.id() != null) {
			base = skin(p.id(), p.name());
		} else {
			base = skin(dev.mcresearch.protocolclient.protocol.packet.GameProfile.offlineUuid(p.name()), p.name());
		}
		String texture = base.texture();
		boolean slim = base.slim();
		if (p != null && p.texture() != null) {
			String t = p.texture().startsWith("minecraft:") ? p.texture().substring(10) : p.texture();
			if (t.matches("[a-z0-9_/]+")) {
				texture = t; // Textur des lokalen JARs (Renderer lädt textures/<pfad>.png)
			}
		}
		if (p != null && p.slim() != null) {
			slim = p.slim();
		}
		return new Skin(texture, slim);
	}

	/** Bild zu einem Schlüssel ({@code skin:default/…}, {@code skin:local/…}) oder null. */
	public synchronized SkinImage image(final String key) {
		SkinImage cached = this.cache.get(key);
		if (cached != null) {
			return cached;
		}
		byte[] bytes = read(key);
		SkinImage img = bytes == null ? null : decode(bytes, key);
		if (img == null) {
			this.rejected++;
			return null;
		}
		this.decoded++;
		this.cache.put(key, img);
		return img;
	}

	public synchronized int cacheSize() {
		return this.cache.size();
	}

	public synchronized long decodedCount() {
		return this.decoded;
	}

	public synchronized long rejectedCount() {
		return this.rejected;
	}

	private byte[] read(final String key) {
		try {
			if (key.startsWith("skin:local/") && this.skinDir != null) {
				String k = key.substring("skin:local/".length());
				Path p = this.skinDir.resolve(k + ".png").normalize();
				if (!p.startsWith(this.skinDir.normalize()) || !Files.isRegularFile(p) || Files.size(p) > MAX_FILE_BYTES) {
					return null;
				}
				return Files.readAllBytes(p);
			}
			if (key.startsWith("skin:default/") && this.assetsJar != null) {
				String rest = key.substring("skin:default/".length());
				if (!rest.matches("(slim|wide)/[a-z]+")) {
					return null;
				}
				try (ZipFile zip = new ZipFile(this.assetsJar.toFile())) {
					ZipEntry e = zip.getEntry("assets/minecraft/textures/entity/player/" + rest + ".png");
					if (e == null || e.getSize() > MAX_FILE_BYTES) {
						return null;
					}
					try (InputStream in = zip.getInputStream(e)) {
						return in.readNBytes(MAX_FILE_BYTES + 1);
					}
				}
			}
		} catch (IOException e) {
			return null;
		}
		return null;
	}

	/**
	 * Prüft und dekodiert ein Skin-PNG. Reihenfolge: Größe, Signatur, IHDR-Maße (nur 64×64 oder 64×32), erst dann Dekodierung;
	 * danach Legacy-Umrechnung und Alpha-Regeln wie Vanilla. null bei jedem Fehler.
	 */
	public static SkinImage decode(final byte[] png, final String source) {
		if (png == null || png.length < 33 || png.length > MAX_FILE_BYTES) {
			return null;
		}
		for (int i = 0; i < PNG_SIGNATURE.length; i++) {
			if (png[i] != PNG_SIGNATURE[i]) {
				return null;
			}
		}
		// IHDR muss der erste Block sein: Länge 13, Typ "IHDR", dann Breite/Höhe (big endian)
		if (!new String(png, 12, 4, StandardCharsets.ISO_8859_1).equals("IHDR")) {
			return null;
		}
		int w = readInt(png, 16);
		int h = readInt(png, 20);
		if (w != 64 || (h != 64 && h != 32)) {
			return null;
		}
		BufferedImage img;
		try {
			img = PngImages.read(new ByteArrayInputStream(png));
		} catch (IOException | RuntimeException e) {
			return null;
		}
		if (img == null || img.getWidth() != w || img.getHeight() != h) {
			return null;
		}
		int[] argb = new int[64 * 64];
		for (int y = 0; y < h; y++) {
			for (int x = 0; x < 64; x++) {
				argb[y * 64 + x] = img.getRGB(x, y);
			}
		}
		boolean legacy = h == 32;
		if (legacy) {
			legacyToModern(argb);
		}
		setNoAlpha(argb, 0, 0, 32, 16);
		if (legacy) {
			notchTransparencyHack(argb, 32, 0, 64, 32);
		}
		setNoAlpha(argb, 0, 16, 64, 32);
		setNoAlpha(argb, 16, 48, 48, 64);
		return new SkinImage(argb, source);
	}

	private static int readInt(final byte[] b, final int o) {
		return (b[o] & 0xFF) << 24 | (b[o + 1] & 0xFF) << 16 | (b[o + 2] & 0xFF) << 8 | b[o + 3] & 0xFF;
	}

	/** Altes 64×32-Format: rechtes Bein/Arm gespiegelt in die Felder des linken Beins/Arms kopieren. */
	static void legacyToModern(final int[] a) {
		copyRect(a, 4, 16, 16, 32, 4, 4);
		copyRect(a, 8, 16, 16, 32, 4, 4);
		copyRect(a, 0, 20, 24, 32, 4, 12);
		copyRect(a, 4, 20, 16, 32, 4, 12);
		copyRect(a, 8, 20, 8, 32, 4, 12);
		copyRect(a, 12, 20, 16, 32, 4, 12);
		copyRect(a, 44, 16, -8, 32, 4, 4);
		copyRect(a, 48, 16, -8, 32, 4, 4);
		copyRect(a, 40, 20, 0, 32, 4, 12);
		copyRect(a, 44, 20, -8, 32, 4, 12);
		copyRect(a, 48, 20, -16, 32, 4, 12);
		copyRect(a, 52, 20, -8, 32, 4, 12);
	}

	/** Rechteck (x, y, w, h) um (dx, dy) verschoben und waagerecht gespiegelt kopieren. */
	private static void copyRect(final int[] a, final int x, final int y, final int dx, final int dy, final int w, final int h) {
		for (int j = 0; j < h; j++) {
			for (int i = 0; i < w; i++) {
				int src = a[(y + j) * 64 + x + i];
				a[(y + dy + j) * 64 + x + dx + w - 1 - i] = src;
			}
		}
	}

	private static void setNoAlpha(final int[] a, final int x0, final int y0, final int x1, final int y1) {
		for (int y = y0; y < y1; y++) {
			for (int x = x0; x < x1; x++) {
				a[y * 64 + x] |= 0xFF000000;
			}
		}
	}

	/** Alte Skins ohne Transparenz im Hutbereich: Hut ganz durchsichtig machen (sonst schwarzer Kasten). */
	private static void notchTransparencyHack(final int[] a, final int x0, final int y0, final int x1, final int y1) {
		for (int y = y0; y < y1; y++) {
			for (int x = x0; x < x1; x++) {
				if ((a[y * 64 + x] >>> 24) < 128) {
					return;
				}
			}
		}
		for (int y = y0; y < y1; y++) {
			for (int x = x0; x < x1; x++) {
				a[y * 64 + x] &= 0x00FFFFFF;
			}
		}
	}

	/**
	 * Vorbereitung (letzter Meilenstein): {@code textures}-Eigenschaft (Base64-JSON) auswerten. Liefert URL und Modell, lädt
	 * <b>nichts</b> herunter; merkt sich nur das Modell für {@link #skin}. null bei ungültigen Daten.
	 */
	public ProfileTextures profileTextures(final UUID uuid, final String base64) {
		if (base64 == null || base64.length() > 16 * 1024) {
			return null;
		}
		try {
			String json = new String(Base64.getDecoder().decode(base64), StandardCharsets.UTF_8);
			Object root = dev.mcresearch.protocolclient.protocol.json.MiniJson.parse(json);
			if (!(root instanceof Map<?, ?> m) || !(m.get("textures") instanceof Map<?, ?> t) || !(t.get("SKIN") instanceof Map<?, ?> skin)) {
				return null;
			}
			String url = skin.get("url") instanceof String s ? s : null;
			boolean slim = skin.get("metadata") instanceof Map<?, ?> md && "slim".equals(md.get("model"));
			if (uuid != null) {
				this.profileModels.put(uuid, slim);
			}
			return new ProfileTextures(url, slim);
		} catch (RuntimeException | dev.mcresearch.protocolclient.protocol.ProtocolException e) {
			return null;
		}
	}
}
