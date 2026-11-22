package dev.mcresearch.protocolclient.input;

import dev.mcresearch.protocolclient.physics.PlayerPhysics;
import java.util.ArrayList;
import java.util.EnumMap;
import java.util.List;

/**
 * Merlin Input System (docs/60 §4): fragt je Bild alle registrierten Quellen ab und bildet über die Belegung den Zustand jeder
 * {@link GameAction}. Die Spielmechanik sieht nur Aktionen; Gerätecodes kennt nur diese Klasse.
 *
 * <p>Bereiche: Bei offenem Menü ({@link Scope#WORLD_SCREEN}, {@link Scope#MENU}) sind alle Spielweltaktionen losgelassen und
 * Umschaltzustände (Sprint/Schleichen umschalten) gelöscht – wie {@code KeyMapping#releaseAll} beim Öffnen eines Screens.
 * Menünavigation (GUI_*) erhält der {@code ScreenManager} aus Tastenereignissen; eine Controllerquelle kann sie später über
 * dieselben Aktionen liefern.
 */
public final class InputManager {
	/** Wo die Eingabe gerade hingehört. */
	public enum Scope {
		/** Spielwelt ohne offenen Screen. */
		WORLD,
		/** Spielwelt läuft, ein Screen (Pause, Inventar, Tod, Einstellungen) ist offen. */
		WORLD_SCREEN,
		/** Keine Spielwelt (Hauptmenü). */
		MENU
	}

	/** Zustand einer Aktion im aktuellen Bild. */
	public record ActionState(boolean down, int presses, double value) {
		static final ActionState IDLE = new ActionState(false, 0, 0);
	}

	private final List<InputSource> sources = new ArrayList<>();
	private KeyBindings bindings = new KeyBindings();
	private final EnumMap<GameAction, ActionState> states = new EnumMap<>(GameAction.class);
	private double sensitivity = 0.5;
	private boolean invertY;
	private boolean toggleSprint;
	private boolean toggleSneak;
	private boolean sprintLatched;
	private boolean sneakLatched;
	private Scope scope = Scope.MENU;

	public InputManager() {
		for (GameAction a : GameAction.values()) {
			this.states.put(a, ActionState.IDLE);
		}
	}

	/** Weitere Eingabequelle (z. B. später input4j); Reihenfolge = Priorität bei gleichen Geräten. */
	public void register(final InputSource source) {
		this.sources.add(source);
	}

	public void unregister(final InputSource source) {
		this.sources.remove(source);
	}

	public List<InputSource> sources() {
		return List.copyOf(this.sources);
	}

	public KeyBindings bindings() {
		return this.bindings;
	}

	public void setBindings(final KeyBindings b) {
		this.bindings = b;
	}

	/** Mausempfindlichkeit 0…1 (Vanilla-Skala, 0,5 = 100 %), Y-Achse umkehren, Sprint/Schleichen als Umschalter. */
	public void configure(final double sensitivity, final boolean invertY, final boolean toggleSprint, final boolean toggleSneak) {
		this.sensitivity = Math.max(0, Math.min(1, sensitivity));
		this.invertY = invertY;
		if (this.toggleSprint && !toggleSprint) {
			this.sprintLatched = false;
		}
		if (this.toggleSneak && !toggleSneak) {
			this.sneakLatched = false;
		}
		this.toggleSprint = toggleSprint;
		this.toggleSneak = toggleSneak;
	}

	public Scope scope() {
		return this.scope;
	}

	private static boolean allowed(final GameAction.Context c, final Scope s) {
		return switch (c) {
			case GAME -> s == Scope.WORLD;
			case GLOBAL_GAME -> s != Scope.MENU;
			case GUI -> s != Scope.WORLD;
			case GLOBAL -> true;
		};
	}

	/** Einmal je Bild: Quellen abfragen und Aktionszustände für den Bereich {@code s} bilden. */
	public void update(final Scope s) {
		for (InputSource src : this.sources) {
			src.poll();
		}
		if (s != Scope.WORLD) {
			this.sprintLatched = false;
			this.sneakLatched = false;
		}
		this.scope = s;
		for (GameAction a : GameAction.values()) {
			this.states.put(a, allowed(a.context, s) ? read(this.bindings.get(a)) : ActionState.IDLE);
		}
		if (this.toggleSprint) {
			if (presses(GameAction.SPRINT) % 2 == 1) {
				this.sprintLatched = !this.sprintLatched;
			}
		}
		if (this.toggleSneak) {
			if (presses(GameAction.SNEAK) % 2 == 1) {
				this.sneakLatched = !this.sneakLatched;
			}
		}
	}

	private ActionState read(final InputCode code) {
		if (code.isNone()) {
			return ActionState.IDLE;
		}
		boolean down = false;
		int presses = 0;
		double value = 0;
		for (InputSource src : this.sources) {
			if (src.handles(code.device())) {
				down |= src.down(code);
				presses += src.presses(code);
				value += code.device().analog() ? src.analog(code) : 0;
			}
		}
		if (!code.device().analog()) {
			value = down ? 1 : 0;
		}
		return new ActionState(down, presses, value);
	}

	public ActionState state(final GameAction a) {
		return this.states.get(a);
	}

	public boolean down(final GameAction a) {
		return this.states.get(a).down();
	}

	public int presses(final GameAction a) {
		return this.states.get(a).presses();
	}

	/**
	 * Betätigungen der Belegung von {@code a} unabhängig vom Bereich – für vom Spiel bediente Screens, die Spieltasten selbst
	 * auswerten (Inventar: Inventartaste schließt, 1–9/F tauschen, Q wirft; wie Vanilla {@code AbstractContainerScreen#keyPressed}).
	 */
	public int rawPresses(final GameAction a) {
		InputCode code = this.bindings.get(a);
		if (code.isNone()) {
			return 0;
		}
		int n = 0;
		for (InputSource src : this.sources) {
			if (src.handles(code.device())) {
				n += src.presses(code);
			}
		}
		return n;
	}

	/** Gehalten mit Umschaltlogik für Sprinten/Schleichen. */
	public boolean active(final GameAction a) {
		if (a == GameAction.SPRINT && this.toggleSprint) {
			return this.sprintLatched;
		}
		if (a == GameAction.SNEAK && this.toggleSneak) {
			return this.sneakLatched;
		}
		return down(a);
	}

	/** Bewegungseingabe für die Physik (gleiche Struktur wie bisher, Physik bleibt unverändert). */
	public PlayerPhysics.Input movement() {
		if (this.scope != Scope.WORLD) {
			return PlayerPhysics.Input.NONE;
		}
		return new PlayerPhysics.Input(active(GameAction.MOVE_FORWARD), active(GameAction.MOVE_BACKWARD), active(GameAction.MOVE_LEFT),
			active(GameAction.MOVE_RIGHT), active(GameAction.JUMP), active(GameAction.SNEAK), active(GameAction.SPRINT));
	}

	/** Grad je Bildschirmpixel Mausbewegung: Vanilla {@code MouseHandler#turnPlayer}: (s·0,6+0,2)³·8·0,15. */
	public static double degreesPerPixel(final double sensitivity) {
		double f = sensitivity * 0.6 + 0.2;
		return f * f * f * 8.0 * 0.15;
	}

	/** Blickänderung dieses Bildes in Grad {Gier, Neigung}; Y-Umkehr berücksichtigt. */
	public float[] lookDelta() {
		double dx = this.states.get(GameAction.LOOK_HORIZONTAL).value();
		double dy = this.states.get(GameAction.LOOK_VERTICAL).value();
		if (dx == 0 && dy == 0) {
			return new float[]{0, 0};
		}
		float k = (float) degreesPerPixel(this.sensitivity);
		return new float[]{(float) dx * k, (float) dy * k * (this.invertY ? -1 : 1)};
	}

	/**
	 * Schnellzugriff-Schritte: positiv = nach links ({@code ScrollWheelHandler}: Rad hoch = vorheriger Platz), je Richtung höchstens 9.
	 */
	public int hotbarSteps() {
		int prev = steps(this.states.get(GameAction.HOTBAR_PREVIOUS), this.bindings.get(GameAction.HOTBAR_PREVIOUS));
		int next = steps(this.states.get(GameAction.HOTBAR_NEXT), this.bindings.get(GameAction.HOTBAR_NEXT));
		return prev - next;
	}

	private static int steps(final ActionState s, final InputCode code) {
		if (code.device() == InputCode.Device.MOUSE_WHEEL) {
			return (int) Math.min(9, Math.ceil(Math.abs(s.value())));
		}
		return Math.min(9, s.presses());
	}

	/** Gedrückter Schnellzugriffsplatz 0–8 dieses Bildes (letzter gewinnt) oder −1. */
	public int hotbarSlotPressed() {
		int slot = -1;
		for (int k = 0; k < 9; k++) {
			if (presses(GameAction.hotbar(k)) > 0) {
				slot = k;
			}
		}
		return slot;
	}
}
