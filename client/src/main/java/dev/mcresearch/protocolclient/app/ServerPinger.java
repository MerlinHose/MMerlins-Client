package dev.mcresearch.protocolclient.app;

import dev.mcresearch.protocolclient.net.Connection;
import dev.mcresearch.protocolclient.net.LoopbackGuard;
import dev.mcresearch.protocolclient.session.StatusQuery;
import java.net.InetAddress;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

/**
 * Statusping im Hintergrund (docs/61 §6) über die vorhandene {@link StatusQuery}. Nur Loopback-Adressen; externe Einträge werden
 * ohne Netzwerkzugriff als {@link ServerStatus#BLOCKED} gemeldet. Ein Daemon-Thread, beim Beenden geschlossen.
 */
public final class ServerPinger implements AutoCloseable {
	private final ExecutorService pool = Executors.newSingleThreadExecutor(r -> {
		Thread t = new Thread(r, "server-ping");
		t.setDaemon(true);
		return t;
	});

	public CompletableFuture<ServerStatus> ping(final ServerEntry e) {
		if (!e.allowed()) {
			return CompletableFuture.completedFuture(ServerStatus.BLOCKED);
		}
		return CompletableFuture.supplyAsync(() -> pingNow(e), this.pool);
	}

	/** Blockierender Ping (Hintergrundthread). */
	public static ServerStatus pingNow(final ServerEntry e) {
		if (!e.allowed()) {
			return ServerStatus.BLOCKED;
		}
		try {
			InetAddress a = LoopbackGuard.resolveLoopback(e.host());
			StatusQuery.Result r = StatusQuery.query(Connection.open(a, e.port(), 2000, 3000, dev.mcresearch.protocolclient.net.PacketLog.disabled()), e.host(), e.port());
			return new ServerStatus(ServerStatus.State.ONLINE, r.pingMillis(), r.status().versionName(), r.status().protocol(),
				r.status().onlinePlayers(), r.status().maxPlayers(), r.status().description() == null ? "" : r.status().description(), "");
		} catch (Exception ex) {
			return ServerStatus.offline(ConnectErrors.describe(ex, null).title());
		}
	}

	@Override
	public void close() {
		this.pool.shutdownNow();
		try {
			this.pool.awaitTermination(2, TimeUnit.SECONDS);
		} catch (InterruptedException ie) {
			Thread.currentThread().interrupt();
		}
	}
}
