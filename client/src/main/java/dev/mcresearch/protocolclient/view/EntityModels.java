package dev.mcresearch.protocolclient.view;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * Eigenständige Entity-Modellgeometrie (M9, docs/36 §2). Ein Modell besteht aus Teilen (Drehpunkt in Modellpixeln, Grunddrehung)
 * mit Quadern (Ursprung, Größe, Texturversatz, Vergrößerung, Spiegelung). Die Zahlenwerte (Quadergrößen, Texturversätze,
 * Drehpunkte) wurden aus den Texturlayouts und den Modellabmessungen der lokalen 26.3-Quellen abgelesen; Aufbau, Abwicklung und
 * Animation sind eigener Code.
 *
 * <p>Modellraum wie in den Texturen: Einheit 1/16 Block, y nach unten, Boden bei y = 24, Vorderseite bei −z. Die Umrechnung in
 * Weltkoordinaten (x und y gespiegelt, um 1,501 Blöcke angehoben, dann um 180° − Körpergier gedreht) übernimmt der Renderer.
 */
public final class EntityModels {
	/** Ein Quader: Ecke (x, y, z) und Größe (w, h, d) in Pixeln relativ zum Drehpunkt, Texturversatz (u, v). */
	public record Box(float x, float y, float z, float w, float h, float d, int u, int v, float inflate, boolean mirror) {
		static Box of(final float x, final float y, final float z, final float w, final float h, final float d, final int u, final int v) {
			return new Box(x, y, z, w, h, d, u, v, 0, false);
		}

		Box inflated(final float g) {
			return new Box(this.x, this.y, this.z, this.w, this.h, this.d, this.u, this.v, g, this.mirror);
		}

		Box mirrored() {
			return new Box(this.x, this.y, this.z, this.w, this.h, this.d, this.u, this.v, this.inflate, true);
		}
	}

	/** Ein Teil mit Drehpunkt und Grunddrehung (Bogenmaß, Reihenfolge Z·Y·X); {@code parent} = Name des Elternteils oder null. */
	public record Part(String name, String parent, float px, float py, float pz, float rx, float ry, float rz, List<Box> boxes) {
	}

	/** Ein Modell: Texturgröße und Teile in Zeichenreihenfolge (Eltern vor Kindern). */
	public record Model(String name, int texWidth, int texHeight, List<Part> parts) {
		public Part part(final String n) {
			for (Part p : this.parts) {
				if (p.name().equals(n)) {
					return p;
				}
			}
			return null;
		}
	}

	private static final float HALF_PI = (float) (Math.PI / 2);

	private EntityModels() {
	}

	private static Part part(final String name, final float px, final float py, final float pz, final Box... boxes) {
		return new Part(name, null, px, py, pz, 0, 0, 0, List.of(boxes));
	}

	private static Part body(final String name, final float px, final float py, final float pz, final Box... boxes) {
		return new Part(name, null, px, py, pz, HALF_PI, 0, 0, List.of(boxes)); // liegender Rumpf der Vierbeiner
	}

	private static List<Part> legs(final float legSize, final float sideX, final float frontZ, final float backZ, final boolean mirrorLeft,
		final boolean mirrorRight, final int u, final int v, final float g) {
		Box leg = Box.of(-2, 0, -2, 4, legSize, 4, u, v).inflated(g);
		Box left = mirrorLeft ? leg.mirrored() : leg;
		Box right = mirrorRight ? leg.mirrored() : leg;
		float y = 24 - legSize;
		return List.of(part("right_hind_leg", -sideX, y, backZ, right), part("left_hind_leg", sideX, y, backZ, left),
			part("right_front_leg", -sideX, y, frontZ, right), part("left_front_leg", sideX, y, frontZ, left));
	}

	private static Model model(final String name, final int tw, final int th, final List<Part> a, final List<Part> b) {
		List<Part> all = new ArrayList<>(a);
		all.addAll(b);
		return new Model(name, tw, th, List.copyOf(all));
	}

	public static final Model PIG = model("pig", 64, 64, List.of(
		body("body", 0, 11, 2, Box.of(-5, -10, -7, 10, 16, 8, 28, 8)),
		part("head", 0, 12, -6, Box.of(-4, -4, -8, 8, 8, 8, 0, 0), Box.of(-2, 0, -9, 4, 3, 1, 16, 16))),
		legs(6, 3, -5, 7, true, false, 0, 16, 0));

	public static final Model COW = model("cow", 64, 64, List.of(
		body("body", 0, 5, 2, Box.of(-6, -10, -7, 12, 18, 10, 18, 4), Box.of(-2, 2, -8, 4, 6, 1, 52, 0)),
		part("head", 0, 4, -8, Box.of(-4, -4, -6, 8, 8, 6, 0, 0), Box.of(-3, 1, -7, 6, 3, 1, 1, 33), Box.of(-5, -5, -5, 1, 3, 1, 22, 0),
			Box.of(4, -5, -5, 1, 3, 1, 22, 0))),
		legs(12, 4, -5, 7, true, false, 0, 16, 0));

	public static final Model SHEEP = model("sheep", 64, 32, List.of(
		body("body", 0, 5, 2, Box.of(-4, -10, -7, 8, 16, 6, 28, 8)),
		part("head", 0, 6, -8, Box.of(-3, -4, -6, 6, 6, 8, 0, 0))),
		legs(12, 3, -5, 7, false, true, 0, 16, 0));

	/** Wollschicht des Schafs (eigene Textur, mit der Wollfarbe eingefärbt). */
	public static final Model SHEEP_WOOL = model("sheep_wool", 64, 32, List.of(
		body("body", 0, 5, 2, Box.of(-4, -10, -7, 8, 16, 6, 28, 8).inflated(1.75F)),
		part("head", 0, 6, -8, Box.of(-3, -4, -4, 6, 6, 6, 0, 0).inflated(0.6F))),
		legs(6, 3, -5, 7, false, false, 0, 16, 0.5F).stream().map(p -> new Part(p.name(), null, p.px(), 12, p.pz(), 0, 0, 0, p.boxes())).toList());

	private static List<Part> humanoid(final int armWidth, final boolean thin, final boolean playerLayout, final boolean overlays) {
		List<Part> l = new ArrayList<>();
		l.add(part("head", 0, 0, 0, Box.of(-4, -8, -4, 8, 8, 8, 0, 0)));
		l.add(new Part("hat", "head", 0, 0, 0, 0, 0, 0, List.of(Box.of(-4, -8, -4, 8, 8, 8, 32, 0).inflated(0.5F))));
		List<Box> body = new ArrayList<>(List.of(Box.of(-4, 0, -2, 8, 12, 4, 16, 16)));
		if (overlays) {
			body.add(Box.of(-4, 0, -2, 8, 12, 4, 16, 32).inflated(0.25F));
		}
		l.add(new Part("body", null, 0, 0, 0, 0, 0, 0, List.copyOf(body)));
		if (thin) {
			l.add(part("right_arm", -5, 2, 0, Box.of(-1, -2, -1, 2, 12, 2, 40, 16)));
			l.add(part("left_arm", 5, 2, 0, Box.of(-1, -2, -1, 2, 12, 2, 40, 16).mirrored()));
			l.add(part("right_leg", -2, 12, 0, Box.of(-1, 0, -1, 2, 12, 2, 0, 16)));
			l.add(part("left_leg", 2, 12, 0, Box.of(-1, 0, -1, 2, 12, 2, 0, 16).mirrored()));
			return l;
		}
		float rx = armWidth == 3 ? -2 : -3;
		if (playerLayout) {
			List<Box> ra = new ArrayList<>(List.of(Box.of(rx, -2, -2, armWidth, 12, 4, 40, 16)));
			List<Box> la = new ArrayList<>(List.of(Box.of(-1, -2, -2, armWidth, 12, 4, 32, 48)));
			List<Box> rl = new ArrayList<>(List.of(Box.of(-2, 0, -2, 4, 12, 4, 0, 16)));
			List<Box> ll = new ArrayList<>(List.of(Box.of(-2, 0, -2, 4, 12, 4, 16, 48)));
			if (overlays) {
				ra.add(Box.of(rx, -2, -2, armWidth, 12, 4, 40, 32).inflated(0.25F));
				la.add(Box.of(-1, -2, -2, armWidth, 12, 4, 48, 48).inflated(0.25F));
				rl.add(Box.of(-2, 0, -2, 4, 12, 4, 0, 32).inflated(0.25F));
				ll.add(Box.of(-2, 0, -2, 4, 12, 4, 0, 48).inflated(0.25F));
			}
			l.add(new Part("right_arm", null, -5, 2, 0, 0, 0, 0, List.copyOf(ra)));
			l.add(new Part("left_arm", null, 5, 2, 0, 0, 0, 0, List.copyOf(la)));
			l.add(new Part("right_leg", null, -1.9F, 12, 0, 0, 0, 0, List.copyOf(rl)));
			l.add(new Part("left_leg", null, 1.9F, 12, 0, 0, 0, 0, List.copyOf(ll)));
		} else {
			l.add(part("right_arm", -5, 2, 0, Box.of(-3, -2, -2, 4, 12, 4, 40, 16)));
			l.add(part("left_arm", 5, 2, 0, Box.of(-1, -2, -2, 4, 12, 4, 40, 16).mirrored()));
			l.add(part("right_leg", -1.9F, 12, 0, Box.of(-2, 0, -2, 4, 12, 4, 0, 16)));
			l.add(part("left_leg", 1.9F, 12, 0, Box.of(-2, 0, -2, 4, 12, 4, 0, 16).mirrored()));
		}
		return l;
	}

	public static final Model ZOMBIE = new Model("zombie", 64, 64, List.copyOf(humanoid(4, false, false, false)));
	public static final Model SKELETON = new Model("skeleton", 64, 32, List.copyOf(humanoid(2, true, false, false)));
	public static final Model PLAYER_WIDE = new Model("player_wide", 64, 64, List.copyOf(humanoid(4, false, true, true)));
	public static final Model PLAYER_SLIM = new Model("player_slim", 64, 64, List.copyOf(humanoid(3, false, true, true)));

	/** Rüstungsschicht außen (Helm, Brust, Stiefel): Textur 64 × 32, Vergrößerung 1,0. */
	public static final Model ARMOR_OUTER = new Model("armor_outer", 64, 32, List.of(
		part("head", 0, 0, 0, Box.of(-4, -8, -4, 8, 8, 8, 0, 0).inflated(1.0F)),
		part("body", 0, 0, 0, Box.of(-4, 0, -2, 8, 12, 4, 16, 16).inflated(1.0F)),
		part("right_arm", -5, 2, 0, Box.of(-3, -2, -2, 4, 12, 4, 40, 16).inflated(1.0F)),
		part("left_arm", 5, 2, 0, Box.of(-1, -2, -2, 4, 12, 4, 40, 16).inflated(1.0F).mirrored()),
		part("right_leg", -1.9F, 12, 0, Box.of(-2, 0, -2, 4, 12, 4, 0, 16).inflated(1.0F)),
		part("left_leg", 1.9F, 12, 0, Box.of(-2, 0, -2, 4, 12, 4, 0, 16).inflated(1.0F).mirrored())));

	/** Rüstungsschicht innen (Hose): Textur 64 × 32, Vergrößerung 0,5. */
	public static final Model ARMOR_INNER = new Model("armor_inner", 64, 32, List.of(
		part("body", 0, 0, 0, Box.of(-4, 0, -2, 8, 12, 4, 16, 16).inflated(0.5F)),
		part("right_leg", -1.9F, 12, 0, Box.of(-2, 0, -2, 4, 12, 4, 0, 16).inflated(0.5F)),
		part("left_leg", 1.9F, 12, 0, Box.of(-2, 0, -2, 4, 12, 4, 0, 16).inflated(0.5F).mirrored())));

	/** Einheitswürfel 0…1 (Debug-Geometrie und Items), ganze Textur auf jeder Seite. */
	public static final Model UNIT_CUBE = new Model("unit_cube", 16, 16, List.of(part("cube", 0, 0, 0, Box.of(0, 0, 0, 16, 16, 16, 0, 0))));

	public static final Map<String, Model> ALL = Map.of("pig", PIG, "cow", COW, "sheep", SHEEP, "sheep_wool", SHEEP_WOOL, "zombie", ZOMBIE, "skeleton",
		SKELETON, "player_wide", PLAYER_WIDE, "player_slim", PLAYER_SLIM, "armor_outer", ARMOR_OUTER, "armor_inner", ARMOR_INNER);

	/**
	 * Vertexdaten eines Teils im Format von {@link MeshBuilder} (Position in Pixeln relativ zum Drehpunkt, UV 0–1, Farbe weiß).
	 * Abwicklung je Quader (eigene Umsetzung der üblichen Kastenabwicklung): oben/unten in der ersten Zeile, darunter rechte Seite,
	 * Vorderseite, linke Seite, Rückseite.
	 */
	public static int[] partMesh(final Model m, final Part p) {
		MeshBuilder b = new MeshBuilder(p.boxes().size() * 36, 1 << 16);
		for (Box box : p.boxes()) {
			box(b, m, box);
		}
		return b.toArray();
	}

	private static void box(final MeshBuilder mb, final Model m, final Box bx) {
		float g = bx.inflate();
		float x0 = bx.x() - g, y0 = bx.y() - g, z0 = bx.z() - g;
		float x1 = bx.x() + bx.w() + g, y1 = bx.y() + bx.h() + g, z1 = bx.z() + bx.d() + g;
		float u = bx.u(), v = bx.v(), w = bx.w(), h = bx.h(), d = bx.d();
		float tw = m.texWidth(), th = m.texHeight();
		int white = MeshBuilder.rgba(255, 255, 255, 255);
		int[] c = {white, white, white, white};
		boolean mir = bx.mirror();
		// Rechteck (ua, va)–(ub, vb) in Texturpixeln auf die vier Ecken (Reihenfolge wie p) legen: Ecke 0 = (links, oben)
		// Seiten: rechts (x0) | vorne (z0) | links (x1) | hinten (z1), je v von y0 (oben) bis y1 (unten)
		float sv0 = v + d, sv1 = v + d + h;
		// x0-Fläche: links in der Textur = hinten (z1), rechts = vorne (z0); bei Spiegelung tauschen beide Seitenflächen
		// Regionen der Abwicklung (links → rechts); gespiegelt: Seitenregionen tauschen, alle u-Richtungen umkehren
		float[] rightR = {u, u + d};
		float[] frontR = {u + d, u + d + w};
		float[] leftR = {u + d + w, u + d + w + d};
		float[] backR = {u + d + w + d, u + d + w + d + w};
		float[] topR = {u + d, u + d + w};
		float[] botR = {u + d + w, u + d + w + w};
		float[] rightU = mir ? rev(leftR) : rightR;
		float[] leftU = mir ? rev(rightR) : leftR;
		float[] frontU = mir ? rev(frontR) : frontR;
		float[] backU = mir ? rev(backR) : backR;
		float[] topU = mir ? rev(topR) : topR;
		float[] botU = mir ? rev(botR) : botR;
		// x0 (rechte Seite des Wesens): Ecken (z1,y0) (z0,y0) (z0,y1) (z1,y1)
		quad(mb, new float[]{x0, y0, z1, x0, y0, z0, x0, y1, z0, x0, y1, z1}, rightU, sv0, sv1, tw, th, c);
		// z0 (vorne): (x0,y0) (x1,y0) (x1,y1) (x0,y1)
		quad(mb, new float[]{x0, y0, z0, x1, y0, z0, x1, y1, z0, x0, y1, z0}, frontU, sv0, sv1, tw, th, c);
		// x1 (linke Seite): (z0,y0) (z1,y0) (z1,y1) (z0,y1)
		quad(mb, new float[]{x1, y0, z0, x1, y0, z1, x1, y1, z1, x1, y1, z0}, leftU, sv0, sv1, tw, th, c);
		// z1 (hinten): (x1,y0) (x0,y0) (x0,y1) (x1,y1)
		quad(mb, new float[]{x1, y0, z1, x0, y0, z1, x0, y1, z1, x1, y1, z1}, backU, sv0, sv1, tw, th, c);
		// y0 (oben): Textur oben = hinten (z1), unten = vorne (z0): (x0,z1) (x1,z1) (x1,z0) (x0,z0)
		quad(mb, new float[]{x0, y0, z1, x1, y0, z1, x1, y0, z0, x0, y0, z0}, topU, v, v + d, tw, th, c);
		// y1 (unten): Textur oben = vorne (z0): (x0,z0) (x1,z0) (x1,z1) (x0,z1)
		quad(mb, new float[]{x0, y1, z0, x1, y1, z0, x1, y1, z1, x0, y1, z1}, botU, v, v + d, tw, th, c);
	}

	private static float[] rev(final float[] r) {
		return new float[]{r[1], r[0]};
	}

	private static void quad(final MeshBuilder mb, final float[] p, final float[] us, final float v0, final float v1, final float tw, final float th,
		final int[] c) {
		float ua = us[0] / tw, ub = us[1] / tw, va = v0 / th, vb = v1 / th;
		mb.quad(p, new float[]{ua, va, ub, va, ub, vb, ua, vb}, c);
	}
}
