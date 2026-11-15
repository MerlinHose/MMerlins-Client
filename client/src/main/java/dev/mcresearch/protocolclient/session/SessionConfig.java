package dev.mcresearch.protocolclient.session;

import java.nio.file.Path;
import java.time.Duration;

/**
 * Laufzeitkonfiguration.
 *
 * @param playDuration     Zeit im Play-Zustand bis zum kontrollierten Disconnect; {@link Duration#ZERO} = unbegrenzt (Terminalmodus bis {@code quit})
 * @param acceptKnownPacks true = dem Server die angebotenen Known Packs bestätigen (Vanilla-Verhalten, Registry-Inhalte entfallen);
 *                         false = leere Liste senden, der Server überträgt alle Registry-Inhalte als NBT
 */
public record SessionConfig(
	String host,
	int port,
	String username,
	int viewDistance,
	Duration playDuration,
	boolean acceptKnownPacks,
	String brand,
	float desiredChunksPerTick,
	Duration connectTimeout,
	Duration readTimeout,
	Path packetLog
) {
	public static final Duration DEFAULT_READ_TIMEOUT = Duration.ofSeconds(30);

	public SessionConfig {
		if (port < 1 || port > 65535) {
			throw new IllegalArgumentException("port " + port);
		}
		if (!username.matches("[A-Za-z0-9_]{3,16}")) {
			throw new IllegalArgumentException("username must match [A-Za-z0-9_]{3,16}");
		}
		if (viewDistance < 2 || viewDistance > 32) {
			throw new IllegalArgumentException("viewDistance must be 2..32");
		}
		if (playDuration.isNegative() || playDuration.compareTo(Duration.ofHours(24)) > 0) {
			throw new IllegalArgumentException("playDuration out of range");
		}
		if (!(desiredChunksPerTick >= 0.01f && desiredChunksPerTick <= 64f)) {
			throw new IllegalArgumentException("desiredChunksPerTick must be 0.01..64");
		}
	}

	public static SessionConfig defaults() {
		// Known Packs ablehnen: nur so liefert der Server die Inhalte von minecraft:dimension_type (min_y/height),
		// die für die Bodenhöhen der Bewegung (docs/15 §4) nötig sind. accept bleibt per CLI wählbar.
		return new SessionConfig("127.0.0.1", 25565, "ProtoBot", 2, Duration.ofSeconds(70), false, "mcresearch-protocol-client",
			9.0f, Duration.ofSeconds(5), DEFAULT_READ_TIMEOUT, null);
	}
}
