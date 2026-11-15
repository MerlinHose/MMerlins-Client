package dev.mcresearch.protocolclient.session;

import dev.mcresearch.protocolclient.net.PacketChannel;
import dev.mcresearch.protocolclient.protocol.ProtocolException;
import dev.mcresearch.protocolclient.protocol.ProtocolState;
import dev.mcresearch.protocolclient.protocol.RawPacket;
import dev.mcresearch.protocolclient.protocol.packet.Clientbound;
import dev.mcresearch.protocolclient.protocol.packet.ClientboundDecoder;
import dev.mcresearch.protocolclient.protocol.packet.GameProfile;
import dev.mcresearch.protocolclient.protocol.packet.Serverbound;
import dev.mcresearch.protocolclient.protocol.packet.ServerboundPacket;
import dev.mcresearch.protocolclient.world.BlockRegistry;
import dev.mcresearch.protocolclient.world.DimensionInfo;
import dev.mcresearch.protocolclient.world.ItemStack;
import dev.mcresearch.protocolclient.world.TerrainCache;
import dev.mcresearch.protocolclient.world.WorldState;
import java.io.EOFException;
import java.io.IOException;
import java.lang.System.Logger.Level;
import java.net.SocketException;
import java.net.SocketTimeoutException;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.function.LongSupplier;

/**
 * Zustandsautomat Login → Configuration → Play (docs/12 §4, docs/13).
 *
 * <p>Threads: {@link #run()} liest und verarbeitet alle Pakete in einem Thread. Ein Tick-Thread sendet 20×/s
 * {@code client_tick_end} und beendet nach {@link SessionConfig#playDuration()} die Verbindung kontrolliert.
 * Zustandswechsel und Tick-Senden sind über {@link #lock} serialisiert.
 */
public final class ClientSession {
	public static final int BUNDLE_SIZE_LIMIT = 4096;
	private static final System.Logger LOG = System.getLogger("mcclient.session");

	/** Ergebnis eines Laufs. */
	public record Result(boolean success, String outcome, boolean reachedPlay, Duration timeInPlay, String serverDisconnectReason,
		Throwable error, String serverBrand, int entityId, String position, SessionStats stats, dev.mcresearch.protocolclient.net.TransportStats transport) {
	}

	private final PacketChannel channel;
	private final SessionConfig config;
	/** M13: Login-Identität; Standard ist der Offline-Testmodus mit {@code config.username()}. */
	private final dev.mcresearch.protocolclient.auth.LoginIdentity identity;
	/** M13: Ablauf des Online-Logins für Diagnose und Tests (nie sensitive Daten). */
	private volatile String loginTrace = "";
	/** M13: vom Server bestätigtes Profil aus {@code login_finished}. */
	private volatile GameProfile loginProfile;

	/** M13: vom Server bestätigtes Profil (null vor {@code login_finished}). */
	public GameProfile loginProfile() {
		return this.loginProfile;
	}

	/** M13: true, wenn der Datenstrom verschlüsselt ist. */
	public boolean encrypted() {
		return this.channel.encrypted();
	}
	private final LongSupplier nanoClock;
	private final SessionStats stats = new SessionStats();
	private final PlayerState player = new PlayerState();
	private final Object lock = new Object();

	private volatile ProtocolState state = ProtocolState.HANDSHAKE;
	private volatile boolean playLoginReceived;
	private volatile boolean stopRequested;
	private volatile String stopReason;
	private volatile String serverDisconnectReason;
	private volatile boolean ended;
	private volatile long playStartNanos = -1;
	private boolean playerLoadedSent;
	private volatile boolean respawnRequested;
	private volatile float health = 20.0F;
	/** Protokoll-IDs im festen Register {@code minecraft:attribute} (reports/registries.json, 26.3). */
	static final int ATTRIBUTE_BLOCK_INTERACTION_RANGE = 8;
	static final int ATTRIBUTE_ENTITY_INTERACTION_RANGE = 13;
	static final int ATTRIBUTE_BLOCK_BREAK_SPEED = 7;
	static final int ATTRIBUTE_MINING_EFFICIENCY = 24;
	static final int ATTRIBUTE_SUBMERGED_MINING_SPEED = 34;
	static final int GAME_EVENT_CHANGE_GAME_MODE = 3;
	static final int GAME_EVENT_IMMEDIATE_RESPAWN = 11;
	private final PlayerInventory inventory = new PlayerInventory();
	/** M10: Gesundheit, Hunger, Effekte, Tod (docs/40). */
	private final PlayerSurvivalState survival = new PlayerSurvivalState();
	/** M12: Spielzeit, Weltuhren und Wetter vom Server (nur Darstellung, docs/48 §2). */
	private final LevelEnvironment levelEnvironment = new LevelEnvironment();
	/** M10: automatische Wiederbelebung (M5-Verhalten) nur für Terminal/Headless; der Grafikclient wartet auf den Spieler. */
	private volatile boolean autoRespawn = true;
	private volatile List<String> damageTypes = List.of();
	/** M9: Entities und Spielerliste (docs/35). */
	private final dev.mcresearch.protocolclient.entity.EntityManager entities = new dev.mcresearch.protocolclient.entity.EntityManager();
	private final InventoryController inventoryController = new InventoryController(this.inventory);
	private final BlockInteraction interaction = new BlockInteraction(dev.mcresearch.protocolclient.physics.ShapeRegistry.get(), BlockRegistry.get(),
		dev.mcresearch.protocolclient.world.ItemRegistry.get());
	private final java.util.concurrent.ConcurrentHashMap<Long, Integer> destruction = new java.util.concurrent.ConcurrentHashMap<>();
	private volatile int gameMode;
	private volatile int abilities;
	private volatile double blockBreakSpeed = 1.0;
	private volatile double miningEfficiency = 0.0;
	private volatile double submergedMiningSpeed = 0.2;
	private volatile double blockInteractionRange = 4.5;
	private volatile double entityInteractionRange = 3.0;
	private boolean inBundle;
	private int bundlePackets;
	private int dimensionTypeCount = -1;
	private List<DimensionInfo> dimensionTypes;
	private List<String> biomeNames;
	private volatile String dimensionKey;
	private volatile WorldState world;
	private final MovementController movement = new MovementController(this.player);
	private long lastKeepAliveNanos = -1;
	private String serverBrand;
	private int entityId = -1;

	public ClientSession(final PacketChannel channel, final SessionConfig config) {
		this(channel, config, System::nanoTime);
	}

	/** M13: Sitzung mit beliebiger Login-Identität (offline, Microsoft oder lokale Simulation), docs/52 §2. */
	public ClientSession(final PacketChannel channel, final SessionConfig config, final dev.mcresearch.protocolclient.auth.LoginIdentity identity) {
		this(channel, config, System::nanoTime, identity);
	}

	ClientSession(final PacketChannel channel, final SessionConfig config, final LongSupplier nanoClock) {
		this(channel, config, nanoClock, new dev.mcresearch.protocolclient.auth.OfflineIdentity(config.username()));
	}

	ClientSession(final PacketChannel channel, final SessionConfig config, final LongSupplier nanoClock,
		final dev.mcresearch.protocolclient.auth.LoginIdentity identity) {
		this.channel = channel;
		this.config = config;
		this.nanoClock = nanoClock;
		this.identity = identity;
		this.interaction.setSurvival(this.survival); // M10: Angriffsstärke und Item-Benutzung
	}

	public ProtocolState state() {
		return this.state;
	}

	public SessionStats stats() {
		return this.stats;
	}

	public dev.mcresearch.protocolclient.net.TransportStats transportStats() {
		return this.channel.transportStats();
	}

	public MovementController movement() {
		return this.movement;
	}

	/** Im Spiel: Play-Zustand, Login empfangen, weder eigener Stopp noch Trennung durch den Server oder Verbindungsende. */
	public boolean playing() {
		return this.state == ProtocolState.PLAY && this.playLoginReceived && !this.stopRequested && this.serverDisconnectReason == null && !this.ended;
	}

	/** Verbindung beendet (Paketschleife verlassen). */
	public boolean ended() {
		return this.ended;
	}

	public PlayerState player() {
		return this.player;
	}

	public boolean stopRequested() {
		return this.stopRequested;
	}

	/** Handshake (Intent LOGIN) und {@code hello} senden. */
	public void start() throws IOException {
		send(new Serverbound.Intention(this.config.host(), this.config.port(), Serverbound.Intention.LOGIN));
		transition(ProtocolState.LOGIN);
		// Vanilla: ServerboundHelloPacket(user.getName(), user.getProfileId())
		send(new Serverbound.Hello(this.identity.name(), this.identity.profileId()));
	}

	public dev.mcresearch.protocolclient.auth.LoginIdentity identity() {
		return this.identity;
	}

	/** M13: Login-Schritte, z. B. {@code hello(auth) join key encrypted compression finished}. */
	public String loginTrace() {
		return this.loginTrace.trim();
	}

	private void trace(final String step) {
		this.loginTrace = this.loginTrace + " " + step;
	}

	/**
	 * M13: Online-Mode-Login (docs/51 §2–§5) nach Vanilla {@code ClientHandshakePacketListenerImpl#handleHello}:
	 * Schlüssel erzeugen, Session-Hash berechnen, bei {@code shouldAuthenticate} Session-Nachweis (join) abwarten,
	 * dann {@code key} unverschlüsselt senden und danach beide Richtungen auf AES/CFB8 umschalten. Läuft im Lesethread,
	 * daher wird bis zur Umschaltung kein weiteres Paket gelesen.
	 */
	private void onEncryptionRequest(final Clientbound.EncryptionRequest p) throws IOException {
		requireState(ProtocolState.LOGIN, "hello");
		trace(p.shouldAuthenticate() ? "hello(auth)" : "hello(noauth)");
		if (p.shouldAuthenticate() && !this.identity.canAuthenticate()) {
			throw new ProtocolException("Server requires online-mode authentication (encryption); offline identity cannot join");
		}
		javax.crypto.SecretKey secret;
		byte[] encSecret;
		byte[] encChallenge;
		String hash;
		try {
			java.security.PublicKey key = dev.mcresearch.protocolclient.net.LoginCrypto.serverPublicKey(p.publicKey());
			secret = dev.mcresearch.protocolclient.net.LoginCrypto.newSharedSecret();
			hash = dev.mcresearch.protocolclient.net.LoginCrypto.serverHash(p.serverId(), secret.getEncoded(), p.publicKey());
			encSecret = dev.mcresearch.protocolclient.net.LoginCrypto.rsaEncrypt(key, secret.getEncoded());
			encChallenge = dev.mcresearch.protocolclient.net.LoginCrypto.rsaEncrypt(key, p.challenge());
		} catch (java.security.GeneralSecurityException e) {
			throw new ProtocolException("Invalid server key in hello: " + e.getMessage());
		}
		if (p.shouldAuthenticate()) {
			LOG.log(Level.INFO, "Session join for " + this.identity.name() + " (" + this.identity.kind() + ")");
			this.identity.joinServer(hash); // AuthException: kontrollierter Abbruch, Server trennt nach Timeout
			trace("join");
		}
		send(new Serverbound.Key(encSecret, encChallenge));
		trace("key");
		this.channel.enableEncryption(secret);
		trace("encrypted");
	}

	/** Vollständiger Lauf: start, Paketschleife, Tick-Thread, Auswertung. */
	public Result run() {
		ScheduledExecutorService ticker = Executors.newSingleThreadScheduledExecutor(r -> {
			Thread t = new Thread(r, "client-tick");
			t.setDaemon(true);
			return t;
		});
		Throwable error = null;
		try {
			start();
			ticker.scheduleAtFixedRate(this::tickSafely, 50, 50, TimeUnit.MILLISECONDS);
			while (true) {
				RawPacket raw = this.channel.receive();
				Clientbound packet = ClientboundDecoder.decode(raw);
				this.stats.received(raw.name());
				countBundleMember(packet);
				handle(packet);
			}
		} catch (EOFException | SocketException e) {
			if (!this.stopRequested && this.serverDisconnectReason == null) {
				error = e;
			}
		} catch (SocketTimeoutException e) {
			error = new IOException("No data from server for " + this.config.readTimeout().toSeconds() + " s", e);
		} catch (IOException | RuntimeException e) {
			error = e;
		} finally {
			this.ended = true;
			ticker.shutdownNow();
			this.channel.close();
		}
		return result(error);
	}

	private Result result(final Throwable error) {
		Duration inPlay = this.playStartNanos < 0 ? Duration.ZERO : Duration.ofNanos(this.nanoClock.getAsLong() - this.playStartNanos);
		boolean reachedPlay = this.playLoginReceived;
		String outcome;
		boolean success;
		if (error != null) {
			outcome = "ERROR: " + error;
			success = false;
			LOG.log(Level.ERROR, "Session failed in state " + this.state, error);
		} else if (this.serverDisconnectReason != null) {
			outcome = "Disconnected by server: " + this.serverDisconnectReason;
			success = false;
		} else if (this.stopRequested) {
			outcome = "Controlled disconnect (" + this.stopReason + ")";
			success = reachedPlay && inPlay.compareTo(this.config.playDuration()) >= 0;
		} else {
			outcome = "Connection ended unexpectedly";
			success = false;
		}
		return new Result(success, outcome, reachedPlay, inPlay, this.serverDisconnectReason, error, this.serverBrand, this.entityId,
			this.player.toString(), this.stats, this.channel.transportStats());
	}

	/** Beendet die Sitzung kontrolliert (Vanilla-Client sendet beim Verlassen kein Paket, nur TCP-Close). */
	public void requestStop(final String reason) {
		if (this.stopRequested) {
			return;
		}
		this.stopRequested = true;
		this.stopReason = reason;
		this.channel.marker("STEP " + this.state.name() + " -> DISCONNECT (" + reason + ")");
		LOG.log(Level.INFO, "Stopping: " + reason);
		synchronized (this.lock) {
			this.channel.close();
		}
	}

	private void tickSafely() {
		try {
			tick();
		} catch (IOException | RuntimeException e) {
			if (!this.stopRequested) {
				LOG.log(Level.WARNING, "Tick failed", e);
			}
		}
	}

	/** Ein Client-Tick (50 ms): {@code client_tick_end} im Play-Zustand, Laufzeitende prüfen. */
	public void tick() throws IOException {
		if (this.stopRequested) {
			return;
		}
		synchronized (this.lock) {
			if (this.state == ProtocolState.PLAY && this.playLoginReceived) {
				// Reihenfolge wie Minecraft#tick: player.sendChanges() (Input, Position) … dann client_tick_end.
				// Höchstens ein Positionspaket je client_tick_end (Serverregel receivedPositionThisTick).
				// M8: Inventarklicks (Bildschirmeingaben) vor gameMode.tick/handleKeybinds
				for (ServerboundPacket packet : this.inventoryController.tick()) {
					send(packet);
				}
				// M7: Minecraft#tick ruft gameMode.tick() und handleKeybinds() vor dem Spieler-Tick (sendPosition) auf
				for (ServerboundPacket packet : interactionTick()) {
					send(packet);
				}
				for (ServerboundPacket packet : this.movement.tick()) {
					send(packet);
				}
				send(new Serverbound.ClientTickEnd());
				this.entities.tick();
				this.survival.tick();
				this.levelEnvironment.tick();
				this.stats.ticksSent.incrementAndGet();
			}
		}
		if (!this.config.playDuration().isZero() && this.playStartNanos >= 0
			&& this.nanoClock.getAsLong() - this.playStartNanos >= this.config.playDuration().toNanos()) {
			requestStop("play duration of " + this.config.playDuration().toSeconds() + " s reached");
		}
	}

	private List<ServerboundPacket> interactionTick() {
		WorldState w = this.world;
		if (w == null || !this.movement.ready()) {
			return List.of();
		}
		ItemStack[] slots = this.inventory.snapshot();
		List<ItemStack> hotbar = java.util.Arrays.asList(slots).subList(PlayerInventory.HOTBAR_START, PlayerInventory.HOTBAR_START + 9);
		synchronized (w) {
			BlockInteraction.Snapshot snap = new BlockInteraction.Snapshot(w::stateId, this.player.x(), this.player.y(), this.player.z(), this.player.yRot(),
				this.player.xRot(), this.player.onGround(), this.blockInteractionRange, this.entityInteractionRange, this.gameMode,
				(this.abilities & 8) != 0, hotbar, slots[PlayerInventory.OFFHAND], this.blockBreakSpeed, this.miningEfficiency, this.submergedMiningSpeed, this.entities.snapshot(1.0F, this.gameMode));
			List<ServerboundPacket> out = this.interaction.tick(snap);
			this.inventory.setSelected(this.interaction.selectedSlot());
			return out;
		}
	}

	/** Inventarfenster geschlossen ({@code container_close}, Vanilla AbstractContainerScreen#removed → closeContainer). */
	public void closeContainer(final int containerId) {
		this.inventoryController.submit(new InventoryController.Close(containerId));
	}

	/** M8: Inventarbedienung (Gesten → container_click, docs/31 §3). */
	public InventoryController inventoryController() {
		return this.inventoryController;
	}

	/** M7: Linksklick, Rechtsklick, Hotbar (docs/27, docs/28). */
	public BlockInteraction interaction() {
		return this.interaction;
	}

	public PlayerInventory inventory() {
		return this.inventory;
	}

	/** Spielmodus: 0 Überleben, 1 Kreativ, 2 Abenteuer, 3 Zuschauer (login/respawn/game_event 3). */
	public int gameMode() {
		return this.gameMode;
	}

	/** {@code player_abilities}-Bits (8 = instabuild). */
	public int abilities() {
		return this.abilities;
	}

	/** Vom Server gemeldeter Abbaufortschritt (block_destruction) je Position, 0–9; andere Werte entfernen ihn. */
	public java.util.Map<Long, Integer> destruction() {
		return java.util.Map.copyOf(this.destruction);
	}

	/** Offener Container ({@code open_screen}) oder −1. */
	public int openContainer() {
		PlayerInventory.OpenContainer c = this.inventory.openContainer();
		return c == null ? -1 : c.id();
	}

	private void countBundleMember(final Clientbound packet) throws ProtocolException {
		if (this.inBundle && !(packet instanceof Clientbound.BundleDelimiter) && ++this.bundlePackets > BUNDLE_SIZE_LIMIT) {
			throw new ProtocolException("Bundle exceeds " + BUNDLE_SIZE_LIMIT + " packets");
		}
	}

	/** Verarbeitet ein dekodiertes Paket im aktuellen Zustand. Öffentlich für Zustandstests. */
	public void handle(final Clientbound packet) throws IOException {
		// M12: darstellungsrelevante Ereignisse (Partikel, Abbau, kritische Treffer) für den Grafikteil vormerken
		if (this.state == ProtocolState.PLAY) {
			switch (packet) {
				case Clientbound.LevelParticles p -> offerVisual(new VisualEvent.Particles(p));
				case Clientbound.LevelEvent p -> {
					if (p.type() == 2001) {
						offerVisual(new VisualEvent.BlockBroken(p.x(), p.y(), p.z(), p.data()));
					}
					this.levelEvents.incrementAndGet();
				}
				case Clientbound.Animate p -> {
					if (p.action() == 4 || p.action() == 5) {
						offerVisual(new VisualEvent.Crit(p.id(), p.action() == 5));
					}
				}
				default -> {
				}
			}
		}
		// M9: Entity-Pakete (eigene update_attributes gehen weiter an onUpdateAttributes)
		if (this.state == ProtocolState.PLAY && handleSurvival(packet)) {
			return;
		}
		if (this.state == ProtocolState.PLAY && this.entities.handle(packet)) {
			return;
		}
		switch (packet) {
			// ----- Login -----
			case Clientbound.LoginFinished p -> onLoginFinished(p);
			case Clientbound.LoginCompression p -> {
				LOG.log(Level.INFO, "Compression threshold " + p.threshold());
				this.channel.setCompressionThreshold(p.threshold());
				trace("compression(" + p.threshold() + ")");
			}
			case Clientbound.EncryptionRequest p -> onEncryptionRequest(p);
			case Clientbound.LoginDisconnect p -> serverDisconnect(p.reason());
			case Clientbound.CustomQuery p -> send(new Serverbound.CustomQueryAnswer(p.transactionId()));
			case Clientbound.CookieRequest p -> send(new Serverbound.CookieResponse(this.state, p.key()));
			// ----- Configuration -----
			case Clientbound.SelectKnownPacks p -> send(new Serverbound.SelectKnownPacks(this.config.acceptKnownPacks() ? p.packs() : List.of()));
			case Clientbound.RegistryData p -> onRegistryData(p);
			case Clientbound.UpdateTags p -> LOG.log(Level.DEBUG, "Tags: " + p);
			case Clientbound.UpdateEnabledFeatures p -> LOG.log(Level.DEBUG, "Features: " + p.features());
			case Clientbound.CustomPayload p -> {
				if (p.brand() != null) {
					this.serverBrand = p.brand();
				}
			}
			case Clientbound.FinishConfiguration p -> onFinishConfiguration();
			// ----- Configuration + Play -----
			case Clientbound.KeepAlive p -> onKeepAlive(p);
			case Clientbound.Ping p -> send(new Serverbound.Pong(this.state, p.id()));
			case Clientbound.Disconnect p -> serverDisconnect(p.reason());
			// ----- Play -----
			case Clientbound.PlayLogin p -> onPlayLogin(p);
			case Clientbound.PlayerPosition p -> onPlayerPosition(p);
			case Clientbound.ChunkBatchStart p -> requireState(ProtocolState.PLAY, "chunk_batch_start");
			case Clientbound.ChunkBatchFinished p -> {
				this.stats.chunksAnnounced.addAndGet(p.batchSize());
				send(new Serverbound.ChunkBatchReceived(this.config.desiredChunksPerTick()));
				this.stats.chunkBatchesAcknowledged.incrementAndGet();
			}
			case Clientbound.StartConfiguration p -> onStartConfiguration();
			case Clientbound.PlayerRotation p -> {
				requireState(ProtocolState.PLAY, "player_rotation");
				this.player.applyRotation(p); // der nächste Tick meldet die Rotation per move_player_rot
			}
			case Clientbound.LevelChunkWithLight p -> {
				requireState(ProtocolState.PLAY, "level_chunk_with_light");
				WorldState w = this.world;
				if (w != null) {
					w.loadChunk(p); // vollständige Dekodierung inkl. Restbyte-Prüfung
				}
				TerrainCache terrain = this.movement.terrain();
				if (terrain != null) {
					terrain.accept(p.heightmapsRecord());
				}
			}
			case Clientbound.ForgetChunk p -> {
				WorldState w = this.world;
				if (w != null) {
					w.unloadChunk(p.chunkX(), p.chunkZ());
				}
				TerrainCache terrain = this.movement.terrain();
				if (terrain != null) {
					terrain.forget(p.chunkX(), p.chunkZ());
				}
			}
			case Clientbound.BlockUpdate p -> {
				requireState(ProtocolState.PLAY, "block_update");
				if (this.world != null) {
					this.world.applyBlockUpdate(p);
				}
			}
			case Clientbound.SectionBlocksUpdate p -> {
				requireState(ProtocolState.PLAY, "section_blocks_update");
				if (this.world != null) {
					this.world.applySectionUpdate(p);
				}
			}
			case Clientbound.LightUpdate p -> {
				if (this.world != null) {
					this.world.applyLightUpdate(p);
				}
			}
			case Clientbound.BlockEntityData p -> {
				if (this.world != null) {
					this.world.applyBlockEntityData(p);
				}
			}
			case Clientbound.ChunksBiomes p -> {
				if (this.world != null) {
					this.world.applyBiomes(p);
				}
			}
			case Clientbound.Respawn p -> onRespawn(p);
			case Clientbound.SetHealth p -> onSetHealth(p);
			case Clientbound.UpdateAttributes p -> onUpdateAttributes(p);
			// ---- M7: Blockinteraktion und Inventar ----
			case Clientbound.BlockChangedAck p -> {
				this.interaction.onAck(p.sequence());
				this.stats.blockAcks.incrementAndGet();
			}
			case Clientbound.BlockDestruction p -> {
				long k = dev.mcresearch.protocolclient.protocol.codec.Positions.packBlock(p.x(), p.y(), p.z());
				if (p.progress() >= 0 && p.progress() <= 9) {
					this.destruction.put(k, p.progress());
				} else {
					this.destruction.remove(k);
				}
			}
			case Clientbound.SetHeldSlot p -> {
				if (p.slot() >= 0 && p.slot() <= 8) {
					this.inventory.setSelected(p.slot());
					this.interaction.onServerSelectedSlot(p.slot());
				}
			}
			// M8: alle Containerpakete ans Modell; es prüft Container-ID und Platz selbst (docs/31 §4)
			case Clientbound.ContainerSetContent p -> this.inventory.setContent(p.containerId(), p.stateId(), p.items(), p.carried());
			case Clientbound.ContainerSetSlot p -> this.inventory.setSlot(p.containerId(), p.stateId(), p.slot(), p.item());
			case Clientbound.ContainerSetData p -> this.inventory.setData(p.containerId(), p.id(), p.value());
			case Clientbound.SetPlayerInventory p -> this.inventory.setInventoryIndex(p.slot(), p.item());
			case Clientbound.SetCursorItem p -> this.inventory.setCursor(p.item());
			case Clientbound.PlayerAbilities p -> {
				this.abilities = p.flags();
				this.movement.setMayfly((p.flags() & 4) != 0); // M11: mayfly erlaubt Sprinten ohne Nahrung
			}
			case Clientbound.SetTime p -> {
				java.util.Map<Integer, long[]> totals = new java.util.HashMap<>();
				java.util.Map<Integer, float[]> rates = new java.util.HashMap<>();
				for (Clientbound.ClockUpdate c : p.clocks()) {
					totals.put(c.clockId(), new long[]{c.totalTicks()});
					rates.put(c.clockId(), new float[]{c.partialTick(), c.rate()});
				}
				this.levelEnvironment.onSetTime(p.gameTime(), totals, rates);
			}
			case Clientbound.GameEvent p -> {
				this.levelEnvironment.onGameEvent(p.event(), p.param());
				if (p.event() == GAME_EVENT_CHANGE_GAME_MODE) {
					this.gameMode = (int) p.param();
				} else if (p.event() == GAME_EVENT_IMMEDIATE_RESPAWN) {
					this.survival.onImmediateRespawn(p.param() == 1.0F);
				}
			}
			case Clientbound.OpenScreen p -> this.inventory.openScreen(p.containerId(), p.menuType(), p.title());
			case Clientbound.ContainerClose p -> this.inventory.closeContainer();
			case Clientbound.ItemDataUnsupported p -> {
				this.inventory.unsupported(p.packet(), p.reason());
				LOG.log(Level.WARNING, "Inventory packet " + p.packet() + " not decodable: " + p.reason());
			}
			case Clientbound.BundleDelimiter p -> {
				this.inBundle = !this.inBundle;
				if (this.inBundle) {
					this.bundlePackets = 0;
				} else {
					this.stats.bundles.incrementAndGet();
				}
			}
			case Clientbound.Skipped p -> {
				// gültige, für das Ziel nicht benötigte Pakete (Chunks, Entities, …)
			}
			case Clientbound.StatusResponse p -> throw new ProtocolException("status_response outside status state");
			case Clientbound.PongResponse p -> throw new ProtocolException("pong_response outside status state");
			default -> {
				// Entity-Pakete außerhalb von Play kommen nicht vor
			}
		}
	}

	private void onLoginFinished(final Clientbound.LoginFinished p) throws IOException {
		requireState(ProtocolState.LOGIN, "login_finished");
		trace("finished");
		this.loginProfile = p.profile();
		if (!p.profile().name().equals(this.identity.name())) {
			LOG.log(Level.WARNING, "Server assigned name " + p.profile().name() + " instead of " + this.identity.name());
		}
		if (!p.profile().id().equals(this.identity.profileId())) {
			LOG.log(Level.WARNING, "Server assigned UUID " + p.profile().id() + " instead of " + this.identity.profileId() + " (" + this.identity.kind() + ")");
		}
		if (this.identity.canAuthenticate() && !this.channel.encrypted()) {
			LOG.log(Level.WARNING, "Login finished without encryption although an online identity is used (offline-mode server)");
		}
		LOG.log(Level.INFO, "Logged in as " + p.profile().name() + " " + p.profile().id());
		synchronized (this.lock) {
			// Reihenfolge wie Vanilla (ClientHandshakePacketListenerImpl#handleLoginFinished): Eingang umschalten, ack, Ausgang umschalten
			this.channel.setInboundState(ProtocolState.CONFIGURATION);
			send(new Serverbound.LoginAcknowledged());
			transition(ProtocolState.CONFIGURATION);
		}
		send(new Serverbound.Brand(this.config.brand()));
		send(Serverbound.ClientInformation.defaults(this.config.viewDistance()));
	}

	private void onRegistryData(final Clientbound.RegistryData p) throws ProtocolException {
		requireState(ProtocolState.CONFIGURATION, "registry_data");
		this.stats.registries.incrementAndGet();
		this.stats.registryEntries.addAndGet(p.entries().size());
		this.stats.registryEntriesWithData.addAndGet((int) p.entries().stream().filter(Clientbound.RegistryEntry::hasData).count());
		if (p.registry().equals("minecraft:dimension_type")) {
			this.dimensionTypeCount = p.entries().size();
			// Registry-IDs = Reihenfolge der Einträge; Inhalte nur vorhanden, wenn Known Packs abgelehnt wurden
			this.dimensionTypes = new ArrayList<>(p.entries().size());
			for (Clientbound.RegistryEntry e : p.entries()) {
				this.dimensionTypes.add(e.hasData() ? DimensionInfo.fromNbt(e.id(), e.data()) : null);
			}
		} else if (p.registry().equals("minecraft:damage_type")) {
			// M10: Schadenstyp-IDs = Reihenfolge der Einträge (damage_event, docs/38 §1)
			List<String> names = new ArrayList<>();
			for (Clientbound.RegistryEntry e : p.entries()) {
				names.add(e.id());
			}
			this.damageTypes = List.copyOf(names);
		} else if (p.registry().equals("minecraft:world_clock")) {
			// M12: Uhr-IDs in set_time = Reihenfolge der Einträge
			List<String> names = new ArrayList<>();
			for (Clientbound.RegistryEntry e : p.entries()) {
				names.add(e.id());
			}
			this.levelEnvironment.setClockNames(names);
		} else if (p.registry().equals("minecraft:worldgen/biome")) {
			// Biom-IDs = Reihenfolge der Einträge; Anzahl bestimmt die Bits der globalen Biom-Palette (docs/18 §4.2)
			List<String> names = new ArrayList<>(p.entries().size());
			for (Clientbound.RegistryEntry e : p.entries()) {
				names.add(e.id());
			}
			this.biomeNames = List.copyOf(names);
		}
	}

	private void onFinishConfiguration() throws IOException {
		requireState(ProtocolState.CONFIGURATION, "finish_configuration");
		synchronized (this.lock) {
			this.channel.setInboundState(ProtocolState.PLAY);
			send(new Serverbound.FinishConfiguration());
			transition(ProtocolState.PLAY);
		}
	}

	private void onStartConfiguration() throws IOException {
		requireState(ProtocolState.PLAY, "start_configuration");
		synchronized (this.lock) {
			this.channel.setInboundState(ProtocolState.CONFIGURATION);
			send(new Serverbound.ConfigurationAcknowledged());
			transition(ProtocolState.CONFIGURATION);
		}
		this.dimensionTypeCount = -1;
		this.entities.clear(true); // Konfigurationsphase: Welt und Spielerliste werden neu aufgebaut
	}

	/**
	 * M10: Pakete des eigenen Überlebenszustands (docs/38). Eigene Datenfelder, Ereignisse und Effekte gehen an
	 * {@link PlayerSurvivalState}; Schadensereignisse anderer Entities zusätzlich an den EntityManager. Liefert true, wenn das Paket
	 * vollständig verarbeitet ist.
	 */
	private boolean handleSurvival(final Clientbound packet) throws IOException {
		switch (packet) {
			case Clientbound.SetEntityData p when p.id() == this.entityId -> {
				for (Clientbound.DataValue v : p.values()) {
					this.survival.onOwnData(v.index(), v.value());
					this.movement.onOwnEntityData(v.index(), v.value()); // M11: Sprint-Flag und Pose wie ClientPacketListener
				}
				return true;
			}
			case Clientbound.SetEntityMotion p when p.id() == this.entityId -> {
				// Rückstoß für den eigenen Spieler: Geschwindigkeit ersetzen (Entity#lerpMotion)
				this.movement.applyServerMotion(p.dx(), p.dy(), p.dz());
				return true;
			}
			case Clientbound.EntityEvent p when p.id() == this.entityId -> {
				this.survival.onEntityEvent(p.event());
				return true;
			}
			case Clientbound.DamageEvent p -> {
				if (p.entityId() == this.entityId) {
					this.survival.onDamage(damageTypeName(p.damageType()));
					return true;
				}
				return false; // an den EntityManager (Trefferanzeige, bestätigte Treffer)
			}
			case Clientbound.UpdateMobEffect p -> {
				if (p.entityId() == this.entityId) {
					this.survival.onEffect(p.effect(), p.amplifier(), p.duration(), p.flags());
					syncMovementEffects();
				}
				return true;
			}
			case Clientbound.RemoveMobEffect p -> {
				if (p.entityId() == this.entityId) {
					this.survival.onEffectRemoved(p.effect());
					syncMovementEffects();
				}
				return true;
			}
			case Clientbound.PlayerCombatKill p -> {
				if (p.playerId() == this.entityId) {
					this.survival.onDeath(p.message(), p.component());
					LOG.log(Level.INFO, "Death: " + p.message() + (this.autoRespawn ? " (auto respawn)" : " (waiting for the player)"));
					if (this.autoRespawn && !this.respawnRequested) {
						requestRespawn();
					}
				}
				return true;
			}
			case Clientbound.PlayerCombat p -> {
				return true;
			}
			case Clientbound.SetExperience p -> {
				this.survival.onExperience(p.progress(), p.level());
				return true;
			}
			case Clientbound.ItemCooldown p -> {
				this.survival.onCooldown(p.group(), p.duration());
				return true;
			}
			default -> {
				return false;
			}
		}
	}

	/** Name eines Schadenstyps (Registry aus der Konfiguration) oder "#id". */
	public String damageTypeName(final int id) {
		List<String> t = this.damageTypes;
		return id >= 0 && id < t.size() ? t.get(id) : "#" + id;
	}

	/** M10: Wiederbelebung anfordern ({@code client_command} PERFORM_RESPAWN) – Todesbildschirm oder Headless-Automatik. */
	public void requestRespawn() throws IOException {
		if (this.respawnRequested) {
			return;
		}
		this.respawnRequested = true;
		this.stats.respawnsRequested.incrementAndGet();
		this.survival.respawnRequested();
		synchronized (this.lock) {
			send(new Serverbound.ClientCommand(Serverbound.ClientCommand.PERFORM_RESPAWN));
		}
	}

	/** M10: automatische Wiederbelebung ein/aus (Standard ein = M5-Verhalten für Terminal und Headless-Regression). */
	public void setAutoRespawn(final boolean auto) {
		this.autoRespawn = auto;
	}

	public boolean autoRespawn() {
		return this.autoRespawn;
	}

	/** M10: Überlebenszustand des eigenen Spielers. */
	public PlayerSurvivalState survival() {
		return this.survival;
	}

	/** M9: Entities und Spielerliste (docs/35). */
	public dev.mcresearch.protocolclient.entity.EntityManager entities() {
		return this.entities;
	}

	/**
	 * M9 Phase F: Auswahl zwischen Block und Entity vom eigenen Augenpunkt (Augenhöhe 1,62, Blickrichtung des Spielers), mit den
	 * dargestellten Entity-Positionen bei {@code partialTick} und den Reichweiten aus {@code update_attributes} (docs/35 §6).
	 */
	public dev.mcresearch.protocolclient.entity.EntityPick.Target entityTarget(final float partialTick) {
		WorldState w = this.world;
		java.util.List<dev.mcresearch.protocolclient.entity.EntityManager.View> views = this.entities.snapshot(partialTick, this.gameMode);
		double x = this.player.x();
		double eye = this.player.y() + (double) 1.62F;
		double z = this.player.z();
		if (w == null) {
			return dev.mcresearch.protocolclient.entity.EntityPick.pick(null, null, x, eye, z, this.player.yRot(), this.player.xRot(),
				this.blockInteractionRange, this.entityInteractionRange, views, -1);
		}
		synchronized (w) {
			return dev.mcresearch.protocolclient.entity.EntityPick.pick(w::stateId, dev.mcresearch.protocolclient.physics.ShapeRegistry.get(), x, eye, z,
				this.player.yRot(), this.player.xRot(), this.blockInteractionRange, this.entityInteractionRange, views, -1);
		}
	}

	private void onKeepAlive(final Clientbound.KeepAlive p) throws IOException {
		if (this.state != ProtocolState.CONFIGURATION && this.state != ProtocolState.PLAY) {
			throw new ProtocolException("keep_alive in state " + this.state);
		}
		long now = this.nanoClock.getAsLong();
		if (this.lastKeepAliveNanos >= 0) {
			this.stats.maxKeepAliveGapMillis.accumulateAndGet(TimeUnit.NANOSECONDS.toMillis(now - this.lastKeepAliveNanos), Math::max);
		}
		this.lastKeepAliveNanos = now;
		synchronized (this.lock) {
			send(new Serverbound.KeepAlive(this.state, p.id()));
		}
		this.stats.keepAlivesAnswered.incrementAndGet();
	}

	private void onPlayLogin(final Clientbound.PlayLogin p) throws ProtocolException {
		requireState(ProtocolState.PLAY, "login");
		if (this.dimensionTypeCount >= 0 && p.dimensionTypeId() >= this.dimensionTypeCount) {
			throw new ProtocolException("dimensionType " + p.dimensionTypeId() + " outside received registry (" + this.dimensionTypeCount + " entries)");
		}
		this.entityId = p.entityId();
		this.movement.setEntityId(p.entityId());
		this.entities.setOwnId(p.entityId());
		this.gameMode = p.gameType();
		if (!this.playLoginReceived) {
			this.playStartNanos = this.nanoClock.getAsLong();
		}
		this.playLoginReceived = true;
		DimensionInfo dim = enterDimension(p.dimensionTypeId(), p.dimension(), p.seed());
		LOG.log(Level.INFO, "Joined " + p.dimension() + " as entity " + p.entityId() + ", gameType " + p.gameType()
			+ ", chunkRadius " + p.chunkRadius() + (dim == null ? ", terrain unknown (known packs accepted: no dimension data)"
				: ", dimension type " + dim.id() + " min_y=" + dim.minY() + " height=" + dim.height() + " sections=" + (dim.height() >> 4)));
	}

	/** M11: Effekte mit Bewegungswirkung an die Physik (IDs im Register mob_effect: 7, 14, 24, 27, 29). */
	private void syncMovementEffects() {
		this.movement.setMovementEffects(this.survival.effectAmplifier(14) >= 0, this.survival.effectAmplifier(7), this.survival.effectAmplifier(24),
			this.survival.effectAmplifier(27) >= 0, this.survival.effectAmplifier(29) >= 0);
	}

	/**
	 * {@code set_health}: Ist der Spieler tot (Lebenspunkte ≤ 0), schickt der Server keine Chunks, bis der Client
	 * {@code client_command PERFORM_RESPAWN} sendet ({@code ServerGamePacketListenerImpl#handleClientCommand}). Der
	 * Vanilla-Client zeigt dafür den Todesbildschirm; dieser Testclient belebt automatisch wieder (docs/23 §4).
	 */
	private void onSetHealth(final Clientbound.SetHealth p) throws IOException {
		requireState(ProtocolState.PLAY, "set_health");
		this.health = p.health();
		this.survival.onHealth(p.health(), p.food(), p.saturation());
		this.movement.setFoodLevel(p.food()); // M11: Sprinten nur mit Nahrung > 6
		if (p.health() > 0.0F) {
			this.respawnRequested = false;
		}
		if (p.health() <= 0.0F && !this.respawnRequested && this.autoRespawn) {
			this.respawnRequested = true;
			this.stats.respawnsRequested.incrementAndGet();
			LOG.log(Level.WARNING, "Player is dead (health " + p.health() + ") – requesting respawn");
			synchronized (this.lock) {
				send(new Serverbound.ClientCommand(Serverbound.ClientCommand.PERFORM_RESPAWN));
			}
		}
	}

	/** Reichweiten aus {@code update_attributes} (Creative-Bonus setzt der Server als Modifikator, ServerPlayer#updatePlayerAttributes). */
	private void onUpdateAttributes(final Clientbound.UpdateAttributes p) {
		if (p.entityId() != this.entityId) {
			return;
		}
		java.util.Map<Integer, Double> all = new java.util.HashMap<>();
		for (Clientbound.UpdateAttributes.Attribute a : p.attributes()) {
			all.put(a.id(), a.value(-1.0e9, 1.0e9));
		}
		this.survival.onAttributes(all);
		// M11: alle Bewegungsattribute mit Modifikatoren an die Physik (Sprint-Modifikator, Effekte, Sprungkraft, Schrittweite …)
		for (Clientbound.UpdateAttributes.Attribute a : p.attributes()) {
			this.movement.setAttribute(a);
		}
		for (Clientbound.UpdateAttributes.Attribute a : p.attributes()) {
			if (a.id() == ATTRIBUTE_BLOCK_INTERACTION_RANGE) {
				this.blockInteractionRange = a.value(0.0, 64.0);
			} else if (a.id() == ATTRIBUTE_ENTITY_INTERACTION_RANGE) {
				this.entityInteractionRange = a.value(0.0, 64.0);
			} else if (a.id() == ATTRIBUTE_BLOCK_BREAK_SPEED) {
				this.blockBreakSpeed = a.value(0.0, 1024.0);
			} else if (a.id() == ATTRIBUTE_MINING_EFFICIENCY) {
				this.miningEfficiency = a.value(0.0, 1024.0);
			} else if (a.id() == ATTRIBUTE_SUBMERGED_MINING_SPEED) {
				this.submergedMiningSpeed = a.value(0.0, 20.0);
			}
		}
	}

	/** Blockreichweite des eigenen Spielers (Attribut {@code minecraft:block_interaction_range}, Standard 4,5). */
	public double blockInteractionRange() {
		return this.blockInteractionRange;
	}

	/** Entity-Reichweite des eigenen Spielers (Attribut {@code minecraft:entity_interaction_range}, Standard 3,0). */
	public double entityInteractionRange() {
		return this.entityInteractionRange;
	}

	/** Attribute {@code block_break_speed}, {@code mining_efficiency}, {@code submerged_mining_speed} (Player#getDestroySpeed). */
	public double blockBreakSpeed() {
		return this.blockBreakSpeed;
	}

	public double miningEfficiency() {
		return this.miningEfficiency;
	}

	public double submergedMiningSpeed() {
		return this.submergedMiningSpeed;
	}

	public float health() {
		return this.health;
	}

	/** {@code respawn}: bei Dimensionswechsel neue, leere Welt (wie {@code ClientPacketListener#handleRespawn}). */
	private void onRespawn(final Clientbound.Respawn p) throws ProtocolException {
		requireState(ProtocolState.PLAY, "respawn");
		// wie ClientPacketListener#handleRespawn: Client gilt als nicht geladen → nach der nächsten Position erneut player_loaded
		this.respawnRequested = false;
		this.survival.onRespawnPacket();
		synchronized (this.lock) {
			this.playerLoadedSent = false;
		}
		this.gameMode = p.gameType();
		this.interaction.reset();
		this.destruction.clear();
		this.inventory.reset();
		this.inventoryController.reset();
		if (this.dimensionTypeCount >= 0 && p.dimensionTypeId() >= this.dimensionTypeCount) {
			throw new ProtocolException("respawn dimensionType " + p.dimensionTypeId() + " outside received registry");
		}
		if (!p.dimension().equals(this.dimensionKey)) {
			this.entities.clear(false); // neue Welt: alte Entities verwerfen (ClientPacketListener#handleRespawn)
			DimensionInfo dim = enterDimension(p.dimensionTypeId(), p.dimension(), p.seed());
			LOG.log(Level.INFO, "Respawn into " + p.dimension() + (dim == null ? "" : " min_y=" + dim.minY() + " height=" + dim.height()));
		}
	}

	private DimensionInfo enterDimension(final int dimensionTypeId, final String dimensionKey, final long hashedSeed) {
		DimensionInfo dim = this.dimensionTypes != null && dimensionTypeId < this.dimensionTypes.size()
			? this.dimensionTypes.get(dimensionTypeId) : null;
		this.dimensionKey = dimensionKey;
		this.levelEnvironment.onNewLevel();
		this.movement.setTerrain(dim == null ? null : new TerrainCache(dim));
		if (dim != null && this.biomeNames != null && !this.biomeNames.isEmpty()) {
			this.world = new WorldState(dim, BlockRegistry.get(), this.biomeNames);
			this.world.setHashedSeed(hashedSeed);
		} else {
			this.world = null;
			LOG.log(Level.WARNING, "World model disabled: dimension or biome registry unknown");
		}
		this.movement.setWorld(this.world); // Kollision und Physik auf dem eigenen Weltmodell (M5)
		return dim;
	}

	/** M12: eigene Abbauten als Darstellungsereignis (Block#playerWillDestroy → levelEvent 2001). */
	{
		this.interaction.setDestroyListener((x, y, z, state) -> offerVisual(new VisualEvent.BlockBroken(x, y, z, state)));
	}

	/** M12: Ereignisse für die Darstellung (nur lesend; volle Schlange verwirft, Simulation bleibt unberührt). */
	public sealed interface VisualEvent {
		record Particles(Clientbound.LevelParticles packet) implements VisualEvent {
		}

		record BlockBroken(int x, int y, int z, int state) implements VisualEvent {
		}

		record Crit(int entityId, boolean magic) implements VisualEvent {
		}
	}

	private final java.util.concurrent.ConcurrentLinkedQueue<VisualEvent> visualEvents = new java.util.concurrent.ConcurrentLinkedQueue<>();
	private final java.util.concurrent.atomic.AtomicInteger visualQueued = new java.util.concurrent.atomic.AtomicInteger();
	private final java.util.concurrent.atomic.AtomicLong levelEvents = new java.util.concurrent.atomic.AtomicLong();

	void offerVisual(final VisualEvent e) {
		if (this.visualQueued.get() < 4096) {
			this.visualEvents.add(e);
			this.visualQueued.incrementAndGet();
		}
	}

	/** Nächstes Darstellungsereignis oder {@code null}. */
	public VisualEvent pollVisual() {
		VisualEvent e = this.visualEvents.poll();
		if (e != null) {
			this.visualQueued.decrementAndGet();
		}
		return e;
	}

	/** M12: Spielzeit, Weltuhren, Regen und Gewitter (vom Server, für Himmel, Licht und Wetter). */
	public LevelEnvironment levelEnvironment() {
		return this.levelEnvironment;
	}

	/** Eigene Weltverwaltung; {@code null} vor dem Login oder ohne Dimensions-/Biomdaten. */
	public WorldState world() {
		return this.world;
	}

	public String dimensionKey() {
		return this.dimensionKey;
	}

	private void onPlayerPosition(final Clientbound.PlayerPosition p) throws IOException {
		requireState(ProtocolState.PLAY, "player_position");
		synchronized (this.lock) {
			// Unter der Tick-Sperre: kein Bewegungspaket zwischen Übernahme der Serverposition und der Bestätigung
			boolean correction = this.movement.ready();
			this.player.apply(p);
			send(new Serverbound.AcceptTeleportation(p.teleportId(), this.player.x(), this.player.y(), this.player.z(), this.player.yRot(), this.player.xRot()));
			this.stats.teleportsAccepted.incrementAndGet();
			this.movement.onServerPosition(correction);
			LOG.log(Level.INFO, (correction ? "Server position correction " : "Teleport ") + p.teleportId() + " accepted at " + this.player);
			if (this.playLoginReceived && !this.playerLoadedSent) {
				send(new Serverbound.PlayerLoaded());
				this.playerLoadedSent = true;
				this.movement.setReady();
			}
		}
	}

	private void serverDisconnect(final String reason) {
		this.serverDisconnectReason = reason;
		LOG.log(Level.WARNING, "Disconnected by server: " + reason);
		this.channel.close();
	}

	private void requireState(final ProtocolState expected, final String packet) throws ProtocolException {
		if (this.state != expected) {
			throw new ProtocolException(packet + " received in state " + this.state + ", expected " + expected);
		}
	}

	private void transition(final ProtocolState next) {
		synchronized (this.lock) {
			this.channel.marker("STEP " + this.state.name() + " -> " + next.name());
			LOG.log(Level.INFO, "State " + this.state + " -> " + next);
			this.state = next;
			this.channel.setOutboundState(next);
			this.channel.setInboundState(next);
		}
	}

	private void send(final ServerboundPacket packet) throws IOException {
		this.channel.send(packet);
		this.stats.sent(packet.name());
	}
}
