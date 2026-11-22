package dev.mcresearch.protocolclient.input;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import dev.mcresearch.protocolclient.physics.PlayerPhysics;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.junit.jupiter.api.Test;

/** M15 Phase F: Merlin Input System – Belegung, Konflikte, Bereiche, analoge Werte, weitere Quellen (docs/60 §4–§5). */
class InputSystemTest {
	/** Testquelle: gehaltene Codes, Druckzähler, analoge Werte je Bild. */
	static final class FakeSource implements InputSource {
		final Set<InputCode> down = new HashSet<>();
		final Map<InputCode, Integer> presses = new HashMap<>();
		final Map<InputCode, Double> analog = new HashMap<>();
		final Set<InputCode.Device> devices;
		int polls;

		FakeSource(final InputCode.Device... devices) {
			this.devices = Set.of(devices);
		}

		@Override
		public String id() {
			return "fake";
		}

		@Override
		public boolean handles(final InputCode.Device d) {
			return this.devices.contains(d);
		}

		@Override
		public void poll() {
			this.polls++;
		}

		@Override
		public boolean down(final InputCode c) {
			return this.down.contains(c);
		}

		@Override
		public int presses(final InputCode c) {
			return this.presses.getOrDefault(c, 0);
		}

		@Override
		public double analog(final InputCode c) {
			return this.analog.getOrDefault(c, 0.0);
		}

		void tap(final InputCode c) {
			this.presses.merge(c, 1, Integer::sum);
		}

		void nextFrame() {
			this.presses.clear();
			this.analog.clear();
		}
	}

	private static FakeSource keyboardMouse() {
		return new FakeSource(InputCode.Device.KEYBOARD, InputCode.Device.MOUSE_BUTTON, InputCode.Device.MOUSE_WHEEL, InputCode.Device.MOUSE_AXIS);
	}

	@Test
	void defaultBindingsMatchPreviousHardcodedKeysAndHaveNoConflicts() {
		KeyBindings b = new KeyBindings();
		assertEquals(InputCode.key(Keys.W), b.get(GameAction.MOVE_FORWARD));
		assertEquals(InputCode.key(Keys.LEFT_SHIFT), b.get(GameAction.SNEAK));
		assertEquals(InputCode.key(Keys.LEFT_CONTROL), b.get(GameAction.SPRINT));
		assertEquals(InputCode.mouse(0), b.get(GameAction.ATTACK));
		assertEquals(InputCode.key(Keys.E), b.get(GameAction.INVENTORY));
		assertEquals(InputCode.key(Keys.DIGIT_1 + 8), b.get(GameAction.HOTBAR_9));
		assertTrue(b.conflicts().isEmpty(), b.conflicts().toString());
	}

	@Test
	void conflictsAreDetectedAndExplained() {
		KeyBindings b = new KeyBindings();
		assertEquals(KeyBindings.Change.OK, b.set(GameAction.JUMP, InputCode.key(Keys.W)));
		Map<InputCode, List<GameAction>> c = b.conflicts();
		assertEquals(List.of(GameAction.MOVE_FORWARD, GameAction.JUMP), c.get(InputCode.key(Keys.W)));
		assertEquals(List.of(GameAction.MOVE_FORWARD), b.conflictsOf(GameAction.JUMP));
		// Menütasten (Kontext GUI) kollidieren nicht mit Spielaktionen: Pfeil hoch im Spiel ist erlaubt
		b.reset(GameAction.JUMP);
		assertEquals(KeyBindings.Change.OK, b.set(GameAction.MOVE_FORWARD, InputCode.key(Keys.UP)));
		assertTrue(b.conflicts().isEmpty(), b.conflicts().toString());
		// nicht belegt erzeugt keinen Konflikt
		b.set(GameAction.DROP_ITEM, InputCode.NONE);
		b.set(GameAction.SWAP_OFFHAND, InputCode.NONE);
		assertTrue(b.conflicts().isEmpty());
	}

	@Test
	void reservedAndFixedActionsCannotBeRebound() {
		KeyBindings b = new KeyBindings();
		assertEquals(KeyBindings.Change.RESERVED, b.set(GameAction.JUMP, InputCode.key(Keys.ESCAPE)));
		assertEquals(KeyBindings.Change.NOT_REBINDABLE, b.set(GameAction.PAUSE, InputCode.key('P')));
		assertEquals(KeyBindings.Change.NOT_REBINDABLE, b.set(GameAction.LOOK_HORIZONTAL, InputCode.key(Keys.A)));
		assertEquals(KeyBindings.Change.UNSUPPORTED_DEVICE, b.set(GameAction.JUMP, InputCode.axis(0)));
		assertEquals(GameAction.JUMP.defaultCode, b.get(GameAction.JUMP));
	}

	@Test
	void inputCodeSerializationRoundTrip() {
		for (InputCode c : List.of(InputCode.key(Keys.SPACE), InputCode.mouse(4), InputCode.wheel(InputCode.WHEEL_UP), InputCode.wheel(1),
			InputCode.axis(1), InputCode.NONE, new InputCode(InputCode.Device.GAMEPAD_BUTTON, 3))) {
			assertEquals(c, InputCode.parse(c.serialize()), c.serialize());
		}
		assertThrows(IllegalArgumentException.class, () -> InputCode.parse("key:5"));
		assertThrows(IllegalArgumentException.class, () -> InputCode.parse("joystick:1"));
		assertThrows(IllegalArgumentException.class, () -> InputCode.parse("87"));
		assertEquals("Leertaste", InputCode.key(Keys.SPACE).displayName());
		assertEquals("Mausrad hoch", InputCode.wheel(0).displayName());
	}

	@Test
	void movementGoesThroughBindingsAndIsBlockedOutsideWorld() {
		InputManager im = new InputManager();
		FakeSource src = keyboardMouse();
		im.register(src);
		src.down.add(InputCode.key(Keys.W));
		src.down.add(InputCode.key(Keys.SPACE));
		src.down.add(InputCode.key(Keys.LEFT_CONTROL));
		im.update(InputManager.Scope.WORLD);
		assertEquals(new PlayerPhysics.Input(true, false, false, false, true, false, true), im.movement());
		// Menü offen: keine Bewegung, keine Angriffe (Spielwelteingaben gesperrt)
		src.tap(InputCode.mouse(0));
		src.down.add(InputCode.mouse(0));
		im.update(InputManager.Scope.WORLD_SCREEN);
		assertEquals(PlayerPhysics.Input.NONE, im.movement());
		assertEquals(0, im.presses(GameAction.ATTACK));
		assertFalse(im.down(GameAction.ATTACK));
		// globale Aktionen (Pause, F3) gelten im Spiel weiter
		src.tap(InputCode.key(Keys.F3));
		im.update(InputManager.Scope.WORLD_SCREEN);
		assertEquals(1, im.presses(GameAction.TOGGLE_DEBUG));
		im.update(InputManager.Scope.MENU);
		assertEquals(0, im.presses(GameAction.TOGGLE_DEBUG), "no world: debug toggle inactive");
		// umbelegt: Springen auf J
		im.bindings().set(GameAction.JUMP, InputCode.key('J'));
		src.nextFrame();
		im.update(InputManager.Scope.WORLD);
		assertFalse(im.movement().jump(), "space no longer jumps");
		src.down.add(InputCode.key('J'));
		im.update(InputManager.Scope.WORLD);
		assertTrue(im.movement().jump());
	}

	@Test
	void togglesForSprintAndSneakAreReleasedWhenAScreenOpens() {
		InputManager im = new InputManager();
		FakeSource src = keyboardMouse();
		im.register(src);
		im.configure(0.5, false, true, true);
		src.tap(InputCode.key(Keys.LEFT_CONTROL));
		im.update(InputManager.Scope.WORLD);
		assertTrue(im.movement().sprint());
		src.nextFrame();
		im.update(InputManager.Scope.WORLD);
		assertTrue(im.movement().sprint(), "latched");
		im.update(InputManager.Scope.WORLD_SCREEN);
		im.update(InputManager.Scope.WORLD);
		assertFalse(im.movement().sprint(), "releaseAll on screen open");
		src.tap(InputCode.key(Keys.LEFT_SHIFT));
		im.update(InputManager.Scope.WORLD);
		assertTrue(im.movement().sneak());
		src.nextFrame();
		src.tap(InputCode.key(Keys.LEFT_SHIFT));
		im.update(InputManager.Scope.WORLD);
		assertFalse(im.movement().sneak(), "second press toggles off");
	}

	@Test
	void mouseLookUsesVanillaSensitivityAndInversion() {
		assertEquals(0.15, InputManager.degreesPerPixel(0.5), 1e-12, "100 % = previous constant 0.15°/px");
		assertEquals(0.2 * 0.2 * 0.2 * 8 * 0.15, InputManager.degreesPerPixel(0), 1e-12);
		InputManager im = new InputManager();
		FakeSource src = keyboardMouse();
		im.register(src);
		src.analog.put(InputCode.axis(0), 10.0);
		src.analog.put(InputCode.axis(1), -4.0);
		im.update(InputManager.Scope.WORLD);
		assertArrayEquals(new float[]{10f * 0.15f, -4f * 0.15f}, im.lookDelta(), 1e-6f);
		im.configure(0.5, true, false, false);
		im.update(InputManager.Scope.WORLD);
		assertArrayEquals(new float[]{10f * 0.15f, 4f * 0.15f}, im.lookDelta(), 1e-6f);
		im.update(InputManager.Scope.WORLD_SCREEN);
		assertArrayEquals(new float[]{0f, 0f}, im.lookDelta(), "no camera movement while a menu is open");
	}

	@Test
	void hotbarFromWheelAndKeys() {
		InputManager im = new InputManager();
		FakeSource src = keyboardMouse();
		im.register(src);
		src.analog.put(InputCode.wheel(InputCode.WHEEL_UP), 2.0);
		im.update(InputManager.Scope.WORLD);
		assertEquals(2, im.hotbarSteps(), "wheel up = previous slot (positive)");
		src.nextFrame();
		src.analog.put(InputCode.wheel(InputCode.WHEEL_DOWN), 0.4);
		im.update(InputManager.Scope.WORLD);
		assertEquals(-1, im.hotbarSteps(), "fractional touchpad scroll rounds up like ScrollWheelHandler");
		src.nextFrame();
		src.tap(InputCode.key(Keys.DIGIT_1 + 4));
		im.update(InputManager.Scope.WORLD);
		assertEquals(4, im.hotbarSlotPressed());
		im.bindings().set(GameAction.HOTBAR_NEXT, InputCode.key('X'));
		src.nextFrame();
		src.tap(InputCode.key('X'));
		im.update(InputManager.Scope.WORLD);
		assertEquals(-1, im.hotbarSteps());
	}

	@Test
	void additionalSourceCanBeRegisteredForFutureControllers() {
		InputManager im = new InputManager();
		FakeSource km = keyboardMouse();
		FakeSource pad = new FakeSource(InputCode.Device.GAMEPAD_BUTTON, InputCode.Device.GAMEPAD_AXIS);
		im.register(km);
		im.register(pad);
		InputCode a = new InputCode(InputCode.Device.GAMEPAD_BUTTON, 0);
		im.bindings().set(GameAction.JUMP, a);
		pad.down.add(a);
		im.update(InputManager.Scope.WORLD);
		assertTrue(im.movement().jump(), "gamepad button through the same action");
		assertEquals(1, pad.polls);
		assertEquals(2, im.sources().size());
		im.unregister(pad);
		im.update(InputManager.Scope.WORLD);
		assertFalse(im.movement().jump());
	}
}
