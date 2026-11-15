package dev.mcresearch.protocolclient.session;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Serverseitiger Zeit- und Wetterzustand der aktuellen Welt (docs/48 §2), wie ihn der Vanilla-Client führt:
 *
 * <ul>
 *   <li>{@code set_time}: Spielzeit und je Weltuhr (Registry {@code minecraft:world_clock}) Gesamtticks, Bruchteil und Rate.
 *       Zwischen den Paketen läuft jede Uhr je Client-Tick um {@code Δ Spielzeit × Rate} weiter ({@code ClientClockManager#tick});
 *       {@code advance_time false} sendet Rate 0.</li>
 *   <li>{@code game_event} 1/2/7/8: Regenstärke (Start → 0, Ende → 1, 7 = Wert) und Gewitterstärke (8); Gewitterwirkung =
 *       Gewitter × Regen ({@code Level#getThunderLevel}).</li>
 * </ul>
 * Rein lesend für die Darstellung; nichts davon wird an den Server zurückgemeldet. Thread-sicher (Netzwerk- und Tick-Thread
 * schreiben, Grafikthread liest).
 */
public final class LevelEnvironment {
	private static final class Clock {
		long total;
		float partial;
		float rate = 1f;
	}

	private long gameTime;
	private long lastTickGameTime;
	private final Map<Integer, Clock> clocks = new HashMap<>();
	private List<String> clockNames = List.of();
	private float rain;
	private float thunder;
	private long timePackets;
	private long ticks;

	synchronized void setClockNames(final List<String> names) {
		this.clockNames = List.copyOf(names);
	}

	/** {@code set_time}: Uhren übernehmen (vorher bis zur neuen Spielzeit weiterzählen, wie {@code handleUpdates}). */
	synchronized void onSetTime(final long gameTime, final Map<Integer, long[]> totals, final Map<Integer, float[]> partialAndRate) {
		advance(gameTime);
		this.gameTime = gameTime;
		for (Map.Entry<Integer, long[]> e : totals.entrySet()) {
			Clock c = this.clocks.computeIfAbsent(e.getKey(), k -> new Clock());
			c.total = e.getValue()[0];
			float[] pr = partialAndRate.get(e.getKey());
			c.partial = pr[0];
			c.rate = pr[1];
		}
		this.timePackets++;
	}

	/** Client-Tick: Spielzeit +1, Uhren weiterzählen ({@code ClientLevel#tickTime} + {@code ClientClockManager#tick}). */
	synchronized void tick() {
		this.gameTime++;
		advance(this.gameTime);
		this.ticks++;
	}

	private void advance(final long newGameTime) {
		long delta = newGameTime - this.lastTickGameTime;
		this.lastTickGameTime = newGameTime;
		if (delta == 0) {
			return;
		}
		for (Clock c : this.clocks.values()) {
			double p = c.partial + (double) delta * c.rate;
			long full = (long) Math.floor(p);
			c.partial = (float) (p - full);
			c.total += full;
		}
	}

	synchronized void onGameEvent(final int event, final float param) {
		switch (event) {
			case 1 -> this.rain = 0f;
			case 2 -> this.rain = 1f;
			case 7 -> this.rain = Math.max(0f, Math.min(1f, param));
			case 8 -> this.thunder = Math.max(0f, Math.min(1f, param));
			default -> {
			}
		}
	}

	/** Neue Welt (Login/Dimensionswechsel): Wetter zurück; die Uhren bleiben (sie gehören zum Server, nicht zur Welt). */
	synchronized void onNewLevel() {
		this.rain = 0f;
		this.thunder = 0f;
	}

	/** Uhrname → Gesamtticks (für Zeitleisten). */
	public synchronized Map<String, Long> clockTicks() {
		Map<String, Long> m = new HashMap<>();
		for (Map.Entry<Integer, Clock> e : this.clocks.entrySet()) {
			String name = e.getKey() < this.clockNames.size() ? this.clockNames.get(e.getKey()) : "#" + e.getKey();
			m.put(name, e.getValue().total);
		}
		return m;
	}

	public synchronized long gameTime() {
		return this.gameTime;
	}

	public synchronized float rainLevel() {
		return this.rain;
	}

	/** {@code Level#getThunderLevel}: Gewitterstärke × Regenstärke. */
	public synchronized float thunderLevel() {
		return this.thunder * this.rain;
	}

	public synchronized long timePackets() {
		return this.timePackets;
	}

	public synchronized long ticks() {
		return this.ticks;
	}

	public synchronized String describe() {
		StringBuilder sb = new StringBuilder("gameTime=" + this.gameTime);
		clockTicks().forEach((k, v) -> sb.append(' ').append(k).append('=').append(v));
		return sb.append(String.format(java.util.Locale.ROOT, " rain=%.3f thunder=%.3f", this.rain, this.thunder * this.rain)).toString();
	}
}
