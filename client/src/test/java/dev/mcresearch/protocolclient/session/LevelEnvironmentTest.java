package dev.mcresearch.protocolclient.session;

import static org.junit.jupiter.api.Assertions.assertEquals;

import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

/** M12 (docs/48 §2): Weltuhren aus {@code set_time} mit Weiterzählen je Tick, Wetter aus {@code game_event}. */
class LevelEnvironmentTest {
	@Test
	void clocksAdvanceByRateBetweenTimePackets() {
		LevelEnvironment e = new LevelEnvironment();
		e.setClockNames(List.of("minecraft:overworld", "minecraft:the_end"));
		e.onSetTime(1000, Map.of(0, new long[]{6000}, 1, new long[]{50}), Map.of(0, new float[]{0f, 1f}, 1, new float[]{0f, 0f}));
		for (int i = 0; i < 20; i++) {
			e.tick();
		}
		assertEquals(6020L, e.clockTicks().get("minecraft:overworld"), "rate 1: one tick per client tick");
		assertEquals(50L, e.clockTicks().get("minecraft:the_end"), "rate 0 (advance_time false): frozen");
		assertEquals(1020L, e.gameTime());
		// neues Paket: Spielzeit springt, Uhr wird gesetzt
		e.onSetTime(1040, Map.of(0, new long[]{12000}), Map.of(0, new float[]{0.5f, 0.5f}));
		e.tick();
		e.tick();
		assertEquals(12001L, e.clockTicks().get("minecraft:overworld"), "rate 0.5 with partial 0.5: two ticks add one");
	}

	@Test
	void weatherEventsSetRainAndThunder() {
		LevelEnvironment e = new LevelEnvironment();
		e.onGameEvent(1, 0f);
		e.onGameEvent(7, 0.6f);
		e.onGameEvent(8, 0.5f);
		assertEquals(0.6f, e.rainLevel(), 1e-6);
		assertEquals(0.3f, e.thunderLevel(), 1e-6, "thunder × rain");
		e.onGameEvent(2, 0f);
		assertEquals(1f, e.rainLevel(), 1e-6, "STOP_RAINING sets 1 like the vanilla handler; the server then sends level changes");
		e.onNewLevel();
		assertEquals(0f, e.rainLevel(), 1e-6);
	}
}
