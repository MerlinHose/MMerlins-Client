package dev.mcresearch.protocolclient.protocol.packet;

import dev.mcresearch.protocolclient.protocol.PacketRegistry;
import dev.mcresearch.protocolclient.protocol.ProtocolState;
import dev.mcresearch.protocolclient.protocol.codec.PacketWriter;
import java.util.List;
import java.util.UUID;

/** Alle vom Client gesendeten Pakete (Feldreihenfolge und Grenzen nach docs/12 §5). */
public final class Serverbound {
	private Serverbound() {
	}

	private static void requireState(final ProtocolState state, final ProtocolState... allowed) {
		for (ProtocolState a : allowed) {
			if (a == state) {
				return;
			}
		}
		throw new IllegalArgumentException("Packet not available in state " + state);
	}

	// ----- Handshake -----

	public record Intention(String host, int port, int intent) implements ServerboundPacket {
		public static final int STATUS = 1;
		public static final int LOGIN = 2;

		public Intention {
			if (intent < 1 || intent > 3) {
				throw new IllegalArgumentException("intent " + intent);
			}
			if (port < 0 || port > 65535) {
				throw new IllegalArgumentException("port " + port);
			}
		}

		@Override
		public ProtocolState state() {
			return ProtocolState.HANDSHAKE;
		}

		@Override
		public String name() {
			return "minecraft:intention";
		}

		@Override
		public void write(final PacketWriter out) {
			out.writeVarInt(PacketRegistry.PROTOCOL_VERSION).writeString(this.host, 255).writeShort(this.port).writeVarInt(this.intent);
		}
	}

	// ----- Status -----

	public record StatusRequest() implements ServerboundPacket {
		@Override
		public ProtocolState state() {
			return ProtocolState.STATUS;
		}

		@Override
		public String name() {
			return "minecraft:status_request";
		}

		@Override
		public void write(final PacketWriter out) {
		}
	}

	public record PingRequest(long time) implements ServerboundPacket {
		@Override
		public ProtocolState state() {
			return ProtocolState.STATUS;
		}

		@Override
		public String name() {
			return "minecraft:ping_request";
		}

		@Override
		public void write(final PacketWriter out) {
			out.writeLong(this.time);
		}
	}

	// ----- Login -----

	public record Hello(String playerName, UUID profileId) implements ServerboundPacket {
		public Hello {
			if (!playerName.matches("[A-Za-z0-9_]{3,16}")) {
				throw new IllegalArgumentException("Invalid player name: " + playerName);
			}
		}

		@Override
		public ProtocolState state() {
			return ProtocolState.LOGIN;
		}

		@Override
		public String name() {
			return "minecraft:hello";
		}

		@Override
		public void write(final PacketWriter out) {
			out.writeString(this.playerName, 16).writeUuid(this.profileId);
		}
	}

	/**
	 * M13: Antwort auf {@code hello} (Vanilla {@code ServerboundKeyPacket}): RSA-verschlüsselter AES-Schlüssel und
	 * RSA-verschlüsselte Challenge, je als VarInt-Länge + Bytes. Wird noch unverschlüsselt gesendet.
	 */
	public record Key(byte[] encryptedSecret, byte[] encryptedChallenge) implements ServerboundPacket {
		@Override
		public ProtocolState state() {
			return ProtocolState.LOGIN;
		}

		@Override
		public String name() {
			return "minecraft:key";
		}

		@Override
		public void write(final PacketWriter out) {
			out.writeVarInt(this.encryptedSecret.length).writeBytes(this.encryptedSecret);
			out.writeVarInt(this.encryptedChallenge.length).writeBytes(this.encryptedChallenge);
		}

		@Override
		public String toString() {
			return "Key[secret=" + this.encryptedSecret.length + " B, challenge=" + this.encryptedChallenge.length + " B]";
		}
	}

	public record LoginAcknowledged() implements ServerboundPacket {
		@Override
		public ProtocolState state() {
			return ProtocolState.LOGIN;
		}

		@Override
		public String name() {
			return "minecraft:login_acknowledged";
		}

		@Override
		public void write(final PacketWriter out) {
		}
	}

	/** Antwort auf {@code custom_query}: der Client kennt keinen Kanal und sendet keinen Payload. */
	public record CustomQueryAnswer(int transactionId) implements ServerboundPacket {
		@Override
		public ProtocolState state() {
			return ProtocolState.LOGIN;
		}

		@Override
		public String name() {
			return "minecraft:custom_query_answer";
		}

		@Override
		public void write(final PacketWriter out) {
			out.writeVarInt(this.transactionId).writeBoolean(false);
		}
	}

	/** Antwort auf {@code cookie_request}: der Client speichert keine Cookies (Payload fehlt). */
	public record CookieResponse(ProtocolState state, String key) implements ServerboundPacket {
		public CookieResponse {
			requireState(state, ProtocolState.LOGIN, ProtocolState.CONFIGURATION, ProtocolState.PLAY);
		}

		@Override
		public String name() {
			return "minecraft:cookie_response";
		}

		@Override
		public void write(final PacketWriter out) {
			out.writeIdentifier(this.key).writeBoolean(false);
		}
	}

	// ----- Configuration -----

	public record ClientInformation(String language, int viewDistance, int chatVisibility, boolean chatColors,
		int modelCustomisation, int mainHand, boolean textFiltering, boolean allowsListing, int particleStatus) implements ServerboundPacket {
		public ClientInformation {
			if (viewDistance < 2 || viewDistance > 32) {
				throw new IllegalArgumentException("viewDistance " + viewDistance);
			}
		}

		/** Standardwerte wie der Vanilla-Client, aber mit wählbarer Sichtweite. */
		public static ClientInformation defaults(final int viewDistance) {
			return new ClientInformation("en_us", viewDistance, 0, true, 0x7F, 1, false, true, 0);
		}

		@Override
		public ProtocolState state() {
			return ProtocolState.CONFIGURATION;
		}

		@Override
		public String name() {
			return "minecraft:client_information";
		}

		@Override
		public void write(final PacketWriter out) {
			out.writeString(this.language, 16).writeByte(this.viewDistance).writeVarInt(this.chatVisibility).writeBoolean(this.chatColors)
				.writeByte(this.modelCustomisation).writeVarInt(this.mainHand).writeBoolean(this.textFiltering).writeBoolean(this.allowsListing)
				.writeVarInt(this.particleStatus);
		}
	}

	/** {@code custom_payload} auf Kanal {@code minecraft:brand}. */
	public record Brand(String brand) implements ServerboundPacket {
		@Override
		public ProtocolState state() {
			return ProtocolState.CONFIGURATION;
		}

		@Override
		public String name() {
			return "minecraft:custom_payload";
		}

		@Override
		public void write(final PacketWriter out) {
			out.writeIdentifier("minecraft:brand").writeString(this.brand, 32767);
		}
	}

	public record SelectKnownPacks(List<KnownPack> packs) implements ServerboundPacket {
		public SelectKnownPacks {
			if (packs.size() > 64) {
				throw new IllegalArgumentException("At most 64 known packs");
			}
			packs = List.copyOf(packs);
		}

		@Override
		public ProtocolState state() {
			return ProtocolState.CONFIGURATION;
		}

		@Override
		public String name() {
			return "minecraft:select_known_packs";
		}

		@Override
		public void write(final PacketWriter out) {
			out.writeVarInt(this.packs.size());
			for (KnownPack p : this.packs) {
				out.writeString(p.namespace(), 32767).writeString(p.id(), 32767).writeString(p.version(), 32767);
			}
		}
	}

	public record FinishConfiguration() implements ServerboundPacket {
		@Override
		public ProtocolState state() {
			return ProtocolState.CONFIGURATION;
		}

		@Override
		public String name() {
			return "minecraft:finish_configuration";
		}

		@Override
		public void write(final PacketWriter out) {
		}
	}

	public record KeepAlive(ProtocolState state, long id) implements ServerboundPacket {
		public KeepAlive {
			requireState(state, ProtocolState.CONFIGURATION, ProtocolState.PLAY);
		}

		@Override
		public String name() {
			return "minecraft:keep_alive";
		}

		@Override
		public void write(final PacketWriter out) {
			out.writeLong(this.id);
		}
	}

	public record Pong(ProtocolState state, int id) implements ServerboundPacket {
		public Pong {
			requireState(state, ProtocolState.CONFIGURATION, ProtocolState.PLAY);
		}

		@Override
		public String name() {
			return "minecraft:pong";
		}

		@Override
		public void write(final PacketWriter out) {
			out.writeInt(this.id);
		}
	}

	// ----- Play -----

	public record AcceptTeleportation(int teleportId, double x, double y, double z, float yRot, float xRot) implements ServerboundPacket {
		public AcceptTeleportation {
			if (!Double.isFinite(x) || !Double.isFinite(y) || !Double.isFinite(z) || !Float.isFinite(yRot) || !Float.isFinite(xRot)) {
				throw new IllegalArgumentException("Non-finite teleport values");
			}
		}

		@Override
		public ProtocolState state() {
			return ProtocolState.PLAY;
		}

		@Override
		public String name() {
			return "minecraft:accept_teleportation";
		}

		@Override
		public void write(final PacketWriter out) {
			out.writeVarInt(this.teleportId).writeDouble(this.x).writeDouble(this.y).writeDouble(this.z).writeFloat(this.yRot).writeFloat(this.xRot);
		}
	}

	public record ChunkBatchReceived(float desiredChunksPerTick) implements ServerboundPacket {
		@Override
		public ProtocolState state() {
			return ProtocolState.PLAY;
		}

		@Override
		public String name() {
			return "minecraft:chunk_batch_received";
		}

		@Override
		public void write(final PacketWriter out) {
			out.writeFloat(this.desiredChunksPerTick);
		}
	}

	public record PlayerLoaded() implements ServerboundPacket {
		@Override
		public ProtocolState state() {
			return ProtocolState.PLAY;
		}

		@Override
		public String name() {
			return "minecraft:player_loaded";
		}

		@Override
		public void write(final PacketWriter out) {
		}
	}

	/** {@code client_command}: Aktion als Enum-Ordinal (VarInt); 0 = PERFORM_RESPAWN, 1 = REQUEST_STATS. */
	public record ClientCommand(int action) implements ServerboundPacket {
		public static final int PERFORM_RESPAWN = 0;
		public static final int REQUEST_STATS = 1;

		public ClientCommand {
			if (action != PERFORM_RESPAWN && action != REQUEST_STATS) {
				throw new IllegalArgumentException("unknown client_command action " + action);
			}
		}

		@Override
		public ProtocolState state() {
			return ProtocolState.PLAY;
		}

		@Override
		public String name() {
			return "minecraft:client_command";
		}

		@Override
		public void write(final PacketWriter out) {
			out.writeVarInt(this.action);
		}
	}

	public record ClientTickEnd() implements ServerboundPacket {
		@Override
		public ProtocolState state() {
			return ProtocolState.PLAY;
		}

		@Override
		public String name() {
			return "minecraft:client_tick_end";
		}

		@Override
		public void write(final PacketWriter out) {
		}
	}

	// ----- Play: Bewegung (docs/15) -----

	private static void requireFinite(final double... values) {
		for (double v : values) {
			if (!Double.isFinite(v)) {
				throw new IllegalArgumentException("Non-finite movement value");
			}
		}
	}

	/** Flags-Byte von {@code ServerboundMovePlayerPacket#packFlags}: Bit 0 onGround, Bit 1 horizontalCollision. */
	static int moveFlags(final boolean onGround, final boolean horizontalCollision) {
		return (onGround ? 1 : 0) | (horizontalCollision ? 2 : 0);
	}

	public record MovePlayerPos(double x, double y, double z, boolean onGround, boolean horizontalCollision) implements ServerboundPacket {
		public MovePlayerPos {
			requireFinite(x, y, z);
		}

		@Override
		public ProtocolState state() {
			return ProtocolState.PLAY;
		}

		@Override
		public String name() {
			return "minecraft:move_player_pos";
		}

		@Override
		public void write(final PacketWriter out) {
			out.writeDouble(this.x).writeDouble(this.y).writeDouble(this.z).writeByte(moveFlags(this.onGround, this.horizontalCollision));
		}
	}

	public record MovePlayerPosRot(double x, double y, double z, float yRot, float xRot, boolean onGround, boolean horizontalCollision)
		implements ServerboundPacket {
		public MovePlayerPosRot {
			requireFinite(x, y, z, yRot, xRot);
		}

		@Override
		public ProtocolState state() {
			return ProtocolState.PLAY;
		}

		@Override
		public String name() {
			return "minecraft:move_player_pos_rot";
		}

		@Override
		public void write(final PacketWriter out) {
			out.writeDouble(this.x).writeDouble(this.y).writeDouble(this.z).writeFloat(this.yRot).writeFloat(this.xRot)
				.writeByte(moveFlags(this.onGround, this.horizontalCollision));
		}
	}

	public record MovePlayerRot(float yRot, float xRot, boolean onGround, boolean horizontalCollision) implements ServerboundPacket {
		public MovePlayerRot {
			requireFinite(yRot, xRot);
		}

		@Override
		public ProtocolState state() {
			return ProtocolState.PLAY;
		}

		@Override
		public String name() {
			return "minecraft:move_player_rot";
		}

		@Override
		public void write(final PacketWriter out) {
			out.writeFloat(this.yRot).writeFloat(this.xRot).writeByte(moveFlags(this.onGround, this.horizontalCollision));
		}
	}

	public record MovePlayerStatusOnly(boolean onGround, boolean horizontalCollision) implements ServerboundPacket {
		@Override
		public ProtocolState state() {
			return ProtocolState.PLAY;
		}

		@Override
		public String name() {
			return "minecraft:move_player_status_only";
		}

		@Override
		public void write(final PacketWriter out) {
			out.writeByte(moveFlags(this.onGround, this.horizontalCollision));
		}
	}

	/**
	 * {@code player_command} (M11, docs/42 §5): Entity-ID (VarInt), Aktion als Enum-Ordinal (VarInt), Daten (VarInt). Der Client sendet
	 * START_SPRINTING (1) / STOP_SPRINTING (2) in {@code LocalPlayer#sendIsSprintingIfNeeded} vor dem Positionspaket.
	 */
	public record PlayerCommand(int entityId, int action, int data) implements ServerboundPacket {
		public static final int START_SPRINTING = 1;
		public static final int STOP_SPRINTING = 2;

		public PlayerCommand {
			if (action < 0 || action > 6) {
				throw new IllegalArgumentException("unknown player_command action " + action);
			}
		}

		@Override
		public ProtocolState state() {
			return ProtocolState.PLAY;
		}

		@Override
		public String name() {
			return "minecraft:player_command";
		}

		@Override
		public void write(final PacketWriter out) {
			out.writeVarInt(this.entityId);
			out.writeVarInt(this.action);
			out.writeVarInt(this.data);
		}
	}

	/** Tastenzustand ({@code net.minecraft.world.entity.player.Input}): ein Byte mit 7 Flags. */
	public record PlayerInput(boolean forward, boolean backward, boolean left, boolean right, boolean jump, boolean shift, boolean sprint)
		implements ServerboundPacket {
		public static final PlayerInput EMPTY = new PlayerInput(false, false, false, false, false, false, false);

		public int flags() {
			return (this.forward ? 1 : 0) | (this.backward ? 2 : 0) | (this.left ? 4 : 0) | (this.right ? 8 : 0)
				| (this.jump ? 16 : 0) | (this.shift ? 32 : 0) | (this.sprint ? 64 : 0);
		}

		@Override
		public ProtocolState state() {
			return ProtocolState.PLAY;
		}

		@Override
		public String name() {
			return "minecraft:player_input";
		}

		@Override
		public void write(final PacketWriter out) {
			out.writeByte(flags());
		}
	}

	public record ConfigurationAcknowledged() implements ServerboundPacket {
		@Override
		public ProtocolState state() {
			return ProtocolState.PLAY;
		}

		@Override
		public String name() {
			return "minecraft:configuration_acknowledged";
		}

		@Override
		public void write(final PacketWriter out) {
		}
	}

	// ----- Play: Blockinteraktion und Inventar (M7, docs/27, docs/28) -----

	/** Gemeinsame Teile der Play-Pakete ohne Zustandswechsel. */
	private interface PlayPacket extends ServerboundPacket {
		@Override
		default ProtocolState state() {
			return ProtocolState.PLAY;
		}
	}

	/**
	 * {@code player_action}: Aktion als Enum-Ordinal (VarInt), BlockPos (long), Richtung als <b>Byte</b>
	 * ({@code Direction#get3DDataValue}: down 0, up 1, north 2, south 3, west 4, east 5), Sequenz (VarInt).
	 */
	public record PlayerAction(int action, int x, int y, int z, int direction, int sequence) implements PlayPacket {
		// Reihenfolge von ServerboundPlayerActionPacket.Action (26.3)
		public static final int START_DESTROY_BLOCK = 0;
		public static final int CHANGE_DESTROY_DIRECTION = 1;
		public static final int ABORT_DESTROY_BLOCK = 2;
		public static final int STOP_DESTROY_BLOCK = 3;
		public static final int DROP_ALL_ITEMS = 4;
		public static final int DROP_ITEM = 5;
		public static final int RELEASE_USE_ITEM = 6;
		public static final int SWAP_ITEM_WITH_OFFHAND = 7;
		public static final int STAB = 8;

		public PlayerAction {
			if (action < 0 || action > 8 || direction < 0 || direction > 5 || sequence < 0) {
				throw new IllegalArgumentException("invalid player_action");
			}
		}

		@Override
		public String name() {
			return "minecraft:player_action";
		}

		@Override
		public void write(final PacketWriter out) {
			out.writeVarInt(this.action);
			out.writeLong(dev.mcresearch.protocolclient.protocol.codec.Positions.packBlock(this.x, this.y, this.z));
			out.writeByte(this.direction);
			out.writeVarInt(this.sequence);
		}
	}

	/**
	 * {@code use_item_on}: Hand (VarInt 0 Haupt-, 1 Nebenhand), BlockHitResult (BlockPos, Richtung als VarInt, Trefferpunkt
	 * relativ zum Block als 3 × float, inside, worldBorderHit), Sequenz.
	 */
	public record UseItemOn(int hand, int x, int y, int z, int direction, float cursorX, float cursorY, float cursorZ, boolean inside,
		boolean worldBorderHit, int sequence) implements PlayPacket {
		public UseItemOn {
			if (hand < 0 || hand > 1 || direction < 0 || direction > 5 || sequence < 0 || !Float.isFinite(cursorX) || !Float.isFinite(cursorY)
				|| !Float.isFinite(cursorZ)) {
				throw new IllegalArgumentException("invalid use_item_on");
			}
		}

		@Override
		public String name() {
			return "minecraft:use_item_on";
		}

		@Override
		public void write(final PacketWriter out) {
			out.writeVarInt(this.hand);
			out.writeLong(dev.mcresearch.protocolclient.protocol.codec.Positions.packBlock(this.x, this.y, this.z));
			out.writeVarInt(this.direction);
			out.writeFloat(this.cursorX);
			out.writeFloat(this.cursorY);
			out.writeFloat(this.cursorZ);
			out.writeBoolean(this.inside);
			out.writeBoolean(this.worldBorderHit);
			out.writeVarInt(this.sequence);
		}
	}

	/** {@code use_item}: Hand, Sequenz, Blickrichtung (yRot, xRot) zum Zeitpunkt der Benutzung. */
	public record UseItem(int hand, int sequence, float yRot, float xRot) implements PlayPacket {
		public UseItem {
			if (hand < 0 || hand > 1 || sequence < 0 || !Float.isFinite(yRot) || !Float.isFinite(xRot)) {
				throw new IllegalArgumentException("invalid use_item");
			}
		}

		@Override
		public String name() {
			return "minecraft:use_item";
		}

		@Override
		public void write(final PacketWriter out) {
			out.writeVarInt(this.hand);
			out.writeVarInt(this.sequence);
			out.writeFloat(this.yRot);
			out.writeFloat(this.xRot);
		}
	}

	/** M10: {@code attack} (neu in 26.x): Entity-ID des angegriffenen Ziels (docs/38 §2). */
	public record Attack(int entityId) implements PlayPacket {
		@Override
		public String name() {
			return "minecraft:attack";
		}

		@Override
		public void write(final PacketWriter out) {
			out.writeVarInt(this.entityId);
		}
	}

	/** M10: {@code interact}: Entity-ID, Hand (0/1), Trefferpunkt relativ zur Entity-Position (LpVec3), Schleichen (docs/38 §3). */
	public record Interact(int entityId, int hand, double x, double y, double z, boolean secondaryAction) implements PlayPacket {
		public Interact {
			if (hand < 0 || hand > 1 || !Double.isFinite(x) || !Double.isFinite(y) || !Double.isFinite(z)) {
				throw new IllegalArgumentException("invalid interact");
			}
		}

		@Override
		public String name() {
			return "minecraft:interact";
		}

		@Override
		public void write(final PacketWriter out) {
			out.writeVarInt(this.entityId);
			out.writeVarInt(this.hand);
			writeLpVec3(out, this.x, this.y, this.z);
			out.writeBoolean(this.secondaryAction);
		}
	}

	/**
	 * {@code LpVec3} schreiben (docs/34 §2): 0-Byte für fast null; sonst Skalierung = ceil(max |Komponente|), je Komponente 15 Bit
	 * {@code round((v / Skalierung · 0,5 + 0,5) · 32766)}, Puffer als Byte, Byte, int; Skalierung &gt; 3 mit Fortsetzungsbit + VarInt.
	 */
	public static void writeLpVec3(final PacketWriter out, final double x0, final double y0, final double z0) {
		double x = lpSanitize(x0), y = lpSanitize(y0), z = lpSanitize(z0);
		double max = Math.max(Math.abs(x), Math.max(Math.abs(y), Math.abs(z)));
		if (max < 3.051944088384301E-5) {
			out.writeByte(0);
			return;
		}
		long scale = (long) Math.ceil(max);
		boolean partial = (scale & 3L) != scale;
		long markers = partial ? scale & 3L | 4L : scale;
		long buffer = markers | lpPack(x / scale) << 3 | lpPack(y / scale) << 18 | lpPack(z / scale) << 33;
		out.writeByte((byte) buffer);
		out.writeByte((byte) (buffer >> 8));
		out.writeInt((int) (buffer >> 16));
		if (partial) {
			out.writeVarInt((int) (scale >> 2));
		}
	}

	private static double lpSanitize(final double v) {
		return Double.isNaN(v) ? 0.0 : Math.clamp(v, -1.7179869183E10, 1.7179869183E10);
	}

	private static long lpPack(final double v) {
		return Math.round((v * 0.5 + 0.5) * 32766.0);
	}

	/** {@code punch}: Armschwung/Angriffsgeste (leer; ersetzt in 26.x das frühere swing-Paket beim Linksklick). */
	public record Punch() implements PlayPacket {
		@Override
		public String name() {
			return "minecraft:punch";
		}

		@Override
		public void write(final PacketWriter out) {
		}
	}

	/** {@code set_carried_item}: gewählter Hotbar-Platz 0–8 als short. */
	public record SetCarriedItem(int slot) implements PlayPacket {
		public SetCarriedItem {
			if (slot < 0 || slot > 8) {
				throw new IllegalArgumentException("hotbar slot 0..8");
			}
		}

		@Override
		public String name() {
			return "minecraft:set_carried_item";
		}

		@Override
		public void write(final PacketWriter out) {
			out.writeShort(this.slot);
		}
	}

	/** {@code container_close}: Container-ID (VarInt; 0 = Spielerinventar). */
	public record ContainerClose(int containerId) implements PlayPacket {
		@Override
		public String name() {
			return "minecraft:container_close";
		}

		@Override
		public void write(final PacketWriter out) {
			out.writeVarInt(this.containerId);
		}
	}

	// ----- Play: Inventar und Container (M8, docs/30) -----

	/**
	 * {@code container_click} (26.3): Container-ID (VarInt), stateId (VarInt), Platz (short; −999 außerhalb, −1 keiner), Taste
	 * (byte), Klickart (VarInt: 0 PICKUP, 1 QUICK_MOVE, 2 SWAP, 3 CLONE, 4 THROW, 5 QUICK_CRAFT, 6 PICKUP_ALL), geänderte Plätze
	 * (Map ≤ 128: short → HashedStack) und der Cursor als HashedStack. Der eigene Client sagt nichts voraus: Er meldet keine
	 * geänderten Plätze und als Cursor den zuletzt vom Server bestätigten Stapel (docs/30 §7).
	 */
	public record ContainerClick(int containerId, int stateId, int slot, int button, int input, java.util.Map<Integer, dev.mcresearch.protocolclient.world.ItemStack> changedSlots,
		dev.mcresearch.protocolclient.world.ItemStack carried) implements PlayPacket {
		public static final int PICKUP = 0;
		public static final int QUICK_MOVE = 1;
		public static final int SWAP = 2;
		public static final int CLONE = 3;
		public static final int THROW = 4;
		public static final int QUICK_CRAFT = 5;
		public static final int PICKUP_ALL = 6;

		public ContainerClick {
			if (containerId < 0 || stateId < 0 || slot < Short.MIN_VALUE || slot > Short.MAX_VALUE || button < Byte.MIN_VALUE || button > Byte.MAX_VALUE
				|| input < 0 || input > 6 || changedSlots.size() > 128) {
				throw new IllegalArgumentException("invalid container_click");
			}
			changedSlots = java.util.Map.copyOf(changedSlots);
		}

		@Override
		public String name() {
			return "minecraft:container_click";
		}

		@Override
		public void write(final PacketWriter out) {
			out.writeVarInt(this.containerId);
			out.writeVarInt(this.stateId);
			out.writeShort(this.slot);
			out.writeByte(this.button);
			out.writeVarInt(this.input);
			out.writeVarInt(this.changedSlots.size());
			for (var e : new java.util.TreeMap<>(this.changedSlots).entrySet()) {
				out.writeShort(e.getKey());
				writeHashedStack(out, e.getValue());
			}
			writeHashedStack(out, this.carried);
		}
	}

	/**
	 * {@code HashedStack.STREAM_CODEC}: optional (bool); ActualItem = Item-ID (VarInt), Anzahl (VarInt), HashedPatchMap =
	 * Map (≤ 256) Komponententyp-ID (VarInt) → Hash (int, 4 Byte), Menge (≤ 256) entfernter Typ-IDs (VarInt).
	 */
	public static void writeHashedStack(final PacketWriter out, final dev.mcresearch.protocolclient.world.ItemStack stack) {
		if (stack.isEmpty()) {
			out.writeBoolean(false);
			return;
		}
		dev.mcresearch.protocolclient.world.ItemRegistry items = dev.mcresearch.protocolclient.world.ItemRegistry.get();
		out.writeBoolean(true);
		out.writeVarInt(stack.itemId());
		out.writeVarInt(stack.count());
		out.writeVarInt(stack.components().size());
		for (var e : stack.components().entrySet()) {
			int type = items.componentId(e.getKey());
			out.writeVarInt(type);
			out.writeInt(dev.mcresearch.protocolclient.protocol.codec.ComponentHash.of(e.getKey(), e.getValue())
				.orElse(dev.mcresearch.protocolclient.protocol.codec.ComponentHash.UNKNOWN));
		}
		out.writeVarInt(stack.removed().size());
		for (String r : stack.removed()) {
			out.writeVarInt(items.componentId(r));
		}
	}
}
