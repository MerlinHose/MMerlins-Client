package dev.mcresearch.protocolclient.cli;

import dev.mcresearch.protocolclient.session.ClientSession;
import dev.mcresearch.protocolclient.session.MovementController;
import dev.mcresearch.protocolclient.session.PlayerState;
import dev.mcresearch.protocolclient.world.TerrainCache;
import dev.mcresearch.protocolclient.world.WorldState;
import java.io.BufferedReader;
import java.io.IOException;
import java.io.PrintStream;
import java.util.Locale;
import java.util.OptionalInt;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

/**
 * Steuerbarer Bewegungstest im Terminal (docs/15 §7). Befehle (eine Zeile je Befehl, auch per Pipe/Skript):
 * <pre>
 *   position                 bekannte Position (behauptet) und letzte Serverposition
 *   move &lt;dx&gt; &lt;dy&gt; &lt;dz&gt;      Bewegung über reguläre Ticks (Gehgeschwindigkeit, Schwerkraft, Stufen, Sprünge)
 *   look &lt;yaw&gt; &lt;pitch&gt;       Blickrichtung (Grad; pitch −90…90)
 *   status                   Verbindung, Tick-Zähler, Position, Korrekturen
 *   wait &lt;sekunden&gt;          warten (für Skripte)
 *   claim &lt;dx&gt; &lt;dy&gt; &lt;dz&gt;     TEST: ein einzelnes Positionspaket ohne Physik (prüft Serverkorrekturen)
 *   walk &lt;dir&gt; &lt;ticks&gt; [jump]  Tasten (f/b/l/r/fl/fr/bl/br) für n Ticks halten, danach ausrollen (M5)
 *   jump                     regulärer Sprung
 *   physics | collision | hitbox | trajectory [n]   Physikzustand, Kontakte, Hitbox, letzte Ticks
 *   compression              Transport- und Kompressionszähler
 *   block &lt;x&gt; &lt;y&gt; &lt;z&gt;      Blockzustand, Biom, Licht, Block-Entity (~ = eigene Position)
 *   chunk [&lt;cx&gt; &lt;cz&gt;]       Sektionen, Paletten, Heightmaps, Licht eines Chunks (Standard: eigener Chunk)
 *   world                    Dimension und Weltzähler
 *   nearby [r]               Nicht-Luft-Blöcke im Radius r (Standard 2, max. 8)
 *   blocks [&lt;cx&gt; &lt;cz&gt;]      Häufigkeit der Blockarten in einem Chunk
 *   pick &lt;x&gt; &lt;y&gt; &lt;z&gt; &lt;yaw&gt; &lt;pitch&gt;   Blockauswahl (BlockRaycast, Reichweiten aus update_attributes) (M7)
 *   look-at &lt;x&gt; &lt;y&gt; &lt;z&gt;     Blick auf einen Punkt (M7)
 *   target                   aktuelles Auswahlziel (BlockHitResult)
 *   dig &lt;ticks&gt;              Linksklick halten bis Serveränderung oder n Ticks (Abbau, docs/27 §3)
 *   use                      ein Rechtsklick (Platzieren/Benutzen, docs/27 §4)
 *   slot &lt;1-9&gt;               Hotbar-Platz wählen (set_carried_item)
 *   inventory | interaction  Inventar (Server-Sicht) bzw. Abbau-/Sequenzstatus
 *   picks &lt;datei&gt;           alle pick-Zeilen einer Szenariodatei (analysis/tools/m7-picks.txt), Ausgabe PICK …
 *   help, quit
 * </pre>
 */
public final class TerminalController {
	private static final long JOIN_TIMEOUT_SECONDS = 60;
	static final String HELP = "position | move <dx> <dy> <dz> | walk <f|b|l|r|fl|fr|bl|br> <ticks> [jump], keys <fblrjsp> <ticks> | jump | look <yaw> <pitch> | physics | collision"
		+ " | hitbox | trajectory [n] | status | compression | block <x> <y> <z> | chunk [<cx> <cz>] | world | nearby [r] | blocks [<cx> <cz>] | pick <x> <y> <z> <yaw> <pitch> | picks <file>"
		+ " | look-at <x> <y> <z> | target | dig <ticks> | use | slot <1-9> | inventory | interaction"
		+ " | entities | edump [type] | etarget | players | estats"
		+ " | survival | hit [strength] | hitnext <x> <y> <z> | hitfall <x> <y> <z> | eat <ticks> | usehold on|off | respawn | autorespawn on|off | slog"
		+ " | inv | invdump | click <slot|out> [left|right|middle] [shift] | drag <button> <slots…> | dblclick <slot> [shift] | swap <slot> <1-9|f>"
		+ " | throw <slot> [all] | clone <slot> | close | drop [all]"
		+ " | wait <s> | claim <dx> <dy> <dz> | quit";

	private final ClientSession session;
	private final BufferedReader in;
	private final PrintStream out;

	public TerminalController(final ClientSession session, final BufferedReader in, final PrintStream out) {
		this.session = session;
		this.in = in;
		this.out = out;
	}

	/** Wartet auf den Play-Zustand und verarbeitet dann Befehle bis {@code quit}, Eingabeende oder Verbindungsende. */
	public void run() throws IOException, InterruptedException {
		long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(JOIN_TIMEOUT_SECONDS);
		while (!(this.session.playing() && this.session.movement().ready())) {
			if (this.session.stopRequested() || System.nanoTime() > deadline) {
				this.out.println("! not in play state – terminal not started");
				return;
			}
			Thread.sleep(50);
		}
		this.out.println("> ready – commands: " + HELP);
		String line;
		while ((line = this.in.readLine()) != null) {
			line = line.strip();
			if (line.isEmpty() || line.startsWith("#")) {
				continue;
			}
			this.out.println("> " + line);
			if (!this.session.playing()) {
				this.out.println("! connection no longer in play state");
				return;
			}
			try {
				if (!execute(line.split("\\s+"))) {
					return;
				}
			} catch (IllegalArgumentException e) {
				this.out.println("! " + e.getMessage());
			}
		}
		this.out.println("> end of input – disconnecting");
		this.session.requestStop("end of terminal input");
	}

	private boolean execute(final String[] a) throws InterruptedException {
		MovementController m = this.session.movement();
		switch (a[0].toLowerCase(Locale.ROOT)) {
			case "position" -> this.out.println("  " + describePosition());
			case "status" -> {
				this.out.println(String.format(Locale.ROOT, "  state=%s playing=%s ticks=%d movementTicks=%d moving=%s corrections=%d keepAlives=%d",
					this.session.state(), this.session.playing(), this.session.stats().ticksSent(), m.ticks(), m.moving(), m.corrections(),
					this.session.stats().keepAlivesAnswered()));
				this.out.println("  " + describePosition());
				this.out.println("  " + this.session.transportStats());
			}
			case "compression" -> this.out.println("  " + this.session.transportStats());
			case "move" -> {
				requireArgs(a, 4);
				report(m.move(parse(a[1]), parse(a[2]), parse(a[3])));
			}
			case "claim" -> {
				requireArgs(a, 4);
				report(m.claim(parse(a[1]), parse(a[2]), parse(a[3])));
			}
			case "look" -> {
				requireArgs(a, 3);
				m.look((float) parse(a[1]), (float) parse(a[2]));
				Thread.sleep(150); // ein Tick sendet move_player_rot
				this.out.println("  looking yaw=" + a[1] + " pitch=" + a[2]);
			}
			// ---- M8: Inventar und Container (docs/31 §5) ----
			case "mark" -> this.out.println("MARK " + (a.length > 1 ? a[1] : "")); // Handshake für Testskripte
			case "inv" -> InventoryCommands.show(this.session).forEach(l -> this.out.println("  " + l));
			case "invdump" -> InventoryCommands.dump(this.session).forEach(l -> this.out.println("  " + l));
			case "cdump" -> InventoryCommands.containerDump(this.session).forEach(l -> this.out.println("  " + l));
			case "entities" -> EntityCommands.list(this.session).forEach(l -> this.out.println("  " + l));
			case "edump" -> EntityCommands.dump(this.session, a.length > 1 ? a[1] : null).forEach(l -> this.out.println("  " + l));
			case "players" -> EntityCommands.players(this.session).forEach(l -> this.out.println("  " + l));
			case "survival" -> CombatCommands.survival(this.session).forEach(l -> this.out.println("  " + l));
			case "hit" -> CombatCommands.hit(this.session, a.length > 1 ? Float.parseFloat(a[1]) : 0.0F).forEach(l -> this.out.println("  " + l));
			case "hitfall" -> {
				requireArgs(a, 4);
				CombatCommands.hitFall(this.session, parse(a[1]), parse(a[2]), parse(a[3])).forEach(l -> this.out.println("  " + l));
			}
			case "hitpair" -> {
				requireArgs(a, 7);
				double[] pts = new double[6];
				for (int i = 0; i < 6; i++) {
					pts[i] = parse(a[i + 1]);
				}
				CombatCommands.hitPair(this.session, pts).forEach(l -> this.out.println("  " + l));
			}
			case "hitnext" -> {
				requireArgs(a, 4);
				CombatCommands.hitNext(this.session, parse(a[1]), parse(a[2]), parse(a[3])).forEach(l -> this.out.println("  " + l));
			}
			case "eat" -> CombatCommands.eat(this.session, a.length > 1 ? integer(a[1]) : 40).forEach(l -> this.out.println("  " + l));
			case "usehold" -> {
				// Benutzungstaste drücken und halten (on) bzw. loslassen (off), ohne zu warten – z. B. Schild erhoben halten
				boolean on = a.length > 1 && a[1].equals("on");
				if (on) {
					this.session.interaction().useClick();
				}
				this.session.interaction().setUseHeld(on);
				Thread.sleep(300);
				this.out.println("  usehold " + (on ? "on" : "off") + " using=" + this.session.survival().view(0.0F).usingItem());
			}
			case "respawn" -> this.out.println("  " + CombatCommands.respawn(this.session));
			case "autorespawn" -> {
				this.session.setAutoRespawn(a.length > 1 && a[1].equals("on"));
				this.out.println("  autoRespawn=" + this.session.autoRespawn());
			}
			case "slog" -> CombatCommands.log(this.session).forEach(l -> this.out.println("  " + l));
			case "etarget" -> this.out.println("  " + EntityCommands.target(this.session));
			case "estats" -> EntityCommands.stats(this.session).forEach(l -> this.out.println("  " + l));
			case "click" -> {
				if (a.length < 2 || a.length > 4) {
					throw new IllegalArgumentException("usage: click <slot|out> [left|right|middle] [shift]");
				}
				int slot = a[1].equals("out") ? dev.mcresearch.protocolclient.session.InventoryController.OUTSIDE : integer(a[1]);
				int b = a.length > 2 ? InventoryCommands.button(a[2]) : 0;
				boolean shift = a.length > 3 && a[3].equalsIgnoreCase("shift");
				invRun(new dev.mcresearch.protocolclient.session.InventoryController.Click(this.session.inventory().currentId(), slot, b, shift));
			}
			case "drag" -> {
				if (a.length < 3) {
					throw new IllegalArgumentException("usage: drag <left|right|middle> <slot> [slot …]");
				}
				java.util.List<Integer> slots = new java.util.ArrayList<>();
				for (int i = 2; i < a.length; i++) {
					slots.add(integer(a[i]));
				}
				invRun(new dev.mcresearch.protocolclient.session.InventoryController.Drag(this.session.inventory().currentId(), slots, InventoryCommands.button(a[1])));
			}
			case "dblclick" -> {
				if (a.length < 2) {
					throw new IllegalArgumentException("usage: dblclick <slot> [shift]");
				}
				invRun(new dev.mcresearch.protocolclient.session.InventoryController.DoubleClick(this.session.inventory().currentId(), integer(a[1]),
					a.length > 2 && a[2].equalsIgnoreCase("shift")));
			}
			case "swap" -> {
				requireArgs(a, 3);
				int key = a[2].equalsIgnoreCase("f") ? 40 : integer(a[2]) - 1;
				invRun(new dev.mcresearch.protocolclient.session.InventoryController.Swap(this.session.inventory().currentId(), integer(a[1]), key));
			}
			case "throw" -> {
				if (a.length < 2) {
					throw new IllegalArgumentException("usage: throw <slot> [all]");
				}
				invRun(new dev.mcresearch.protocolclient.session.InventoryController.Throw(this.session.inventory().currentId(), integer(a[1]),
					a.length > 2 && a[2].equalsIgnoreCase("all")));
			}
			case "clone" -> {
				requireArgs(a, 2);
				invRun(new dev.mcresearch.protocolclient.session.InventoryController.Clone(this.session.inventory().currentId(), integer(a[1])));
			}
			case "clickc" -> {
				// Test (Phase G): Klick mit ausdrücklicher Container-ID (z. B. eines bereits geschlossenen Containers)
				requireArgs(a, 3);
				invRun(new dev.mcresearch.protocolclient.session.InventoryController.Click(integer(a[1]), integer(a[2]), 0, false));
			}
			case "burst" -> {
				// Test (Phase H): n Linksklicks abwechselnd auf zwei Plätze sofort in die Warteschlange, ohne Warten
				requireArgs(a, 4);
				int n = integer(a[1]);
				if (n < 1 || n > 1000) {
					throw new IllegalArgumentException("burst 1..1000");
				}
				int cid = this.session.inventory().currentId();
				long t0 = System.nanoTime();
				int accepted = 0;
				for (int i = 0; i < n; i++) {
					while (this.session.inventoryController().pending() >= 60) {
						Thread.sleep(5);
					}
					if (this.session.inventoryController().submit(new dev.mcresearch.protocolclient.session.InventoryController.Click(cid, integer(a[2 + (i & 1)]), 0, false))) {
						accepted++;
					}
				}
				this.out.println("  burst queued " + accepted + "/" + n + " gestures");
				if (a.length == 4) {
					while (!this.session.inventoryController().idle()) {
						Thread.sleep(10);
					}
					Thread.sleep(150);
					this.out.println(String.format(java.util.Locale.ROOT, "  burst done in %.1f s, clicks sent %d, dropped %d", (System.nanoTime() - t0) / 1e9,
						this.session.inventoryController().clicksSent(), this.session.inventoryController().dropped()));
				}
			}
			case "burstq" -> {
				// wie burst, aber ohne auf die Ausführung zu warten (Disconnect während laufender Aktionen)
				requireArgs(a, 4);
				int cid = this.session.inventory().currentId();
				for (int i = 0; i < integer(a[1]); i++) {
					this.session.inventoryController().submit(new dev.mcresearch.protocolclient.session.InventoryController.Click(cid, integer(a[2 + (i & 1)]), 0, false));
				}
				this.out.println("  queued, pending " + this.session.inventoryController().pending());
			}
			case "resync" -> invRun(new dev.mcresearch.protocolclient.session.InventoryController.Resync(this.session.inventory().currentId()));
			case "close" -> invRun(new dev.mcresearch.protocolclient.session.InventoryController.Close(this.session.inventory().currentId()));
			case "drop" -> invRun(new dev.mcresearch.protocolclient.session.InventoryController.DropSelected(this.session.inventory().currentId(),
				a.length > 1 && a[1].equalsIgnoreCase("all")));
			case "lightdump" -> {
				// Licht eines Quaders (je Zelle: L x y z himmel block), für den Abgleich mit dem Server (docs/29 §3.3)
				requireArgs(a, 7);
				int x1 = integer(a[1]), y1 = integer(a[2]), z1 = integer(a[3]), x2 = integer(a[4]), y2 = integer(a[5]), z2 = integer(a[6]);
				if ((long) (Math.abs(x2 - x1) + 1) * (Math.abs(y2 - y1) + 1) * (Math.abs(z2 - z1) + 1) > 20000) {
					throw new IllegalArgumentException("lightdump: at most 20000 cells");
				}
				WorldState w = this.session.world();
				if (w == null) {
					throw new IllegalArgumentException("no world model");
				}
				StringBuilder sb = new StringBuilder();
				synchronized (w) {
					for (int y = Math.min(y1, y2); y <= Math.max(y1, y2); y++) {
						for (int z = Math.min(z1, z2); z <= Math.max(z1, z2); z++) {
							for (int x = Math.min(x1, x2); x <= Math.max(x1, x2); x++) {
								sb.append("  L ").append(x).append(' ').append(y).append(' ').append(z).append(' ').append(w.light(true, x, y, z)).append(' ')
									.append(w.light(false, x, y, z)).append('\n');
							}
						}
					}
				}
				this.out.print(sb);
				this.out.println("  lightdump done");
			}
			case "look-at" -> {
				requireArgs(a, 4);
				float[] r = lookAt(parse(a[1]), parse(a[2]), parse(a[3]));
				m.look(r[0], r[1]);
				Thread.sleep(150);
				this.out.println(String.format(Locale.ROOT, "  looking yaw=%.3f pitch=%.3f", r[0], r[1]));
			}
			case "target" -> {
				Thread.sleep(60); // Auswahl des nächsten Ticks
				this.out.println("  " + InteractionCommands.target(this.session));
			}
			case "dig" -> {
				// Linksklick + Halten für höchstens n Ticks; endet früher, sobald der Server den Zielblock geändert hat (block_update)
				requireArgs(a, 2);
				int n = integer(a[1]);
				if (n < 1 || n > 20 * 60) {
					throw new IllegalArgumentException("dig 1..1200 ticks");
				}
				InteractionCommands.dig(this.session, n).forEach(l -> this.out.println("  " + l));
			}
			case "use" -> {
				InteractionCommands.use(this.session).forEach(l -> this.out.println("  " + l));
			}
			case "slot" -> {
				requireArgs(a, 2);
				int s = integer(a[1]);
				if (s < 1 || s > 9) {
					throw new IllegalArgumentException("slot 1..9");
				}
				this.session.interaction().selectSlot(s - 1);
				Thread.sleep(200); // set_carried_item im nächsten Tick
				this.out.println("  selected hotbar slot " + s + ": " + this.session.inventory().hotbar(s - 1).describe(dev.mcresearch.protocolclient.world.ItemRegistry.get()));
			}
			case "inventory" -> InteractionCommands.inventory(this.session).forEach(l -> this.out.println("  " + l));
			case "interaction" -> InteractionCommands.status(this.session).forEach(l -> this.out.println("  " + l));
			case "wait" -> {
				requireArgs(a, 2);
				double s = parse(a[1]);
				if (s < 0 || s > 3600) {
					throw new IllegalArgumentException("wait 0..3600 s");
				}
				Thread.sleep((long) (s * 1000));
			}
			case "block" -> {
				requireArgs(a, 4);
				PlayerState p = this.session.player();
				int x = coord(a[1], p.x());
				int y = coord(a[2], p.y());
				int z = coord(a[3], p.z());
				print(w -> WorldCommands.block(w, x, y, z));
			}
			case "chunk", "blocks" -> {
				if (a.length != 1 && a.length != 3) {
					throw new IllegalArgumentException("usage: " + a[0] + " [<chunkX> <chunkZ>]");
				}
				PlayerState p = this.session.player();
				int cx = a.length == 3 ? integer(a[1]) : Math.floorDiv((int) Math.floor(p.x()), 16);
				int cz = a.length == 3 ? integer(a[2]) : Math.floorDiv((int) Math.floor(p.z()), 16);
				boolean summary = a[0].equalsIgnoreCase("chunk");
				print(w -> summary ? WorldCommands.chunk(w, cx, cz) : WorldCommands.blocks(w, cx, cz, 25));
			}
			case "world" -> print(w -> WorldCommands.world(w, this.session.dimensionKey()));
			case "nearby" -> {
				int r = a.length > 1 ? integer(a[1]) : 2;
				PlayerState p = this.session.player();
				print(w -> WorldCommands.nearby(w, p.x(), p.y(), p.z(), r, 40));
			}
			case "pick" -> {
				requireArgs(a, 6);
				String r = pickLine("pick", parse(a[1]), parse(a[2]), parse(a[3]), (float) parse(a[4]), (float) parse(a[5]));
				this.out.println("  " + r);
			}
			case "epicks" -> {
				// M9: wie picks, aber mit Entity-Auswahl (EntityPick); Format wie PickReferenceClient (docs/37 §2)
				requireArgs(a, 2);
				java.util.List<String> elines;
				try {
					elines = java.nio.file.Files.readAllLines(java.nio.file.Path.of(a[1]), java.nio.charset.StandardCharsets.UTF_8);
				} catch (IOException e) {
					throw new IllegalArgumentException("cannot read " + a[1] + ": " + e.getMessage());
				}
				for (String l : elines) {
					String[] t = l.strip().split("\s+");
					if (t.length == 7 && t[0].equals("pick")) {
						this.out.println("  PICK " + entityPickLine(t[1], parse(t[2]), parse(t[3]), parse(t[4]), (float) parse(t[5]), (float) parse(t[6])));
					}
				}
			}
			case "picks" -> {
				requireArgs(a, 2);
				java.util.List<String> lines;
				try {
					lines = java.nio.file.Files.readAllLines(java.nio.file.Path.of(a[1]), java.nio.charset.StandardCharsets.UTF_8);
				} catch (IOException e) {
					throw new IllegalArgumentException("cannot read " + a[1] + ": " + e.getMessage());
				}
				for (String l : lines) {
					String[] t = l.strip().split("\\s+");
					if (t.length == 7 && t[0].equals("pick")) {
						this.out.println("  PICK " + pickLine(t[1], parse(t[2]), parse(t[3]), parse(t[4]), (float) parse(t[5]), (float) parse(t[6])));
					}
				}
			}
			case "walk" -> {
				if (a.length != 3 && !(a.length == 4 && a[3].equalsIgnoreCase("jump"))) {
					throw new IllegalArgumentException("usage: walk <f|b|l|r|fl|fr|bl|br> <ticks> [jump]");
				}
				report(m.walk(PhysicsCommands.parseDirection(a[1], a.length == 4), integer(a[2])));
			}
			case "keys" -> {
				// M11: Tastenkürzel wie die Teststrecken (f b l r j, s Schleichen, p Sprinten), z. B. "keys fp 40"
				if (a.length != 3 || !a[1].matches("[fblrjsp-]+")) {
					throw new IllegalArgumentException("usage: keys <f|b|l|r|j|s|p…> <ticks>");
				}
				report(m.walk(dev.mcresearch.protocolclient.physics.PlayerPhysics.Input.parse(a[1]), integer(a[2])));
			}
			case "jump" -> report(m.jump());
			case "physics" -> printPhysics(p -> PhysicsCommands.physics(p, m.lastInput()));
			case "collision" -> printPhysics(p -> PhysicsCommands.collision(p, dev.mcresearch.protocolclient.world.BlockRegistry.get()));
			case "hitbox" -> printPhysics(PhysicsCommands::hitbox);
			case "trajectory" -> {
				int n = a.length > 1 ? integer(a[1]) : 20;
				if (n < 1 || n > MovementController.TRAJECTORY_SIZE) {
					throw new IllegalArgumentException("trajectory 1.." + MovementController.TRAJECTORY_SIZE);
				}
				PhysicsCommands.trajectory(m.trajectory(), n).forEach(l -> this.out.println("  " + l));
			}
			case "help" -> this.out.println("  " + HELP);
			case "quit", "exit" -> {
				this.out.println("  disconnecting");
				this.session.requestStop("quit command");
				return false;
			}
			default -> this.out.println("! unknown command " + a[0] + " (help)");
		}
		return true;
	}

	/**
	 * Eine Auswahlzeile im gemeinsamen Vergleichsformat (docs/29 §1, auch vom Vanilla-Referenztreiber erzeugt):
	 * {@code name hit|miss blockX blockY blockZ face x y z inside blockRange entityRange} – Augenhöhe Füße + 1,62F wie {@code Entity#getEyePosition}.
	 */
	private String pickLine(final String name, final double x, final double y, final double z, final float yaw, final float pitch) {
		WorldState w = this.session.world();
		if (w == null) {
			throw new IllegalArgumentException("no world model");
		}
		dev.mcresearch.protocolclient.physics.BlockRaycast.Hit h;
		synchronized (w) {
			h = dev.mcresearch.protocolclient.physics.BlockRaycast.pick(w::stateId, dev.mcresearch.protocolclient.physics.ShapeRegistry.get(), x,
				y + (double) 1.62F, z, yaw, pitch, this.session.blockInteractionRange(), this.session.entityInteractionRange());
		}
		return String.join(" ", name, h.miss() ? "miss" : "hit", Integer.toString(h.blockX()), Integer.toString(h.blockY()), Integer.toString(h.blockZ()),
			h.face().name().toLowerCase(Locale.ROOT), Double.toString(h.x()), Double.toString(h.y()), Double.toString(h.z()), Boolean.toString(h.inside()),
			Double.toString(this.session.blockInteractionRange()), Double.toString(this.session.entityInteractionRange()));
	}

	private String entityPickLine(final String name, final double x, final double y, final double z, final float yaw, final float pitch) {
		WorldState w = this.session.world();
		if (w == null) {
			throw new IllegalArgumentException("no world model");
		}
		double eye = y + (double) 1.62F;
		dev.mcresearch.protocolclient.entity.EntityPick.Target t;
		synchronized (w) {
			t = dev.mcresearch.protocolclient.entity.EntityPick.pick(w::stateId, dev.mcresearch.protocolclient.physics.ShapeRegistry.get(), x, eye, z, yaw, pitch,
				this.session.blockInteractionRange(), this.session.entityInteractionRange(), this.session.entities().snapshot(1.0F, this.session.gameMode()), -1);
		}
		String ranges = Double.toString(this.session.blockInteractionRange()) + " " + Double.toString(this.session.entityInteractionRange());
		if (t.kind() == dev.mcresearch.protocolclient.entity.EntityPick.Kind.ENTITY) {
			return String.join(" ", name, "entity", t.entity().uuid().toString(), t.entity().type().name(), Double.toString(t.x()), Double.toString(t.y()),
				Double.toString(t.z()), ranges);
		}
		if (t.kind() == dev.mcresearch.protocolclient.entity.EntityPick.Kind.MISS && t.entity() != null) {
			// Entity vor dem Block, aber außerhalb der Entity-Reichweite: Fehlschlag am Trefferpunkt (BlockHitResult.miss)
			dev.mcresearch.protocolclient.view.Face f = dev.mcresearch.protocolclient.physics.BlockRaycast.nearest(t.x() - x, t.y() - eye, t.z() - z);
			return String.join(" ", name, "miss", Integer.toString((int) Math.floor(t.x())), Integer.toString((int) Math.floor(t.y())),
				Integer.toString((int) Math.floor(t.z())), f.name().toLowerCase(Locale.ROOT), Double.toString(t.x()), Double.toString(t.y()), Double.toString(t.z()),
				"false", ranges);
		}
		return pickLine(name, x, y, z, yaw, pitch);
	}

	private void report(final Future<MovementController.MoveResult> future) throws InterruptedException {
		try {
			this.out.println("  " + future.get(MovementController.MAX_MOVE_TICKS / 20 + 5, TimeUnit.SECONDS));
		} catch (ExecutionException e) {
			this.out.println("! move failed: " + e.getCause());
		} catch (TimeoutException e) {
			this.out.println("! move timed out");
		}
	}

	private String describePosition() {
		PlayerState p = this.session.player();
		TerrainCache t = this.session.movement().terrain();
		OptionalInt g = t == null ? OptionalInt.empty() : t.groundY(p.x(), p.z());
		return String.format(Locale.ROOT, "claimed %s | last server position (%.3f, %.3f, %.3f) | ground %s | chunks %d",
			p, p.serverX(), p.serverY(), p.serverZ(), g.isPresent() ? String.valueOf(g.getAsInt()) : "unknown", t == null ? 0 : t.loadedChunks());
	}

	private void print(final java.util.function.Function<WorldState, java.util.List<String>> command) {
		WorldState w = this.session.world();
		if (w == null) {
			this.out.println("! no world model (dimension or biome registry unknown)");
			return;
		}
		java.util.List<String> lines;
		synchronized (w) { // konsistente Sicht: der Netzwerk-Thread ändert die Welt nur unter derselben Sperre
			lines = command.apply(w);
		}
		lines.forEach(l -> this.out.println("  " + l));
	}

	private void printPhysics(final java.util.function.Function<dev.mcresearch.protocolclient.physics.PlayerPhysics, java.util.List<String>> command) {
		MovementController m = this.session.movement();
		java.util.List<String> lines;
		synchronized (m) { // konsistente Sicht auf den Physikzustand zwischen zwei Ticks
			var p = m.physics();
			if (p == null) {
				this.out.println("! no physics (no world model)");
				return;
			}
			lines = command.apply(p);
		}
		lines.forEach(l -> this.out.println("  " + l));
	}

	private void invRun(final dev.mcresearch.protocolclient.session.InventoryController.Gesture g) throws InterruptedException {
		InventoryCommands.run(this.session, g).forEach(l -> this.out.println("  " + l));
	}

	/** Blickrichtung auf einen Punkt ({@code Entity#lookAt}: yaw = atan2(dz, dx) − 90°, pitch = −atan2(dy, horizontal)). */
	private float[] lookAt(final double tx, final double ty, final double tz) {
		PlayerState p = this.session.player();
		double dx = tx - p.x(), dy = ty - (p.y() + (double) 1.62F), dz = tz - p.z();
		float yaw = (float) (Math.toDegrees(Math.atan2(dz, dx)) - 90.0);
		float pitch = (float) -Math.toDegrees(Math.atan2(dy, Math.sqrt(dx * dx + dz * dz)));
		return new float[]{yaw, Math.max(-90f, Math.min(90f, pitch))};
	}

	/** Ganzzahlige Koordinate; {@code ~} bzw. {@code ~n} relativ zur eigenen Blockposition. */
	static int coord(final String s, final double own) {
		if (s.startsWith("~")) {
			return (int) Math.floor(own) + (s.length() == 1 ? 0 : integer(s.substring(1)));
		}
		return integer(s);
	}

	static int integer(final String s) {
		try {
			return Integer.parseInt(s);
		} catch (NumberFormatException e) {
			throw new IllegalArgumentException("not an integer: " + s);
		}
	}

	private static void requireArgs(final String[] a, final int n) {
		if (a.length != n) {
			throw new IllegalArgumentException("usage: " + a[0] + " needs " + (n - 1) + " arguments");
		}
	}

	private static double parse(final String s) {
		try {
			double v = Double.parseDouble(s);
			if (!Double.isFinite(v)) {
				throw new NumberFormatException();
			}
			return v;
		} catch (NumberFormatException e) {
			throw new IllegalArgumentException("not a finite number: " + s);
		}
	}
}
