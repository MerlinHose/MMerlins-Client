package dev.mcresearch.protocolclient.view.model;

import dev.mcresearch.protocolclient.view.MeshBuilder;

/**
 * M14 (docs/55 §2): Vertexdaten eines Modellteils im Format von {@link MeshBuilder} (Position in Pixeln relativ zum Drehpunkt,
 * UV 0–1, Farbe weiß). Kastenabwicklung wie in M9 ({@code EntityModels}, docs/36 §2) – oben/unten in der ersten Zeile, darunter
 * rechte Seite, Vorderseite, linke Seite, Rückseite – erweitert um Vergrößerung je Achse, Texturskalierung und die Maske sichtbarer
 * Seiten (Bits {@link ModelLibrary#FACE_DOWN} … im Modellraum mit y nach unten: „unten“ = Fläche bei minY = sichtbar oben).
 */
public final class ModelMesher {
	private ModelMesher() {
	}

	/** Mesh eines Teils; leeres Array für Teile ohne Quader. */
	public static int[] partMesh(final ModelLibrary.Model m, final ModelLibrary.Part p) {
		if (p.cubes().isEmpty()) {
			return new int[0];
		}
		MeshBuilder b = new MeshBuilder(p.cubes().size() * 24, 1 << 16);
		for (ModelLibrary.Cube c : p.cubes()) {
			cube(b, m.texWidth() * c.texScaleU(), m.texHeight() * c.texScaleV(), c);
		}
		return b.toArray();
	}

	static void cube(final MeshBuilder mb, final float tw, final float th, final ModelLibrary.Cube bx) {
		float x0 = bx.x() - bx.growX(), y0 = bx.y() - bx.growY(), z0 = bx.z() - bx.growZ();
		float x1 = bx.x() + bx.w() + bx.growX(), y1 = bx.y() + bx.h() + bx.growY(), z1 = bx.z() + bx.d() + bx.growZ();
		float u = bx.u(), v = bx.v(), w = bx.w(), h = bx.h(), d = bx.d();
		int white = MeshBuilder.rgba(255, 255, 255, 255);
		int[] c = {white, white, white, white};
		boolean mir = bx.mirror();
		int faces = bx.faces();
		float sv0 = v + d, sv1 = v + d + h;
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
		// Gespiegelt tauschen die beiden Seitenflächen ihre Position (Vanilla vertauscht minX/maxX): Maskenbits mitnehmen
		int westBit = mir ? ModelLibrary.FACE_EAST : ModelLibrary.FACE_WEST;
		int eastBit = mir ? ModelLibrary.FACE_WEST : ModelLibrary.FACE_EAST;
		if ((faces & westBit) != 0) {
			quad(mb, new float[]{x0, y0, z1, x0, y0, z0, x0, y1, z0, x0, y1, z1}, rightU, sv0, sv1, tw, th, c);
		}
		if ((faces & ModelLibrary.FACE_NORTH) != 0) {
			quad(mb, new float[]{x0, y0, z0, x1, y0, z0, x1, y1, z0, x0, y1, z0}, frontU, sv0, sv1, tw, th, c);
		}
		if ((faces & eastBit) != 0) {
			quad(mb, new float[]{x1, y0, z0, x1, y0, z1, x1, y1, z1, x1, y1, z0}, leftU, sv0, sv1, tw, th, c);
		}
		if ((faces & ModelLibrary.FACE_SOUTH) != 0) {
			quad(mb, new float[]{x1, y0, z1, x0, y0, z1, x0, y1, z1, x1, y1, z1}, backU, sv0, sv1, tw, th, c);
		}
		if ((faces & ModelLibrary.FACE_DOWN) != 0) {
			quad(mb, new float[]{x0, y0, z1, x1, y0, z1, x1, y0, z0, x0, y0, z0}, topU, v, v + d, tw, th, c);
		}
		if ((faces & ModelLibrary.FACE_UP) != 0) {
			quad(mb, new float[]{x0, y1, z0, x1, y1, z0, x1, y1, z1, x0, y1, z1}, botU, v, v + d, tw, th, c);
		}
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
