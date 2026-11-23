package dev.mcresearch.protocolclient.gl;

import dev.mcresearch.protocolclient.app.ConnectionManager;
import dev.mcresearch.protocolclient.input.Keys;
import dev.mcresearch.protocolclient.physics.PlayerPhysics;
import dev.mcresearch.protocolclient.session.ClientSession;
import dev.mcresearch.protocolclient.session.MovementController;
import dev.mcresearch.protocolclient.settings.Setting;
import dev.mcresearch.protocolclient.settings.Settings;
import dev.mcresearch.protocolclient.ui.ScreenManager;
import dev.mcresearch.protocolclient.ui.ScrollContainer;
import dev.mcresearch.protocolclient.ui.TabNavigation;
import dev.mcresearch.protocolclient.ui.Widget;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * GUI-Integrationstest im Menümodus (docs/62 §5), Option {@code --ui-test}. Klicks und Tasten laufen über die Ereigniswarteschlangen
 * des Fensters – denselben Weg wie echte Eingaben (Fenster → Anwendung → ScreenManager bzw. Merlin Input System); Prüfungen schreiben
 * {@code RESULT OK|FAIL} in den Bericht.
 *
 * <pre>
 *   wait &lt;s&gt;                    warten (Bilder laufen weiter)
 *   wait-screen &lt;name&gt; [s]      bis der oberste Screen so heißt (Standard 20 s), sonst FAIL
 *   wait-phase &lt;PHASE&gt; [s]      bis der ConnectionManager diese Phase meldet
 *   click &lt;widget-id&gt;           Mausklick auf die Mitte des Widgets (vorher in Sicht scrollen)
 *   click-tab &lt;id&gt; &lt;index&gt;      Reiter anklicken
 *   key &lt;NAME&gt;                  Taste (ESC ENTER TAB UP DOWN LEFT RIGHT SPACE BACKSPACE F3 E W … oder ein Zeichen)
 *   hold &lt;NAME&gt; &lt;s&gt;             Taste halten
 *   type &lt;text&gt;                 Zeichen eingeben
 *   expect &lt;widget-id&gt; &lt;text&gt;    Beschreibung des Widgets enthält text
 *   expect-screen &lt;name&gt;         oberster Screen
 *   expect-setting &lt;key&gt; &lt;wert&gt;
 *   expect-no-overflow           keine gekürzten Texte im zuletzt gezeichneten Menübild
 *   expect-world-locked          bei offenem Menü: keine Bewegung, keine Blockinteraktion
 *   mark-pos / expect-pos-same   Spielerposition merken / unverändert (±0,001)
 *   resize &lt;w&gt; &lt;h&gt;              Fenstergröße (Fenstermodus)
 *   set &lt;key&gt; &lt;wert&gt;            Einstellung setzen (wie ein Regler)
 *   screenshot &lt;datei&gt;  dump  threads &lt;label&gt;  perf-begin  perf-end &lt;label&gt;  mark &lt;text&gt;  quit
 *   repeat &lt;n&gt; … end            Block n-mal
 * </pre>
 */
final class UiAutotest implements AutoCloseable {
	private final List<String[]> steps;
	private final Path report;
	private final MerlinApp app;
	private int index;
	private long stepStart = -1;
	private int ok;
	private int fail;
	private double[] markedPos;
	private long perfStart;
	private long perfFrames;
	private int holdKey = -1;
	private boolean finished;
	private final long started = System.nanoTime();
	private Path console;
	private double maxY = Double.NEGATIVE_INFINITY;

	private UiAutotest(final List<String[]> steps, final Path report, final MerlinApp app) {
		this.steps = steps;
		this.report = report;
		this.app = app;
	}

	static UiAutotest load(final Path script, final Path report, final MerlinApp app) throws IOException {
		List<String> raw = new ArrayList<>();
		for (String line : Files.readAllLines(script, StandardCharsets.UTF_8)) {
			String t = line.strip();
			if (!t.isEmpty() && !t.startsWith("#")) {
				raw.add(t);
			}
		}
		List<String[]> steps = new ArrayList<>();
		expand(raw, 0, raw.size(), steps, 1);
		if (report != null) {
			Files.deleteIfExists(report);
		}
		return new UiAutotest(steps, report, app);
	}

	/** {@code repeat n … end} aufklappen (verschachtelt erlaubt). */
	private static int expand(final List<String> raw, final int from, final int to, final List<String[]> out, final int depth) {
		int i = from;
		while (i < to) {
			String t = raw.get(i);
			if (t.equals("end")) {
				return i;
			}
			if (t.startsWith("repeat ")) {
				int n = Integer.parseInt(t.substring(7).strip());
				List<String[]> body = new ArrayList<>();
				int endAt = expand(raw, i + 1, to, body, depth + 1);
				for (int k = 0; k < n; k++) {
					out.add(new String[]{"mark", "repeat " + (k + 1) + "/" + n});
					out.addAll(body);
				}
				i = endAt + 1;
				continue;
			}
			int sp = t.indexOf(' ');
			out.add(sp < 0 ? new String[]{t, ""} : new String[]{t.substring(0, sp), t.substring(sp + 1).strip()});
			i++;
		}
		return i;
	}

	private void log(final String line) {
		String l = String.format(Locale.ROOT, "%7.1f s  %s", (System.nanoTime() - this.started) / 1e9, line);
		System.out.println("[UITEST] " + l);
		GameView.appendReport(this.report, l);
	}

	private void result(final boolean pass, final String what) {
		if (pass) {
			this.ok++;
		} else {
			this.fail++;
		}
		log((pass ? "RESULT OK " : "RESULT FAIL ") + what);
	}

	private static int key(final String name) {
		return switch (name.toUpperCase(Locale.ROOT)) {
			case "ESC", "ESCAPE" -> Keys.ESCAPE;
			case "ENTER" -> Keys.ENTER;
			case "TAB" -> Keys.TAB;
			case "UP" -> Keys.UP;
			case "DOWN" -> Keys.DOWN;
			case "LEFT" -> Keys.LEFT;
			case "RIGHT" -> Keys.RIGHT;
			case "SPACE" -> Keys.SPACE;
			case "BACKSPACE" -> Keys.BACKSPACE;
			case "F1" -> Keys.F1;
			case "F3" -> Keys.F3;
			case "F11" -> Keys.F11;
			case "LSHIFT" -> Keys.LEFT_SHIFT;
			case "LCTRL" -> Keys.LEFT_CONTROL;
			default -> {
				if (name.length() == 1) {
					yield Character.toUpperCase(name.charAt(0));
				}
				throw new IllegalArgumentException("unknown key " + name);
			}
		};
	}

	
	/** Je Bild; ein fehlerhafter Schritt wird als FAIL gewertet und übersprungen, nie beendet er den Client. */
	void step(final long now) {
		try {
			stepUnsafe(now);
		} catch (IOException | RuntimeException e) {
			result(false, "step " + (this.index < this.steps.size() ? String.join(" ", this.steps.get(this.index)) : "-") + " failed: " + e);
			this.index++;
			this.stepStart = -1;
		}
	}

	private void stepUnsafe(final long now) throws IOException {
		if (this.finished) {
			return;
		}
		if (this.index >= this.steps.size()) {
			this.finished = true;
			log(String.format(Locale.ROOT, "uitest done: steps %d/%d, RESULT OK %d, RESULT FAIL %d", this.index, this.steps.size(), this.ok, this.fail));
			this.app.window().requestClose();
			return;
		}
		if (this.stepStart < 0) {
			this.stepStart = now;
		}
		double elapsed = (now - this.stepStart) / 1e9;
		String[] s = this.steps.get(this.index);
		ScreenManager ui = this.app.screens();
		Window w = this.app.window();
		boolean done = true;
		if (this.markedPos != null && this.app.scene() != null) {
			this.maxY = Math.max(this.maxY, this.app.scene().session.movement().renderState().y());
		}
		switch (s[0]) {
			case "wait" -> done = elapsed >= Double.parseDouble(s[1]);
			case "wait-screen" -> {
				String[] a = s[1].split(" ");
				double limit = a.length > 1 ? Double.parseDouble(a[1]) : 20;
				boolean match = ui.current() != null ? ui.current().name().equals(a[0]) : a[0].equals("none");
				done = match || elapsed > limit;
				if (done) {
					result(match, "screen " + a[0] + (match ? "" : " (is " + (ui.current() == null ? "none" : ui.current().name()) + ")")
						+ String.format(Locale.ROOT, " after %.2f s", elapsed));
				}
			}
			case "wait-phase" -> {
				String[] a = s[1].split(" ");
				double limit = a.length > 1 ? Double.parseDouble(a[1]) : 30;
				ConnectionManager.Phase p = this.app.connections().phase();
				boolean match = p.name().equals(a[0]);
				done = match || elapsed > limit;
				if (done) {
					result(match, "phase " + a[0] + (match ? "" : " (is " + p + ")") + String.format(Locale.ROOT, " after %.2f s", elapsed));
				}
			}
			case "click" -> {
				Widget wd = ui.find(s[1]);
				if (wd == null) {
					result(false, "click " + s[1] + ": widget not found on " + (ui.current() == null ? "-" : ui.current().name()));
				} else {
					if (wd.parent() instanceof ScrollContainer sc) {
						sc.scrollToShow(wd);
					}
					double[] p = ui.pixelCenter(wd);
					w.injectMouse(true, 0, p[0], p[1]);
					w.injectMouse(false, 0, p[0], p[1]);
					log("click " + s[1] + String.format(Locale.ROOT, " at %.0f,%.0f", p[0], p[1]));
				}
			}
			case "click-tab" -> {
				String[] a = s[1].split(" ");
				if (ui.find(a[0]) instanceof TabNavigation t) {
					var r = t.tabRect(Integer.parseInt(a[1]));
					w.injectMouse(true, 0, r.centerX() * ui.scale(), r.centerY() * ui.scale());
					w.injectMouse(false, 0, r.centerX() * ui.scale(), r.centerY() * ui.scale());
					log("click-tab " + s[1]);
				} else {
					result(false, "click-tab " + s[1] + ": no tabs");
				}
			}
			case "key" -> {
				int k = key(s[1]);
				w.simulateKey(k, true);
				w.simulateKey(k, false);
				log("key " + s[1]);
			}
			case "hold" -> {
				String[] a = s[1].split(" ");
				int k = key(a[0]);
				if (this.holdKey < 0) {
					this.holdKey = k;
					w.simulateKey(k, true);
				}
				done = elapsed >= Double.parseDouble(a[1]);
				if (done) {
					w.simulateKey(k, false);
					this.holdKey = -1;
				}
			}
			case "type" -> {
				s[1].codePoints().forEach(w::injectChar);
				log("type \"" + s[1] + "\"");
			}
			case "expect" -> {
				int sp = s[1].indexOf(' ');
				String id = sp < 0 ? s[1] : s[1].substring(0, sp);
				String text = sp < 0 ? "" : s[1].substring(sp + 1);
				Widget wd = ui.find(id);
				String d = wd == null ? "<missing>" : wd.describe() + (wd.enabled() ? "" : " disabled");
				result(wd != null && d.contains(text), "expect " + id + " contains \"" + text + "\" (" + d + ")");
			}
			case "expect-screen" -> {
				String n = ui.current() == null ? "none" : ui.current().name();
				result(n.equals(s[1]), "expect-screen " + s[1] + (n.equals(s[1]) ? "" : " (is " + n + ")"));
			}
			case "expect-setting" -> {
				int sp = s[1].indexOf(' ');
				String key = s[1].substring(0, sp);
				String want = s[1].substring(sp + 1);
				String is = this.app.settings().snapshot().get(key);
				result(want.equals(is), "setting " + key + " = " + want + (want.equals(is) ? "" : " (is " + is + ")"));
			}
			case "expect-no-overflow" -> {
				int n = this.app.resources().ui.lastOverflows();
				result(n == 0, "no truncated text on " + (ui.current() == null ? "-" : ui.current().name()) + " at " + ui.pixelWidth() + "x"
					+ ui.pixelHeight() + " scale " + ui.scale() + (n == 0 ? "" : " " + this.app.resources().ui.overflowSamples()));
			}
			case "expect-world-locked" -> {
				GameScene sc = this.app.scene();
				ClientSession cs = sc == null ? null : sc.session;
				boolean locked = sc != null && GameView.SCREEN_OPEN && this.app.input().movement().equals(PlayerPhysics.Input.NONE)
					&& !cs.interaction().inputAllowed();
				result(locked, "world input locked while " + (ui.current() == null ? "-" : ui.current().name()) + " is open (screenOpen="
					+ GameView.SCREEN_OPEN + ", movement=" + this.app.input().movement() + ")");
			}
			case "mark-pos" -> {
				GameScene sc = this.app.scene();
				if (sc != null) {
					MovementController.RenderState rs = sc.session.movement().renderState();
					this.maxY = rs.y();
					this.markedPos = new double[]{rs.x(), rs.y(), rs.z(), rs.yaw(), rs.pitch(), sc.session.interaction().status().sequence()};
					log(String.format(Locale.ROOT, "mark-pos %.4f %.4f %.4f yaw %.2f pitch %.2f seq %d", rs.x(), rs.y(), rs.z(), rs.yaw(), rs.pitch(),
						(long) this.markedPos[5]));
				}
			}
			case "expect-pos-same" -> {
				GameScene sc = this.app.scene();
				boolean same = false;
				String desc = "no scene";
				if (sc != null && this.markedPos != null) {
					MovementController.RenderState rs = sc.session.movement().renderState();
					double d = Math.abs(rs.x() - this.markedPos[0]) + Math.abs(rs.y() - this.markedPos[1]) + Math.abs(rs.z() - this.markedPos[2]);
					double look = Math.abs(rs.yaw() - this.markedPos[3]) + Math.abs(rs.pitch() - this.markedPos[4]);
					long seq = sc.session.interaction().status().sequence();
					same = d < 0.001 && look < 0.001 && seq == (long) this.markedPos[5];
					desc = String.format(Locale.ROOT, "Δpos %.4f Δlook %.3f sequence %d→%d", d, look, (long) this.markedPos[5], seq);
				}
				result(same, "no movement/look/interaction while menu open: " + desc);
			}
			case "server-console" -> this.console = Path.of(s[1]);
			case "console" -> {
				if (this.console != null) {
					Files.writeString(this.console, s[1] + System.lineSeparator(), StandardCharsets.UTF_8, java.nio.file.StandardOpenOption.APPEND);
				}
				log("console " + s[1]);
			}
			case "verify" -> {
				// Clientposition protokollieren und den Server dieselbe Position melden lassen (Abgleich im Laufskript)
				GameScene sc = this.app.scene();
				if (sc != null) {
					MovementController.RenderState rs = sc.session.movement().renderState();
					log(String.format(Locale.ROOT, "verify:%s pos=(%.4f, %.4f, %.4f) corrections=%d", s[1], rs.x(), rs.y(), rs.z(),
						sc.session.movement().corrections()));
					if (this.console != null) {
						Files.writeString(this.console, "data get entity " + this.app.settings().get(Settings.OFFLINE_NAME) + " Pos" + System.lineSeparator(),
							StandardCharsets.UTF_8, java.nio.file.StandardOpenOption.APPEND);
					}
				}
			}
			case "log-input" -> {
				w.setLogInput(!s[1].equals("off"));
				log("log-input " + s[1]);
			}
			case "expect-look-changed" -> {
				GameScene sc = this.app.scene();
				double d = 0;
				if (sc != null && this.markedPos != null) {
					MovementController.RenderState rs = sc.session.movement().renderState();
					d = Math.abs(rs.yaw() - this.markedPos[3]) + Math.abs(rs.pitch() - this.markedPos[4]);
				}
				result(d > 1.0, String.format(Locale.ROOT, "mouse look changed the camera: %.2f degrees", d));
			}
			case "expect-jumped" -> {
				double rise = this.markedPos == null ? 0 : this.maxY - this.markedPos[1];
				result(rise > 0.5, String.format(Locale.ROOT, "player jumped: max rise %.3f blocks", rise));
			}
			case "expect-inv" -> {
				String[] a = s[1].split(" ");
				GameScene sc = this.app.scene();
				String is = "-";
				if (sc != null) {
					var st = sc.session.inventory().slot(Integer.parseInt(a[0]));
					is = st.isEmpty() ? "empty" : dev.mcresearch.protocolclient.world.ItemRegistry.get().name(st.itemId()) + " " + st.count();
				}
				String want = a.length > 2 ? a[1] + " " + a[2] : a[1];
				result(is.equals(want), "inventory menu slot " + a[0] + " = " + want + (is.equals(want) ? "" : " (is " + is + ")"));
			}
			case "export-slots" -> {
				int[] sr = w.screenRect();
				StringBuilder sb = new StringBuilder();
				InventoryScreen inv = this.app.resources().invScreen;
				for (int slot = 0; slot < 46; slot++) {
					double[] c = inv.slotCenter(slot);
					if (c != null) {
						sb.append("PIXEL slot.").append(slot).append(' ').append(Math.round(sr[0] + c[0] * sr[2] / (double) sr[4])).append(' ')
							.append(Math.round(sr[1] + c[1] * sr[3] / (double) sr[5])).append('\n');
					}
				}
				Path tmp = Path.of(s[1] + ".tmp");
				Files.writeString(tmp, sb.toString(), StandardCharsets.UTF_8);
				Files.move(tmp, Path.of(s[1]), java.nio.file.StandardCopyOption.REPLACE_EXISTING);
				log("export-slots " + s[1]);
			}
			case "expect-pos-changed" -> {
				GameScene sc = this.app.scene();
				double d = 0;
				if (sc != null && this.markedPos != null) {
					MovementController.RenderState rs = sc.session.movement().renderState();
					d = Math.abs(rs.x() - this.markedPos[0]) + Math.abs(rs.z() - this.markedPos[2]);
				}
				result(d > 0.1, String.format(Locale.ROOT, "player moved after resume: Δpos %.3f", d));
			}
			case "click-at" -> {
				String[] a = s[1].split(" ");
				double x = Double.parseDouble(a[0]) * w.width(), y = Double.parseDouble(a[1]) * w.height();
				int b = a.length > 2 ? Integer.parseInt(a[2]) : 0;
				w.injectMouse(true, b, x, y);
				w.injectMouse(false, b, x, y);
				log(String.format(Locale.ROOT, "click-at %.0f,%.0f button %d", x, y, b));
			}
			case "wait-file" -> {
				String[] a = s[1].split(" ");
				double limit = a.length > 1 ? Double.parseDouble(a[1]) : 120;
				boolean exists = Files.exists(Path.of(a[0]));
				done = exists || elapsed > limit;
				if (done) {
					result(exists, "file " + a[0] + (exists ? "" : " not created within " + limit + " s"));
				}
			}
			case "write-file" -> {
				Files.writeString(Path.of(s[1]), "ok\n", StandardCharsets.UTF_8);
				log("write-file " + s[1]);
			}
			case "pixel-map" -> {
				// Bildschirmlage (Desktop-Punkte) der Widgets für den Betriebssystem-Eingabetest, Datei s[1]
				int[] sr = w.screenRect(); // x, y, Fensterbreite, -höhe (Punkte), Framebuffer-Breite, -höhe
				StringBuilder sb = new StringBuilder();
				for (Widget wd : ui.current() == null ? List.<Widget>of() : ui.current().focusOrder()) {
					double[] p = ui.pixelCenter(wd);
					long sx = Math.round(sr[0] + p[0] * sr[2] / Math.max(1, sr[4]));
					long sy = Math.round(sr[1] + p[1] * sr[3] / Math.max(1, sr[5]));
					sb.append("PIXEL ").append(wd.id()).append(' ').append(sx).append(' ').append(sy).append('\n');
					if (wd instanceof TabNavigation tn) {
						for (int t = 0; t < tn.tabs().size(); t++) {
							var tr = tn.tabRect(t);
							sb.append("PIXEL ").append(wd.id()).append('.').append(t).append(' ')
								.append(Math.round(sr[0] + tr.centerX() * ui.scale() * sr[2] / Math.max(1, sr[4]))).append(' ')
								.append(Math.round(sr[1] + tr.centerY() * ui.scale() * sr[3] / Math.max(1, sr[5]))).append('\n');
						}
					}
				}
				sb.append(String.format(Locale.ROOT, "CENTER %d %d%n", sr[0] + sr[2] / 2, sr[1] + sr[3] / 2));
				sb.append(String.format(Locale.ROOT, "EMPTY %d %d%n", sr[0] + sr[2] * 3 / 100, sr[1] + sr[3] * 97 / 100)); // freie Ecke unten links
				Path tmp = Path.of(s[1] + ".tmp");
				Files.writeString(tmp, sb.toString(), StandardCharsets.UTF_8);
				Files.move(tmp, Path.of(s[1]), java.nio.file.StandardCopyOption.REPLACE_EXISTING);
				log("pixel-map " + s[1] + " (" + (ui.current() == null ? "game" : ui.current().name()) + ", window " + sr[0] + "," + sr[1] + " " + sr[2] + "x" + sr[3] + ")");
			}
			case "resize" -> {
				String[] a = s[1].split(" ");
				w.setWindowSize(Integer.parseInt(a[0]), Integer.parseInt(a[1]));
				log("resize " + s[1]);
			}
			case "set" -> {
				int sp = s[1].indexOf(' ');
				String key = s[1].substring(0, sp);
				Setting<?> st = Settings.byKey(key);
				if (st == null) {
					result(false, "set " + key + ": unknown");
				} else {
					setAny(st, s[1].substring(sp + 1));
					log("set " + s[1]);
				}
			}
			case "screenshot" -> {
				w.screenshot(Path.of(s[1]));
				log("screenshot " + s[1]);
			}
			case "dump" -> {
				for (String l : ui.dump()) {
					log("UI " + l);
				}
			}
			case "threads" -> log("THREADS " + s[1] + " " + threads());
			case "perf-begin" -> {
				this.perfStart = now;
				this.perfFrames = this.app.frameStats().frames();
				log("perf-begin " + s[1]);
			}
			case "perf-end" -> {
				double secs = (now - this.perfStart) / 1e9;
				long fr = this.app.frameStats().frames() - this.perfFrames;
				Runtime rt = Runtime.getRuntime();
				log(String.format(Locale.ROOT, "PERF %s frames %d in %.1f s = %.1f FPS, avg %.2f ms, 1%% low %.1f, heap %d MB, ui repaints %d (last %.2f ms), "
					+ "gl %d %s", s[1], fr, secs, fr / Math.max(1e-9, secs), this.app.frameStats().avgMs(), this.app.frameStats().low1(),
					(rt.totalMemory() - rt.freeMemory()) >> 20, this.app.resources().ui.repaints(), this.app.resources().ui.lastRepaintMs(),
					GlResources.live(), GlResources.summary()));
			}
			case "mark" -> log("MARK " + s[1]);
			case "quit" -> {
				log("quit");
				this.app.quit();
			}
			default -> result(false, "unknown step " + s[0]);
		}
		if (done) {
			this.index++;
			this.stepStart = -1;
		}
	}

	@SuppressWarnings("unchecked")
	private <T> void setAny(final Setting<T> st, final String raw) {
		this.app.settings().set(st, st.parse(raw));
	}

	/** Threads des Projekts und Gesamtzahl (Ressourcenprüfung nach Verbindungen). */
	static String threads() {
		Map<Thread, StackTraceElement[]> all = Thread.getAllStackTraces();
		int session = 0, tick = 0, connect = 0, ping = 0;
		for (Thread t : all.keySet()) {
			if (!t.isAlive()) {
				continue;
			}
			switch (t.getName()) {
				case "session" -> session++;
				case "client-tick" -> tick++;
				case "connect" -> connect++;
				case "server-ping" -> ping++;
				default -> {
				}
			}
		}
		Runtime rt = Runtime.getRuntime();
		return String.format(Locale.ROOT, "total=%d session=%d client-tick=%d connect=%d server-ping=%d gl=%d heapMB=%d", all.size(), session, tick, connect,
			ping, GlResources.live(), (rt.totalMemory() - rt.freeMemory()) >> 20);
	}

	@Override
	public void close() {
		if (!this.finished) {
			log(String.format(Locale.ROOT, "uitest end (window closed): steps %d/%d, RESULT OK %d, RESULT FAIL %d", this.index, this.steps.size(), this.ok,
				this.fail));
		}
	}
}
