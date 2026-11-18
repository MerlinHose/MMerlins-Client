package dev.mcresearch.protocolclient.protocol.packet;

import dev.mcresearch.protocolclient.protocol.RawPacket;
import dev.mcresearch.protocolclient.protocol.ProtocolException;
import dev.mcresearch.protocolclient.protocol.ProtocolState;
import dev.mcresearch.protocolclient.protocol.codec.PacketReader;
import dev.mcresearch.protocolclient.protocol.codec.Positions;
import dev.mcresearch.protocolclient.protocol.json.MiniJson;
import dev.mcresearch.protocolclient.protocol.nbt.NbtReader;
import dev.mcresearch.protocolclient.protocol.nbt.TextComponents;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * Dekodiert die in docs/12 spezifizierten Serverpakete mit vollständiger Längen- und Wertprüfung.
 * Jedes dekodierte Paket muss exakt aufgebraucht werden (wie Vanilla {@code PacketDecoder}).
 */
public final class ClientboundDecoder {
	/** Obergrenzen für Sammlungen, die Vanilla nicht explizit begrenzt (Schutz vor Speicherverbrauch). */
	static final int MAX_REGISTRY_ENTRIES = 1 << 16;
	static final int MAX_TAG_REGISTRIES = 1 << 12;
	static final int MAX_TAGS_PER_REGISTRY = 1 << 16;
	static final int MAX_TAG_VALUES = 1 << 20;
	static final int MAX_FEATURES = 1 << 10;
	static final int MAX_KNOWN_PACKS = 1 << 10;
	static final int MAX_LEVELS = 1 << 10;

	private ClientboundDecoder() {
	}

	public static Clientbound decode(final RawPacket raw) throws ProtocolException {
		PacketReader r = new PacketReader(raw.body());
		Clientbound packet = decode(raw.state(), raw.name(), r, raw.size());
		// Skipped: nicht dekodiert; alle anderen Pakete – auch level_chunk_with_light – müssen vollständig verbraucht sein
		// M9: teilweise dekodierte Entity-Daten (nicht umgesetzter Serialisierer) enden kontrolliert vor dem Paketende
		boolean partial = packet instanceof Clientbound.SetEntityData d && d.unsupported() != null
			|| packet instanceof Clientbound.SetEquipment e && e.unsupported() != null;
		if (!(packet instanceof Clientbound.Skipped) && !(packet instanceof Clientbound.ItemDataUnsupported) && !partial) {
			r.expectEnd(raw.name());
		}
		return packet;
	}

	private static Clientbound decode(final ProtocolState state, final String name, final PacketReader r, final int size) throws ProtocolException {
		return switch (state) {
			case STATUS -> switch (name) {
				case "minecraft:status_response" -> statusResponse(r.readString(32767));
				case "minecraft:pong_response" -> new Clientbound.PongResponse(r.readLong());
				default -> new Clientbound.Skipped(name, size);
			};
			case LOGIN -> switch (name) {
				case "minecraft:login_disconnect" -> new Clientbound.LoginDisconnect(jsonText(r.readString(262144)));
				case "minecraft:hello" -> {
					String serverId = r.readString(20);
					byte[] key = r.readByteArray(r.remaining());
					byte[] challenge = r.readByteArray(r.remaining());
					yield new Clientbound.EncryptionRequest(serverId, key, challenge, r.readBoolean());
				}
				case "minecraft:login_finished" -> new Clientbound.LoginFinished(gameProfile(r), r.readUuid());
				case "minecraft:login_compression" -> new Clientbound.LoginCompression(r.readVarInt());
				case "minecraft:custom_query" -> {
					int tx = r.readVarInt();
					String channel = r.readIdentifier();
					yield new Clientbound.CustomQuery(tx, channel, r.readRemaining(1_048_576).length);
				}
				case "minecraft:cookie_request" -> new Clientbound.CookieRequest(r.readIdentifier());
				default -> new Clientbound.Skipped(name, size);
			};
			case CONFIGURATION -> switch (name) {
				case "minecraft:cookie_request" -> new Clientbound.CookieRequest(r.readIdentifier());
				case "minecraft:custom_payload" -> customPayload(r);
				case "minecraft:disconnect" -> new Clientbound.Disconnect(nbtText(r));
				case "minecraft:finish_configuration" -> new Clientbound.FinishConfiguration();
				case "minecraft:keep_alive" -> new Clientbound.KeepAlive(r.readLong());
				case "minecraft:ping" -> new Clientbound.Ping(r.readInt());
				case "minecraft:registry_data" -> registryData(r);
				case "minecraft:update_enabled_features" -> {
					int n = r.readCount(MAX_FEATURES, 2, "features");
					List<String> features = new ArrayList<>(n);
					for (int i = 0; i < n; i++) {
						features.add(r.readIdentifier());
					}
					yield new Clientbound.UpdateEnabledFeatures(List.copyOf(features));
				}
				case "minecraft:update_tags" -> updateTags(r);
				case "minecraft:select_known_packs" -> new Clientbound.SelectKnownPacks(knownPacks(r));
				default -> new Clientbound.Skipped(name, size);
			};
			case PLAY -> switch (name) {
				case "minecraft:bundle_delimiter" -> new Clientbound.BundleDelimiter();
				case "minecraft:chunk_batch_start" -> new Clientbound.ChunkBatchStart();
				case "minecraft:chunk_batch_finished" -> {
					int n = r.readVarInt();
					if (n < 0) {
						throw new ProtocolException("Negative chunk batch size " + n);
					}
					yield new Clientbound.ChunkBatchFinished(n);
				}
				case "minecraft:cookie_request" -> new Clientbound.CookieRequest(r.readIdentifier());
				case "minecraft:disconnect" -> new Clientbound.Disconnect(nbtText(r));
				case "minecraft:keep_alive" -> new Clientbound.KeepAlive(r.readLong());
				case "minecraft:ping" -> new Clientbound.Ping(r.readInt());
				case "minecraft:login" -> playLogin(r);
				case "minecraft:player_position" -> playerPosition(r);
				case "minecraft:move_vehicle" -> new Clientbound.MoveVehicle(r.readDouble(), r.readDouble(), r.readDouble(), r.readFloat(), r.readFloat()); // M14
				case "minecraft:start_configuration" -> new Clientbound.StartConfiguration();
				case "minecraft:player_rotation" -> {
					float yRot = r.readFloat();
					boolean relY = r.readBoolean();
					float xRot = r.readFloat();
					boolean relX = r.readBoolean();
					if (!Float.isFinite(yRot) || !Float.isFinite(xRot)) {
						throw new ProtocolException("Non-finite value in player_rotation");
					}
					yield new Clientbound.PlayerRotation(yRot, relY, xRot, relX);
				}
				case "minecraft:level_chunk_with_light" -> levelChunk(r, size);
				case "minecraft:block_update" -> {
					long pos = r.readLong();
					yield new Clientbound.BlockUpdate(Positions.blockX(pos), Positions.blockY(pos), Positions.blockZ(pos), r.readNonNegativeVarInt("block state id"));
				}
				case "minecraft:section_blocks_update" -> sectionBlocksUpdate(r);
				case "minecraft:light_update" -> new Clientbound.LightUpdate(r.readVarInt(), r.readVarInt(), lightData(r));
				case "minecraft:block_entity_data" -> {
					long pos = r.readLong();
					int type = r.readNonNegativeVarInt("block entity type");
					if (!(NbtReader.readNetworkTag(r) instanceof Map<?, ?> nbt)) {
						throw new ProtocolException("block_entity_data requires a compound tag");
					}
					yield new Clientbound.BlockEntityData(Positions.blockX(pos), Positions.blockY(pos), Positions.blockZ(pos), type, castCompound(nbt));
				}
				case "minecraft:chunks_biomes" -> chunksBiomes(r);
				case "minecraft:respawn" -> respawn(r);
				case "minecraft:set_health" -> {
					float health = r.readFloat();
					int food = r.readVarInt();
					float saturation = r.readFloat();
					if (!Float.isFinite(health) || !Float.isFinite(saturation)) {
						throw new ProtocolException("Non-finite value in set_health");
					}
					yield new Clientbound.SetHealth(health, food, saturation);
				}
				case "minecraft:update_attributes" -> updateAttributes(r);
				case "minecraft:block_changed_ack" -> new Clientbound.BlockChangedAck(r.readVarInt());
				case "minecraft:block_destruction" -> {
					int entity = r.readVarInt();
					long pos = r.readLong();
					yield new Clientbound.BlockDestruction(entity, Positions.blockX(pos), Positions.blockY(pos), Positions.blockZ(pos), r.readUnsignedByte());
				}
				case "minecraft:set_held_slot" -> new Clientbound.SetHeldSlot(r.readVarInt());
				case "minecraft:container_set_content", "minecraft:container_set_slot", "minecraft:set_player_inventory", "minecraft:set_cursor_item" ->
					itemPacket(name, r);
				case "minecraft:player_abilities" -> new Clientbound.PlayerAbilities(r.readByte(), r.readFloat(), r.readFloat());
				case "minecraft:game_event" -> new Clientbound.GameEvent(r.readUnsignedByte(), r.readFloat());
				case "minecraft:set_time" -> setTime(r);
				case "minecraft:level_event" -> {
					int type = r.readInt();
					long pos = r.readLong();
					yield new Clientbound.LevelEvent(type, dev.mcresearch.protocolclient.protocol.codec.Positions.blockX(pos),
						dev.mcresearch.protocolclient.protocol.codec.Positions.blockY(pos), dev.mcresearch.protocolclient.protocol.codec.Positions.blockZ(pos), r.readInt(),
						r.readBoolean());
				}
				case "minecraft:level_particles" -> levelParticles(r);
				case "minecraft:open_screen" -> new Clientbound.OpenScreen(r.readVarInt(), r.readVarInt(), nbtText(r));
				case "minecraft:container_close" -> new Clientbound.ContainerClose(r.readVarInt());
				case "minecraft:container_set_data" -> new Clientbound.ContainerSetData(r.readVarInt(), r.readShort(), r.readShort());
				case "minecraft:forget_level_chunk" -> {
					long packed = r.readLong(); // ChunkPos#pack: x in den unteren, z in den oberen 32 Bit
					yield new Clientbound.ForgetChunk((int) packed, (int) (packed >>> 32));
				}
				default -> {
					Clientbound entity = EntityPacketDecoder.decode(name, r);
					if (entity == null) {
						entity = SurvivalPacketDecoder.decode(name, r); // M10
					}
					yield entity != null ? entity : new Clientbound.Skipped(name, size);
				}
			};
			case HANDSHAKE -> throw new ProtocolException("Server must not send packets in handshake state");
		};
	}

	static Clientbound.StatusResponse statusResponse(final String json) throws ProtocolException {
		if (!(MiniJson.parse(json) instanceof Map<?, ?> root)) {
			throw new ProtocolException("Status response is not a JSON object");
		}
		if (!(root.get("version") instanceof Map<?, ?> version) || !(version.get("protocol") instanceof Long protocol)
			|| !(version.get("name") instanceof String versionName)) {
			throw new ProtocolException("Status response lacks version.name/version.protocol");
		}
		int online = -1;
		int max = -1;
		if (root.get("players") instanceof Map<?, ?> players) {
			if (players.get("online") instanceof Long o) {
				online = Math.toIntExact(o);
			}
			if (players.get("max") instanceof Long m) {
				max = Math.toIntExact(m);
			}
		}
		return new Clientbound.StatusResponse(json, versionName, Math.toIntExact(protocol), online, max, TextComponents.toPlainText(root.get("description")));
	}

	private static String jsonText(final String json) throws ProtocolException {
		return TextComponents.toPlainText(MiniJson.parse(json));
	}

	private static String nbtText(final PacketReader r) throws ProtocolException {
		Object tag = NbtReader.readNetworkTag(r);
		if (tag == null) {
			throw new ProtocolException("Missing text component");
		}
		return TextComponents.toPlainText(tag);
	}

	static GameProfile gameProfile(final PacketReader r) throws ProtocolException {
		UUID id = r.readUuid();
		String name = r.readString(16);
		int count = r.readCount(16, 3, "profile properties");
		List<GameProfile.Property> properties = new ArrayList<>(count);
		for (int i = 0; i < count; i++) {
			String propName = r.readString(64);
			String value = r.readString(32767);
			String signature = r.readBoolean() ? r.readString(1024) : null;
			properties.add(new GameProfile.Property(propName, value, signature));
		}
		return new GameProfile(id, name, List.copyOf(properties));
	}

	private static Clientbound customPayload(final PacketReader r) throws ProtocolException {
		String channel = r.readIdentifier();
		if (channel.equals("minecraft:brand")) {
			int before = r.remaining();
			String brand = r.readString(32767);
			return new Clientbound.CustomPayload(channel, brand, before);
		}
		return new Clientbound.CustomPayload(channel, null, r.readRemaining(1_048_576).length);
	}

	private static Clientbound registryData(final PacketReader r) throws ProtocolException {
		String registry = r.readIdentifier();
		int n = r.readCount(MAX_REGISTRY_ENTRIES, 2, "registry entries");
		List<Clientbound.RegistryEntry> entries = new ArrayList<>(n);
		for (int i = 0; i < n; i++) {
			String id = r.readIdentifier();
			boolean hasData = r.readBoolean();
			Object data = null;
			if (hasData) {
				data = NbtReader.readNetworkTag(r);
				if (data == null) {
					throw new ProtocolException("Registry entry " + id + " declares data but contains an END tag");
				}
			}
			entries.add(new Clientbound.RegistryEntry(id, hasData, data));
		}
		return new Clientbound.RegistryData(registry, List.copyOf(entries));
	}

	private static Clientbound updateTags(final PacketReader r) throws ProtocolException {
		int registries = r.readCount(MAX_TAG_REGISTRIES, 2, "tag registries");
		int tags = 0;
		int values = 0;
		for (int i = 0; i < registries; i++) {
			r.readIdentifier();
			int tagCount = r.readCount(MAX_TAGS_PER_REGISTRY, 2, "tags");
			for (int t = 0; t < tagCount; t++) {
				r.readIdentifier();
				int valueCount = r.readCount(MAX_TAG_VALUES, 1, "tag values");
				for (int v = 0; v < valueCount; v++) {
					if (r.readVarInt() < 0) {
						throw new ProtocolException("Negative registry id in tag");
					}
				}
				values += valueCount;
			}
			tags += tagCount;
		}
		return new Clientbound.UpdateTags(registries, tags, values);
	}

	static List<KnownPack> knownPacks(final PacketReader r) throws ProtocolException {
		int n = r.readCount(MAX_KNOWN_PACKS, 3, "known packs");
		List<KnownPack> packs = new ArrayList<>(n);
		for (int i = 0; i < n; i++) {
			packs.add(new KnownPack(r.readString(32767), r.readString(32767), r.readString(32767)));
		}
		return List.copyOf(packs);
	}

	/**
	 * M12: {@code level_particles} = Partikeloptionen (VarInt-Typ + typabhängige Daten) zuerst, dann Flags, Position, Streuung,
	 * Geschwindigkeit, Anzahl, Zufallsart. Dekodiert werden Typen ohne Daten und Blockpartikel (VarInt-Zustand); bei anderen
	 * Datenformaten ist das Paketende nicht bestimmbar → übersprungen.
	 */
	private static Clientbound levelParticles(final PacketReader r) throws ProtocolException {
		int before = r.remaining();
		int type = r.readNonNegativeVarInt("particle type");
		ParticleTypes.Type t = ParticleTypes.get().byId(type);
		int blockState = -1;
		if (t == null || !t.simple()) {
			if (t == null || !ParticleTypes.BLOCK_DATA.contains(t.name())) {
				r.readRemaining(1_048_576);
				return new Clientbound.Skipped("minecraft:level_particles", before + 1);
			}
			blockState = r.readNonNegativeVarInt("particle block state");
		}
		boolean override = r.readBoolean();
		boolean always = r.readBoolean();
		double x = r.readDouble();
		double y = r.readDouble();
		double z = r.readDouble();
		float dx = r.readFloat();
		float dy = r.readFloat();
		float dz = r.readFloat();
		float sx = r.readFloat();
		float sy = r.readFloat();
		float sz = r.readFloat();
		int count = r.readVarInt();
		int rnd = r.readVarInt();
		return new Clientbound.LevelParticles(type, t.name(), blockState, override, always, x, y, z, dx, dy, dz, sx, sy, sz, count, rnd);
	}

	/** M12: {@code set_time} = long Spielzeit, Map (VarInt-Anzahl; Uhr-Registry-ID VarInt → VarLong Ticks, float Bruchteil, float Rate). */
	private static Clientbound setTime(final PacketReader r) throws ProtocolException {
		long gameTime = r.readLong();
		int n = r.readCount(64, 7, "clocks");
		List<Clientbound.ClockUpdate> clocks = new ArrayList<>(n);
		for (int i = 0; i < n; i++) {
			clocks.add(new Clientbound.ClockUpdate(r.readNonNegativeVarInt("clock"), r.readVarLong(), r.readFloat(), r.readFloat()));
		}
		return new Clientbound.SetTime(gameTime, List.copyOf(clocks));
	}

	/** {@code respawn}: {@code CommonPlayerSpawnInfo} (wie im Login) + Byte {@code dataToKeep}. */
	private static Clientbound respawn(final PacketReader r) throws ProtocolException {
		int dimensionType = r.readNonNegativeVarInt("dimensionType");
		String dimension = r.readIdentifier();
		long seed = r.readLong(); // gehashter Startwert (Biom-Zoom, M12)
		int gameType = gameType(r.readVarInt());
		int previousRaw = r.readVarInt();
		if (previousRaw != 0) {
			gameType(previousRaw - 1);
		}
		r.readBoolean(); // isDebug
		r.readBoolean(); // isFlat
		if (r.readBoolean()) {
			r.readIdentifier();
			r.readLong();
		}
		r.readVarInt(); // portalCooldown
		r.readVarInt(); // seaLevel
		int dataToKeep = r.readByte();
		return new Clientbound.Respawn(dimensionType, dimension, gameType, dataToKeep, seed);
	}

	private static Clientbound playLogin(final PacketReader r) throws ProtocolException {
		int entityId = r.readInt();
		boolean hardcore = r.readBoolean();
		int levelCount = r.readCount(MAX_LEVELS, 2, "levels");
		List<String> levels = new ArrayList<>(levelCount);
		for (int i = 0; i < levelCount; i++) {
			levels.add(r.readIdentifier());
		}
		int maxPlayers = r.readNonNegativeVarInt("maxPlayers");
		int chunkRadius = r.readNonNegativeVarInt("chunkRadius");
		int simulationDistance = r.readNonNegativeVarInt("simulationDistance");
		boolean reducedDebug = r.readBoolean();
		boolean showDeathScreen = r.readBoolean();
		boolean limitedCrafting = r.readBoolean();
		int dimensionType = r.readNonNegativeVarInt("dimensionType");
		String dimension = r.readIdentifier();
		long seed = r.readLong();
		int gameType = gameType(r.readVarInt());
		int previousRaw = r.readVarInt(); // OptionalVarInt: 0 = leer, sonst id + 1
		int previous = previousRaw == 0 ? -1 : gameType(previousRaw - 1);
		boolean isDebug = r.readBoolean();
		boolean isFlat = r.readBoolean();
		String deathDimension = null;
		long deathPos = 0;
		if (r.readBoolean()) {
			deathDimension = r.readIdentifier();
			deathPos = r.readLong();
		}
		int portalCooldown = r.readVarInt();
		int seaLevel = r.readVarInt();
		boolean onlineMode = r.readBoolean();
		boolean secureChat = r.readBoolean();
		if (!levels.contains(dimension)) {
			throw new ProtocolException("Login dimension " + dimension + " not in level list " + levels);
		}
		return new Clientbound.PlayLogin(entityId, hardcore, List.copyOf(levels), maxPlayers, chunkRadius, simulationDistance, reducedDebug,
			showDeathScreen, limitedCrafting, dimensionType, dimension, seed, gameType, previous, isDebug, isFlat, deathDimension, deathPos,
			portalCooldown, seaLevel, onlineMode, secureChat);
	}

	/** {@code ClientboundUpdateAttributesPacket#STREAM_CODEC}: VarInt Entity, Liste (≤ 128) aus Attribut-ID, double Grundwert, Modifikatoren. */
	private static Clientbound.UpdateAttributes updateAttributes(final PacketReader r) throws ProtocolException {
		int entity = r.readVarInt();
		int n = r.readVarInt();
		if (n < 0 || n > 128) {
			throw new ProtocolException("update_attributes: " + n + " attributes");
		}
		List<Clientbound.UpdateAttributes.Attribute> list = new java.util.ArrayList<>(n);
		for (int i = 0; i < n; i++) {
			int id = r.readVarInt();
			double base = r.readDouble();
			int m = r.readVarInt();
			if (m < 0 || m > 1024) {
				throw new ProtocolException("update_attributes: " + m + " modifiers");
			}
			List<Clientbound.UpdateAttributes.Modifier> mods = new java.util.ArrayList<>(m);
			for (int j = 0; j < m; j++) {
				String mid = r.readIdentifier();
				double amount = r.readDouble();
				int op = r.readVarInt();
				if (op < 0 || op > 2) {
					throw new ProtocolException("update_attributes: modifier operation " + op);
				}
				mods.add(new Clientbound.UpdateAttributes.Modifier(mid, amount, op));
			}
			list.add(new Clientbound.UpdateAttributes.Attribute(id, base, mods));
		}
		return new Clientbound.UpdateAttributes(entity, list);
	}

	/** Inventarpakete mit Item-Stapeln (docs/28 §4); nicht dekodierbare Komponenten → {@link Clientbound.ItemDataUnsupported}. */
	private static Clientbound itemPacket(final String name, final PacketReader r) throws ProtocolException {
		dev.mcresearch.protocolclient.world.ItemRegistry items = dev.mcresearch.protocolclient.world.ItemRegistry.get();
		try {
			return switch (name) {
				case "minecraft:container_set_content" -> {
					int container = r.readVarInt();
					int stateId = r.readVarInt();
					int n = r.readCount(1024, 1, "container_set_content items");
					List<dev.mcresearch.protocolclient.world.ItemStack> list = new ArrayList<>(n);
					for (int i = 0; i < n; i++) {
						list.add(dev.mcresearch.protocolclient.protocol.codec.ItemStackCodec.read(r, items));
					}
					yield new Clientbound.ContainerSetContent(container, stateId, list, dev.mcresearch.protocolclient.protocol.codec.ItemStackCodec.read(r, items));
				}
				case "minecraft:container_set_slot" -> new Clientbound.ContainerSetSlot(r.readVarInt(), r.readVarInt(), r.readShort(),
					dev.mcresearch.protocolclient.protocol.codec.ItemStackCodec.read(r, items));
				case "minecraft:set_player_inventory" -> new Clientbound.SetPlayerInventory(r.readVarInt(),
					dev.mcresearch.protocolclient.protocol.codec.ItemStackCodec.read(r, items));
				default -> new Clientbound.SetCursorItem(dev.mcresearch.protocolclient.protocol.codec.ItemStackCodec.read(r, items));
			};
		} catch (dev.mcresearch.protocolclient.protocol.codec.ItemStackCodec.UnsupportedComponentException e) {
			return new Clientbound.ItemDataUnsupported(name, e.getMessage());
		}
	}

	private static int gameType(final int id) throws ProtocolException {
		if (id < 0 || id > 3) {
			throw new ProtocolException("Invalid game type " + id);
		}
		return id;
	}

	/** Anzahl der Heightmap-Typen in {@code Heightmap.Types} (IDs 0–5). */
	static final int HEIGHTMAP_TYPES = 6;

	/** Maximale Sektionspuffergröße ({@code ClientboundLevelChunkPacketData.TWO_MEGABYTES}). */
	public static final int MAX_CHUNK_BUFFER = 2_097_152;
	/** Nibble-Array einer Lichtsektion ({@code DataLayer.SIZE}). */
	public static final int LIGHT_ARRAY_SIZE = 2048;
	/** Obergrenzen für Listen ohne explizites Vanilla-Limit (Schutz vor Speicherverbrauch). */
	static final int MAX_BLOCK_ENTITIES = 1 << 16;
	static final int MAX_LIGHT_ARRAYS = 4064 / 16 + 2;
	static final int MAX_BIOME_CHUNKS = 1 << 12;

	/** {@code ClientboundLightUpdatePacketData}: 4 BitSets (ByteArray → BitSet.valueOf), 2 Listen von ByteArray(2048). */
	static Clientbound.LightData lightData(final PacketReader r) throws ProtocolException {
		java.util.BitSet sky = bitSet(r);
		java.util.BitSet block = bitSet(r);
		java.util.BitSet emptySky = bitSet(r);
		java.util.BitSet emptyBlock = bitSet(r);
		List<byte[]> skyArrays = lightArrays(r, "sky");
		List<byte[]> blockArrays = lightArrays(r, "block");
		if (skyArrays.size() != sky.cardinality() || blockArrays.size() != block.cardinality()) {
			throw new ProtocolException("Light arrays (" + skyArrays.size() + "/" + blockArrays.size() + ") do not match mask bits ("
				+ sky.cardinality() + "/" + block.cardinality() + ")");
		}
		return new Clientbound.LightData(sky, block, emptySky, emptyBlock, skyArrays, blockArrays);
	}

	private static java.util.BitSet bitSet(final PacketReader r) throws ProtocolException {
		return java.util.BitSet.valueOf(r.readByteArray(r.remaining()));
	}

	private static List<byte[]> lightArrays(final PacketReader r, final String what) throws ProtocolException {
		int n = r.readCount(MAX_LIGHT_ARRAYS, 1, what + " light arrays");
		List<byte[]> out = new ArrayList<>(n);
		for (int i = 0; i < n; i++) {
			byte[] data = r.readByteArray(LIGHT_ARRAY_SIZE);
			if (data.length != LIGHT_ARRAY_SIZE) {
				throw new ProtocolException(what + " light array of " + data.length + " bytes, expected " + LIGHT_ARRAY_SIZE + " (DataLayer.SIZE)");
			}
			out.add(data);
		}
		return List.copyOf(out);
	}

	/** {@code ClientboundSectionBlocksUpdatePacket}: SectionPos Long, VarInt n, n × VarLong (stateId ≪ 12 | lokalePos). */
	private static Clientbound sectionBlocksUpdate(final PacketReader r) throws ProtocolException {
		long section = r.readLong();
		int n = r.readCount(4096, 1, "section block changes");
		short[] positions = new short[n];
		int[] states = new int[n];
		for (int i = 0; i < n; i++) {
			long packed = r.readVarLong();
			long state = packed >>> 12;
			if (state > Integer.MAX_VALUE) {
				throw new ProtocolException("Block state id out of range: " + state);
			}
			positions[i] = (short) (packed & 4095L);
			states[i] = (int) state;
		}
		return new Clientbound.SectionBlocksUpdate(Positions.sectionX(section), Positions.sectionY(section), Positions.sectionZ(section), positions, states);
	}

	private static Clientbound chunksBiomes(final PacketReader r) throws ProtocolException {
		int n = r.readCount(MAX_BIOME_CHUNKS, 9, "biome chunks");
		List<Clientbound.ChunkBiomeData> list = new ArrayList<>(n);
		for (int i = 0; i < n; i++) {
			long packed = r.readLong();
			list.add(new Clientbound.ChunkBiomeData((int) packed, (int) (packed >>> 32), r.readByteArray(MAX_CHUNK_BUFFER)));
		}
		return new Clientbound.ChunksBiomes(List.copyOf(list));
	}

	@SuppressWarnings("unchecked")
	private static Map<String, Object> castCompound(final Map<?, ?> nbt) {
		return (Map<String, Object>) nbt;
	}

	/**
	 * Vollständiges {@code ClientboundLevelChunkWithLightPacket} (docs/18 §2): x Int, z Int,
	 * Heightmaps Map(VarInt → LongArray), Sektionspuffer ByteArray(2 MiB), Block-Entities, Lichtdaten.
	 * Die Sektionen im Puffer interpretiert {@code world.ChunkDecoder} (braucht Dimension und Registries).
	 */
	static Clientbound levelChunk(final PacketReader r, final int size) throws ProtocolException {
		int x = r.readInt();
		int z = r.readInt();
		int n = r.readCount(HEIGHTMAP_TYPES, 2, "heightmaps");
		java.util.Map<Integer, long[]> maps = new java.util.HashMap<>();
		for (int i = 0; i < n; i++) {
			int type = r.readVarInt();
			if (type < 0 || type >= HEIGHTMAP_TYPES) {
				throw new ProtocolException("Unknown heightmap type " + type);
			}
			int length = r.readNonNegativeVarInt("heightmap length");
			if ((long) length * 8 > r.remaining()) {
				throw new ProtocolException("Heightmap long array of " + length + " exceeds remaining data");
			}
			long[] data = new long[length];
			for (int k = 0; k < length; k++) {
				data[k] = r.readLong();
			}
			if (maps.put(type, data) != null) {
				throw new ProtocolException("Duplicate heightmap type " + type);
			}
		}
		byte[] sections = r.readByteArray(MAX_CHUNK_BUFFER);
		int count = r.readCount(MAX_BLOCK_ENTITIES, 4, "block entities");
		List<Clientbound.BlockEntityInfo> entities = new ArrayList<>(count);
		for (int i = 0; i < count; i++) {
			int packedXZ = r.readUnsignedByte();
			int y = r.readShort();
			int type = r.readNonNegativeVarInt("block entity type");
			Object nbt = NbtReader.readNetworkTag(r);
			if (nbt != null && !(nbt instanceof Map<?, ?>)) {
				throw new ProtocolException("Block entity NBT is not a compound tag");
			}
			entities.add(new Clientbound.BlockEntityInfo(packedXZ >> 4 & 15, packedXZ & 15, y, type, nbt == null ? null : castCompound((Map<?, ?>) nbt)));
		}
		Clientbound.LightData light = lightData(r);
		return new Clientbound.LevelChunkWithLight(x, z, java.util.Map.copyOf(maps), sections, List.copyOf(entities), light, size);
	}

	private static Clientbound playerPosition(final PacketReader r) throws ProtocolException {
		int id = r.readVarInt();
		double x = r.readDouble();
		double y = r.readDouble();
		double z = r.readDouble();
		double dx = r.readDouble();
		double dy = r.readDouble();
		double dz = r.readDouble();
		float yRot = r.readFloat();
		float xRot = r.readFloat();
		int relatives = r.readInt();
		for (double v : new double[]{x, y, z, dx, dy, dz, yRot, xRot}) {
			if (!Double.isFinite(v)) {
				throw new ProtocolException("Non-finite value in player_position");
			}
		}
		if ((relatives & ~Clientbound.PlayerPosition.ALL_KNOWN_BITS) != 0) {
			throw new ProtocolException("Unknown relative flags 0x" + Integer.toHexString(relatives));
		}
		return new Clientbound.PlayerPosition(id, x, y, z, dx, dy, dz, yRot, xRot, relatives);
	}
}
