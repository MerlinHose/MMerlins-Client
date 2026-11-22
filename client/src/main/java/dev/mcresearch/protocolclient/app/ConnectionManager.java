package dev.mcresearch.protocolclient.app;

import dev.mcresearch.protocolclient.auth.LoginIdentity;
import dev.mcresearch.protocolclient.net.Connection;
import dev.mcresearch.protocolclient.net.LoopbackGuard;
import dev.mcresearch.protocolclient.net.PacketLog;
import dev.mcresearch.protocolclient.protocol.ProtocolState;
import dev.mcresearch.protocolclient.session.ClientSession;
import dev.mcresearch.protocolclient.session.SessionConfig;
import java.io.IOException;
import java.net.InetAddress;
import java.time.Duration;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

/**
 * Verbindungsverwaltung des grafischen Clients (docs/61 §4): Statusprüfung, Verbindungsaufbau im Hintergrund, Abbruch, Trennen,
 * Freigabe. {@link #phase()} spiegelt den tatsächlichen Zustandsautomaten der {@link ClientSession} (Login → Configuration →
 * Play → Welt geladen); der ConnectingScreen zeigt genau diese Phasen.
 *
 * <p>Threads: ein kurzlebiger Thread „connect“ (Statusping, TCP-Aufbau) und je Sitzung der Thread „session“ (Paketschleife) samt
 * Tick-Thread der Sitzung. Nach {@link #disconnect} bzw. {@link #cancel} sind alle beendet ({@link #awaitIdle}).
 */
public final class ConnectionManager {
	/** Sichtbare Phasen. */
	public enum Phase {
		IDLE("Bereit"), CHECKING("Server wird geprüft"), CONNECTING("Verbindung wird aufgebaut"), LOGIN("Anmeldung läuft"),
		CONFIGURATION("Konfiguration wird geladen"), LOADING_WORLD("Welt wird geladen"), CONNECTED("Verbindung erfolgreich"),
		FAILED("Fehler beim Verbinden"), CANCELLED("Abgebrochen"), DISCONNECTED("Verbindung getrennt");

		public final String label;

		Phase(final String label) {
			this.label = label;
		}

		public boolean active() {
			return this == CHECKING || this == CONNECTING || this == LOGIN || this == CONFIGURATION || this == LOADING_WORLD || this == CONNECTED;
		}
	}

	/** Erzeugt die Sitzung; ersetzbar für Tests. */
	public interface SessionFactory {
		ClientSession open(ServerEntry target, LoginIdentity identity, int viewDistance) throws IOException;
	}

	/** Statusprüfung; ersetzbar für Tests. */
	public interface StatusCheck {
		ServerStatus check(ServerEntry target);
	}

	/** Standard: TCP zur Loopback-Adresse, Sitzungskonfiguration wie der bisherige grafische Client. */
	public static SessionFactory defaultFactory(final PacketLog log) {
		return (target, identity, viewDistance) -> {
			SessionConfig cfg = new SessionConfig(target.host(), target.port(), identity.name(), viewDistance, Duration.ZERO, false,
				"mcresearch-graphical", 9f, Duration.ofSeconds(5), SessionConfig.DEFAULT_READ_TIMEOUT, null);
			InetAddress address = LoopbackGuard.resolveLoopback(target.host());
			return new ClientSession(Connection.open(address, cfg.port(), (int) cfg.connectTimeout().toMillis(), (int) cfg.readTimeout().toMillis(),
				log), cfg, identity);
		};
	}

	private final SessionFactory factory;
	private final StatusCheck statusCheck;
	private volatile Phase basePhase = Phase.IDLE;
	private volatile ClientSession session;
	private volatile CompletableFuture<ClientSession.Result> result;
	private volatile ConnectErrors.UserError error;
	private volatile ServerEntry target;
	private volatile ServerStatus lastStatus = ServerStatus.UNKNOWN;
	private volatile boolean cancelled;
	private volatile boolean userDisconnect;
	private Thread worker;
	private Thread sessionThread;
	private long connects;
	/** Nummer des aktuellen Versuchs: ein abgebrochener, noch laufender Aufbau darf den Zustand eines neuen nicht verändern. */
	private volatile long attempt;

	public ConnectionManager(final SessionFactory factory, final StatusCheck statusCheck) {
		this.factory = factory;
		this.statusCheck = statusCheck;
	}

	/** Startet den Verbindungsaufbau im Hintergrund; nur aus dem Zustand ohne aktive Verbindung. */
	public synchronized void connect(final ServerEntry t, final LoginIdentity identity, final int viewDistance, final boolean checkVersion) {
		if (busy()) {
			throw new IllegalStateException("Verbindung läuft bereits (" + phase() + ")");
		}
		release();
		this.target = t;
		this.error = null;
		this.cancelled = false;
		this.userDisconnect = false;
		this.lastStatus = ServerStatus.UNKNOWN;
		this.connects++;
		final long id = ++this.attempt;
		if (!t.allowed()) {
			fail(new ConnectErrors.UserError(ConnectErrors.Kind.NOT_ALLOWED, "Adresse nicht erlaubt",
				"Diese Entwicklungsversion verbindet sich nur mit localhost bzw. 127.0.0.1 (keine externen Server)."));
			return;
		}
		this.basePhase = checkVersion ? Phase.CHECKING : Phase.CONNECTING;
		this.worker = Thread.ofPlatform().name("connect").daemon(true).start(() -> work(id, t, identity, viewDistance, checkVersion));
	}

	private void work(final long id, final ServerEntry t, final LoginIdentity identity, final int viewDistance, final boolean checkVersion) {
		try {
			if (checkVersion) {
				ServerStatus st = this.statusCheck.check(t);
				if (this.cancelled || id != this.attempt) {
					return;
				}
				this.lastStatus = st;
				if (st.state() != ServerStatus.State.ONLINE) {
					if (st.state() == ServerStatus.State.BLOCKED) {
						fail(new ConnectErrors.UserError(ConnectErrors.Kind.NOT_ALLOWED, "Adresse nicht erlaubt", st.error()));
					} else if (st.error().equals("Zeitüberschreitung")) {
						fail(new ConnectErrors.UserError(ConnectErrors.Kind.TIMEOUT, "Zeitüberschreitung",
							"Unter " + t.address() + " nimmt ein Dienst Verbindungen an, antwortet aber nicht wie ein Minecraft-Server."));
					} else {
						fail(new ConnectErrors.UserError(ConnectErrors.Kind.UNREACHABLE, "Server nicht erreichbar",
							"An " + t.address() + " antwortet kein Minecraft-Server. Läuft der lokale Testserver?"));
					}
					return;
				}
				if (st.support() != ProtocolVersions.Support.NATIVE) {
					fail(new ConnectErrors.UserError(ConnectErrors.Kind.UNSUPPORTED_VERSION, "Nicht unterstützte Serverversion",
						ProtocolVersions.describe(st.protocol(), st.versionName())));
					return;
				}
				this.basePhase = Phase.CONNECTING;
			}
			ClientSession s = this.factory.open(t, identity, viewDistance);
			synchronized (this) {
				if (this.cancelled || id != this.attempt) {
					s.requestStop("cancelled before start");
					if (id == this.attempt) {
						this.basePhase = Phase.CANCELLED;
					}
					return;
				}
				this.session = s;
				this.result = new CompletableFuture<>();
				CompletableFuture<ClientSession.Result> r = this.result;
				this.sessionThread = Thread.ofPlatform().name("session").daemon(true).start(() -> r.complete(s.run()));
			}
		} catch (IOException | RuntimeException e) {
			if (id != this.attempt) {
				return;
			}
			if (!this.cancelled) {
				fail(ConnectErrors.describe(e, null));
			} else {
				this.basePhase = Phase.CANCELLED;
			}
		}
	}

	private void fail(final ConnectErrors.UserError e) {
		this.error = e;
		this.basePhase = Phase.FAILED;
	}

	/** Aktuelle Phase aus dem Sitzungszustand. */
	public Phase phase() {
		ClientSession s = this.session;
		if (s == null) {
			return this.basePhase;
		}
		CompletableFuture<ClientSession.Result> r = this.result;
		if (r != null && r.isDone() || s.ended()) {
			if (this.cancelled) {
				return Phase.CANCELLED;
			}
			ClientSession.Result res = r == null ? null : r.getNow(null);
			if (this.userDisconnect && res != null && res.serverDisconnectReason() == null && res.error() == null) {
				return Phase.DISCONNECTED;
			}
			if (res != null && this.error == null) {
				this.error = ConnectErrors.describe(res.error(), res.serverDisconnectReason());
			}
			return res == null ? Phase.DISCONNECTED : Phase.FAILED;
		}
		if (this.cancelled || s.stopRequested()) {
			return this.cancelled ? Phase.CANCELLED : Phase.DISCONNECTED;
		}
		ProtocolState st = s.state();
		return switch (st) {
			case HANDSHAKE, STATUS -> Phase.CONNECTING;
			case LOGIN -> Phase.LOGIN;
			case CONFIGURATION -> Phase.CONFIGURATION;
			case PLAY -> s.playing() && s.movement().ready() && s.world() != null && s.world().loadedChunks() > 0 ? Phase.CONNECTED
				: Phase.LOADING_WORLD;
		};
	}

	public boolean busy() {
		return phase().active();
	}

	public ClientSession session() {
		return this.session;
	}

	public CompletableFuture<ClientSession.Result> result() {
		return this.result;
	}

	/** Verständliche Fehlermeldung nach {@link Phase#FAILED} (sonst null). */
	public ConnectErrors.UserError error() {
		phase();
		return this.error;
	}

	public ServerEntry target() {
		return this.target;
	}

	public ServerStatus lastStatus() {
		return this.lastStatus;
	}

	public long connects() {
		return this.connects;
	}

	/** Abbrechen während des Aufbaus (oder Trennen, falls schon verbunden). */
	public void cancel() {
		this.cancelled = true;
		ClientSession s = this.session;
		if (s != null) {
			s.requestStop("cancelled by user");
		}
		Thread w = this.worker;
		if (w != null) {
			w.interrupt();
		}
		if (this.session == null) {
			this.basePhase = Phase.CANCELLED;
		}
	}

	/** Trennt die Sitzung kontrolliert (Vanilla: TCP schließen ohne Paket). */
	public void disconnect(final String reason) {
		this.userDisconnect = true;
		ClientSession s = this.session;
		if (s != null) {
			s.requestStop(reason);
		}
	}

	/**
	 * Wartet, bis Aufbau- und Sitzungsthread beendet sind (höchstens {@code timeoutMillis}); true = alles beendet. Danach sind
	 * Netzwerkthreads und Socket freigegeben.
	 */
	public boolean awaitIdle(final long timeoutMillis) {
		long end = System.nanoTime() + timeoutMillis * 1_000_000L;
		try {
			Thread w = this.worker;
			if (w != null) {
				w.join(Math.max(1, (end - System.nanoTime()) / 1_000_000L));
			}
			CompletableFuture<ClientSession.Result> r = this.result;
			if (r != null) {
				r.get(Math.max(1, (end - System.nanoTime()) / 1_000_000L), TimeUnit.MILLISECONDS);
			}
			Thread st = this.sessionThread;
			if (st != null) {
				st.join(Math.max(1, (end - System.nanoTime()) / 1_000_000L));
			}
			return (w == null || !w.isAlive()) && (st == null || !st.isAlive());
		} catch (InterruptedException e) {
			Thread.currentThread().interrupt();
			return false;
		} catch (java.util.concurrent.ExecutionException | TimeoutException e) {
			return false;
		}
	}

	/**
	 * Gibt die beendete Sitzung frei (Welt- und Entity-Daten werden damit unerreichbar). Endzustand und Fehlermeldung bleiben lesbar
	 * (FAILED/CANCELLED/DISCONNECTED), bis {@link #reset()} oder ein neuer Verbindungsversuch sie löscht.
	 */
	public synchronized void release() {
		Phase end = phase();
		ClientSession s = this.session;
		if (s != null && !s.ended()) {
			s.requestStop("released");
		}
		this.session = null;
		this.result = null;
		this.worker = null;
		this.sessionThread = null;
		this.basePhase = end.active() ? Phase.IDLE : end;
	}

	/** Nach Fehler/Abbruch wieder bereit (Fehlermeldung bleibt bis zum nächsten Versuch lesbar). */
	public synchronized void reset() {
		release();
		this.basePhase = Phase.IDLE;
		this.error = null;
	}

	/** Trennen wurde vom Spieler ausgelöst (nicht vom Server). */
	public boolean userDisconnect() {
		return this.userDisconnect;
	}
}
