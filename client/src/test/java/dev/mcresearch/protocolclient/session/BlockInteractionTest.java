package dev.mcresearch.protocolclient.session;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import dev.mcresearch.protocolclient.physics.ShapeRegistry;
import dev.mcresearch.protocolclient.protocol.packet.Serverbound;
import dev.mcresearch.protocolclient.protocol.packet.ServerboundPacket;
import dev.mcresearch.protocolclient.world.BlockRegistry;
import dev.mcresearch.protocolclient.world.ItemRegistry;
import dev.mcresearch.protocolclient.world.ItemStack;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.junit.jupiter.api.Test;

/** Paketfolgen von Linksklick/Rechtsklick nach Vanilla (docs/27 §3–§4), ohne Netzwerk. */
class BlockInteractionTest {
	private static final BlockRegistry BLOCKS = BlockRegistry.get();
	private static final ItemRegistry ITEMS = ItemRegistry.get();
	private static final int STONE = BLOCKS.idOf("minecraft:stone", Map.of());
	private static final int DIRT = BLOCKS.idOf("minecraft:dirt", Map.of());
	private static final int BEDROCK = BLOCKS.idOf("minecraft:bedrock", Map.of());

	private final Map<Long, Integer> world = new HashMap<>();
	private final BlockInteraction bi = new BlockInteraction(ShapeRegistry.get(), BLOCKS, ITEMS);
	private final ItemStack[] hotbar = new ItemStack[9];
	private int gameMode = BlockInteraction.SURVIVAL;
	private boolean onGround = true;
	private float pitch = 25f;

	BlockInteractionTest() {
		Arrays.fill(this.hotbar, ItemStack.EMPTY);
		// Boden y = 63; Zielblock vor dem Spieler bei (0, 64, 2); Spieler bei (0,5 64 0,5), Blick nach Süden leicht nach unten
		for (int x = -3; x <= 3; x++) {
			for (int z = -3; z <= 6; z++) {
				set(x, 63, z, STONE);
			}
		}
	}

	private void set(final int x, final int y, final int z, final int state) {
		this.world.put(dev.mcresearch.protocolclient.protocol.codec.Positions.packBlock(x, y, z), state);
	}

	private List<ServerboundPacket> tick() {
		BlockInteraction.Snapshot s = new BlockInteraction.Snapshot(
			(x, y, z) -> this.world.getOrDefault(dev.mcresearch.protocolclient.protocol.codec.Positions.packBlock(x, y, z), 0), 0.5, 64, 0.5, 0f, this.pitch,
			this.onGround, 4.5 + (this.gameMode == BlockInteraction.CREATIVE ? 0.5 : 0), 3.0 + (this.gameMode == BlockInteraction.CREATIVE ? 2 : 0),
			this.gameMode, this.gameMode == BlockInteraction.CREATIVE, List.of(this.hotbar), ItemStack.EMPTY, 1.0, 0.0, 0.2);
		return this.bi.tick(s);
	}

	private static ItemStack item(final String name) {
		return new ItemStack(ITEMS.idOf(name), 1, Map.of(), Set.of());
	}

	private static List<String> names(final List<ServerboundPacket> ps) {
		List<String> out = new ArrayList<>();
		for (ServerboundPacket p : ps) {
			out.add(p instanceof Serverbound.PlayerAction a ? switch (a.action()) {
				case Serverbound.PlayerAction.START_DESTROY_BLOCK -> "START#" + a.sequence();
				case Serverbound.PlayerAction.STOP_DESTROY_BLOCK -> "STOP#" + a.sequence();
				case Serverbound.PlayerAction.ABORT_DESTROY_BLOCK -> "ABORT";
				case Serverbound.PlayerAction.CHANGE_DESTROY_DIRECTION -> "DIR";
				default -> "ACTION" + a.action();
			} : p.name().substring("minecraft:".length()));
		}
		return out;
	}

	@Test
	void firstTickSendsNothingForSlotZeroLikeVanilla() {
		// MultiPlayerGameMode#carriedIndex startet bei 0; der Vanilla-Client sendet für Platz 0 kein set_carried_item (M10-Vergleich)
		assertEquals(List.of(), names(tick()));
		this.bi.selectSlot(1);
		tick();
		assertEquals(List.of("set_carried_item"), names(tick()));
	}

	@Test
	void survivalHandBreaksDirtAfterFifteenTicks() {
		set(0, 64, 2, DIRT);
		tick();
		// Blick (Pitch 25) trifft den Dirt-Block
		assertFalse(this.bi.status().target().miss());
		assertEquals(64, this.bi.status().target().blockY());
		this.bi.attackClick();
		this.bi.setAttackHeld(true);
		List<String> first = names(tick());
		// startAttack: START + Punch; continueAttack (gehalten): erster Fortschritt + Punch
		assertEquals(List.of("START#1", "punch", "punch"), first);
		int ticks = 1;
		List<String> all = new ArrayList<>();
		while (ticks < 40) {
			List<String> t = names(tick());
			ticks++;
			all.addAll(t);
			if (t.contains("STOP#2")) {
				break;
			}
		}
		// Härte 0,5, Hand: 1/0,5/30 = 1/15 je Tick; Tick 1 hat bereits einmal addiert → STOP im 15. Tick
		assertEquals(15, ticks, all.toString());
		assertTrue(this.bi.status().pendingPredictions() == 1, "dirt predicted until ack");
		this.bi.onAck(2);
		assertEquals(0, this.bi.status().pendingPredictions());
	}

	@Test
	void stoneByHandIsSlowAndPickaxeIsCorrectTool() {
		set(0, 64, 2, STONE);
		tick();
		// Hand: Stein erfordert Werkzeug → 1/1,5/100
		assertEquals(1f / 1.5f / 100f, this.bi.destroyProgressPerTick(STONE, 0, 64, 2), 1e-7);
		this.hotbar[0] = item("minecraft:wooden_pickaxe");
		tick();
		assertEquals(2f / 1.5f / 30f, this.bi.destroyProgressPerTick(STONE, 0, 64, 2), 1e-7, "wooden pickaxe speed 2, correct tool");
		this.onGround = false;
		tick();
		assertEquals(2f / 5f / 1.5f / 30f, this.bi.destroyProgressPerTick(STONE, 0, 64, 2), 1e-7, "in the air ÷5");
		assertEquals(0f, this.bi.destroyProgressPerTick(BEDROCK, 0, 64, 2));
	}

	@Test
	void releasingAbortsTheBreak() {
		set(0, 64, 2, STONE);
		tick();
		this.bi.attackClick();
		this.bi.setAttackHeld(true);
		tick();
		tick();
		this.bi.setAttackHeld(false);
		assertEquals(List.of("ABORT"), names(tick()));
		assertFalse(this.bi.status().destroying());
	}

	@Test
	void creativeBreaksInstantlyWithFiveTickDelay() {
		this.gameMode = BlockInteraction.CREATIVE;
		set(0, 64, 2, STONE);
		set(0, 64, 3, STONE);
		tick();
		this.bi.attackClick();
		this.bi.setAttackHeld(true);
		// startAttack: START (vorhergesagt Luft → endAttack → continueAttack(false)) + Punch
		assertEquals(List.of("START#1", "punch"), names(tick()));
		// danach zielt die Auswahl (Vorhersage) auf den Block dahinter; destroyDelay 5 → 5 Ticks nur Punch, dann nächster START
		List<String> seq = new ArrayList<>();
		for (int i = 0; i < 6; i++) {
			seq.add(String.join("+", names(tick())));
		}
		assertEquals(List.of("punch", "punch", "punch", "punch", "punch", "START#2+punch"), seq);
		assertEquals(3, this.bi.status().target().blockZ());
	}

	@Test
	void creativeSwordCannotDestroy() {
		this.gameMode = BlockInteraction.CREATIVE;
		this.hotbar[0] = item("minecraft:diamond_sword");
		set(0, 64, 2, STONE);
		tick();
		this.bi.attackClick();
		names(tick());
		assertEquals(0, this.bi.status().pendingPredictions(), "Item#canDestroyBlock false: no predicted removal");
	}

	@Test
	void rightClickPlacesBlockItemWithMainHandOnly() {
		set(0, 64, 2, STONE);
		this.hotbar[0] = item("minecraft:oak_planks");
		tick();
		this.bi.useClick();
		List<ServerboundPacket> ps = tick();
		assertEquals(List.of("use_item_on"), names(ps));
		Serverbound.UseItemOn u = (Serverbound.UseItemOn) ps.getFirst();
		assertEquals(0, u.hand());
		assertEquals(2, u.direction(), "north face (3D value 2)");
		assertEquals(0.0f, u.cursorZ(), 1e-6);
		assertEquals(1, u.sequence());
	}

	@Test
	void rightClickEmptyHandOnStoneTriesBothHands() {
		set(0, 64, 2, STONE);
		tick();
		this.bi.useClick();
		List<ServerboundPacket> ps = tick();
		assertEquals(List.of("use_item_on", "use_item_on"), names(ps));
		assertEquals(1, ((Serverbound.UseItemOn) ps.get(1)).hand());
	}

	@Test
	void rightClickOnLeverIsConsumedByTheBlock() {
		set(0, 64, 2, BLOCKS.idOf("minecraft:lever", Map.of("face", "floor", "facing", "north", "powered", "false")));
		this.pitch = 35.4f; // Hebel-Umriss ist klein: Blick auf seine Mitte (0,5 64,2 2,5)
		tick();
		assertFalse(this.bi.status().target().miss());
		assertEquals(64, this.bi.status().target().blockY(), "lever selected, not the floor behind it");
		assertEquals(2, this.bi.status().target().blockZ());
		this.bi.useClick();
		assertEquals(List.of("use_item_on"), names(tick()));
	}

	@Test
	void hotbarSelectionIsSentBeforeTheNextAction() {
		set(0, 64, 2, DIRT);
		tick();
		this.bi.selectSlot(3);
		assertEquals(List.of(), names(tick()), "selection applied in handleKeybinds, sent next tick");
		assertEquals(List.of("set_carried_item"), names(tick()));
		this.bi.onServerSelectedSlot(5);
		assertEquals(List.of("set_carried_item"), names(tick()), "server-set slot is echoed like Vanilla");
	}
}
