package dev.mcresearch.protocolclient.gl;

import dev.mcresearch.protocolclient.session.ClientSession;
import dev.mcresearch.protocolclient.session.PlayerSurvivalState;
import java.awt.Color;
import java.awt.Font;
import java.awt.FontMetrics;
import java.util.List;
import java.util.Locale;

/**
 * Überlebensanzeige (M10, docs/40 §6): Herzen (an die Maximalgesundheit angepasst, mehrere Reihen über 20), Absorptionsherzen,
 * Rüstung, Hunger, Angriffsabklingzeit unter dem Fadenkreuz, aktive Effekte, roter Bildschirmrand bei Schaden und der Todesbildschirm
 * mit „Wiederbeleben“. Eigene Darstellung aus Rechtecken und Text (keine Mojang-Grafiken); Größen skalieren mit der Fensterhöhe.
 */
final class SurvivalHud implements AutoCloseable {
	private final TextPanel effectsText = new TextPanel();
	private final TextPanel deathText = new TextPanel();
	private final Font font = new Font(Font.SANS_SERIF, Font.BOLD, 13);
	private final Font big = new Font(Font.SANS_SERIF, Font.BOLD, 34);
	/** Knopf „Wiederbeleben“ des letzten Bildes (x, y, b, h) – für Mausklicks und den Autotest. */
	private float[] respawnButton = new float[4];
	private boolean deathScreenVisible;
	/** Übersetzungen aus der lokalen en_us.json des Client-JARs (nur zur Laufzeit gelesen, nie kopiert); leer ohne Ressourcen. */
	private final java.util.Map<String, String> lang;

	SurvivalHud(final java.nio.file.Path assets) {
		java.util.Map<String, String> m = java.util.Map.of();
		if (assets != null) {
			try (java.util.zip.ZipFile zip = new java.util.zip.ZipFile(assets.toFile())) {
				java.util.zip.ZipEntry e = zip.getEntry("assets/minecraft/lang/en_us.json");
				if (e != null) {
					String json = new String(zip.getInputStream(e).readAllBytes(), java.nio.charset.StandardCharsets.UTF_8);
					if (dev.mcresearch.protocolclient.protocol.json.MiniJson.parse(json) instanceof java.util.Map<?, ?> raw) {
						java.util.Map<String, String> t = new java.util.HashMap<>();
						raw.forEach((k, v) -> {
							if (k instanceof String ks && v instanceof String vs) {
								t.put(ks, vs);
							}
						});
						m = t;
					}
				}
			} catch (java.io.IOException ex) {
				m = java.util.Map.of();
			}
		}
		this.lang = m;
	}

	/** Todesnachricht übersetzt (lokale Sprachdatei) oder als Klartext mit Schlüssel. */
	String deathMessage(final ClientSession session) {
		Object c = session.survival().deathComponent();
		return c == null || this.lang.isEmpty() ? session.survival().view(0).deathMessage()
			: dev.mcresearch.protocolclient.protocol.nbt.TextComponents.toPlainText(c, this.lang::get);
	}

	boolean deathScreenVisible() {
		return this.deathScreenVisible;
	}

	float[] respawnButton() {
		return this.respawnButton.clone();
	}

	boolean overRespawnButton(final double x, final double y) {
		float[] b = this.respawnButton;
		return this.deathScreenVisible && x >= b[0] && x <= b[0] + b[2] && y >= b[1] && y <= b[1] + b[3];
	}

	/** Zeichnet alles über dem Spiel-HUD. {@code entityTargeted}: Fadenkreuz zeigt auf eine Entity (Abklingzeit-Anzeige wie Vanilla). */
	void draw(final HudRenderer hud, final ClientSession session, final int w, final int h, final boolean entityTargeted, final float partialTick) {
		PlayerSurvivalState.View v = session.survival().view(partialTick);
		int scale = Math.max(1, h / 720);
		int slot = 44 * scale;
		int hotbarX = (w - 9 * slot) / 2;
		int hotbarTop = h - slot - 8 * scale;
		boolean survivalMode = session.gameMode() == 0 || session.gameMode() == 2;
		int icon = 9 * scale;
		int gap = 1 * scale;
		if (survivalMode && !v.dead()) {
			// Herzen: 2 Lebenspunkte je Herz, 10 je Reihe; Reihen nach oben bei Maximalgesundheit > 20
			int hearts = (int) Math.ceil(Math.max(v.maxHealth(), 1) / 2.0);
			int absorbHearts = (int) Math.ceil(v.absorption() / 2.0);
			int total = hearts + absorbHearts;
			int rows = (total + 9) / 10;
			int rowStep = Math.max(icon - 2 * scale, 3 * scale);
			int baseY = hotbarTop - 4 * scale - icon;
			boolean flash = v.hurtTicks() > 0 && v.hurtTicks() % 4 >= 2;
			for (int i = 0; i < total; i++) {
				int x = hotbarX + (i % 10) * (icon + gap);
				int y = baseY - (i / 10) * rowStep;
				hud.rect(x, y, icon, icon, flash ? 0xFFFFFFFF : 0xFF2A0A0A); // Rahmen/Hintergrund
				float fill;
				int color;
				if (i < hearts) {
					fill = Math.clamp((v.health() - i * 2) / 2.0F, 0F, 1F);
					color = 0xFFD8202A;
				} else {
					fill = Math.clamp((v.absorption() - (i - hearts) * 2) / 2.0F, 0F, 1F);
					color = 0xFFE8C020;
				}
				if (fill > 0) {
					hud.rect(x + scale, y + scale, (icon - 2 * scale) * fill, icon - 2 * scale, color);
				}
			}
			// Rüstung über den Herzen (nur wenn > 0)
			int armorY = baseY - rows * rowStep - 2 * scale;
			if (v.armor() > 0) {
				for (int i = 0; i < 10; i++) {
					int x = hotbarX + i * (icon + gap);
					hud.rect(x, armorY, icon, icon, 0xFF202028);
					float fill = (float) Math.clamp((v.armor() - i * 2) / 2.0, 0, 1);
					if (fill > 0) {
						hud.rect(x + scale, armorY + scale, (icon - 2 * scale) * fill, icon - 2 * scale, 0xFFB8C0C8);
					}
				}
			}
			// Hunger rechts, von rechts nach links gefüllt
			int foodRight = hotbarX + 9 * slot;
			for (int i = 0; i < 10; i++) {
				int x = foodRight - (i + 1) * (icon + gap);
				hud.rect(x, baseY, icon, icon, 0xFF201408);
				float fill = Math.clamp((v.food() - i * 2) / 2.0F, 0F, 1F);
				if (fill > 0) {
					float fw = (icon - 2 * scale) * fill;
					hud.rect(x + icon - scale - fw, baseY + scale, fw, icon - 2 * scale, 0xFFC07830);
				}
			}
		}
		// Eigener Armschwung (vereinfachte Ich-Perspektive, eigene Geometrie): WHACK als Bogen, STAB als Stoß nach vorn;
		// Fortschritt aus SwingState#getAnimation (Dauer aus attack_/interact_animation, Eile/Abbaulähmung berücksichtigt)
		if (!v.dead() && session.gameMode() != 3) {
			float p = v.swingProgress();
			float arc = (float) Math.sin(Math.sqrt(p) * Math.PI), push = (float) Math.sin(p * Math.PI);
			boolean left = v.swingHand() == 1;
			float side = left ? -1 : 1;
			float armW = 46 * scale, armH = 150 * scale;
			float cx = w / 2f + side * w * 0.30f, cy = h - armH * 0.32f;
			float angle = side * 18;
			if (v.swingType() == PlayerSurvivalState.SWING_WHACK) {
				cx -= side * w * 0.10f * arc;
				cy -= h * 0.08f * push;
				angle -= side * 70 * arc;
			} else if (v.swingType() == PlayerSurvivalState.SWING_STAB) {
				cx -= side * w * 0.06f * push;
				cy -= h * 0.22f * push;
				angle -= side * 12 * push;
			}
			hud.rotatedRect(cx, cy, armW, armH, angle, 0xFFB98A68);
			double rad = Math.toRadians(angle);
			float d = armH * 0.38f; // Hand am oberen Ende der gedrehten Armachse
			hud.rotatedRect(cx + d * (float) Math.sin(rad), cy - d * (float) Math.cos(rad), armW * 0.92f, armH * 0.22f, angle, 0xFF9C7254);
		}
		// Angriffsabklingzeit unter dem Fadenkreuz (Vanilla: Anzeige, solange < 1 oder Ziel mit voller Stärke)
		if (!v.dead() && session.gameMode() != 3) {
			float s = v.attackStrength();
			int bw = 16 * scale, bh = 2 * scale;
			int bx = (w - bw) / 2, by = h / 2 + 9 * scale;
			if (s < 1.0F) {
				hud.rect(bx, by, bw, bh, 0x90000000);
				hud.rect(bx, by, bw * s, bh, 0xFFE0E0E0);
			} else if (entityTargeted) {
				hud.rect(bx + bw / 2 - 2 * scale, by - scale, 4 * scale, 4 * scale, 0xFFE0E0E0);
			}
		}
		// Schadensrückmeldung: roter Rand, verblasst über 10 Ticks
		if (v.hurtTicks() > 0 && !v.dead()) {
			int a = (int) (110 * v.hurtTicks() / 10.0);
			int edge = Math.max(8, h / 18);
			int c = a << 24 | 0xB01010;
			hud.rect(0, 0, w, edge, c);
			hud.rect(0, h - edge, w, edge, c);
			hud.rect(0, edge, edge, h - 2 * edge, c);
			hud.rect(w - edge, edge, edge, h - 2 * edge, c);
		}
		// Todesbildschirm
		this.deathScreenVisible = v.dead() && !session.autoRespawn();
		if (this.deathScreenVisible) {
			hud.rect(0, 0, w, h, 0x90501010);
			float bw = 220 * scale, bh = 36 * scale;
			float bx = (w - bw) / 2f, by = h * 0.55f;
			this.respawnButton = new float[]{bx, by, bw, bh};
			hud.rect(bx - 2, by - 2, bw + 4, bh + 4, 0xFF101010);
			hud.rect(bx, by, bw, bh, 0xFF707070);
		}
		hud.flushScreen(w, h);
		if (this.deathScreenVisible) {
			String msg = deathMessage(session);
			int tw = Math.min(w, 900 * scale), th = 160 * scale;
			int tx = (w - tw) / 2, ty = (int) (h * 0.55f) - 110 * scale;
			this.deathText.draw(w + "x" + h + msg + v.respawnsRequested(), tx, ty, tw, th, g -> {
				g.setFont(this.big.deriveFont(34f * scale));
				FontMetrics fm = g.getFontMetrics();
				g.setColor(Color.WHITE);
				String title = "Du bist gestorben!";
				g.drawString(title, (tw - fm.stringWidth(title)) / 2, 40 * scale);
				g.setFont(this.font.deriveFont(15f * scale));
				fm = g.getFontMetrics();
				g.drawString(msg, (tw - fm.stringWidth(msg)) / 2, 75 * scale);
				String b = v.respawnsRequested() > 0 ? "Wiederbelebung angefordert …" : "Wiederbeleben";
				g.drawString(b, (tw - fm.stringWidth(b)) / 2, 110 * scale + 23 * scale);
			}, w, h);
		}
		// aktive Effekte oben rechts: Name, Stufe, Restzeit
		List<PlayerSurvivalState.Effect> effects = v.effects();
		if (!effects.isEmpty()) {
			StringBuilder key = new StringBuilder();
			for (PlayerSurvivalState.Effect e : effects) {
				key.append(e.id()).append(':').append(e.amplifier()).append(':').append(e.remaining() / 20).append(';');
			}
			int lineH = 18 * scale;
			int pw = 210 * scale, ph = effects.size() * lineH + 6 * scale;
			this.effectsText.draw(key.toString(), w - pw - 6 * scale, 6 * scale, pw, ph, g -> {
				g.setColor(new Color(0, 0, 0, 120));
				g.fillRect(0, 0, pw, ph);
				g.setFont(this.font.deriveFont(13f * scale));
				int y = 15 * scale;
				for (PlayerSurvivalState.Effect e : effects) {
					String t = e.remaining() < 0 ? "∞" : String.format(Locale.ROOT, "%d:%02d", e.remaining() / 1200, e.remaining() / 20 % 60);
					g.setColor(e.id() == 18 || e.id() == 1 || e.id() == 17 || e.id() == 6 ? new Color(255, 140, 140) : new Color(170, 230, 255));
					g.drawString(e.name() + (e.amplifier() > 0 ? " " + roman(e.amplifier() + 1) : "") + "  " + t, 6 * scale, y);
					y += lineH;
				}
			}, w, h);
		}
	}

	private static String roman(final int n) {
		String[] r = {"", "I", "II", "III", "IV", "V", "VI", "VII", "VIII", "IX", "X"};
		return n >= 0 && n < r.length ? r[n] : Integer.toString(n);
	}

	@Override
	public void close() {
		this.effectsText.close();
		this.deathText.close();
	}
}
