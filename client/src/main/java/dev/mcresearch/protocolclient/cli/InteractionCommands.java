package dev.mcresearch.protocolclient.cli;

import dev.mcresearch.protocolclient.physics.BlockRaycast;
import dev.mcresearch.protocolclient.session.BlockInteraction;
import dev.mcresearch.protocolclient.session.ClientSession;
import dev.mcresearch.protocolclient.session.PlayerInventory;
import dev.mcresearch.protocolclient.world.BlockRegistry;
import dev.mcresearch.protocolclient.world.ItemRegistry;
import dev.mcresearch.protocolclient.world.ItemStack;
import dev.mcresearch.protocolclient.world.WorldState;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/** Terminalbefehle der Blockinteraktion (M7, docs/27 §6): Ziel, Abbau, Rechtsklick, Inventar. */
final class InteractionCommands {
	private static final String[] MODES = {"survival", "creative", "adventure", "spectator"};

	private InteractionCommands() {
	}

	static String target(final ClientSession s) {
		BlockRaycast.Hit h = s.interaction().status().target();
		if (h == null) {
			return "no target (not in play)";
		}
		if (h.miss()) {
			return "miss";
		}
		return String.format(Locale.ROOT, "hit %d %d %d face=%s at (%.4f, %.4f, %.4f)%s state=%s", h.blockX(), h.blockY(), h.blockZ(),
			h.face().name().toLowerCase(Locale.ROOT), h.x(), h.y(), h.z(), h.inside() ? " inside" : "", state(s, h.blockX(), h.blockY(), h.blockZ()));
	}

	static String state(final ClientSession s, final int x, final int y, final int z) {
		WorldState w = s.world();
		if (w == null) {
			return "?";
		}
		int id;
		synchronized (w) {
			id = w.stateId(x, y, z);
		}
		return id < 0 || !BlockRegistry.get().isValid(id) ? "unloaded" : BlockRegistry.get().state(id).toString();
	}

	private static int stateId(final ClientSession s, final int x, final int y, final int z) {
		WorldState w = s.world();
		synchronized (w) {
			return w.stateId(x, y, z);
		}
	}

	/** Linksklick halten, bis der Server den Zielblock ändert oder {@code maxTicks} vergangen sind; danach loslassen. */
	static List<String> dig(final ClientSession s, final int maxTicks) throws InterruptedException {
		List<String> out = new ArrayList<>();
		BlockInteraction bi = s.interaction();
		BlockRaycast.Hit h = bi.status().target();
		if (h == null || h.miss()) {
			out.add("! no block targeted");
			return out;
		}
		int x = h.blockX(), y = h.blockY(), z = h.blockZ();
		int before = stateId(s, x, y, z);
		int seq0 = bi.status().sequence();
		long t0 = System.nanoTime();
		bi.attackClick();
		bi.setAttackHeld(true);
		int changedAfterMs = -1;
		float maxProgress = 0;
		try {
			while ((System.nanoTime() - t0) / 1_000_000L < maxTicks * 50L + 100) {
				Thread.sleep(10);
				maxProgress = Math.max(maxProgress, bi.status().progress());
				if (stateId(s, x, y, z) != before) {
					changedAfterMs = (int) ((System.nanoTime() - t0) / 1_000_000L);
					break;
				}
			}
		} finally {
			bi.setAttackHeld(false);
		}
		Thread.sleep(150); // ABORT bzw. Ack abwarten
		BlockInteraction.Status st = bi.status();
		out.add(String.format(Locale.ROOT, "dig %d %d %d (%s) -> %s after %s", x, y, z, BlockRegistry.get().state(before).name(), state(s, x, y, z),
			changedAfterMs < 0 ? "no server change within " + maxTicks + " ticks" : changedAfterMs + " ms (~" + (changedAfterMs + 25) / 50 + " ticks)"));
		out.add(String.format(Locale.ROOT, "sequences %d..%d, last ack %d, pending predictions %d, max local progress %.3f, mode %s", seq0 + 1, st.sequence(),
			st.lastAck(), st.pendingPredictions(), maxProgress, MODES[Math.max(0, Math.min(3, s.gameMode()))]));
		return out;
	}

	/** Ein Rechtsklick; meldet Ziel, Hand-Item und die Serverantwort (Blockänderungen am Ziel und an der Nachbarposition). */
	static List<String> use(final ClientSession s) throws InterruptedException {
		List<String> out = new ArrayList<>();
		BlockInteraction bi = s.interaction();
		BlockRaycast.Hit h = bi.status().target();
		if (h == null) {
			out.add("! not in play");
			return out;
		}
		ItemStack held = s.inventory().mainHand();
		int nx = 0, ny = 0, nz = 0;
		int beforeTarget = -2, beforeNeighbour = -2;
		if (!h.miss()) {
			nx = h.blockX() + h.face().dx;
			ny = h.blockY() + h.face().dy;
			nz = h.blockZ() + h.face().dz;
			beforeTarget = stateId(s, h.blockX(), h.blockY(), h.blockZ());
			beforeNeighbour = stateId(s, nx, ny, nz);
		}
		int seq0 = bi.status().sequence();
		String on = h.miss() ? "nothing (miss)" : target(s);
		bi.useClick();
		Thread.sleep(300);
		BlockInteraction.Status st = bi.status();
		out.add("use with " + held.describe(ItemRegistry.get()) + " on " + on);
		if (!h.miss()) {
			out.add(String.format(Locale.ROOT, "target %s -> %s; adjacent %d %d %d %s -> %s", BlockRegistry.get().state(beforeTarget).name(),
				state(s, h.blockX(), h.blockY(), h.blockZ()), nx, ny, nz, beforeNeighbour < 0 ? "unloaded" : BlockRegistry.get().state(beforeNeighbour).name(),
				state(s, nx, ny, nz)));
		}
		out.add(String.format(Locale.ROOT, "sequences %d..%d, last ack %d; now holding %s", seq0 + 1, st.sequence(), st.lastAck(),
			s.inventory().mainHand().describe(ItemRegistry.get())));
		return out;
	}

	static List<String> inventory(final ClientSession s) {
		List<String> out = new ArrayList<>();
		PlayerInventory inv = s.inventory();
		ItemRegistry items = ItemRegistry.get();
		out.add(String.format(Locale.ROOT, "synced %s, stateId %d, selected slot %d, undecodable packets %d%s", inv.synced(), inv.stateId(), inv.selected() + 1,
			inv.unsupportedPackets(), inv.unsupportedPackets() > 0 ? " (last: " + inv.lastUnsupported() + ")" : ""));
		StringBuilder hb = new StringBuilder("hotbar:");
		for (int i = 0; i < 9; i++) {
			ItemStack st = inv.hotbar(i);
			hb.append(' ').append(i + 1).append('=').append(st.isEmpty() ? "-" : st.count() + "×" + items.name(st.itemId()).replace("minecraft:", ""));
		}
		out.add(hb.toString());
		ItemStack[] all = inv.snapshot();
		for (int i = 0; i < PlayerInventory.SLOTS; i++) {
			if (!all[i].isEmpty() && (i < PlayerInventory.HOTBAR_START || i == PlayerInventory.OFFHAND)) {
				out.add("slot " + i + ": " + all[i].describe(items));
			}
		}
		if (!inv.cursor().isEmpty()) {
			out.add("cursor: " + inv.cursor().describe(items));
		}
		return out;
	}

	static List<String> status(final ClientSession s) {
		BlockInteraction.Status st = s.interaction().status();
		List<String> out = new ArrayList<>();
		out.add(String.format(Locale.ROOT, "mode %s, instabuild %s, ranges block %.2f entity %.2f, break speed %.2f", MODES[Math.max(0, Math.min(3, s.gameMode()))],
			(s.abilities() & 8) != 0, s.blockInteractionRange(), s.entityInteractionRange(), s.blockBreakSpeed()));
		out.add(String.format(Locale.ROOT, "destroying %s%s progress %.3f stage %d, sequence %d, last ack %d, pending %d, missTime %d, destroyDelay %d",
			st.destroying(), st.destroying() ? " " + st.destroyX() + " " + st.destroyY() + " " + st.destroyZ() : "", st.progress(), st.stage(), st.sequence(),
			st.lastAck(), st.pendingPredictions(), st.missTime(), st.destroyDelay()));
		out.add("target: " + target(s));
		out.add("acks received " + s.stats().blockAcks() + ", server break progress entries " + s.destruction().size());
		return out;
	}
}
