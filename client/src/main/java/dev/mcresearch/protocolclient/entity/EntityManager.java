package dev.mcresearch.protocolclient.entity;

import dev.mcresearch.protocolclient.protocol.packet.Clientbound;
import dev.mcresearch.protocolclient.protocol.packet.GameProfile;
import dev.mcresearch.protocolclient.world.ItemStack;
import java.util.ArrayList;
import java.util.Collection;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * Entity-Verwaltung (M9, docs/35). Wendet die Entity-Pakete aus docs/34 auf {@link ClientEntity}-Objekte an, fÃ¼hrt die
 * Spielerliste und treibt die Darstellungs-Interpolation im Client-Tick.
 *
 * <p>Threads: Pakete kommen aus dem Netzwerk-Thread, {@link #tick()} aus dem Tick-Thread, Abfragen aus Render-/Terminal-Thread.
 * Alle Ã¶ffentlichen Methoden sind Ã¼ber {@code this} synchronisiert und kurz (kein I/O, keine GPU), damit weder Keep-Alive noch
 * Netzwerk-Thread blockiert werden.
 */
public final class EntityManager {
	/** Abstand, ab dem eine absolute Positionsmeldung als Teleport gilt (grÃ¶Ãer als jedes kodierbare Delta, docs/35 Â§4). */
	public static final double TELEPORT_DISTANCE = 8.0;
	public static final long TICK_NANOS = 50_000_000L;

	/** Eintrag der Spielerliste ({@code player_info_update}). */
	public record PlayerInfo(UUID uuid, String name, List<GameProfile.Property> properties, int gameMode, boolean listed, int latency,
		String displayName, boolean showHat) {
		/** Der Server liefert eine Skin-Eigenschaft; sie wird nie geladen (keine externen Verbindungen, docs/36 Â§5). */
		public boolean hasSkinProperty() {
			for (GameProfile.Property p : this.properties) {
				if (p.name().equals("textures")) {
					return true;
				}
			}
			return false;
		}
	}

	/** ZÃ¤hler fÃ¼r Tests und Anzeige. */
	public static final class Stats {
		public long spawned, removed, cleared, unknownIdPackets, duplicateSpawns, dataUnsupported, equipmentUnsupported, unknownTypes,
			movePackets, steppedMoves, syncPackets, teleports, snapsTeleport, rotationPackets, headPackets, dataPackets, attributePackets,
			equipmentPackets, motionPackets, playerInfoUpdates, packetsHandled, handleNanos, maxHandleNanos, tickNanos, maxTickNanos, ticks, damageEvents,
			confirmedHits, passengerPackets, passengerCycles, ownMounts, ownDismounts, lateLinks, ignoredLocal;
		public int maxEntities;

		Stats copy() {
			Stats s = new Stats();
			s.spawned = spawned; s.removed = removed; s.cleared = cleared; s.unknownIdPackets = unknownIdPackets; s.duplicateSpawns = duplicateSpawns;
			s.dataUnsupported = dataUnsupported; s.equipmentUnsupported = equipmentUnsupported; s.unknownTypes = unknownTypes;
			s.movePackets = movePackets; s.steppedMoves = steppedMoves; s.syncPackets = syncPackets; s.teleports = teleports;
			s.snapsTeleport = snapsTeleport; s.rotationPackets = rotationPackets; s.headPackets = headPackets; s.dataPackets = dataPackets;
			s.attributePackets = attributePackets; s.equipmentPackets = equipmentPackets; s.motionPackets = motionPackets;
			s.playerInfoUpdates = playerInfoUpdates; s.packetsHandled = packetsHandled; s.handleNanos = handleNanos;
			s.maxHandleNanos = maxHandleNanos; s.tickNanos = tickNanos; s.maxTickNanos = maxTickNanos; s.ticks = ticks; s.maxEntities = maxEntities;
			s.damageEvents = damageEvents; s.confirmedHits = confirmedHits;
			s.passengerPackets = passengerPackets; s.passengerCycles = passengerCycles; s.ownMounts = ownMounts; s.ownDismounts = ownDismounts;
			s.lateLinks = lateLinks; s.ignoredLocal = ignoredLocal;
			return s;
		}
	}

	private final EntityTypes types;
	private final Map<Integer, ClientEntity> entities = new LinkedHashMap<>();
	private final Map<UUID, Integer> byUuid = new HashMap<>();
	private final Map<UUID, PlayerInfo> players = new LinkedHashMap<>();
	private final Stats stats = new Stats();
	/** M14: Varianten-Register (Katze, Wolf, Schwein, Kuh, Huhn) aus registry_data. */
	private final EntityVariants variants = new EntityVariants();
	private long tickCount;
	private long lastTickNanos = System.nanoTime();
	private int ownId = -1;
	/** M14: Fahrzeug des eigenen Spielers (set_passengers) oder -1. */
	private int ownVehicle = -1;
	private String lastUnsupported;

	public EntityManager() {
		this(EntityTypes.get());
	}

	public EntityManager(final EntityTypes types) {
		this.types = types;
	}

	/** Eigene Entity-ID ({@code login}); die eigene Spieler-Entity wird vom Server nicht gesendet und hier nie gefÃ¼hrt. */
	public synchronized void setOwnId(final int id) {
		this.ownId = id;
	}

	/** Verarbeitet ein Entity-Paket; {@code false}, wenn das Paket keines ist. */
	public synchronized boolean handle(final Clientbound packet) {
		long t0 = System.nanoTime();
		boolean handled = switch (packet) {
			case Clientbound.AddEntity p -> {
				add(p);
				yield true;
			}
			case Clientbound.RemoveEntities p -> {
				for (int id : p.ids()) {
					ClientEntity e = this.entities.remove(id);
					if (e != null) {
						this.byUuid.remove(e.uuid, e.id);
						this.stats.removed++;
						unlinkVehicle(e);
					}
				}
				yield true;
			}
			case Clientbound.MoveEntity p -> {
				move(p);
				yield true;
			}
			case Clientbound.EntityPositionSync p -> {
				sync(p);
				yield true;
			}
			case Clientbound.TeleportEntity p -> {
				teleport(p);
				yield true;
			}
			case Clientbound.RotateHead p -> {
				ClientEntity e = get(p.id());
				if (e != null) {
					e.headYRot = p.headYRot();
					e.display.setHeadTarget(p.headYRot());
					this.stats.headPackets++;
					e.updates++;
				}
				yield true;
			}
			case Clientbound.SetEntityMotion p -> {
				ClientEntity e = get(p.id());
				if (e != null) {
					e.motionX = p.dx();
					e.motionY = p.dy();
					e.motionZ = p.dz();
					this.stats.motionPackets++;
				}
				yield true;
			}
			case Clientbound.SetEntityData p -> {
				ClientEntity e = get(p.id());
				if (e != null) {
					for (Clientbound.DataValue v : p.values()) {
						e.data.put(v.index(), v.value());
					}
					this.stats.dataPackets++;
					e.updates++;
				}
				if (p.unsupported() != null) {
					this.stats.dataUnsupported++;
					this.lastUnsupported = "set_entity_data " + p.id() + " " + p.unsupported();
				}
				yield true;
			}
			case Clientbound.UpdateAttributes p -> {
				ClientEntity e = get(p.entityId());
				if (e != null) {
					for (Clientbound.UpdateAttributes.Attribute a : p.attributes()) {
						e.attributes.put(a.id(), a.value(-1.0e9, 1.0e9));
					}
					this.stats.attributePackets++;
				}
				yield p.entityId() != this.ownId;
			}
			case Clientbound.SetEquipment p -> {
				ClientEntity e = get(p.id());
				if (e != null) {
					for (Map.Entry<Integer, ItemStack> s : p.slots().entrySet()) {
						e.equipment[s.getKey()] = s.getValue();
					}
					this.stats.equipmentPackets++;
					e.updates++;
				}
				if (p.unsupported() != null) {
					this.stats.equipmentUnsupported++;
					this.lastUnsupported = "set_equipment " + p.id() + " " + p.unsupported();
				}
				yield true;
			}
			case Clientbound.SwingAnimation p -> {
				ClientEntity e = get(p.id());
				if (e != null) {
					e.swingTick = this.tickCount;
					e.swingHand = p.hand();
				}
				yield true;
			}
			case Clientbound.DamageEvent p -> {
				// M10: Schaden an einer anderen Entity; Verursacher = eigener Spieler â vom Server bestÃ¤tigter Treffer
				ClientEntity e = get(p.entityId());
				if (e != null) {
					e.hurtTick = this.tickCount;
					e.damageEvents++;
					e.lastDamageType = p.damageType();
					if (p.causeId() == this.ownId && this.ownId >= 0) {
						e.confirmedHits++;
						this.stats.confirmedHits++;
					}
				}
				this.stats.damageEvents++;
				yield true;
			}
			case Clientbound.HurtAnimation p -> {
				ClientEntity e = get(p.id());
				if (e != null) {
					e.hurtTick = this.tickCount;
				}
				yield true;
			}
			case Clientbound.EntityEvent p -> {
				ClientEntity e = get(p.id());
				if (e != null) {
					e.lastEvent = p.event();
					e.anim.event(e, p.event());
				}
				yield true;
			}
			case Clientbound.Animate p -> {
				get(p.id());
				yield true;
			}
			case Clientbound.TakeItemEntity p -> true; // die Entfernung folgt mit remove_entities
			case Clientbound.SetPassengers p -> {
				setPassengers(p);
				yield true;
			}
			case Clientbound.PlayerInfoUpdate p -> {
				playerInfo(p);
				yield true;
			}
			case Clientbound.PlayerInfoRemove p -> {
				for (UUID u : p.uuids()) {
					this.players.remove(u);
				}
				yield true;
			}
			default -> false;
		};
		if (handled) {
			long dt = System.nanoTime() - t0;
			this.stats.packetsHandled++;
			this.stats.handleNanos += dt;
			this.stats.maxHandleNanos = Math.max(this.stats.maxHandleNanos, dt);
		}
		return handled;
	}

	/** Liefert die Entity; Pakete fÃ¼r unbekannte IDs werden gezÃ¤hlt und ignoriert (wie Vanilla). */
	private ClientEntity get(final int id) {
		ClientEntity e = this.entities.get(id);
		if (e == null && id != this.ownId) {
			this.stats.unknownIdPackets++;
		}
		return e;
	}

	private void add(final Clientbound.AddEntity p) {
		if (p.id() == this.ownId) {
			return;
		}
		EntityTypes.Type type = this.types.type(p.type());
		if (type == EntityTypes.UNKNOWN) {
			this.stats.unknownTypes++;
		}
		ClientEntity old = this.entities.remove(p.id());
		if (old != null) {
			// wie ClientLevel#addEntity: dieselbe ID ersetzt die alte Entity
			this.byUuid.remove(old.uuid, old.id);
			this.stats.duplicateSpawns++;
		}
		Integer sameUuid = this.byUuid.get(p.uuid());
		if (sameUuid != null && sameUuid != p.id()) {
			this.entities.remove(sameUuid);
			this.stats.duplicateSpawns++;
		}
		ClientEntity e = new ClientEntity(p.id(), p.uuid(), type, this.tickCount);
		e.x = e.baseX = p.x();
		e.y = e.baseY = p.y();
		e.z = e.baseZ = p.z();
		e.yRot = p.yRot();
		e.xRot = p.xRot();
		e.headYRot = p.headYRot();
		e.motionX = p.dx();
		e.motionY = p.dy();
		e.motionZ = p.dz();
		e.display.snap(p.x(), p.y(), p.z(), p.yRot(), p.xRot());
		e.display.snapHead(p.headYRot());
		this.entities.put(e.id, e);
		this.byUuid.put(e.uuid, e.id);
		// M14: set_passengers kann vor dem Spawn des Passagiers ankommen → beim Spawn verknüpfen
		for (ClientEntity v : this.entities.values()) {
			for (int pid : v.passengers) {
				if (pid == e.id && v != e) {
					e.vehicle = v.id;
					this.stats.lateLinks++;
				}
			}
		}
		this.stats.spawned++;
		this.stats.maxEntities = Math.max(this.stats.maxEntities, this.entities.size());
	}

	/** {@code VecDeltaCodec#decode}: Achse unverÃ¤ndert bei Delta 0, sonst (round(Basis Â· 4096) + Delta) / 4096. */
	static double decodeAxis(final double base, final short delta) {
		return delta == 0 ? base : (Math.round(base * 4096.0) + delta) / 4096.0;
	}

	private void move(final Clientbound.MoveEntity p) {
		ClientEntity e = get(p.id());
		if (e == null) {
			return;
		}
		e.updates++;
		if (e.localControl) {
			this.stats.ignoredLocal++; // M14: selbst gesteuertes Fahrzeug (ClientPacketListener: isLocalInstanceAuthoritative)
			return;
		}
		e.onGround = p.onGround();
		if (p.hasPos()) {
			this.stats.movePackets++;
			if (p.stepped()) {
				this.stats.steppedMoves++;
			}
			int n = p.steps().size();
			float y0 = e.yRot;
			float x0 = e.xRot;
			int total = 0;
			for (Clientbound.DeltaStep s : p.steps()) {
				total += Math.max(1, s.ticks());
			}
			int acc = 0;
			for (int i = 0; i < n; i++) {
				Clientbound.DeltaStep s = p.steps().get(i);
				e.baseX = decodeAxis(e.baseX, s.dx());
				e.baseY = decodeAxis(e.baseY, s.dy());
				e.baseZ = decodeAxis(e.baseZ, s.dz());
				e.x = e.baseX;
				e.y = e.baseY;
				e.z = e.baseZ;
				if (p.hasRot()) {
					acc += Math.max(1, s.ticks());
					float a = p.stepped() ? (float) acc / total : 1.0F;
					e.display.addTarget(e.x, e.y, e.z, y0 + EntityInterpolator.wrapDegrees(p.yRot() - y0) * a, x0 + (p.xRot() - x0) * a, s.ticks());
				} else {
					e.display.addPosition(e.x, e.y, e.z, s.ticks());
				}
			}
			if (p.hasRot()) {
				e.yRot = p.yRot();
				e.xRot = p.xRot();
			}
		} else if (p.hasRot()) {
			this.stats.rotationPackets++;
			e.yRot = p.yRot();
			e.xRot = p.xRot();
			e.display.addRotation(p.yRot(), p.xRot());
		}
	}

	private void sync(final Clientbound.EntityPositionSync p) {
		ClientEntity e = get(p.id());
		if (e == null) {
			return;
		}
		this.stats.syncPackets++;
		e.updates++;
		if (e.localControl) {
			this.stats.ignoredLocal++;
			return;
		}
		Clientbound.PathStep end = p.path().getLast();
		double dist = Math.sqrt(sq(end.x() - e.x) + sq(end.y() - e.y) + sq(end.z() - e.z));
		e.x = e.baseX = end.x();
		e.y = e.baseY = end.y();
		e.z = e.baseZ = end.z();
		e.yRot = p.yRot();
		e.xRot = p.xRot();
		e.onGround = p.onGround();
		if (dist > TELEPORT_DISTANCE) {
			this.stats.snapsTeleport++;
			e.display.snap(e.x, e.y, e.z, e.yRot, e.xRot);
		} else if (p.stepped()) {
			for (Clientbound.PathStep s : p.path()) {
				e.display.addTarget(s.x(), s.y(), s.z(), p.yRot(), p.xRot(), s.ticks());
			}
		} else {
			e.display.addTarget(e.x, e.y, e.z, p.yRot(), p.xRot(), 0);
		}
	}

	/**
	 * {@code teleport_entity}: absolute oder teilrelative Werte bezogen auf den bestÃ¤tigten Zustand. Die Delta-Basis bleibt wie in
	 * Vanilla unverÃ¤ndert ({@code setValuesFromPositionPacket} Ã¤ndert den PositionCodec nicht; auch der Server setzt seine Basis dabei
	 * nicht neu), damit folgende relative Bewegungen gleich dekodiert werden (docs/34 Â§3.2).
	 */
	private void teleport(final Clientbound.TeleportEntity p) {
		ClientEntity e = get(p.id());
		if (e == null) {
			return;
		}
		this.stats.teleports++;
		e.updates++;
		if (e.localControl) {
			this.stats.ignoredLocal++;
			return;
		}
		int r = p.relatives();
		e.x = ((r & 1) != 0 ? e.x : 0) + p.x();
		e.y = ((r & 2) != 0 ? e.y : 0) + p.y();
		e.z = ((r & 4) != 0 ? e.z : 0) + p.z();
		e.yRot = ((r & 8) != 0 ? e.yRot : 0) + p.yRot();
		e.xRot = ((r & 16) != 0 ? e.xRot : 0) + p.xRot();
		e.onGround = p.onGround();
		e.display.snap(e.x, e.y, e.z, e.yRot, e.xRot);
		this.stats.snapsTeleport++;
	}

	/**
	 * M14 Phase F (docs/57 Â§2): {@code set_passengers} wie {@code ClientPacketListener#handleSetEntityPassengersPacket}: bisherige
	 * Passagiere absteigen lassen, neue aufsitzen lassen (auch der eigene Spieler, der nicht in der Entity-Liste steht). Zyklen
	 * (Passagier ist das Fahrzeug selbst oder einer seiner TrÃ¤ger) werden verworfen; ein Passagier, der noch auf einem anderen Fahrzeug
	 * sitzt, wechselt (Vanilla {@code startRiding(â¦, force)}).
	 */
	private void setPassengers(final Clientbound.SetPassengers p) {
		ClientEntity v = this.entities.get(p.vehicleId());
		if (v == null) {
			if (p.vehicleId() != this.ownId) {
				this.stats.unknownIdPackets++;
			}
			return;
		}
		// Vanilla wasPlayerMounted: der Server sendet beim Einsteigen dieselbe Liste doppelt (ServerPlayer#startRiding direkt + Tracker);
		// bleibt der eigene Spieler auf diesem Fahrzeug, ist das weder Ab- noch Aufsteigen
		boolean ownStays = this.ownId >= 0 && this.ownVehicle == v.id && java.util.Arrays.stream(p.passengers()).anyMatch(id -> id == this.ownId);
		for (int old : v.passengers) {
			ClientEntity o = this.entities.get(old);
			if (o != null && o.vehicle == v.id) {
				o.vehicle = -1;
			}
			if (old == this.ownId && this.ownVehicle == v.id && !ownStays) {
				this.ownVehicle = -1;
				v.localControl = false;
				this.stats.ownDismounts++;
			}
		}
		java.util.List<Integer> accepted = new java.util.ArrayList<>();
		for (int pid : p.passengers()) {
			if (pid == v.id || isCarrierOf(pid, v)) {
				this.stats.passengerCycles++;
				continue;
			}
			if (pid == this.ownId && this.ownId >= 0) {
				if (this.ownVehicle != v.id) {
					this.stats.ownMounts++;
					ClientEntity prevOwn = this.entities.get(this.ownVehicle);
					if (prevOwn != null) {
						prevOwn.localControl = false;
					}
				}
				this.ownVehicle = v.id;
				accepted.add(pid);
				continue;
			}
			ClientEntity o = this.entities.get(pid);
			if (o != null && o.vehicle >= 0 && o.vehicle != v.id) {
				ClientEntity prev = this.entities.get(o.vehicle);
				if (prev != null) {
					prev.passengers = java.util.Arrays.stream(prev.passengers).filter(id -> id != pid).toArray();
				}
			}
			if (o != null) {
				o.vehicle = v.id;
			}
			accepted.add(pid); // auch noch nicht gespawnte Passagiere merken (VerknÃ¼pfung beim Spawn)
		}
		v.passengers = accepted.stream().mapToInt(Integer::intValue).toArray();
		this.stats.passengerPackets++;
	}

	/** true, wenn {@code id} das Fahrzeug {@code v} (direkt oder Ã¼ber weitere Fahrzeuge) trÃ¤gt â Aufsitzen wÃ¼rde einen Zyklus bilden. */
	private boolean isCarrierOf(final int id, final ClientEntity v) {
		ClientEntity cur = v;
		for (int guard = 0; cur != null && cur.vehicle >= 0 && guard < 64; guard++) {
			if (cur.vehicle == id) {
				return true;
			}
			cur = this.entities.get(cur.vehicle);
		}
		return false;
	}

	/**
	 * M14 Phase J (docs/57 §6): Kollisionsboxen fester Entities für die eigene Bewegung ({@code Level#getEntityCollisions} mit
	 * {@code canBeCollidedWith}: Boote und Shulker), ohne das eigene Fahrzeug (Passagiere desselben Fahrzeugs kollidieren nicht).
	 * Position = Darstellung nach dem letzten Tick.
	 */
	public synchronized List<dev.mcresearch.protocolclient.physics.Aabb> collisionBoxes() {
		List<dev.mcresearch.protocolclient.physics.Aabb> out = new ArrayList<>();
		for (ClientEntity e : this.entities.values()) {
			String t = e.type.shortName();
			boolean solid = t.endsWith("_boat") || t.endsWith("_raft") || t.equals("shulker");
			if (!solid || e.id == this.ownVehicle) {
				continue;
			}
			double[] p = renderPos(e, 1.0F, 0);
			out.add(e.boundingBox(p[0], p[1], p[2]));
		}
		return out;
	}

	/**
	 * M14 Phase J: Schiebeimpuls auf den eigenen Spieler ({@code Entity#push(Entity)} aus dem {@code pushEntities} der anderen
	 * Lebewesen und Boote): je überlappender, schiebbarer Entity (lebend, nicht Zuschauer; Boote) ±0,05 · min(1, 1/√d) weg von ihr.
	 * Nicht auf demselben Fahrzeug. Rückgabe {dx, dz}.
	 */
	public synchronized double[] pushImpulse(final dev.mcresearch.protocolclient.physics.Aabb playerBox, final double px, final double pz) {
		double sx = 0;
		double sz = 0;
		for (ClientEntity e : this.entities.values()) {
			String t = e.type.shortName();
			boolean boat = t.endsWith("_boat") || t.endsWith("_raft");
			Float health = e.health();
			boolean pushable = boat || (e.living() && !e.isArmorStand() && (health == null || health > 0));
			if (!pushable || e.id == this.ownVehicle || (this.ownVehicle >= 0 && e.vehicle == this.ownVehicle)) {
				continue;
			}
			if (e.isPlayer() && this.players.get(e.uuid) != null && this.players.get(e.uuid).gameMode() == 3) {
				continue; // Zuschauer
			}
			double[] p = renderPos(e, 1.0F, 0);
			dev.mcresearch.protocolclient.physics.Aabb box = e.boundingBox(p[0], p[1], p[2]);
			if (boat) {
				// AbstractBoat#tick: getEntities(getBoundingBox().inflate(0.2F, -0.01F, 0.2F), pushableBy) → push(entity)
				box = new dev.mcresearch.protocolclient.physics.Aabb(box.minX() - 0.2F, box.minY() + 0.01F, box.minZ() - 0.2F, box.maxX() + 0.2F,
					box.maxY() - 0.01F, box.maxZ() + 0.2F);
			}
			if (!box.intersects(playerBox)) {
				continue;
			}
			double xa = p[0] - px;
			double za = p[2] - pz;
			double dd = Math.max(Math.abs(xa), Math.abs(za));
			if (dd < 0.01F) {
				continue;
			}
			dd = Math.sqrt(dd);
			xa /= dd;
			za /= dd;
			double pow = Math.min(1.0, 1.0 / dd);
			sx -= xa * pow * 0.05F;
			sz -= za * pow * 0.05F;
		}
		return new double[]{sx, sz};
	}

	/**
	 * M14 Phase G/I: Position des selbst gesteuerten Fahrzeugs (Boot, Pferd) aus der eigenen Physik übernehmen – der Client ist
	 * dafür autoritativ; Serverkorrekturen kommen über {@code move_vehicle}.
	 */
	public synchronized void setLocalVehicle(final int id, final double x, final double y, final double z, final float yRot, final float xRot) {
		ClientEntity e = this.entities.get(id);
		if (e == null) {
			return;
		}
		e.x = e.baseX = x;
		e.y = e.baseY = y;
		e.z = e.baseZ = z;
		e.yRot = yRot;
		e.xRot = xRot;
		e.display.addTarget(x, y, z, yRot, xRot, 1);
		e.localControl = true;
	}

	/** Entity-Momentaufnahme des eigenen Fahrzeugs (Teiltick 1) oder null. */
	public synchronized View ownVehicleView() {
		ClientEntity v = this.entities.get(this.ownVehicle);
		return v == null ? null : view(v, 1.0F, -1);
	}

	/** M14: eigenes Fahrzeug und seine anderen Passagiere sind nicht auswählbar (getEntityHitResult: gleiche Wurzel, canRiderInteract false). */
	private boolean sameRootVehicle(final ClientEntity e) {
		if (this.ownVehicle < 0) {
			return false;
		}
		int root = rootOf(this.ownVehicle);
		return rootOf(e.id) == root;
	}

	private int rootOf(final int id) {
		int cur = id;
		for (int guard = 0; guard < 64; guard++) {
			ClientEntity c = this.entities.get(cur);
			if (c == null || c.vehicle < 0) {
				return cur;
			}
			cur = c.vehicle;
		}
		return cur;
	}

	/** Eigenes Fahrzeug (Entity-ID) oder -1. */
	public synchronized int ownVehicle() {
		return this.ownVehicle;
	}

	/** Index des eigenen Spielers in der Passagierliste seines Fahrzeugs (0 = Fahrer) oder -1. */
	public synchronized int ownSeatIndex() {
		ClientEntity v = this.entities.get(this.ownVehicle);
		if (v == null) {
			return -1;
		}
		for (int i = 0; i < v.passengers.length; i++) {
			if (v.passengers[i] == this.ownId) {
				return i;
			}
		}
		return -1;
	}

	/**
	 * Sitzposition (FÃ¼Ãe) des eigenen Spielers bei Teiltick {@code pt} ({@code positionRider} des Fahrzeugs; Darstellung) oder null.
	 * {@code ownType}: Typ des eigenen Spielers (Ankerpunkt VEHICLE).
	 */
	public synchronized double[] ownSeat(final float pt) {
		ClientEntity v = this.entities.get(this.ownVehicle);
		int idx = ownSeatIndex();
		if (v == null || idx < 0) {
			return null;
		}
		double[] vp = renderPos(v, pt, 0);
		double[] off = PassengerSeats.offset(v.type, v.baby(), v.living() ? v.scale() : 1.0, idx, v.passengers.length, this.types.byName("player"), false,
			v.display.renderYRot(pt), v.anim.standAnimO);
		return new double[]{vp[0] + off[0], vp[1] + off[1], vp[2] + off[2]};
	}

	/** Sitzposition nach dem letzten Tick (Teiltick 1) â Grundlage fÃ¼r die eigene Position beim Reiten. */
	public synchronized double[] ownSeatTick() {
		return ownSeat(1.0F);
	}

	/** Darstellungsposition; Passagiere folgen ihrem Fahrzeug (rekursiv, mit Tiefenschutz) statt ihrer eigenen Interpolation. */
	private double[] renderPos(final ClientEntity e, final float pt, final int depth) {
		ClientEntity v = e.vehicle >= 0 ? this.entities.get(e.vehicle) : null;
		if (v == null || depth > 16) {
			return new double[]{e.display.renderX(pt), e.display.renderY(pt), e.display.renderZ(pt)};
		}
		int idx = 0;
		for (int i = 0; i < v.passengers.length; i++) {
			if (v.passengers[i] == e.id) {
				idx = i;
			}
		}
		double[] vp = renderPos(v, pt, depth + 1);
		double[] off = PassengerSeats.offset(v.type, v.baby(), v.living() ? v.scale() : 1.0, idx, v.passengers.length, e.type, e.baby(), v.display
			.renderYRot(pt), v.anim.standAnimO);
		return new double[]{vp[0] + off[0], vp[1] + off[1], vp[2] + off[2]};
	}

	private void unlinkVehicle(final ClientEntity e) {
		if (e.id == this.ownVehicle) {
			this.ownVehicle = -1; // Fahrzeug entfernt: eigener Spieler steigt ab
			this.stats.ownDismounts++;
		}
		if (e.vehicle >= 0) {
			ClientEntity v = this.entities.get(e.vehicle);
			if (v != null) {
				v.passengers = java.util.Arrays.stream(v.passengers).filter(id -> id != e.id).toArray();
			}
		}
		for (int pid : e.passengers) {
			ClientEntity o = this.entities.get(pid);
			if (o != null && o.vehicle == e.id) {
				o.vehicle = -1;
			}
		}
	}

	private void playerInfo(final Clientbound.PlayerInfoUpdate p) {
		this.stats.playerInfoUpdates++;
		for (Clientbound.PlayerInfoEntry n : p.entries()) {
			PlayerInfo old = this.players.get(n.uuid());
			if (old == null && (p.actions() & 1) == 0) {
				continue; // Aktualisierung ohne vorheriges ADD_PLAYER: wie Vanilla ignorieren
			}
			int a = p.actions();
			PlayerInfo base = old != null ? old : new PlayerInfo(n.uuid(), n.name(), n.properties(), -1, false, 0, null, false);
			this.players.put(n.uuid(), new PlayerInfo(n.uuid(),
				(a & 1) != 0 ? n.name() : base.name(),
				(a & 1) != 0 ? n.properties() : base.properties(),
				(a & 4) != 0 ? n.gameMode() : base.gameMode(),
				(a & 8) != 0 ? n.listed() : base.listed(),
				(a & 16) != 0 ? n.latency() : base.latency(),
				(a & 32) != 0 ? n.displayName() : base.displayName(),
				(a & 128) != 0 ? n.showHat() : base.showHat()));
		}
	}

	private static double sq(final double v) {
		return v * v;
	}

	/** Ein Client-Tick (Tick-Thread, 20/s): Interpolation weiterschalten. */
	public synchronized void tick() {
		long t0 = System.nanoTime();
		this.tickCount++;
		for (ClientEntity e : this.entities.values()) {
			e.display.tick();
			e.anim.tick(e, e.display.x(), e.display.y(), e.display.z(), this.tickCount); // M14: AnimationszustÃ¤nde (docs/55 Â§4)
		}
		long dt = System.nanoTime() - t0;
		this.lastTickNanos = t0;
		this.stats.ticks++;
		this.stats.tickNanos += dt;
		this.stats.maxTickNanos = Math.max(this.stats.maxTickNanos, dt);
	}

	/** Weltwechsel ({@code respawn} in andere Dimension), Konfigurationsphase, Trennung: alle Entities verwerfen. */
	public synchronized void clear(final boolean alsoPlayerList) {
		if (!this.entities.isEmpty()) {
			this.stats.cleared++;
		}
		this.entities.clear();
		this.byUuid.clear();
		this.ownVehicle = -1;
		if (alsoPlayerList) {
			this.players.clear();
		}
	}

	// ================= Abfragen =================

	public synchronized int size() {
		return this.entities.size();
	}

	public synchronized long tickCount() {
		return this.tickCount;
	}

	/** Anteil des laufenden Ticks (0â1) fÃ¼r die Darstellung zwischen zwei Ticks. */
	public synchronized float partialTick(final long nowNanos) {
		return (float) Math.clamp((double) (nowNanos - this.lastTickNanos) / TICK_NANOS, 0.0, 1.0);
	}

	public EntityVariants variants() {
		return this.variants;
	}

	public synchronized Stats stats() {
		return this.stats.copy();
	}

	public synchronized String lastUnsupported() {
		return this.lastUnsupported;
	}

	public synchronized PlayerInfo playerInfo(final UUID uuid) {
		return this.players.get(uuid);
	}

	public synchronized List<PlayerInfo> players() {
		return List.copyOf(this.players.values());
	}

	public synchronized Integer idOf(final UUID uuid) {
		return this.byUuid.get(uuid);
	}

	/**
	 * UnverÃ¤nderliche Momentaufnahme einer Entity (bestÃ¤tigter Zustand + Darstellung bei {@code partialTick}) fÃ¼r Terminal,
	 * Renderer und Auswahl.
	 */
	public record View(int id, UUID uuid, EntityTypes.Type type, double x, double y, double z, float yRot, float xRot, float headYRot,
		boolean onGround, double rx, double ry, double rz, float rYRot, float rXRot, float rHead, float width, float height, int pose,
		int flags, boolean baby, int sheepWool, ItemStack item, ItemStack[] equipment, String customName, String playerName, boolean skinProperty,
		Float health, double walkSpeed, long ageTicks, float swingProgress, int swingHand, float hurtProgress, int vehicle, boolean living,
		boolean pickable, int skinParts, Map<Integer, Object> data, Map<Integer, Double> attributes, long updates, int damageEvents, int confirmedHits,
		double motionX, double motionY, double motionZ, EntityAnimationState.Snapshot anim, int[] passengers) {
	}

	/** Alle Entities als {@link View} ({@code playerGameMode}: Spielmodus des eigenen Spielers, derzeit nur durchgereicht). */
	public synchronized List<View> snapshot(final float partialTick, final int playerGameMode) {
		List<View> out = new ArrayList<>(this.entities.size());
		for (ClientEntity e : this.entities.values()) {
			out.add(view(e, partialTick, playerGameMode));
		}
		return out;
	}

	public synchronized View view(final int id, final float partialTick, final int playerGameMode) {
		ClientEntity e = this.entities.get(id);
		return e == null ? null : view(e, partialTick, playerGameMode);
	}

	private View view(final ClientEntity e, final float pt, final int gm) {
		float[] d = e.dimensions();
		PlayerInfo info = e.isPlayer() ? this.players.get(e.uuid) : null;
		float swing = e.swingTick == Long.MIN_VALUE ? 0 : (this.tickCount - e.swingTick + pt) / 6.0F;
		float hurt = e.hurtTick == Long.MIN_VALUE ? 0 : (this.tickCount - e.hurtTick + pt) / 10.0F;
		ItemStack[] eq = e.equipment.clone();
		double[] rp = renderPos(e, pt, 0); // M14: Passagiere sitzen auf ihrem Fahrzeug (Phase F)
		return new View(e.id, e.uuid, e.type, e.x, e.y, e.z, e.yRot, e.xRot, e.headYRot, e.onGround, rp[0], rp[1],
			rp[2], e.display.renderYRot(pt), e.display.renderXRot(pt), e.display.renderHead(pt), d[0], d[1], e.pose(), e.sharedFlags(),
			e.baby(), e.sheepWool(), e.item(), eq, e.customName(), info == null ? null : info.name(), info != null && info.hasSkinProperty(),
			e.health(), e.display.horizontalSpeed(), this.tickCount - e.spawnTick, swing >= 1 ? 0 : swing, e.swingHand, hurt >= 1 ? 0 : hurt,
			e.vehicle, e.living(), e.pickable(info == null ? -1 : info.gameMode()) && !sameRootVehicle(e), e.skinParts(), Map.copyOf(e.data.entrySet().stream().filter(x -> x.getValue() != null)
				.collect(java.util.stream.Collectors.toMap(Map.Entry::getKey, Map.Entry::getValue))), Map.copyOf(e.attributes), e.updates, e.damageEvents, e.confirmedHits, e.motionX, e.motionY, e.motionZ, e.anim.snapshot(pt, this.tickCount), e.passengers.clone());
	}

	public synchronized Collection<Integer> ids() {
		return List.copyOf(this.entities.keySet());
	}
}
