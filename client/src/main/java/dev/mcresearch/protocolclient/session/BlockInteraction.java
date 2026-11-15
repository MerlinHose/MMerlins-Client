package dev.mcresearch.protocolclient.session;

import dev.mcresearch.protocolclient.physics.BlockRaycast;
import dev.mcresearch.protocolclient.physics.BlockView;
import dev.mcresearch.protocolclient.physics.ShapeRegistry;
import dev.mcresearch.protocolclient.protocol.packet.Serverbound;
import dev.mcresearch.protocolclient.protocol.packet.ServerboundPacket;
import dev.mcresearch.protocolclient.world.BlockRegistry;
import dev.mcresearch.protocolclient.world.BlockState;
import dev.mcresearch.protocolclient.world.ItemRegistry;
import dev.mcresearch.protocolclient.world.ItemStack;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Linksklick (Abbau) und Rechtsklick (Benutzen/Platzieren) nach Vanilla 26.3 (docs/27 §3–§4): Ablauf von
 * {@code Minecraft#handleKeybinds}, {@code startAttack}, {@code continueAttack}, {@code startUseItem} und
 * {@code MultiPlayerGameMode} (start/continue/stopDestroyBlock, useItemOn, useItem, ensureHasSentCarriedItem), je Client-Tick.
 *
 * <p><b>Keine Weltvorhersage:</b> Der Client verändert seinen {@code WorldState} nie selbst; Blöcke ändern sich nur durch
 * {@code block_update}/{@code section_blocks_update} des Servers. Vanilla trifft einige Folgeentscheidungen aber anhand der
 * eigenen Vorhersage (z. B. "Block ist schon Luft" nach Creative-Abbau). Dafür führt diese Klasse eine <i>unsichtbare</i>
 * Vorhersageliste: Positionen, deren Abbau Vanilla vorhergesagt hätte, gelten <i>nur für diese Entscheidungen</i> als Luft,
 * bis der Server die Sequenz mit {@code block_changed_ack} bestätigt. Dargestellt und gespeichert wird ausschließlich der
 * Serverzustand. Nicht threadsicher bis auf die Eingabe-Methoden; {@link #tick} läuft im Tick-Thread unter der Weltsperre.
 */
public final class BlockInteraction {
	public static final int SURVIVAL = 0;
	public static final int CREATIVE = 1;
	public static final int ADVENTURE = 2;
	public static final int SPECTATOR = 3;

	/** Zustand des Spielers für einen Tick (vom Sitzungs-Thread zusammengestellt). */
	public record Snapshot(BlockView world, double x, double y, double z, float yaw, float pitch, boolean onGround, double blockRange,
		double entityRange, int gameMode, boolean instabuild, List<ItemStack> hotbar, ItemStack offHand, double blockBreakSpeed,
		double miningEfficiency, double submergedMiningSpeed, List<dev.mcresearch.protocolclient.entity.EntityManager.View> entities) {
		public Snapshot {
			hotbar = List.copyOf(hotbar);
			entities = entities == null ? List.of() : List.copyOf(entities);
			if (hotbar.size() != 9) {
				throw new IllegalArgumentException("hotbar needs 9 slots");
			}
		}

		/** Ohne Entities (M7-Aufrufer und -Tests). */
		public Snapshot(final BlockView world, final double x, final double y, final double z, final float yaw, final float pitch, final boolean onGround,
			final double blockRange, final double entityRange, final int gameMode, final boolean instabuild, final List<ItemStack> hotbar, final ItemStack offHand,
			final double blockBreakSpeed, final double miningEfficiency, final double submergedMiningSpeed) {
			this(world, x, y, z, yaw, pitch, onGround, blockRange, entityRange, gameMode, instabuild, hotbar, offHand, blockBreakSpeed, miningEfficiency,
				submergedMiningSpeed, List.of());
		}
	}

	/** Für Anzeige und Tests: letzter Tick. */
	public record Status(BlockRaycast.Hit target, boolean destroying, int destroyX, int destroyY, int destroyZ, float progress, int stage,
		int sequence, int lastAck, int pendingPredictions, int missTime, int destroyDelay) {
	}

	private final ShapeRegistry shapes;
	private final BlockRegistry blocks;
	private final ItemRegistry items;

	// ---- Eingabe (Grafik-/Terminal-Thread) ----
	private int attackClicks;
	private int useClicks;
	private boolean attackHeld;
	private boolean useHeld;
	private boolean inputAllowed = true; // Vanilla: kein Bildschirm offen und Maus gefangen
	private int requestedSlot = -1;

	// ---- Spielzustand (Tick-Thread) ----
	private int carriedIndex; // zuletzt gesendeter Hotbar-Platz (MultiPlayerGameMode#carriedIndex, Vanilla-Startwert 0; M10-Vergleich)
	private int selected;
	private int missTime;
	private int rightClickDelay;
	private int destroyDelay;
	private boolean isDestroying;
	private int destroyX;
	private int destroyY;
	private int destroyZ;
	private boolean hasDestroyPos;
	private int destroyDirection = -1;
	private ItemStack destroyingItem = ItemStack.EMPTY;
	private float destroyProgress;
	private float destroyTicks;
	private int sequence; // BlockStatePredictionHandler#currentSequenceNr
	private int lastAck;
	private final Map<Long, Integer> predicted = new HashMap<>();
	private BlockRaycast.Hit target;
	/** M10: Entity-Auswahl dieses Ticks (null = keine); ersetzt dann die Blockauswahl wie {@code Minecraft#hitResult}. */
	private dev.mcresearch.protocolclient.entity.EntityPick.Target entityTarget;
	private PlayerSurvivalState survival;
	private ItemStack lastMainHand = ItemStack.EMPTY;
	private long attacksSent;
	private long interactsSent;
	private final java.util.ArrayDeque<String> combatLog = new java.util.ArrayDeque<>();
	private Snapshot snap;
	private List<ServerboundPacket> out;
	private long attacksStarted;
	private long blocksFinished;

	public BlockInteraction(final ShapeRegistry shapes, final BlockRegistry blocks, final ItemRegistry items) {
		this.shapes = shapes;
		this.blocks = blocks;
		this.items = items;
	}

	// ================= Eingabe =================

	/** Linksklick (ein Tastendruck, {@code keyAttack.consumeClick}). */
	public synchronized void attackClick() {
		this.attackClicks = Math.min(this.attackClicks + 1, 16);
	}

	/** Rechtsklick (ein Tastendruck, {@code keyUse.consumeClick}). */
	public synchronized void useClick() {
		this.useClicks = Math.min(this.useClicks + 1, 16);
	}

	public synchronized void setAttackHeld(final boolean held) {
		this.attackHeld = held;
	}

	public synchronized void setUseHeld(final boolean held) {
		this.useHeld = held;
	}

	/** false, solange ein Bildschirm (Inventar) offen oder die Maus frei ist: wie {@code gui.screen() != null}. */
	public synchronized void setInputAllowed(final boolean allowed) {
		this.inputAllowed = allowed;
	}

	/** Hotbar-Platz wählen (Tasten 1–9, Mausrad): wirkt im nächsten Tick ({@code keyHotbarSlots}). */
	public synchronized void selectSlot(final int slot) {
		if (slot < 0 || slot > 8) {
			throw new IllegalArgumentException("hotbar slot 0..8");
		}
		this.requestedSlot = slot;
	}

	// ================= Serverereignisse =================

	/** {@code block_changed_ack}: Vorhersagen bis zu dieser Sequenz sind vom Server verarbeitet. */
	public synchronized void onAck(final int seq) {
		this.lastAck = Math.max(this.lastAck, seq);
		this.predicted.values().removeIf(s -> s <= seq);
	}

	/**
	 * {@code set_held_slot}: der Server setzt den Platz ({@code inventory.setSelectedSlot}); {@code carriedIndex} bleibt, daher
	 * bestätigt {@code ensureHasSentCarriedItem} den Platz im nächsten Tick mit {@code set_carried_item} – wie Vanilla.
	 */
	public synchronized void onServerSelectedSlot(final int slot) {
		this.selected = slot;
		this.requestedSlot = -1;
	}

	/** Dimensionswechsel/Wiederbelebung: laufende Aktionen und Vorhersagen verwerfen (neue Welt, neuer LocalPlayer). */
	public synchronized void reset() {
		this.isDestroying = false;
		this.destroyProgress = 0;
		this.destroyTicks = 0;
		this.hasDestroyPos = false;
		this.predicted.clear();
		this.attackClicks = 0;
		this.useClicks = 0;
		this.missTime = 0;
		this.destroyDelay = 0;
	}

	/** M10: Ueberlebenszustand fuer Angriffsstaerke und Item-Benutzung. */
	public synchronized void setSurvival(final PlayerSurvivalState s) {
		this.survival = s;
	}

	/** M10: Entity-Auswahl des letzten Ticks (null = keine). */
	public synchronized dev.mcresearch.protocolclient.entity.EntityPick.Target entityTarget() {
		return this.entityTarget;
	}

	public synchronized long attacksSent() {
		return this.attacksSent;
	}

	public synchronized long interactsSent() {
		return this.interactsSent;
	}

	public synchronized List<String> combatLog() {
		return new ArrayList<>(this.combatLog);
	}

	private void combat(final String s) {
		this.combatLog.addLast(s);
		while (this.combatLog.size() > 100) {
			this.combatLog.removeFirst();
		}
	}

	public synchronized int selectedSlot() {
		return this.requestedSlot >= 0 ? this.requestedSlot : this.selected;
	}

	public synchronized Status status() {
		return new Status(this.target, this.isDestroying, this.destroyX, this.destroyY, this.destroyZ, this.destroyProgress, destroyStage(), this.sequence,
			this.lastAck, this.predicted.size(), this.missTime, this.destroyDelay);
	}

	public synchronized long attacksStarted() {
		return this.attacksStarted;
	}

	public synchronized long blocksFinished() {
		return this.blocksFinished;
	}

	// ================= Tick =================

	/**
	 * Ein Client-Tick in Vanilla-Reihenfolge: {@code gameMode.tick()} (carried item) → {@code pick} → {@code handleKeybinds}
	 * (Hotbar, Klicks, gehaltene Tasten) → {@code missTime--}. Liefert die Pakete in Sendereihenfolge (vor den Bewegungspaketen).
	 */
	public synchronized List<ServerboundPacket> tick(final Snapshot s) {
		this.snap = s;
		this.out = new ArrayList<>(4);
		if (this.rightClickDelay > 0) {
			this.rightClickDelay--;
		}
		ensureHasSentCarriedItem(); // MultiPlayerGameMode#tick
		// Minecraft#pick läuft auf der lokalen (vorhergesagten) Welt: vorhergesagte Abbauorte sind für die Auswahl Luft
		this.target = BlockRaycast.pick(this::decisionState, this.shapes, s.x(), s.y() + (double) 1.62F, s.z(), s.yaw(), s.pitch(), s.blockRange(), s.entityRange());
		// M10: Entity vor Block (LocalPlayer#pick, docs/34 §12); eine Entity ausserhalb der Reichweite vor dem Block ergibt einen Fehlschlag
		this.entityTarget = null;
		if (!s.entities().isEmpty()) {
			dev.mcresearch.protocolclient.entity.EntityPick.Target t = dev.mcresearch.protocolclient.entity.EntityPick.pick(this::decisionState, this.shapes, s.x(),
				s.y() + (double) 1.62F, s.z(), s.yaw(), s.pitch(), s.blockRange(), s.entityRange(), s.entities(), -1);
			if (t.kind() == dev.mcresearch.protocolclient.entity.EntityPick.Kind.ENTITY) {
				this.entityTarget = t;
				this.target = BlockRaycast.Hit.miss(t.x(), t.y(), t.z(), this.target.face());
			} else if (t.entity() != null) {
				this.target = BlockRaycast.Hit.miss(t.x(), t.y(), t.z(), this.target.face());
			}
		}
		// Player#tick: nur ein anderer Item-Typ in der Haupthand (ItemStack#isSameItem) setzt die Angriffsstaerke zurueck; Anzahl und
		// Komponenten (z. B. Haltbarkeit nach einem Treffer) nicht. lastItemInMainHand startet leer wie in Vanilla.
		ItemStack mh = s.hotbar().get(this.selected);
		if (this.survival != null && (mh.isEmpty() ? -1 : mh.itemId()) != (this.lastMainHand.isEmpty() ? -1 : this.lastMainHand.itemId())) {
			this.survival.resetAttackStrength();
		}
		this.lastMainHand = mh;
		if (!this.inputAllowed) {
			this.missTime = 10000; // Bildschirm offen
			this.attackClicks = 0;
			this.useClicks = 0;
		} else {
			handleKeybinds();
			if (this.missTime > 0) {
				this.missTime--;
			}
		}
		List<ServerboundPacket> result = this.out;
		this.out = null; // this.snap bleibt: letzter Zustand für destroyProgressPerTick (Anzeige, Tests)
		return result;
	}

	private void handleKeybinds() {
		if (this.requestedSlot >= 0) {
			this.selected = this.requestedSlot; // inventory.setSelectedSlot – gesendet beim nächsten ensureHasSentCarriedItem
			this.requestedSlot = -1;
		}
		boolean instantAttack = false;
		boolean using = this.survival != null && this.survival.usingItem();
		if (using) {
			// Minecraft#handleKeybinds: waehrend der Benutzung keine Angriffe/Benutzungen; Loslassen sendet RELEASE_USE_ITEM
			if (!this.useHeld) {
				ensureHasSentCarriedItem();
				this.out.add(new Serverbound.PlayerAction(Serverbound.PlayerAction.RELEASE_USE_ITEM, 0, 0, 0, 0, 0));
				this.survival.stopUsing("released");
				combat("release_use_item");
			}
			this.attackClicks = 0;
			this.useClicks = 0;
		} else {
			while (this.attackClicks > 0) {
				this.attackClicks--;
				instantAttack |= startAttack();
			}
			while (this.useClicks > 0) {
				this.useClicks--;
				startUseItem();
			}
		}
		if (this.useHeld && this.rightClickDelay == 0 && !(this.survival != null && this.survival.usingItem())) {
			startUseItem();
		}
		continueAttack(!instantAttack && this.attackHeld);
	}

	/** {@code player.getMainHandItem()}: Stapel des aktuell (clientseitig) gewählten Hotbar-Platzes. */
	private ItemStack mainHand() {
		return this.snap.hotbar().get(this.selected);
	}

	private void ensureHasSentCarriedItem() {
		if (this.selected != this.carriedIndex) {
			this.carriedIndex = this.selected;
			this.out.add(new Serverbound.SetCarriedItem(this.carriedIndex));
		}
	}

	/** Zustand für Entscheidungen: Serverwelt, aber vorhergesagter Abbau gilt bis zur Bestätigung als Luft. */
	private int decisionState(final int x, final int y, final int z) {
		if (this.predicted.containsKey(key(x, y, z))) {
			return 0; // minecraft:air
		}
		return this.snap.world().stateAt(x, y, z);
	}

	private boolean isAir(final int stateId) {
		if (stateId < 0 || !this.blocks.isValid(stateId)) {
			return true; // nicht geladen: Vanilla liefert VOID_AIR
		}
		return this.blocks.state(stateId).isAir();
	}

	private static long key(final int x, final int y, final int z) {
		return dev.mcresearch.protocolclient.protocol.codec.Positions.packBlock(x, y, z);
	}

	private boolean blockHit() {
		return this.target != null && !this.target.miss();
	}

	private boolean hasMissTime() {
		return this.snap.gameMode() != CREATIVE; // !localPlayerMode.isCreative()
	}

	/** {@code Player#blockActionRestricted}: Abenteuermodus ohne can_break/can_place_on (nicht modelliert) bzw. Zuschauer. */
	private boolean blockActionRestricted() {
		return this.snap.gameMode() == ADVENTURE || this.snap.gameMode() == SPECTATOR;
	}

	// ---------- Linksklick ----------

	private boolean startAttack() {
		if (this.missTime > 0) {
			return false;
		}
		this.attacksStarted++;
		if (this.snap.gameMode() == SPECTATOR) {
			return true; // spectatorNoAction / spectate(entity) – keine Blockaktion
		}
		String held = mainHand().isEmpty() ? null : this.items.name(mainHand().itemId());
		if (PlayerSurvivalState.piercingWeapon(held)) {
			// Speere: Vanilla nutzt piercingAttack (eigener Angriffsweg mit Aufladung), in M10 nicht umgesetzt – kein falsches attack senden
			combat("piercing attack with " + held + " not implemented (no packet)");
			swing(0, true); // piercingAttack schwingt mit der attack_animation (STAB); nur Darstellung
			return false;
		}
		boolean endAttack = false;
		boolean miss = true;
		if (this.entityTarget != null) {
			// MultiPlayerGameMode#attack: Hotbar-Platz, attack, lokale Angriffsstaerke zuruecksetzen (Ergebnis bestaetigt der Server)
			ensureHasSentCarriedItem();
			this.out.add(new Serverbound.Attack(this.entityTarget.entity().id()));
			this.attacksSent++;
			if (this.survival != null) {
				combat(String.format(java.util.Locale.ROOT, "attack %d %s strength %.3f dist %.3f", this.entityTarget.entity().id(),
					this.entityTarget.entity().type().shortName(), this.survival.attackStrength(0.0F), this.entityTarget.distance()));
				this.survival.resetAttackStrength();
			}
			swing(0, true);
			this.out.add(new Serverbound.Punch()); // Armschwung (Angriffsanimation des Items) + punch
			return false;
		}
		if (blockHit()) {
			int x = this.target.blockX(), y = this.target.blockY(), z = this.target.blockZ();
			if (!isAir(decisionState(x, y, z))) {
				miss = false;
				startDestroyBlock(x, y, z, this.target.face().ordinal());
				if (isAir(decisionState(x, y, z))) {
					endAttack = true;
				}
			}
		}
		if (miss && hasMissTime()) {
			this.missTime = 10;
		}
		if (miss && this.survival != null) {
			this.survival.resetAttackStrength(); // Fehlschlag: resetAttackStrengthTicker
		}
		swing(0, true);
		this.out.add(new Serverbound.Punch()); // player.swing(...) + ServerboundPunchPacket
		return endAttack;
	}

	/** Eigener Armschwung mit der Animation des Items in der Hand (nur Darstellung, docs/39 §4). */
	private void swing(final int hand, final boolean attack) {
		if (this.survival != null) {
			ItemStack held = hand == 0 ? mainHand() : this.snap.offHand();
			this.survival.swing(hand, held.isEmpty() ? null : this.items.name(held.itemId()), attack);
		}
	}

	private void continueAttack(final boolean down) {
		if (!down) {
			this.missTime = 0;
		}
		if (this.missTime <= 0 && !(this.survival != null && this.survival.usingItem())) {
			if (down && blockHit()) {
				int x = this.target.blockX(), y = this.target.blockY(), z = this.target.blockZ();
				if (!isAir(decisionState(x, y, z)) && continueDestroyBlock(x, y, z, this.target.face().ordinal())) {
					swing(0, true);
					this.out.add(new Serverbound.Punch());
				}
			} else {
				stopDestroyBlock();
			}
		}
	}

	private boolean startDestroyBlock(final int x, final int y, final int z, final int direction) {
		if (blockActionRestricted()) {
			return false;
		}
		if (this.snap.instabuild()) {
			predictDestroy(x, y, z, Serverbound.PlayerAction.START_DESTROY_BLOCK, direction);
			this.destroyDelay = 5;
		} else if (!this.isDestroying || !sameDestroyTarget(x, y, z)) {
			if (this.isDestroying) {
				this.out.add(new Serverbound.PlayerAction(Serverbound.PlayerAction.ABORT_DESTROY_BLOCK, this.destroyX, this.destroyY, this.destroyZ, direction, 0));
			}
			int state = decisionState(x, y, z);
			int seq = ++this.sequence;
			boolean notAir = !isAir(state);
			if (notAir && destroyProgressPerTick(state, x, y, z) >= 1.0F) {
				markPredicted(x, y, z, state, seq);
			} else {
				this.isDestroying = true;
				this.destroyX = x;
				this.destroyY = y;
				this.destroyZ = z;
				this.hasDestroyPos = true;
				this.destroyDirection = direction;
				this.destroyingItem = mainHand();
				this.destroyProgress = 0.0F;
				this.destroyTicks = 0.0F;
			}
			this.out.add(new Serverbound.PlayerAction(Serverbound.PlayerAction.START_DESTROY_BLOCK, x, y, z, direction, seq));
		}
		return true;
	}

	private boolean continueDestroyBlock(final int x, final int y, final int z, final int direction) {
		ensureHasSentCarriedItem();
		if (this.destroyDelay > 0) {
			this.destroyDelay--;
			return true;
		}
		if (this.snap.instabuild()) {
			this.destroyDelay = 5;
			predictDestroy(x, y, z, Serverbound.PlayerAction.START_DESTROY_BLOCK, direction);
			return true;
		}
		if (sameDestroyTarget(x, y, z)) {
			int state = decisionState(x, y, z);
			if (isAir(state)) {
				this.isDestroying = false;
				return false;
			}
			this.destroyProgress += destroyProgressPerTick(state, x, y, z);
			this.destroyTicks++;
			if (this.destroyProgress >= 1.0F) {
				this.isDestroying = false;
				predictDestroy(x, y, z, Serverbound.PlayerAction.STOP_DESTROY_BLOCK, direction);
				this.blocksFinished++;
				this.destroyDirection = -1;
				this.destroyProgress = 0.0F;
				this.destroyTicks = 0.0F;
				this.destroyDelay = 5;
			} else if (this.destroyDirection != direction) {
				this.destroyDirection = direction;
				this.out.add(new Serverbound.PlayerAction(Serverbound.PlayerAction.CHANGE_DESTROY_DIRECTION, x, y, z, direction, 0));
			}
			return true;
		}
		return startDestroyBlock(x, y, z, direction);
	}

	private void stopDestroyBlock() {
		if (this.isDestroying) {
			this.out.add(new Serverbound.PlayerAction(Serverbound.PlayerAction.ABORT_DESTROY_BLOCK, this.destroyX, this.destroyY, this.destroyZ, 0, 0));
			this.isDestroying = false;
			this.destroyProgress = 0.0F;
		}
	}

	/** {@code startPrediction(… destroyBlock(pos) …)}: Sequenz erhöhen, Paket senden; Abbau nur in der Entscheidungsliste. */
	private void predictDestroy(final int x, final int y, final int z, final int action, final int direction) {
		int seq = ++this.sequence;
		markPredicted(x, y, z, decisionState(x, y, z), seq);
		this.out.add(new Serverbound.PlayerAction(action, x, y, z, direction, seq));
	}

	/** {@code MultiPlayerGameMode#destroyBlock}: Vanilla entfernt den Block lokal nur, wenn das Item ihn zerstören darf. */
	private void markPredicted(final int x, final int y, final int z, final int state, final int seq) {
		if (isAir(state) || blockActionRestricted()) {
			return;
		}
		if (this.snap.gameMode() == CREATIVE) {
			ItemRegistry.Tool tool = mainHand().tool(this.items);
			if (tool != null && !tool.canDestroyBlocksInCreative()) {
				return; // Item#canDestroyBlock: Schwert, Dreizack, Streitkolben im Kreativmodus
			}
		}
		this.predicted.put(key(x, y, z), seq);
		// M12: Block#playerWillDestroy → levelEvent 2001 lokal (Abbaupartikel des eigenen Spielers, docs/48 §7)
		this.lastDestroyed = new int[]{x, y, z, state};
		DestroyListener l = this.destroyListener;
		if (l != null) {
			l.destroyed(x, y, z, state);
		}
	}

	/** M12: Beobachter für eigene (vorhergesagte) Abbauten – nur Darstellung. */
	@FunctionalInterface
	public interface DestroyListener {
		void destroyed(int x, int y, int z, int state);
	}

	private volatile DestroyListener destroyListener;
	private volatile int[] lastDestroyed;

	public void setDestroyListener(final DestroyListener listener) {
		this.destroyListener = listener;
	}

	/** Position und Zustand des zuletzt lokal abgebauten Blocks (x, y, z, Zustand) oder {@code null}. */
	public int[] lastDestroyed() {
		return this.lastDestroyed;
	}

	private boolean sameDestroyTarget(final int x, final int y, final int z) {
		return this.hasDestroyPos && x == this.destroyX && y == this.destroyY && z == this.destroyZ && mainHand().sameItemSameComponents(this.destroyingItem);
	}

	/**
	 * {@code BlockBehaviour#getDestroyProgress}: Spieler-Abbaugeschwindigkeit / Härte / (richtiges Werkzeug ? 30 : 100);
	 * {@code Player#getDestroySpeed}: Werkzeugregel, +mining_efficiency (wenn &gt; 1), × block_break_speed, unter Wasser ×
	 * submerged_mining_speed, in der Luft ÷ 5. Statuseffekte (Eile, Abbaulähmung) werden nicht modelliert (docs/27 §3).
	 */
	public float destroyProgressPerTick(final int stateId, final int x, final int y, final int z) {
		float hardness = this.shapes.hardness(stateId);
		if (hardness == -1.0F) {
			return 0.0F;
		}
		String block = this.blocks.state(stateId).name();
		ItemRegistry.Tool tool = mainHand().tool(this.items);
		boolean correct = !this.shapes.requiresCorrectTool(stateId) || tool != null && tool.correctForDrops(block);
		float speed = tool == null ? 1.0F : tool.miningSpeed(block);
		if (speed > 1.0F) {
			speed += (float) this.snap.miningEfficiency();
		}
		if (this.survival != null) {
			// M10: Eile (2) bzw. Leitfeuerkraft (28) und Abbaulähmung (3), Player#getDestroySpeed
			int haste = this.survival.effectAmplifier(2);
			int conduit = this.survival.effectAmplifier(28);
			if (haste >= 0 || conduit >= 0) {
				speed *= 1.0F + (Math.max(Math.max(haste, 0), Math.max(conduit, 0)) + 1) * 0.2F;
			}
			int fatigue = this.survival.effectAmplifier(3);
			if (fatigue >= 0) {
				speed *= (float) Math.pow(0.3, fatigue + 1);
			}
		}
		speed *= (float) this.snap.blockBreakSpeed();
		if (eyeInWater()) {
			speed *= (float) this.snap.submergedMiningSpeed();
		}
		if (!this.snap.onGround()) {
			speed /= 5.0F;
		}
		return speed / hardness / (correct ? 30 : 100);
	}

	/** Vereinfachtes {@code isEyeInFluid(WATER)}: Augenblock ist Wasser bzw. wassergeflutet (Füllhöhe 8/9 angenommen). */
	private boolean eyeInWater() {
		double eye = this.snap.y() + (double) 1.62F;
		int bx = (int) Math.floor(this.snap.x()), by = (int) Math.floor(eye), bz = (int) Math.floor(this.snap.z());
		int id = this.snap.world().stateAt(bx, by, bz);
		if (id < 0 || !this.blocks.isValid(id)) {
			return false;
		}
		BlockState st = this.blocks.state(id);
		boolean water = st.name().equals("minecraft:water") || st.name().equals("minecraft:bubble_column") || "true".equals(st.properties().get("waterlogged"))
			|| st.name().equals("minecraft:kelp") || st.name().equals("minecraft:kelp_plant") || st.name().endsWith("seagrass");
		return water && eye < by + 8.0 / 9.0;
	}

	private int destroyStage() {
		return this.isDestroying && this.destroyProgress > 0.0F ? (int) (this.destroyProgress * 10.0F) : -1;
	}

	// ---------- Rechtsklick ----------

	private enum Result {
		SUCCESS, CONSUME, FAIL, PASS
	}

	private void startUseItem() {
		if (this.isDestroying) {
			return;
		}
		this.rightClickDelay = 4;
		for (int hand = 0; hand <= 1; hand++) {
			ItemStack held = hand == 0 ? mainHand() : this.snap.offHand();
			if (this.entityTarget != null && this.snap.gameMode() != SPECTATOR) {
				// MultiPlayerGameMode#interact: Ort relativ zur Entity-Position; Ergebnis von interactOn nicht vorhergesagt (PASS angenommen)
				dev.mcresearch.protocolclient.entity.EntityManager.View v = this.entityTarget.entity();
				ensureHasSentCarriedItem();
				this.out.add(new Serverbound.Interact(v.id(), hand, this.entityTarget.x() - v.rx(), this.entityTarget.y() - v.ry(), this.entityTarget.z() - v.rz(), false));
				this.interactsSent++;
				combat("interact " + v.id() + " hand " + hand);
			}
			if (blockHit()) {
				Result r = useItemOn(hand, held);
				if (r == Result.SUCCESS) {
					swing(hand, false); // InteractionResult.Success mit SwingSource.CLIENT: player.swing(hand, interactAnimation)
				}
				if (r == Result.SUCCESS || r == Result.CONSUME || r == Result.FAIL) {
					return;
				}
			}
			if (!held.isEmpty() && useItem(hand) == Result.SUCCESS) {
				return;
			}
		}
	}

	private Result useItemOn(final int hand, final ItemStack held) {
		ensureHasSentCarriedItem();
		int seq = ++this.sequence;
		BlockRaycast.Hit h = this.target;
		Result r = performUseItemOn(hand, held, h);
		this.out.add(new Serverbound.UseItemOn(hand, h.blockX(), h.blockY(), h.blockZ(), h.face().ordinal(), (float) (h.x() - h.blockX()),
			(float) (h.y() - h.blockY()), (float) (h.z() - h.blockZ()), h.inside(), false, seq));
		return r;
	}

	/**
	 * Ergebnis von {@code performUseItemOn} ohne lokale Simulation (docs/27 §4, Näherung): Blöcke mit eigener Rechtsklick-Logik
	 * (Klasse überschreibt useItemOn bzw. – nur Haupthand – useWithoutItem) gelten als verbraucht; sonst platziert ein
	 * Block-Item (Erfolg) und andere Items gelten als PASS (dann folgt wie in Vanilla {@code use_item}). Schleichen (Secondary
	 * Use) kennt der Client nicht.
	 */
	private Result performUseItemOn(final int hand, final ItemStack held, final BlockRaycast.Hit h) {
		if (this.snap.gameMode() == SPECTATOR) {
			return Result.CONSUME;
		}
		int state = decisionState(h.blockX(), h.blockY(), h.blockZ());
		if (state >= 0 && this.blocks.isValid(state)) {
			if (!held.isEmpty() && this.shapes.usesItemOn(state)) {
				return Result.SUCCESS;
			}
			if (hand == 0 && this.shapes.usesWithoutItem(state) && this.snap.gameMode() != ADVENTURE) {
				return Result.SUCCESS;
			}
		}
		if (held.isEmpty()) {
			return Result.PASS;
		}
		if (this.items.placedBlock(held.itemId()) != null) {
			return blockActionRestricted() ? Result.PASS : Result.SUCCESS;
		}
		return Result.PASS;
	}

	private Result useItem(final int hand) {
		if (this.snap.gameMode() == SPECTATOR) {
			return Result.PASS;
		}
		ensureHasSentCarriedItem();
		int seq = ++this.sequence;
		this.out.add(new Serverbound.UseItem(hand, seq, this.snap.yaw(), this.snap.pitch()));
		// M10: verzehrbare Items starten die Benutzung (Consumable#startConsuming = CONSUME = Erfolg, keine weitere Hand)
		ItemStack held = hand == 0 ? mainHand() : this.snap.offHand();
		if (!held.isEmpty() && this.survival != null && this.survival.onCooldown(this.items.name(held.itemId()))) {
			combat("use_item " + this.items.name(held.itemId()) + " on cooldown (PASS)");
			return Result.PASS; // MultiPlayerGameMode#useItem: Paket gesendet, Ergebnis PASS
		}
		PlayerSurvivalState.Consumable c = held.isEmpty() ? null : PlayerSurvivalState.consumables().get(this.items.name(held.itemId()));
		if (c != null && this.survival != null && (!c.isFood() || this.survival.invulnerableOrCanEat(this.snap.instabuild(), c.canAlwaysEat()))) {
			this.survival.startUsing(hand, c.ticks());
			combat("use_item " + c.item() + " (" + c.ticks() + " ticks)");
			return Result.SUCCESS;
		}
		// Schild/Speer (blocks_attacks, kinetic_weapon), Dreizack, Fernrohr, Pinsel: Item#use → startUsingItem (CONSUME); das Loslassen
		// sendet RELEASE_USE_ITEM. Ohne lokale Benutzung bliebe z. B. der Schild auf dem Server dauerhaft erhoben.
		String name = held.isEmpty() ? null : this.items.name(held.itemId());
		int hold = PlayerSurvivalState.holdUseTicks(name);
		if (c == null && hold > 0 && this.survival != null) {
			this.survival.startUsing(hand, hold);
			combat("use_item " + name + " (hold, " + hold + " ticks)");
			return Result.SUCCESS;
		}
		return Result.PASS; // Ergebnis von ItemStack#use nicht simuliert: Vanilla fährt bei PASS mit der Nebenhand fort
	}
}
