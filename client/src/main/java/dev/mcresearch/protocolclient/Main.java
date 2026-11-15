package dev.mcresearch.protocolclient;

import dev.mcresearch.protocolclient.net.Connection;
import dev.mcresearch.protocolclient.net.LoopbackGuard;
import dev.mcresearch.protocolclient.net.PacketLog;
import dev.mcresearch.protocolclient.session.ClientSession;
import dev.mcresearch.protocolclient.session.SessionConfig;
import dev.mcresearch.protocolclient.session.StatusQuery;
import java.io.IOException;
import java.net.InetAddress;
import java.nio.file.Path;
import java.time.Duration;
import java.util.Locale;
import java.util.logging.ConsoleHandler;
import java.util.logging.Level;
import java.util.logging.Logger;
import java.util.logging.SimpleFormatter;

/**
 * Eigenständiger Minecraft-Java-26.3-Protokollclient (Protokoll 777).
 * Verbindet sich ausschließlich mit localhost; siehe docs/13 für Architektur und Startanleitung.
 *
 * <pre>
 * Optionen:
 *   --host &lt;localhost|127.0.0.1|::1&gt;   (Standard 127.0.0.1)
 *   --port &lt;n&gt;                          (Standard 25565)
 *   --username &lt;name&gt;                   (Standard ProtoBot, [A-Za-z0-9_]{3,16})
 *   --duration &lt;sekunden&gt;              Zeit im Play-Zustand (Standard 70)
 *   --view-distance &lt;2..32&gt;            (Standard 2)
 *   --known-packs accept|decline        (Standard decline: Registry-Inhalte für Bodenhöhen; accept = Vanilla-Verhalten)
 *   --interactive                       Terminal-Bewegungstest (stdin); Dauer unbegrenzt bis quit, falls --duration fehlt
 *   --packet-log &lt;datei&gt;               Paketlog im Vanilla-Format (Standard logs/packets.log)
 *   --status-only | --no-status
 *   --verbose
 *   --no-auto-respawn                   M10: nach dem Tod nicht selbst wiederbeleben (Befehl respawn); Standard: automatisch (Headless)
 *   --sessions &lt;n&gt;                      M13: n Verbindungen nacheinander mit derselben Identität (Neuverbindung)
 *   --auth offline|simulation|microsoft M13 (docs/52 §5): Standard offline (bisheriger Testmodus)
 *   --sim-discovery &lt;http://127.0.0.1:port/…&gt;  lokale Simulation der Minecraft-Services (Testdaten)
 *   --sim-uuid &lt;uuid&gt;
 *   --client-id &lt;guid&gt;                  eigene Microsoft-App-Registrierung (öffentlicher Client)
 *   --allow-external-auth               ausdrückliche Freigabe der offiziellen Anmeldeendpunkte (docs/50 §6)
 *   --login-method browser|device-code
 * </pre>
 * Exit-Code: 0 Erfolg, 1 Fehlschlag, 2 ungültige Argumente.
 */
public final class Main {
	private Main() {
	}

	public static void main(final String[] args) {
		System.exit(run(args));
	}

	static int run(final String[] args) {
		SessionConfig defaults = SessionConfig.defaults();
		String host = defaults.host();
		int port = defaults.port();
		String username = defaults.username();
		int duration = (int) defaults.playDuration().toSeconds();
		int viewDistance = defaults.viewDistance();
		boolean acceptPacks = defaults.acceptKnownPacks();
		Path packetLog = Path.of("logs", "packets.log");
		boolean statusOnly = false;
		boolean status = true;
		boolean verbose = false;
		boolean interactive = false;
		boolean durationGiven = false;
		boolean autoRespawn = true;
		int sessions = 1;
		var auth = new dev.mcresearch.protocolclient.auth.minecraft.LoginFactory.Parser();
		try {
			for (int i = 0; i < args.length; i++) {
				int consumed = auth.parse(args, i);
				if (consumed >= 0) {
					i = consumed;
					continue;
				}
				switch (args[i]) {
					case "--sessions" -> sessions = Integer.parseInt(args[++i]);
					case "--host" -> host = args[++i];
					case "--port" -> port = Integer.parseInt(args[++i]);
					case "--username" -> username = args[++i];
					case "--duration" -> {
						duration = Integer.parseInt(args[++i]);
						durationGiven = true;
					}
					case "--view-distance" -> viewDistance = Integer.parseInt(args[++i]);
					case "--known-packs" -> {
						String mode = args[++i].toLowerCase(Locale.ROOT);
						if (!mode.equals("accept") && !mode.equals("decline")) {
							throw new IllegalArgumentException("--known-packs accept|decline");
						}
						acceptPacks = mode.equals("accept");
					}
					case "--packet-log" -> packetLog = Path.of(args[++i]);
					case "--status-only" -> statusOnly = true;
					case "--no-status" -> status = false;
					case "--verbose" -> verbose = true;
					case "--interactive" -> interactive = true;
					case "--no-auto-respawn" -> autoRespawn = false;
					case "--help", "-h" -> {
						System.out.println("See docs/13-eigener-client-architektur.md for options.");
						return 0;
					}
					default -> throw new IllegalArgumentException("Unknown option " + args[i]);
				}
			}
		} catch (RuntimeException e) {
			System.err.println("Invalid arguments: " + e.getMessage());
			return 2;
		}
		if (interactive && !durationGiven) {
			duration = 0;
		}
		configureLogging(verbose);
		Logger log = Logger.getLogger("mcclient");

		SessionConfig config;
		InetAddress address;
		try {
			config = new SessionConfig(host, port, username, viewDistance, Duration.ofSeconds(duration), acceptPacks, defaults.brand(),
				defaults.desiredChunksPerTick(), defaults.connectTimeout(), defaults.readTimeout(), packetLog);
			address = LoopbackGuard.resolveLoopback(host);
		} catch (IOException | IllegalArgumentException e) {
			System.err.println("Invalid configuration: " + e.getMessage());
			return 2;
		}

		try (PacketLog packets = PacketLog.open(packetLog)) {
			if (status || statusOnly) {
				StatusQuery.Result s = StatusQuery.query(open(address, config, packets), host, port);
				log.info(String.format("STATUS ok: version=%s protocol=%d (%s) players=%d/%d motd=\"%s\" ping=%d ms",
					s.status().versionName(), s.status().protocol(), s.protocolMatches() ? "matches 777" : "MISMATCH",
					s.status().onlinePlayers(), s.status().maxPlayers(), s.status().description(), s.pingMillis()));
				if (!s.protocolMatches()) {
					return 1;
				}
				if (statusOnly) {
					return 0;
				}
			}
			// M13: Identität einmal anlegen (Anmeldung), danach pro Verbindung ein neuer Session-Nachweis (docs/52 §5)
			dev.mcresearch.protocolclient.auth.LoginIdentity identity;
			try {
				identity = dev.mcresearch.protocolclient.auth.minecraft.LoginFactory.create(auth.options(username), log::info,
					new dev.mcresearch.protocolclient.auth.AuthCancellation());
			} catch (dev.mcresearch.protocolclient.auth.AuthException e) {
				log.severe("LOGIN FAILED " + e.error() + ": " + e.userMessage());
				return 1;
			}
			int exit = 0;
			for (int n = 1; n <= sessions; n++) {
				if (sessions > 1) {
					log.info("=== session " + n + "/" + sessions);
				}
				ClientSession session = new ClientSession(open(address, config, packets), config, identity);
				session.setAutoRespawn(autoRespawn);
				ClientSession.Result r = interactive ? runInteractive(session) : session.run();
				exit = Math.max(exit, report(log, session, r));
			}
			return exit;
		} catch (IOException | RuntimeException e) {
			log.log(Level.SEVERE, "Client failed", e);
			return 1;
		}
	}

	private static int report(final Logger log, final ClientSession session, final ClientSession.Result r) {
		{
			var profile = session.loginProfile();
			log.info("LOGIN identity=" + session.identity().kind() + " name=" + session.identity().name() + " uuid=" + session.identity().profileId()
				+ " encrypted=" + session.encrypted() + " trace=[" + session.loginTrace() + "] serverProfile="
				+ (profile == null ? "none" : profile.name() + " " + profile.id()));
			log.info("RESULT " + (r.success() ? "SUCCESS" : "FAILURE") + ": " + r.outcome());
			log.info(String.format("reachedPlay=%s timeInPlay=%.1f s entityId=%d serverBrand=%s position=%s",
				r.reachedPlay(), r.timeInPlay().toMillis() / 1000.0, r.entityId(), r.serverBrand(), r.position()));
			log.info(String.format("received=%d sent=%d keepAlives=%d (max gap %d ms) teleports=%d chunkBatches=%d chunks=%d ticks=%d "
					+ "registries=%d entries=%d (with data %d)",
				r.stats().receivedTotal(), r.stats().sentTotal(), r.stats().keepAlivesAnswered(), r.stats().maxKeepAliveGapMillis(),
				r.stats().teleportsAccepted(), r.stats().chunkBatchesAcknowledged(), r.stats().chunksAnnounced(), r.stats().ticksSent(),
				r.stats().registries(), r.stats().registryEntries(), r.stats().registryEntriesWithData()));
			log.info("transport: " + r.transport());
			log.info("sent by type: " + r.stats().sentByName());
			return r.success() ? 0 : 1;
		}
	}

	/** Session im Hintergrund, Terminalbefehle im aufrufenden Thread (docs/15 §7). */
	private static ClientSession.Result runInteractive(final ClientSession session) throws IOException {
		java.util.concurrent.CompletableFuture<ClientSession.Result> result = new java.util.concurrent.CompletableFuture<>();
		Thread worker = Thread.ofPlatform().name("session").start(() -> result.complete(session.run()));
		try {
			new dev.mcresearch.protocolclient.cli.TerminalController(session,
				new java.io.BufferedReader(new java.io.InputStreamReader(System.in, java.nio.charset.StandardCharsets.UTF_8)), System.out).run();
			session.requestStop("terminal finished");
			worker.join(10_000);
			return result.getNow(null) != null ? result.join() : result.get(5, java.util.concurrent.TimeUnit.SECONDS);
		} catch (InterruptedException e) {
			Thread.currentThread().interrupt();
			session.requestStop("interrupted");
			throw new IOException("interrupted", e);
		} catch (java.util.concurrent.ExecutionException | java.util.concurrent.TimeoutException e) {
			throw new IOException("session did not finish", e);
		}
	}

	private static Connection open(final InetAddress address, final SessionConfig config, final PacketLog log) throws IOException {
		return Connection.open(address, config.port(), (int) config.connectTimeout().toMillis(), (int) config.readTimeout().toMillis(), log);
	}

	private static void configureLogging(final boolean verbose) {
		System.setProperty("java.util.logging.SimpleFormatter.format", "[%1$tT] [%4$s] %3$s: %5$s%6$s%n");
		Logger root = Logger.getLogger("");
		for (var h : root.getHandlers()) {
			root.removeHandler(h);
		}
		ConsoleHandler handler = new ConsoleHandler();
		handler.setFormatter(new SimpleFormatter());
		handler.setLevel(verbose ? Level.FINE : Level.INFO);
		root.addHandler(handler);
		root.setLevel(verbose ? Level.FINE : Level.INFO);
	}
}
