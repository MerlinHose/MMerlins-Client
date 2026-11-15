package dev.mcresearch.protocolclient.session;

import java.util.Map;
import java.util.TreeMap;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;

/** Zähler für die Auswertung eines Laufs (docs/14). Thread-sicher. */
public final class SessionStats {
	private final Map<String, AtomicInteger> received = new ConcurrentHashMap<>();
	private final Map<String, AtomicInteger> sent = new ConcurrentHashMap<>();
	final AtomicInteger keepAlivesAnswered = new AtomicInteger();
	final AtomicInteger teleportsAccepted = new AtomicInteger();
	final AtomicInteger chunkBatchesAcknowledged = new AtomicInteger();
	final AtomicInteger chunksAnnounced = new AtomicInteger();
	final AtomicInteger ticksSent = new AtomicInteger();
	final AtomicInteger blockAcks = new AtomicInteger();
	final AtomicInteger registries = new AtomicInteger();
	final AtomicInteger registryEntries = new AtomicInteger();
	final AtomicInteger registryEntriesWithData = new AtomicInteger();
	final AtomicInteger bundles = new AtomicInteger();
	final AtomicInteger respawnsRequested = new AtomicInteger();
	final AtomicLong maxKeepAliveGapMillis = new AtomicLong();

	void received(final String name) {
		this.received.computeIfAbsent(name, n -> new AtomicInteger()).incrementAndGet();
	}

	void sent(final String name) {
		this.sent.computeIfAbsent(name, n -> new AtomicInteger()).incrementAndGet();
	}

	public int receivedCount(final String name) {
		AtomicInteger c = this.received.get(name);
		return c == null ? 0 : c.get();
	}

	public int sentCount(final String name) {
		AtomicInteger c = this.sent.get(name);
		return c == null ? 0 : c.get();
	}

	public int receivedTotal() {
		return this.received.values().stream().mapToInt(AtomicInteger::get).sum();
	}

	public int sentTotal() {
		return this.sent.values().stream().mapToInt(AtomicInteger::get).sum();
	}

	/** Automatische Wiederbelebungen (Spieler war tot, z. B. aus einem früheren Lauf). */
	public int respawnsRequested() {
		return this.respawnsRequested.get();
	}

	public int keepAlivesAnswered() {
		return this.keepAlivesAnswered.get();
	}

	public int teleportsAccepted() {
		return this.teleportsAccepted.get();
	}

	public int chunkBatchesAcknowledged() {
		return this.chunkBatchesAcknowledged.get();
	}

	public int chunksAnnounced() {
		return this.chunksAnnounced.get();
	}

	public int blockAcks() {
		return this.blockAcks.get();
	}

	public int ticksSent() {
		return this.ticksSent.get();
	}

	public int registries() {
		return this.registries.get();
	}

	public int registryEntries() {
		return this.registryEntries.get();
	}

	public int registryEntriesWithData() {
		return this.registryEntriesWithData.get();
	}

	public long maxKeepAliveGapMillis() {
		return this.maxKeepAliveGapMillis.get();
	}

	public Map<String, Integer> receivedByName() {
		Map<String, Integer> m = new TreeMap<>();
		this.received.forEach((k, v) -> m.put(k, v.get()));
		return m;
	}

	public Map<String, Integer> sentByName() {
		Map<String, Integer> m = new TreeMap<>();
		this.sent.forEach((k, v) -> m.put(k, v.get()));
		return m;
	}
}
