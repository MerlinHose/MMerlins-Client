package dev.mcresearch.protocolclient.gl;

import dev.mcresearch.protocolclient.entity.EntityManager;
import dev.mcresearch.protocolclient.view.Mat4;
import java.awt.Color;
import java.awt.Font;
import java.awt.FontMetrics;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Namensschilder über anderen Spielern und benannten Entities (M9, docs/36 §5): Punkt über dem Kopf in Bildschirmkoordinaten
 * projiziert und als kleine Text-Textur gezeichnet. Je Name eine {@link TextPanel}-Textur, höchstens {@link #MAX} gleichzeitig
 * (älteste wird freigegeben) – keine GPU-Objekte je Bild.
 */
final class NameTags implements AutoCloseable {
	static final int MAX = 24;
	private static final double RANGE = 32.0;
	private final Map<String, TextPanel> panels = new LinkedHashMap<>(16, 0.75f, true);
	private final Font font = new Font(Font.SANS_SERIF, Font.BOLD, 14);

	void draw(final Mat4 viewProj, final List<EntityManager.View> views, final double camX, final double camY, final double camZ, final int sw,
		final int sh) {
		int drawn = 0;
		for (EntityManager.View v : views) {
			String name = v.playerName() != null ? v.playerName() : v.customName();
			if (name == null || name.isEmpty() || (v.flags() & 0x20) != 0) {
				continue;
			}
			double dx = v.rx() - camX, dy = v.ry() + v.height() + 0.5 - camY, dz = v.rz() - camZ;
			if (dx * dx + dy * dy + dz * dz > RANGE * RANGE || ++drawn > MAX) {
				continue;
			}
			float[] p = viewProj.transform((float) dx, (float) dy, (float) dz);
			if (p[3] <= 0.05f) {
				continue; // hinter der Kamera
			}
			float sx = (p[0] / p[3] * 0.5f + 0.5f) * sw;
			float sy = (0.5f - p[1] / p[3] * 0.5f) * sh;
			int w = 8 + name.length() * 9;
			int h = 20;
			TextPanel panel = this.panels.computeIfAbsent(name, k -> new TextPanel());
			panel.draw(name, Math.round(sx - w / 2f), Math.round(sy - h), w, h, g -> {
				g.setColor(new Color(0, 0, 0, 110));
				g.fillRect(0, 0, w, h);
				g.setFont(this.font);
				FontMetrics fm = g.getFontMetrics();
				g.setColor(Color.WHITE);
				g.drawString(name, (w - fm.stringWidth(name)) / 2, (h + fm.getAscent() - fm.getDescent()) / 2);
			}, sw, sh);
		}
		while (this.panels.size() > MAX) {
			String oldest = this.panels.keySet().iterator().next();
			this.panels.remove(oldest).close();
		}
	}

	@Override
	public void close() {
		this.panels.values().forEach(TextPanel::close);
		this.panels.clear();
	}
}
