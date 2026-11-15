package dev.mcresearch.protocolclient.cli;

import dev.mcresearch.protocolclient.entity.EntityManager;
import dev.mcresearch.protocolclient.session.ClientSession;
import dev.mcresearch.protocolclient.world.ItemRegistry;
import dev.mcresearch.protocolclient.world.ItemStack;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/** Terminalbefehle für Entities (M9, docs/37): Übersicht, maschinenlesbare Ausgabe für den Serverabgleich, Spielerliste, Zähler. */
public final class EntityCommands {
	private static final String[] SLOT_NAMES = {"mainhand", "offhand", "feet", "legs", "chest", "head", "body", "saddle"};

	private EntityCommands() {
	}

	/** {@code entities}: eine Zeile je Entity, nach Abstand sortiert. */
	static List<String> list(final ClientSession s) {
		EntityManager m = s.entities();
		List<EntityManager.View> all = new ArrayList<>(m.snapshot(1.0F, s.gameMode()));
		double px = s.player().x();
		double py = s.player().y();
		double pz = s.player().z();
		all.sort(java.util.Comparator.comparingDouble(v -> sq(v.x() - px) + sq(v.y() - py) + sq(v.z() - pz)));
		List<String> out = new ArrayList<>();
		out.add(all.size() + " entities, " + m.players().size() + " players in list, dimension " + s.dimensionKey());
		for (EntityManager.View v : all) {
			out.add(String.format(Locale.ROOT, "%5d %-18s (%.3f, %.3f, %.3f) yRot %.1f head %.1f dist %.1f%s", v.id(), v.type().shortName(), v.x(), v.y(), v.z(),
				v.yRot(), v.headYRot(), Math.sqrt(sq(v.x() - px) + sq(v.y() - py) + sq(v.z() - pz)), v.playerName() != null ? " " + v.playerName() : ""));
		}
		return out;
	}

	/**
	 * {@code edump [typ]}: je Entity eine Zeile {@code ENT id uuid typ x y z yRot xRot head onGround key=value …}; Positionen mit
	 * voller double-Genauigkeit für den Vergleich mit {@code data get entity} (Toleranz 1/8192, docs/34 §3.1).
	 */
	public static List<String> dump(final ClientSession s, final String typeFilter) {
		ItemRegistry items = ItemRegistry.get();
		List<String> out = new ArrayList<>();
		for (EntityManager.View v : s.entities().snapshot(1.0F, s.gameMode())) {
			if (typeFilter != null && !v.type().shortName().equals(typeFilter) && !v.type().name().equals(typeFilter)) {
				continue;
			}
			StringBuilder b = new StringBuilder();
			b.append("ENT ").append(v.id()).append(' ').append(v.uuid()).append(' ').append(v.type().name()).append(' ').append(v.x()).append(' ')
				.append(v.y()).append(' ').append(v.z()).append(' ').append(v.yRot()).append(' ').append(v.xRot()).append(' ').append(v.headYRot())
				.append(' ').append(v.onGround());
			if (v.health() != null) {
				b.append(" health=").append(v.health());
			}
			b.append(" pose=").append(v.pose()).append(" flags=").append(v.flags()).append(" baby=").append(v.baby());
			if (v.customName() != null) {
				b.append(" name=").append(v.customName().replace(' ', '_'));
			}
			if (v.playerName() != null) {
				b.append(" player=").append(v.playerName());
			}
			if (v.type().shortName().equals("sheep")) {
				b.append(" wool=").append(v.sheepWool() & 15).append(" sheared=").append((v.sheepWool() & 16) != 0);
			}
			if (!v.item().isEmpty()) {
				b.append(" item=").append(items.name(v.item().itemId())).append(':').append(v.item().count());
			}
			for (int i = 0; i < v.equipment().length; i++) {
				ItemStack st = v.equipment()[i];
				if (!st.isEmpty()) {
					b.append(" eq.").append(SLOT_NAMES[i]).append('=').append(items.name(st.itemId())).append(':').append(st.count());
				}
			}
			Double scale = v.attributes().get(30);
			if (scale != null) {
				b.append(" scale=").append(scale);
			}
			Double maxHealth = v.attributes().get(23);
			if (maxHealth != null) {
				b.append(" max_health=").append(maxHealth);
			}
			if (v.vehicle() >= 0) {
				b.append(" vehicle=").append(v.vehicle());
			}
			b.append(String.format(Locale.ROOT, " box=%.4fx%.4f", v.width(), v.height()));
			b.append(String.format(Locale.ROOT, " shown=(%.4f,%.4f,%.4f)", v.rx(), v.ry(), v.rz()));
			out.add(b.toString());
		}
		return out;
	}

	/** {@code etarget}: Auswahl zwischen Block und Entity (Phase F). */
	static String target(final ClientSession s) {
		dev.mcresearch.protocolclient.entity.EntityPick.Target t = s.entityTarget(1.0F);
		return switch (t.kind()) {
			case ENTITY -> String.format(Locale.ROOT, "ENTITY %d %s %s at (%.4f, %.4f, %.4f) dist %.4f", t.entity().id(), t.entity().type().name(), t.entity().uuid(),
				t.x(), t.y(), t.z(), t.distance());
			case BLOCK -> String.format(Locale.ROOT, "BLOCK %d %d %d at (%.4f, %.4f, %.4f) dist %.4f", t.block().blockX(), t.block().blockY(), t.block().blockZ(),
				t.x(), t.y(), t.z(), t.distance());
			case MISS -> String.format(Locale.ROOT, "MISS dist %.4f", t.distance());
		};
	}

	/** {@code players}: Spielerliste. */
	static List<String> players(final ClientSession s) {
		List<String> out = new ArrayList<>();
		for (EntityManager.PlayerInfo p : s.entities().players()) {
			Integer id = s.entities().idOf(p.uuid());
			out.add("PLAYER " + p.uuid() + " " + p.name() + " gamemode=" + p.gameMode() + " listed=" + p.listed() + " latency=" + p.latency()
				+ " skinProperty=" + p.hasSkinProperty() + " entity=" + (id == null ? "-" : id));
		}
		return out;
	}

	/** {@code estats}: Zähler des Entity-Systems. */
	static List<String> stats(final ClientSession s) {
		EntityManager.Stats t = s.entities().stats();
		List<String> out = new ArrayList<>();
		out.add("entities " + s.entities().size() + " (max " + t.maxEntities + "), spawned " + t.spawned + ", removed " + t.removed + ", cleared " + t.cleared
			+ ", duplicate spawns " + t.duplicateSpawns + ", unknown types " + t.unknownTypes);
		out.add("packets " + t.packetsHandled + ": move " + t.movePackets + " (stepped " + t.steppedMoves + "), sync " + t.syncPackets + ", teleport " + t.teleports
			+ ", rot " + t.rotationPackets + ", head " + t.headPackets + ", data " + t.dataPackets + ", attributes " + t.attributePackets + ", equipment "
			+ t.equipmentPackets + ", motion " + t.motionPackets + ", player info " + t.playerInfoUpdates);
		out.add("unknown-id packets " + t.unknownIdPackets + ", data unsupported " + t.dataUnsupported + ", equipment unsupported " + t.equipmentUnsupported
			+ ", teleport snaps " + t.snapsTeleport + (s.entities().lastUnsupported() != null ? ", last unsupported: " + s.entities().lastUnsupported() : ""));
		out.add(String.format(Locale.ROOT, "handle avg %.2f µs max %.1f µs | tick avg %.2f µs max %.1f µs over %d ticks",
			t.packetsHandled == 0 ? 0.0 : t.handleNanos / 1000.0 / t.packetsHandled, t.maxHandleNanos / 1000.0,
			t.ticks == 0 ? 0.0 : t.tickNanos / 1000.0 / t.ticks, t.maxTickNanos / 1000.0, t.ticks));
		return out;
	}

	private static double sq(final double v) {
		return v * v;
	}
}
