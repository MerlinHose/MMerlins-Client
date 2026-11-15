package dev.mcresearch.protocolclient.protocol.packet;

import java.util.List;

/** Vom Client dekodierte Serverpakete (docs/12 §5). Alle übrigen gültigen IDs werden als {@link Skipped} geliefert. */
public sealed interface Clientbound {
	// ----- Status -----
	record StatusResponse(String json, String versionName, int protocol, int onlinePlayers, int maxPlayers, String description) implements Clientbound {
	}

	record PongResponse(long time) implements Clientbound {
	}

	// ----- Login -----
	record LoginDisconnect(String reason) implements Clientbound {
	}

	/**
	 * Online-Modus-Anfrage ({@code hello}, docs/51 §2). M13: wird mit RSA-Schlüsselaustausch und AES/CFB8 beantwortet.
	 *
	 * @param publicKey X.509-DER-kodierter RSA-Schlüssel des Servers
	 * @param challenge vom Server gewählte Prüfbytes (Vanilla: 4 Bytes), werden RSA-verschlüsselt zurückgeschickt
	 */
	record EncryptionRequest(String serverId, byte[] publicKey, byte[] challenge, boolean shouldAuthenticate) implements Clientbound {
		public int publicKeyLength() {
			return this.publicKey.length;
		}

		@Override
		public String toString() {
			return "EncryptionRequest[serverId=" + this.serverId + ", publicKey=" + this.publicKey.length + " B, challenge=" + this.challenge.length
				+ " B, shouldAuthenticate=" + this.shouldAuthenticate + "]";
		}
	}

	/** @param sessionId Server-Sitzungskennung ({@code ServerConnectionListener#getSessionId}) */
	record LoginFinished(GameProfile profile, java.util.UUID sessionId) implements Clientbound {
	}

	record LoginCompression(int threshold) implements Clientbound {
	}

	record CustomQuery(int transactionId, String channel, int payloadBytes) implements Clientbound {
	}

	record CookieRequest(String key) implements Clientbound {
	}

	// ----- Configuration und Play (gemeinsam) -----
	record CustomPayload(String channel, String brand, int payloadBytes) implements Clientbound {
	}

	record Disconnect(String reason) implements Clientbound {
	}

	record KeepAlive(long id) implements Clientbound {
	}

	record Ping(int id) implements Clientbound {
	}

	// ----- Configuration -----
	record FinishConfiguration() implements Clientbound {
	}

	/** @param data dekodiertes NBT (Map/List/…) oder {@code null}, wenn der Server den Inhalt weggelassen hat */
	record RegistryEntry(String id, boolean hasData, Object data) {
		public RegistryEntry(final String id, final boolean hasData) {
			this(id, hasData, null);
		}
	}

	record RegistryData(String registry, List<RegistryEntry> entries) implements Clientbound {
	}

	record UpdateEnabledFeatures(List<String> features) implements Clientbound {
	}

	record UpdateTags(int registries, int tags, int values) implements Clientbound {
	}

	record SelectKnownPacks(List<KnownPack> packs) implements Clientbound {
	}

	// ----- Play -----
	record PlayLogin(int entityId, boolean hardcore, List<String> levels, int maxPlayers, int chunkRadius, int simulationDistance,
		boolean reducedDebugInfo, boolean showDeathScreen, boolean doLimitedCrafting, int dimensionTypeId, String dimension, long seed,
		int gameType, int previousGameType, boolean isDebug, boolean isFlat, String lastDeathDimension, long lastDeathPos,
		int portalCooldown, int seaLevel, boolean onlineMode, boolean enforcesSecureChat) implements Clientbound {
	}

	/** Bits in {@code relatives}: 0 X, 1 Y, 2 Z, 3 Y_ROT, 4 X_ROT, 5–7 DELTA_X/Y/Z, 8 ROTATE_DELTA. */
	record PlayerPosition(int teleportId, double x, double y, double z, double dx, double dy, double dz, float yRot, float xRot, int relatives)
		implements Clientbound {
		public static final int REL_X = 1;
		public static final int REL_Y = 1 << 1;
		public static final int REL_Z = 1 << 2;
		public static final int REL_Y_ROT = 1 << 3;
		public static final int REL_X_ROT = 1 << 4;
		public static final int REL_DELTA_X = 1 << 5;
		public static final int REL_DELTA_Y = 1 << 6;
		public static final int REL_DELTA_Z = 1 << 7;
		public static final int ALL_KNOWN_BITS = (1 << 9) - 1;
	}

	/** {@code player_rotation} (0x4A): Rotation setzen bzw. relativ ändern. */
	record PlayerRotation(float yRot, boolean relativeY, float xRot, boolean relativeX) implements Clientbound {
	}

	/**
	 * Heightmaps eines Chunks (aus {@link LevelChunkWithLight}); Eingabe für {@code TerrainCache} (docs/15 §4).
	 * Kein eigenes Paket mehr: seit M4 wird {@code level_chunk_with_light} vollständig dekodiert.
	 *
	 * @param heightmaps Heightmap-Typ-ID (0–5, {@code Heightmap.Types}) → gepackte Long-Werte
	 */
	record ChunkHeightmaps(int chunkX, int chunkZ, java.util.Map<Integer, long[]> heightmaps, int size) {
		public static final int MOTION_BLOCKING = 4;
	}

	/** {@code set_health}: Lebenspunkte (≤ 0 = tot, wartet auf {@code client_command PERFORM_RESPAWN}), Hunger, Sättigung. */
	record SetHealth(float health, int food, float saturation) implements Clientbound {
	}

	/** {@code update_attributes} (M7): Grundwert und Modifikatoren je Attribut (ID im festen Register {@code minecraft:attribute}). */
	record UpdateAttributes(int entityId, java.util.List<Attribute> attributes) implements Clientbound {
		public UpdateAttributes {
			attributes = java.util.List.copyOf(attributes);
		}

		public record Attribute(int id, double base, java.util.List<Modifier> modifiers) {
			public Attribute {
				modifiers = java.util.List.copyOf(modifiers);
			}

			/** {@code AttributeInstance#calculateValue}: ADD_VALUE, dann ADD_MULTIPLIED_BASE, dann ADD_MULTIPLIED_TOTAL; danach Bereich. */
			public double value(final double min, final double max) {
				double b = this.base;
				for (Modifier m : this.modifiers) {
					if (m.operation() == 0) {
						b += m.amount();
					}
				}
				double result = b;
				for (Modifier m : this.modifiers) {
					if (m.operation() == 1) {
						result += b * m.amount();
					}
				}
				for (Modifier m : this.modifiers) {
					if (m.operation() == 2) {
						result *= 1.0 + m.amount();
					}
				}
				return Double.isNaN(result) ? min : Math.max(min, Math.min(max, result)); // RangedAttribute#sanitizeValue
			}
		}

		public record Modifier(String id, double amount, int operation) {
		}
	}

	/** {@code respawn}: Dimensionswechsel oder Wiederbelebung; der Client verwirft dabei alle Chunks. */
	record Respawn(int dimensionTypeId, String dimension, int gameType, int dataToKeep, long seed) implements Clientbound {
	}

	record ForgetChunk(int chunkX, int chunkZ) implements Clientbound {
	}

	/** Block-Entity im Chunk-Paket: lokale x/z (0–15), absolute y, Typ-ID, optionales NBT-Compound (docs/18 §2.1). */
	record BlockEntityInfo(int localX, int localZ, int y, int typeId, java.util.Map<String, Object> nbt) {
	}

	/**
	 * Lichtdaten (docs/18 §5). Masken-Bit i ↔ Lichtsektion {@code minSectionY − 1 + i}.
	 * {@code skyArrays}/{@code blockArrays} enthalten je gesetztem Masken-Bit (aufsteigend) ein Nibble-Array.
	 */
	record LightData(java.util.BitSet skyMask, java.util.BitSet blockMask, java.util.BitSet emptySkyMask, java.util.BitSet emptyBlockMask,
		List<byte[]> skyArrays, List<byte[]> blockArrays) {
	}

	/**
	 * Vollständig dekodiertes {@code level_chunk_with_light} (0x2E). {@code sectionData} ist der Sektionspuffer;
	 * seine Interpretation braucht Dimension und Registries und erfolgt in {@code world.ChunkDecoder}.
	 */
	record LevelChunkWithLight(int chunkX, int chunkZ, java.util.Map<Integer, long[]> heightmaps, byte[] sectionData,
		List<BlockEntityInfo> blockEntities, LightData light, int size) implements Clientbound {
		public ChunkHeightmaps heightmapsRecord() {
			return new ChunkHeightmaps(this.chunkX, this.chunkZ, this.heightmaps, this.size);
		}
	}

	record BlockUpdate(int x, int y, int z, int stateId) implements Clientbound {
	}

	/** {@code section_blocks_update}: je Eintrag lokale Position (x≪8 | z≪4 | y) und Zustands-ID. */
	record SectionBlocksUpdate(int sectionX, int sectionY, int sectionZ, short[] localPositions, int[] stateIds) implements Clientbound {
	}

	record LightUpdate(int chunkX, int chunkZ, LightData light) implements Clientbound {
	}

	record BlockEntityData(int x, int y, int z, int typeId, java.util.Map<String, Object> nbt) implements Clientbound {
	}

	record ChunkBiomeData(int chunkX, int chunkZ, byte[] buffer) {
	}

	record ChunksBiomes(List<ChunkBiomeData> chunks) implements Clientbound {
	}

	record ChunkBatchStart() implements Clientbound {
	}

	record ChunkBatchFinished(int batchSize) implements Clientbound {
	}

	record StartConfiguration() implements Clientbound {
	}

	record BundleDelimiter() implements Clientbound {
	}

	/** Gültiges, aber für das Ziel nicht benötigtes Paket; nur Name und Größe werden erfasst. */
	record Skipped(String name, int size) implements Clientbound {
	}

	// ---- M7: Blockinteraktion und Inventar (docs/27, docs/28) ----

	/** {@code block_changed_ack}: alle Vorhersage-Sequenzen bis einschließlich {@code sequence} sind verarbeitet. */
	record BlockChangedAck(int sequence) implements Clientbound {
	}

	/** {@code block_destruction}: Abbaufortschritt eines (anderen) Spielers; Stufe 0–9, sonst entfernt. */
	record BlockDestruction(int entityId, int x, int y, int z, int progress) implements Clientbound {
	}

	/** {@code set_held_slot}: gewählter Hotbar-Platz 0–8. */
	record SetHeldSlot(int slot) implements Clientbound {
	}

	/** {@code container_set_content}: alle Plätze eines Containers (0 = Spielerinventar) und der Cursor-Stapel. */
	record ContainerSetContent(int containerId, int stateId, java.util.List<dev.mcresearch.protocolclient.world.ItemStack> items,
		dev.mcresearch.protocolclient.world.ItemStack carried) implements Clientbound {
		public ContainerSetContent {
			items = java.util.List.copyOf(items);
		}
	}

	/** {@code container_set_slot}: ein Platz eines Containers (Menüplatz-Index). */
	record ContainerSetSlot(int containerId, int stateId, int slot, dev.mcresearch.protocolclient.world.ItemStack item) implements Clientbound {
	}

	/** {@code set_player_inventory}: ein Platz des Spielerinventars (Inventory-Index, nicht Menüplatz). */
	record SetPlayerInventory(int slot, dev.mcresearch.protocolclient.world.ItemStack item) implements Clientbound {
	}

	/** {@code set_cursor_item}: Stapel am Mauszeiger. */
	record SetCursorItem(dev.mcresearch.protocolclient.world.ItemStack item) implements Clientbound {
	}

	/** {@code player_abilities}: Bits 1 unverwundbar, 2 fliegt, 4 darf fliegen, 8 instabuild (Creative). */
	record PlayerAbilities(int flags, float flyingSpeed, float walkingSpeed) implements Clientbound {
		public boolean instabuild() {
			return (this.flags & 8) != 0;
		}
	}

	/**
	 * M12 {@code level_particles} (docs/48 §7): Partikeltyp (Netzwerk-ID), Blockzustand für Blockpartikel (sonst −1), Position,
	 * Streuung, Geschwindigkeit, Anzahl, Zufallsart (0 Gauß, 1 gleichverteilt, 2 gleichverteilt mit Geschwindigkeit).
	 */
	record LevelParticles(int typeId, String typeName, int blockState, boolean overrideLimiter, boolean alwaysShow, double x, double y, double z, float xDist,
		float yDist, float zDist, float xSpeed, float ySpeed, float zSpeed, int count, int randomization) implements Clientbound {
	}

	/** M12 {@code level_event}: Ereignis (2001 = Block abgebaut, Daten = Zustands-ID), Position, Daten, global. */
	record LevelEvent(int type, int x, int y, int z, int data, boolean global) implements Clientbound {
	}

	/** M12 {@code set_time}: Spielzeit und Weltuhren (Registry {@code minecraft:world_clock}, docs/48 §2). */
	record SetTime(long gameTime, java.util.List<ClockUpdate> clocks) implements Clientbound {
	}

	/** Zustand einer Weltuhr: Gesamtticks, Bruchteil, Rate (0 = angehalten). */
	record ClockUpdate(int clockId, long totalTicks, float partialTick, float rate) {
	}

	/** {@code game_event}: Ereignis-ID (3 = Spielmodus wechseln, Parameter = Modus) und Parameter. */
	record GameEvent(int event, float param) implements Clientbound {
	}

	/** {@code open_screen}: Container-ID, Menütyp (Register {@code minecraft:menu}), Titel. */
	record OpenScreen(int containerId, int menuType, String title) implements Clientbound {
	}

	/** {@code container_close} (vom Server). */
	record ContainerClose(int containerId) implements Clientbound {
	}

	/** Inventarpaket mit nicht dekodierbarer Item-Komponente: kontrolliert verworfen (sichtbar gezählt, kein Abbruch). */
	record ItemDataUnsupported(String packet, String reason) implements Clientbound {
	}

	/** {@code container_set_data}: Datenwert eines Containers (z. B. Ofenfortschritt), Container-ID, short ID, short Wert. */
	record ContainerSetData(int containerId, int id, int value) implements Clientbound {
	}

	// ---- M9: Entities (docs/34) ----

	/** {@code add_entity}: Winkel bereits in Grad (Byte · 360 / 256), Bewegung aus LpVec3. */
	record AddEntity(int id, java.util.UUID uuid, int type, double x, double y, double z, double dx, double dy, double dz,
		float xRot, float yRot, float headYRot, int data) implements Clientbound {
	}

	/** {@code remove_entities}. */
	record RemoveEntities(int[] ids) implements Clientbound {
	}

	/**
	 * Ein Schritt einer relativen Bewegung: Delta in 1/4096 Block je Achse, {@code ticks} = Tick-Abstand (0 bei linearer Bewegung).
	 * {@code VecDelta.Linear} wird als ein Schritt mit ticks 0 dargestellt.
	 */
	record DeltaStep(int ticks, short dx, short dy, short dz) {
	}

	/** {@code move_entity_pos} / {@code _pos_rot} / {@code _rot}; {@code stepped} = VecDelta mit Schrittzahl &gt; 0. */
	record MoveEntity(int id, boolean hasPos, boolean hasRot, boolean stepped, java.util.List<DeltaStep> steps, float yRot, float xRot,
		boolean onGround) implements Clientbound {
		public MoveEntity {
			steps = java.util.List.copyOf(steps);
		}
	}

	/** Absoluter Pfadpunkt ({@code PositionStep}); {@code ticks} 0 bei linearem Pfad. */
	record PathStep(double x, double y, double z, int ticks) {
	}

	/** {@code entity_position_sync}: Pfad (linear = ein Punkt), Rotation als float. */
	record EntityPositionSync(int id, boolean stepped, java.util.List<PathStep> path, float yRot, float xRot, boolean onGround)
		implements Clientbound {
		public EntityPositionSync {
			path = java.util.List.copyOf(path);
		}
	}

	/** {@code teleport_entity}: PositionMoveRotation, Relativ-Maske wie {@code player_position}, onGround. */
	record TeleportEntity(int id, double x, double y, double z, double dx, double dy, double dz, float yRot, float xRot, int relatives,
		boolean onGround) implements Clientbound {
	}

	/** {@code rotate_head}. */
	record RotateHead(int id, float headYRot) implements Clientbound {
	}

	/** {@code set_entity_motion} (LpVec3). */
	record SetEntityMotion(int id, double dx, double dy, double dz) implements Clientbound {
	}

	/** Ein synchronisiertes Datenfeld: Index, Serialisierer-ID, dekodierter Wert. */
	record DataValue(int index, int serializer, Object value) {
	}

	/**
	 * {@code set_entity_data}: dekodierte Werte in Paketreihenfolge. {@code unsupported} ≠ null: an dieser Stelle stand ein nicht
	 * umgesetzter Serialisierer oder ein nicht dekodierbarer Wert; die Auswertung endete dort (docs/34 §5).
	 */
	record SetEntityData(int id, java.util.List<DataValue> values, String unsupported) implements Clientbound {
		public SetEntityData {
			values = java.util.List.copyOf(values);
		}
	}

	/** {@code set_equipment}: Platz-Ordinalzahl (0 Haupthand … 7 Sattel) → Stapel; {@code unsupported} wie bei {@link SetEntityData}. */
	record SetEquipment(int id, java.util.Map<Integer, dev.mcresearch.protocolclient.world.ItemStack> slots, String unsupported)
		implements Clientbound {
		public SetEquipment {
			slots = java.util.Map.copyOf(slots);
		}
	}

	/** {@code swing_animation}: Hand 0 Haupt-, 1 Nebenhand. */
	record SwingAnimation(int id, int hand) implements Clientbound {
	}

	/** {@code animate}: 0 Aufwachen, 1 kritischer Treffer, 2 magischer kritischer Treffer. */
	record Animate(int id, int action) implements Clientbound {
	}

	/** {@code entity_event}. */
	record EntityEvent(int id, int event) implements Clientbound {
	}

	/** {@code hurt_animation}. */
	record HurtAnimation(int id, float yaw) implements Clientbound {
	}

	/** {@code take_item_entity}. */
	record TakeItemEntity(int itemId, int collectorId, int amount) implements Clientbound {
	}

	/** {@code set_passengers}. */
	record SetPassengers(int vehicleId, int[] passengers) implements Clientbound {
	}

	/** Ein Eintrag von {@code player_info_update}; nur die Felder der gesetzten Aktionen sind gültig. */
	record PlayerInfoEntry(java.util.UUID uuid, String name, java.util.List<GameProfile.Property> properties, int gameMode, boolean listed,
		int latency, String displayName, boolean showHat) {
	}

	/** {@code player_info_update}: Aktionsmaske (Bit 0 ADD_PLAYER … 7 UPDATE_HAT). */
	record PlayerInfoUpdate(int actions, java.util.List<PlayerInfoEntry> entries) implements Clientbound {
		public PlayerInfoUpdate {
			entries = java.util.List.copyOf(entries);
		}
	}

	/** {@code player_info_remove}. */
	record PlayerInfoRemove(java.util.List<java.util.UUID> uuids) implements Clientbound {
		public PlayerInfoRemove {
			uuids = java.util.List.copyOf(uuids);
		}
	}

	// ---- M10: Kampf, Gesundheit, Effekte, Tod (docs/38) ----

	/** {@code damage_event}: Ziel, Schadenstyp (Registry-ID aus der Konfiguration), Verursacher und direkte Quelle (−1 = keine), opt. Position. */
	record DamageEvent(int entityId, int damageType, int causeId, int directId, boolean hasPosition, double x, double y, double z) implements Clientbound {
	}

	/** {@code update_mob_effect}: Effekt-ID (Registry mob_effect), Verstärker, Dauer in Ticks (−1 unendlich), Flags. */
	record UpdateMobEffect(int entityId, int effect, int amplifier, int duration, int flags) implements Clientbound {
	}

	/** {@code remove_mob_effect}. */
	record RemoveMobEffect(int entityId, int effect) implements Clientbound {
	}

	/** {@code player_combat_kill}: Spieler-ID und Todesnachricht (als Klartext). */
	record PlayerCombatKill(int playerId, String message, Object component) implements Clientbound {
	}

	/** {@code player_combat_enter} / {@code player_combat_end} (Dauer, sonst −1). */
	record PlayerCombat(boolean enter, int duration) implements Clientbound {
	}

	/** {@code set_experience}. */
	record SetExperience(float progress, int level, int total) implements Clientbound {
	}

	/** {@code cooldown}: Item-Abklingzeit einer Gruppe in Ticks. */
	record ItemCooldown(String group, int duration) implements Clientbound {
	}
}
