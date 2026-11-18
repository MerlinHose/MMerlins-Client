package dev.mcresearch.protocolclient.gl;

import dev.mcresearch.protocolclient.physics.PlayerPhysics;
import dev.mcresearch.protocolclient.session.ClientSession;
import dev.mcresearch.protocolclient.session.MovementController;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * Skriptgesteuerter grafischer Integrationstest (docs/26 §1). Ersetzt die echte Tastatur/Maus durch denselben
 * Eingabeweg (Tastenzustand → {@link MovementController#setManualInput}, Blickrichtung → {@code look}); alles andere
 * (Netzwerk, Physik, Meshing, Zeichnen) läuft wie im normalen Betrieb.
 *
 * <pre>
 *   wait-play                     bis Play-Zustand, Spieler positioniert und Chunks gezeichnet
 *   wait &lt;s&gt;                       Sekunden warten (Rendering läuft weiter)
 *   keys &lt;f|b|l|r|j…|-&gt; &lt;s&gt;        Tasten für s Sekunden halten
 *   look &lt;yaw&gt; &lt;pitch&gt;             Blickrichtung setzen
 *   console &lt;Serverbefehl&gt;          Befehl an die Konsole des lokalen Testservers (Datei console.in)
 *   screenshot &lt;datei.png&gt;          Bildschirmfoto
 *   mark &lt;text&gt;                    Markierung im Bericht (mit Position, FPS, Renderzahlen)
 *   wait-meshes                   bis alle Meshes gebaut und hochgeladen sind (max. 30 s)
 * </pre>
 */
final class Autotest implements AutoCloseable {
	private final List<String[]> steps;
	private final Path report;
	private final Path console;
	private int index;
	private long stepStart;
	private PlayerPhysics.Input input = PlayerPhysics.Input.NONE;
	private String status = "start";
	private boolean finished;
	private boolean attacking;
	private long useHoldUntil;
	private boolean externalInput;
	private int inventoryRequest = -1;
	private int stillFrames;
	private long verifySentNanos;
	private final String playerName;
	private long lastPeriodic;
	private final long started = System.nanoTime();

	private Autotest(final List<String[]> steps, final Path report, final Path console, final String playerName) {
		this.playerName = playerName;
		this.steps = steps;
		this.report = report;
		this.console = console;
	}

	static Autotest load(final Path script, final Path report, final String playerName) throws IOException {
		List<String[]> steps = new ArrayList<>();
		Path console = null;
		for (String line : Files.readAllLines(script, StandardCharsets.UTF_8)) {
			String t = line.strip();
			if (t.isEmpty() || t.startsWith("#")) {
				continue;
			}
			if (t.startsWith("server-console ")) {
				console = Path.of(t.substring(15).strip());
				continue;
			}
			int sp = t.indexOf(' ');
			steps.add(sp < 0 ? new String[]{t, ""} : new String[]{t.substring(0, sp), t.substring(sp + 1).strip()});
		}
		if (report != null) {
			Files.deleteIfExists(report);
		}
		return new Autotest(steps, report, console, playerName);
	}

	/** M11: gehaltene Tasten (Schritt hold) gelten, solange kein keys-Schritt läuft. */
	private PlayerPhysics.Input held = PlayerPhysics.Input.NONE;

	PlayerPhysics.Input input() {
		return this.input.any() ? this.input : this.held;
	}

	String status() {
		return this.status;
	}

	private static java.util.List<String> invDump(final ClientSession session) {
		java.util.List<String> out = new java.util.ArrayList<>();
		dev.mcresearch.protocolclient.session.PlayerInventory inv = session.inventory();
		dev.mcresearch.protocolclient.world.ItemRegistry items = dev.mcresearch.protocolclient.world.ItemRegistry.get();
		for (int index = 0; index <= 40; index++) {
			dev.mcresearch.protocolclient.world.ItemStack st = inv.slot(dev.mcresearch.protocolclient.session.PlayerInventory.menuSlotOfInventoryIndex(index));
			if (!st.isEmpty()) {
				StringBuilder sb = new StringBuilder("INV ").append(index).append(' ').append(items.name(st.itemId())).append(' ').append(st.count());
				new java.util.TreeMap<>(st.components()).forEach((k, v) -> sb.append(' ').append(k).append('=').append(v));
				out.add(sb.toString());
			}
		}
		dev.mcresearch.protocolclient.session.PlayerInventory.OpenContainer oc = inv.openContainer();
		if (oc != null) {
			for (int i = 0; i < oc.playerStart(); i++) {
				dev.mcresearch.protocolclient.world.ItemStack st = inv.currentSlot(i);
				if (!st.isEmpty()) {
					out.add("SLOT " + i + " " + items.name(st.itemId()) + " " + st.count());
				}
			}
		}
		return out;
	}

	/** Echte Eingaben an das Inventarfenster weitergeben (Schritt external-input on). */
	boolean externalInput() {
		return this.externalInput;
	}

	/** Autotest-Wunsch für das Inventarfenster: 1 öffnen, 0 schließen, −1 keiner (von GameView abgeholt). */
	int consumeInventoryRequest() {
		int r = this.inventoryRequest;
		this.inventoryRequest = -1;
		return r;
	}

	boolean finished() {
		return this.finished;
	}

	void step(final long now, final Window w, final ClientSession session, final ChunkRenderer renderer, final GameView.FrameStats fps,
		final MovementController.RenderState rs, final InventoryScreen inv) throws IOException {
		if (now - this.lastPeriodic > 10_000_000_000L) {
			this.lastPeriodic = now;
			mark("periodic", session, renderer, fps, rs);
		}
		if (this.finished) {
			return;
		}
		if (this.index >= this.steps.size()) {
			this.finished = true;
			this.input = PlayerPhysics.Input.NONE;
			mark("done", session, renderer, fps, rs);
			return;
		}
		if (this.stepStart == 0) {
			this.stepStart = now;
		}
		String[] s = this.steps.get(this.index);
		double elapsed = (now - this.stepStart) / 1e9;
		this.status = (this.index + 1) + "/" + this.steps.size() + " " + s[0] + " " + s[1];
		boolean done;
		switch (s[0]) {
			case "wait-play" -> {
				done = session.playing() && rs.ready() && renderer != null && renderer.stats().meshes() > 0;
				if (!done && elapsed > 60) {
					throw new IllegalStateException("autotest: no play state with meshes after 60 s");
				}
			}
			case "wait-meshes" -> done = renderer != null && renderer.pendingWork() == 0 && elapsed > 0.5 || elapsed > 30;
			case "wait" -> done = elapsed >= Double.parseDouble(s[1]);
			case "keys" -> {
				String[] a = s[1].split("\\s+");
				String k = a[0];
				this.input = PlayerPhysics.Input.parse(k); // M11: zusätzlich s (Schleichen), p (Sprinten)
				done = elapsed >= Double.parseDouble(a[1]);
				if (done) {
					this.input = PlayerPhysics.Input.NONE;
				}
			}
			case "look" -> {
				String[] a = s[1].split("\\s+");
				session.movement().look(Float.parseFloat(a[0]), Float.parseFloat(a[1]));
				done = true;
			}
			case "console" -> {
				if (this.console == null) {
					throw new IllegalStateException("autotest: console command without 'server-console <path>'");
				}
				Files.writeString(this.console, s[1] + "\n", StandardCharsets.UTF_8, StandardOpenOption.APPEND);
				done = true;
			}
			case "screenshot" -> {
				w.screenshot(Path.of(s[1]));
				done = true;
			}
			case "verify" -> {
				// erst wenn der Spieler steht (zwei gleiche Physikzustände, am Boden) – dann Client- und Serverposition festhalten
				// danach 2 s stillhalten: die Serverkonsole (tail -f) liest den Befehl mit bis zu ~1 s Verzögerung
				boolean still = rs.onGround() && rs.x() == rs.prevX() && rs.y() == rs.prevY() && rs.z() == rs.prevZ();
				this.stillFrames = still ? this.stillFrames + 1 : 0;
				if (this.verifySentNanos != 0) {
					done = now - this.verifySentNanos > 2_000_000_000L;
					if (done) {
						this.verifySentNanos = 0;
					}
				} else if (this.stillFrames >= 15 || elapsed > 15) {
					done = false;
					this.stillFrames = 0;
					this.verifySentNanos = now;
					mark("verify:" + s[1], session, renderer, fps, rs);
					if (this.console != null) {
						Files.writeString(this.console, "data get entity " + this.playerName + " Pos\n", StandardCharsets.UTF_8,
							StandardOpenOption.APPEND);
					}
				} else {
					done = false;
				}
			}
			// ---- M7: Interaktion (docs/29 §3) ----
			case "look-at" -> {
				String[] a = s[1].split("\\s+");
				double dx = Double.parseDouble(a[0]) - rs.x(), dy = Double.parseDouble(a[1]) - (rs.y() + (double) rs.eyeHeight()), dz = Double.parseDouble(a[2]) - rs.z();
				float yaw = (float) (Math.toDegrees(Math.atan2(dz, dx)) - 90.0);
				float pitch = (float) -Math.toDegrees(Math.atan2(dy, Math.sqrt(dx * dx + dz * dz)));
				session.movement().look(yaw, pitch);
				done = true;
			}
			case "attack" -> {
				// Linksklick und für n Sekunden halten
				if (!this.attacking) {
					this.attacking = true;
					session.interaction().attackClick();
					session.interaction().setAttackHeld(true);
				}
				done = elapsed >= Double.parseDouble(s[1]);
				if (done) {
					session.interaction().setAttackHeld(false);
					this.attacking = false;
				}
			}
			case "attack-start" -> {
				// Linksklick und Halten beginnen; endet erst mit attack-stop (Bildschirmfotos während des Abbaus)
				session.interaction().attackClick();
				session.interaction().setAttackHeld(true);
				done = true;
			}
			case "attack-stop" -> {
				session.interaction().setAttackHeld(false);
				done = true;
			}
			case "use" -> {
				session.interaction().useClick();
				done = true;
			}
			case "slot" -> {
				session.interaction().selectSlot(Integer.parseInt(s[1].trim()) - 1);
				done = true;
			}
			case "inventory" -> {
				this.inventoryRequest = s[1].trim().equals("open") ? 1 : 0;
				done = true;
			}
			// ---- M8: Inventarfenster über dieselben Maus-/Tastenmethoden wie die echte Eingabe (docs/33 §3) ----
			case "gui-click" -> {
				// gui-click <platz|out> <left|right> [shift]: Drücken und Loslassen an der Platzmitte
				String[] a = s[1].split("\s+");
				double[] p = a[0].equals("out") ? inv.outsidePoint() : inv.slotCenter(Integer.parseInt(a[0]));
				if (p == null) {
					throw new IllegalStateException("autotest: slot " + a[0] + " not in the current window");
				}
				int b = a.length > 1 && a[1].startsWith("r") ? 1 : 0;
				boolean shift = a.length > 2 && a[2].equals("shift");
				inv.move(session, p[0], p[1]);
				inv.press(session, b, p[0], p[1], shift, now);
				inv.release(session, b, p[0], p[1], shift);
				done = true;
			}
			case "gui-drag" -> {
				// gui-drag <left|right> <platz> …: Drücken am ersten Platz, über alle Plätze ziehen, am letzten loslassen
				String[] a = s[1].split("\s+");
				int b = a[0].startsWith("r") ? 1 : 0;
				double[] first = inv.slotCenter(Integer.parseInt(a[1]));
				inv.move(session, first[0], first[1]);
				inv.press(session, b, first[0], first[1], false, now);
				double[] last = first;
				for (int i = 1; i < a.length; i++) {
					last = inv.slotCenter(Integer.parseInt(a[i]));
					inv.move(session, last[0], last[1]);
				}
				inv.release(session, b, last[0], last[1], false);
				done = true;
			}
			case "gui-dbl" -> {
				// gui-dbl <platz>: zwei Linksklicks innerhalb der Doppelklickzeit
				double[] p = inv.slotCenter(Integer.parseInt(s[1].trim()));
				inv.move(session, p[0], p[1]);
				inv.press(session, 0, p[0], p[1], false, now);
				inv.release(session, 0, p[0], p[1], false);
				inv.press(session, 0, p[0], p[1], false, now + 100_000_000L);
				inv.release(session, 0, p[0], p[1], false);
				done = true;
			}
			case "gui-key" -> {
				// gui-key <platz> <1-9|f|q|ctrl-q>: Maus über den Platz, Taste
				String[] a = s[1].split("\s+");
				double[] p = inv.slotCenter(Integer.parseInt(a[0]));
				inv.move(session, p[0], p[1]);
				switch (a[1]) {
					case "f" -> inv.key(session, 40, false);
					case "q" -> inv.key(session, -1, false);
					case "ctrl-q" -> inv.key(session, -1, true);
					default -> inv.key(session, Integer.parseInt(a[1]) - 1, false);
				}
				done = true;
			}
			case "external-input" -> {
				// echte Maus-/Tastaturereignisse (von außen eingespielt) an das Inventarfenster weitergeben (docs/33 §3.2)
				this.externalInput = s[1].trim().equals("on");
				done = true;
			}
			case "export-slots" -> {
				// Fensterlage und Platzmitten (Bildschirmkoordinaten) für den externen Eingabetreiber
				int[] r = w.screenRect();
				java.util.List<String> out = new java.util.ArrayList<>();
				out.add("WINDOW " + r[0] + " " + r[1] + " " + r[2] + " " + r[3] + " " + r[4] + " " + r[5]);
				for (int slot = -1; slot < 100; slot++) {
					double[] c = slot < 0 ? inv.outsidePoint() : inv.slotCenter(slot);
					if (c != null) {
						long sx = Math.round(r[0] + c[0] * r[2] / (double) r[4]);
						long sy = Math.round(r[1] + c[1] * r[3] / (double) r[5]);
						out.add((slot < 0 ? "OUTSIDE" : "SLOT " + slot) + " " + sx + " " + sy);
					}
				}
				Files.write(Path.of(s[1].trim()), out, StandardCharsets.UTF_8);
				done = true;
			}
			case "wait-file" -> done = Files.exists(Path.of(s[1].trim())) || elapsed > 180;
			case "inv-history" -> {
				for (var e : session.inventoryController().history()) {
					GameView.appendReport(this.report, "INVHISTORY " + e.packets() + " (" + e.note() + ")");
				}
				done = true;
			}
			case "wait-inv" -> done = session.inventoryController().idle() && elapsed > 0.25 || elapsed > 5;
			case "inv-dump" -> {
				// Inventar (Inventory-Index) und geöffneten Container in den Bericht schreiben (Serverabgleich im Skript)
				for (String line : invDump(session)) {
					GameView.appendReport(this.report, "INVDUMP " + s[1].trim() + " " + line);
				}
				done = true;
			}
			case "wait-disconnect" -> done = !session.playing() || elapsed > 30;
			// ---- M9: Entities (docs/37 §3) ----
			// ---- M10: Kampf, Gesundheit, Tod (docs/41 §6) ----
			case "wait-dead" -> done = session.survival().dead() && GameView.DEATH_SCREEN || elapsed > 20;
			case "wait-alive" -> done = !session.survival().dead() && session.survival().health() > 0 || elapsed > 20;
			case "death-click" -> {
				// Klick in die Mitte des Knopfs „Wiederbeleben“ über die Mausereignis-Verarbeitung von GameView
				float[] b = GameView.RESPAWN_BUTTON;
				GameView.SYNTHETIC_MOUSE.add(new Window.MouseEvent(true, 0, b[0] + b[2] / 2, b[1] + b[3] / 2, 0, now));
				GameView.SYNTHETIC_MOUSE.add(new Window.MouseEvent(false, 0, b[0] + b[2] / 2, b[1] + b[3] / 2, 0, now));
				done = true;
			}
			case "survival-dump" -> {
				dev.mcresearch.protocolclient.session.PlayerSurvivalState.View v = session.survival().view(0.0F);
				GameView.appendReport(this.report, String.format(java.util.Locale.ROOT,
					"SURVIVAL %s health=%s max=%s absorption=%s food=%d saturation=%s armor=%s dead=%s deaths=%d respawns=%d effects=%s lastDamage=%s deathScreen=%s message=%s",
					s[1].trim(), v.health(), v.maxHealth(), v.absorption(), v.food(), v.saturation(), v.armor(), v.dead(), v.deaths(), v.respawnsRequested(),
					v.effects().stream().map(e -> e.name() + ":" + e.amplifier()).toList(), v.lastDamageType(), GameView.DEATH_SCREEN, v.deathMessage().replace(' ', '_')));
				done = true;
			}
			case "use-hold" -> {
				// Benutzen-Taste halten (Essen) für s[1] Sekunden, dann loslassen
				if (this.useHoldUntil == 0) {
					session.interaction().useClick();
					session.interaction().setUseHeld(true);
					this.useHoldUntil = now + (long) (Double.parseDouble(s[1].trim()) * 1e9);
				}
				done = now >= this.useHoldUntil;
				if (done) {
					session.interaction().setUseHeld(false);
					this.useHoldUntil = 0;
				}
			}
			case "wait-entities" -> done = session.entities().size() >= Integer.parseInt(s[1].trim()) && elapsed > 0.5 || elapsed > 20;
			case "entity-dump" -> {
				for (String line : dev.mcresearch.protocolclient.cli.EntityCommands.dump(session, null)) {
					GameView.appendReport(this.report, "EDUMP " + s[1].trim() + " " + line);
				}
				done = true;
			}
			case "entity-query" -> {
				// Client-Zustand in den Bericht, danach je UUID "data get entity" an die Serverkonsole (Auswertung: m9-compare.js gfx)
				List<String> dump = dev.mcresearch.protocolclient.cli.EntityCommands.dump(session, null);
				for (String line : dump) {
					GameView.appendReport(this.report, "EDUMP " + s[1].trim() + " " + line);
				}
				if (this.console != null) {
					StringBuilder cmds = new StringBuilder("say CHECK " + s[1].trim() + "\n");
					for (String line : dump) {
						cmds.append("data get entity ").append(line.split(" ")[2]).append('\n');
					}
					Files.writeString(this.console, cmds.toString(), StandardCharsets.UTF_8, StandardOpenOption.APPEND);
				}
				done = true;
			}
			case "entity-target" -> {
				dev.mcresearch.protocolclient.entity.EntityPick.Target t = GameView.ENTITY_TARGET;
				GameView.appendReport(this.report, "ETARGET " + s[1].trim() + " " + (t == null ? "none" : t.kind() + (t.entity() != null
					? " " + t.entity().uuid() + " " + t.entity().type().name() : "") + String.format(java.util.Locale.ROOT, " dist=%.3f", t.distance())));
				done = true;
			}
			case "entity-frame" -> {
				EntityRenderer.FrameInfo f = GameView.ENTITY_FRAME;
				dev.mcresearch.protocolclient.entity.EntityManager.Stats st = session.entities().stats();
				GameView.appendReport(this.report, String.format(java.util.Locale.ROOT,
					"EFRAME %s entities=%d drawn=%d culled=%d debug=%d draws=%d entityCpuMs=%.3f fps=%.0f low1=%.0f avgMs=%.2f handleAvgUs=%.2f handleMaxUs=%.1f tickAvgUs=%.2f tickMaxUs=%.1f heapMB=%d",
					s[1].trim(), f.total(), f.drawn(), f.culled(), f.debugBoxes(), f.drawCalls(), f.cpuNanos() / 1e6, fps.fps(), fps.low1(), fps.avgMs(),
					st.packetsHandled == 0 ? 0.0 : st.handleNanos / 1000.0 / st.packetsHandled, st.maxHandleNanos / 1000.0,
					st.ticks == 0 ? 0.0 : st.tickNanos / 1000.0 / st.ticks, st.maxTickNanos / 1000.0,
					(Runtime.getRuntime().totalMemory() - Runtime.getRuntime().freeMemory()) >> 20));
				done = true;
			}
			case "look-dump" -> {
				// M14: Darstellungsplan je Entity (Modell-Layer, Textur, Farbe, Schichtart) oder DEBUG; GPU-Bestand des Entity-Renderers
				java.util.Map<Integer, String> looks = new java.util.TreeMap<>(GameView.ENTITY_LOOKS);
				for (java.util.Map.Entry<Integer, String> e : looks.entrySet()) {
					GameView.appendReport(this.report, "ELOOK " + s[1].trim() + " " + e.getKey() + " " + e.getValue());
				}
				GameView.appendReport(this.report, "EGPU " + s[1].trim() + " " + GameView.ENTITY_GPU + " glResources=" + GlResources.summary());
				done = true;
			}
			case "ride-dump" -> {
				// M14: Fahrzeug des eigenen Spielers, Sitzposition, Auf-/Absteigen, letzte Fahrzeug-Ticks
				dev.mcresearch.protocolclient.entity.EntityManager em = session.entities();
				dev.mcresearch.protocolclient.entity.EntityManager.Stats st = em.stats();
				double[] seat = em.ownSeat(1.0F);
				var mv = session.movement();
				GameView.appendReport(this.report, String.format(java.util.Locale.ROOT,
					"ERIDE %s vehicle=%d seat=%s player=(%.4f %.4f %.4f) yaw=%.2f mounts=%d dismounts=%d passengerPackets=%d cycles=%d lateLinks=%d ignoredLocal=%d corrections=%d pushes=%d",
					s[1].trim(), em.ownVehicle(), seat == null ? "-" : String.format(java.util.Locale.ROOT, "(%.4f %.4f %.4f)", seat[0], seat[1], seat[2]),
					session.player().x(), session.player().y(), session.player().z(), session.player().yRot(), st.ownMounts, st.ownDismounts,
					st.passengerPackets, st.passengerCycles, st.lateLinks, st.ignoredLocal, mv.vehicleCorrections(), mv.pushes()));
				java.util.List<String> tr = mv.vehicleTrace();
				for (String line : tr.subList(Math.max(0, tr.size() - 3), tr.size())) {
					GameView.appendReport(this.report, "ERIDE " + s[1].trim() + " trace " + line);
				}
				for (dev.mcresearch.protocolclient.entity.EntityManager.View v : em.snapshot(1.0F, 0)) {
					if (v.vehicle() >= 0 || v.passengers().length > 0) {
						GameView.appendReport(this.report, String.format(java.util.Locale.ROOT, "ERIDE %s entity %d %s vehicle=%d passengers=%s render=(%.4f %.4f %.4f)",
							s[1].trim(), v.id(), v.type().shortName(), v.vehicle(), java.util.Arrays.toString(v.passengers()), v.rx(), v.ry(), v.rz()));
					}
				}
				done = true;
			}
			case "hold" -> {
				// M11: hold <tasten>|- : Tasten dauerhaft halten (z. B. Schleichen während eines Bildschirmfotos)
				this.held = PlayerPhysics.Input.parse(s[1].trim());
				done = true;
			}
			case "motion-dump" -> {
				// M11: Bewegungs- und Sichtzustand (Pose, Sprint, Schleichen, Augen-/Kamerahöhe, Fluid, Sichtweite, Bildschirm)
				dev.mcresearch.protocolclient.physics.PlayerPhysics p = session.movement().physics();
				if (p != null) {
					dev.mcresearch.protocolclient.physics.PlayerPhysics.MotionState ms = p.motionState();
					GameView.appendReport(this.report, String.format(java.util.Locale.ROOT,
						"MOTION %s pos=(%.4f,%.4f,%.4f) pose=%s sprinting=%s crouching=%s shift=%s eye=%.4f cameraEye=%.4f inWater=%s underWater=%s eyeInWater=%s"
							+ " eyeInLava=%s swimming=%s waterHeight=%.4f waterVision=%.3f cameraFluid=%d screenOpen=%s sprintCommands=%d",
						s[1].trim(), p.x(), p.y(), p.z(), ms.pose(), ms.sprinting(), ms.crouching(), ms.shift(), p.eyeHeight(), rs.eyeHeight(), ms.inWater(),
						ms.underWater(), p.eyeInWater(), p.eyeInLava(), ms.swimming(), ms.waterHeight(), p.waterVision(), GameView.CAMERA_FLUID,
						GameView.SCREEN_OPEN, session.movement().sprintCommands()));
				}
				done = true;
			}
			case "append" -> {
				// "append <datei> <text>": Zeile an eine Datei anhängen (z. B. Befehl an den zweiten Spieler)
				String[] t = s[1].trim().split(" ", 2);
				Files.writeString(Path.of(t[0]), t[1] + System.lineSeparator(), StandardCharsets.UTF_8, StandardOpenOption.CREATE, StandardOpenOption.APPEND);
				done = true;
			}
			case "mark" -> {
				mark(s[1], session, renderer, fps, rs);
				done = true;
			}
			case "perf-begin" -> {
				// M12 Phase K: Messfenster für Bildzeiten (CPU/GPU), Draw Calls, transparente Objekte, VRAM
				GameView.PROFILER.begin(now);
				done = true;
			}
			case "perf-end" -> {
				ChunkRenderer.Stats st = renderer == null ? null : renderer.stats();
				Runtime rt = Runtime.getRuntime();
				String line = String.format(Locale.ROOT, "perf: %s %s meshes=%s gpuMeshMB=%s builds=%s avgBuildMs=%s maxBuildMs=%s heapMB=%d", s[1],
					GameView.PROFILER.report(now), st == null ? "-" : st.meshes(), st == null ? "-" : String.format(Locale.ROOT, "%.1f", st.gpuBytes() / 1048576.0),
					st == null ? "-" : st.builds(), st == null ? "-" : String.format(Locale.ROOT, "%.2f", st.avgBuildMs()),
					st == null ? "-" : String.format(Locale.ROOT, "%.1f", st.maxBuildMs()), (rt.totalMemory() - rt.freeMemory()) >> 20);
				System.out.println("[AUTOTEST] " + line);
				GameView.appendReport(this.report, line);
				done = true;
			}
			case "env-dump" -> {
				// M12: Umgebung, Nebel, Partikel, Wolken, Niederschlag, Animation, Lightmap (docs/49 §6)
				String line = "env: " + s[1] + " " + GameView.envLine(session) + " animBytes=" + GameView.ANIMATION_BYTES + " biome="
					+ (session.world() == null ? "-" : GameView.biomeAt(session.world(), rs.x(), rs.y() + 1.62, rs.z()));
				var envState = GameView.ENV;
				if (session.world() != null && envState != null) {
					// Färbungsstufe wie die Referenz (ScreenshotReferenceClient TINT): Boden 3 Blöcke vor dem Spieler (+z), Biom ungemittelt
					int fx = (int) Math.floor(rs.x()), fy = (int) Math.floor(rs.y() + 0.5) - 1, fz = (int) Math.floor(rs.z() + 3);
					String fb = GameView.biomeAt(session.world(), fx, fy, fz);
					var bd = envState.data().biome(fb);
					line += String.format(Locale.ROOT, " floor=%d %d %d floorBiome=%s grassBiome=%06x", fx, fy, fz, fb,
						bd == null ? 0 : new dev.mcresearch.protocolclient.view.env.BiomeColors(envState.data()).grass(bd, fx, fz) & 0xFFFFFF);
				}
				System.out.println("[AUTOTEST] " + line);
				GameView.appendReport(this.report, line);
				done = true;
			}
			case "hud-dump" -> {
				String line = "hud: " + s[1].trim() + " " + GameView.HUD_INFO;
				System.out.println("[AUTOTEST] " + line);
				GameView.appendReport(this.report, line);
				done = true;
			}
			case "hide-gui" -> {
				GameView.HIDE_GUI = s[1].trim().equals("on");
				done = true;
			}
			default -> throw new IllegalArgumentException("autotest: unknown step " + s[0]);
		}
		if (done) {
			this.index++;
			this.stepStart = 0;
		}
	}

	private void mark(final String label, final ClientSession session, final ChunkRenderer renderer, final GameView.FrameStats fps,
		final MovementController.RenderState rs) {
		Runtime rt = Runtime.getRuntime();
		ChunkRenderer.Stats st = renderer == null ? null : renderer.stats();
		String line = String.format(Locale.ROOT,
			"%7.1f s  %-24s pos=(%.4f, %.4f, %.4f) ground=%s fps=%.0f low1=%.0f frames=%d meshes=%s draws=%s verts=%s gpuMB=%s builds=%s "
				+ "avgBuildMs=%s maxBuildMs=%s heapMB=%d chunks=%s corrections=%d keepAlives=%d maxGapMs=%d playing=%s",
			(System.nanoTime() - this.started) / 1e9, label, rs.x(), rs.y(), rs.z(), rs.onGround(), fps.fps(), fps.low1(), fps.frames(),
			st == null ? "-" : st.meshes(), st == null ? "-" : st.drawCalls(), st == null ? "-" : st.drawnVertices(),
			st == null ? "-" : String.format(Locale.ROOT, "%.1f", st.gpuBytes() / 1048576.0), st == null ? "-" : st.builds(),
			st == null ? "-" : String.format(Locale.ROOT, "%.2f", st.avgBuildMs()), st == null ? "-" : String.format(Locale.ROOT, "%.1f", st.maxBuildMs()),
			(rt.totalMemory() - rt.freeMemory()) >> 20, session.world() == null ? "-" : session.world().loadedChunks(),
			session.movement().corrections(), session.stats().keepAlivesAnswered(), session.stats().maxKeepAliveGapMillis(), session.playing());
		System.out.println("[AUTOTEST] " + line);
		GameView.appendReport(this.report, line);
	}

	void close(final ClientSession session, final GameView.FrameStats fps) {
		GameView.appendReport(this.report, String.format(Locale.ROOT, "end: finished=%s steps=%d/%d frames=%d minFps=%.0f playing=%s", this.finished,
			this.index, this.steps.size(), fps.frames(), fps.minFps(), session.playing()));
	}

	@Override
	public void close() {
	}
}
