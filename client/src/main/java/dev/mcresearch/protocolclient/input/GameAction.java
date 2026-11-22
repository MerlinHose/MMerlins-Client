package dev.mcresearch.protocolclient.input;

/**
 * Zentrale Spielaktionen des Merlin Input Systems (docs/60 §4): Eingabegerät → Belegung → Aktion → Spielmechanik. Die Spiellogik
 * fragt nur Aktionen ab, nie Tastencodes. {@link Context} trennt Spielwelt- und Menüeingaben; {@link #analog} kennzeichnet
 * Achsen, die eine spätere Controllerquelle (M17) stufenlos liefern kann.
 */
public enum GameAction {
	MOVE_FORWARD("Vorwärts", Category.MOVEMENT, Context.GAME, false, true, InputCode.key(Keys.W)),
	MOVE_BACKWARD("Rückwärts", Category.MOVEMENT, Context.GAME, false, true, InputCode.key(Keys.S)),
	MOVE_LEFT("Links", Category.MOVEMENT, Context.GAME, false, true, InputCode.key(Keys.A)),
	MOVE_RIGHT("Rechts", Category.MOVEMENT, Context.GAME, false, true, InputCode.key(Keys.D)),
	JUMP("Springen", Category.MOVEMENT, Context.GAME, false, true, InputCode.key(Keys.SPACE)),
	SNEAK("Schleichen", Category.MOVEMENT, Context.GAME, false, true, InputCode.key(Keys.LEFT_SHIFT)),
	SPRINT("Sprinten", Category.MOVEMENT, Context.GAME, false, true, InputCode.key(Keys.LEFT_CONTROL)),
	LOOK_HORIZONTAL("Umsehen waagerecht", Category.MOVEMENT, Context.GAME, true, false, InputCode.axis(InputCode.AXIS_X)),
	LOOK_VERTICAL("Umsehen senkrecht", Category.MOVEMENT, Context.GAME, true, false, InputCode.axis(InputCode.AXIS_Y)),
	ATTACK("Angreifen/Abbauen", Category.INTERACTION, Context.GAME, false, true, InputCode.mouse(0)),
	USE("Benutzen/Platzieren", Category.INTERACTION, Context.GAME, false, true, InputCode.mouse(1)),
	INVENTORY("Inventar öffnen/schließen", Category.INVENTORY, Context.GAME, false, true, InputCode.key(Keys.E)),
	DROP_ITEM("Gegenstand fallen lassen", Category.INVENTORY, Context.GAME, false, true, InputCode.key(Keys.Q)),
	SWAP_OFFHAND("Mit Zweithand tauschen (Inventar)", Category.INVENTORY, Context.GAME, false, true, InputCode.key(Keys.F)),
	HOTBAR_NEXT("Nächster Schnellzugriffsplatz", Category.INVENTORY, Context.GAME, true, true, InputCode.wheel(InputCode.WHEEL_DOWN)),
	HOTBAR_PREVIOUS("Vorheriger Schnellzugriffsplatz", Category.INVENTORY, Context.GAME, true, true, InputCode.wheel(InputCode.WHEEL_UP)),
	HOTBAR_1("Schnellzugriff 1", Category.INVENTORY, Context.GAME, false, true, InputCode.key(Keys.DIGIT_1)),
	HOTBAR_2("Schnellzugriff 2", Category.INVENTORY, Context.GAME, false, true, InputCode.key(Keys.DIGIT_1 + 1)),
	HOTBAR_3("Schnellzugriff 3", Category.INVENTORY, Context.GAME, false, true, InputCode.key(Keys.DIGIT_1 + 2)),
	HOTBAR_4("Schnellzugriff 4", Category.INVENTORY, Context.GAME, false, true, InputCode.key(Keys.DIGIT_1 + 3)),
	HOTBAR_5("Schnellzugriff 5", Category.INVENTORY, Context.GAME, false, true, InputCode.key(Keys.DIGIT_1 + 4)),
	HOTBAR_6("Schnellzugriff 6", Category.INVENTORY, Context.GAME, false, true, InputCode.key(Keys.DIGIT_1 + 5)),
	HOTBAR_7("Schnellzugriff 7", Category.INVENTORY, Context.GAME, false, true, InputCode.key(Keys.DIGIT_1 + 6)),
	HOTBAR_8("Schnellzugriff 8", Category.INVENTORY, Context.GAME, false, true, InputCode.key(Keys.DIGIT_1 + 7)),
	HOTBAR_9("Schnellzugriff 9", Category.INVENTORY, Context.GAME, false, true, InputCode.key(Keys.DIGIT_1 + 8)),
	TOGGLE_HUD("HUD ein/aus", Category.MISC, Context.GLOBAL_GAME, false, true, InputCode.key(Keys.F1)),
	SCREENSHOT("Bildschirmfoto", Category.MISC, Context.GLOBAL, false, true, InputCode.key(Keys.F2)),
	TOGGLE_DEBUG("Debug-Anzeige", Category.MISC, Context.GLOBAL_GAME, false, true, InputCode.key(Keys.F3)),
	TOGGLE_PERSPECTIVE("Perspektive wechseln", Category.MISC, Context.GLOBAL_GAME, false, true, InputCode.key(Keys.F5)),
	TOGGLE_FULLSCREEN("Vollbild umschalten", Category.MISC, Context.GLOBAL, false, true, InputCode.key(Keys.F11)),
	PAUSE("Pause/Menü", Category.MISC, Context.GLOBAL_GAME, false, false, InputCode.key(Keys.ESCAPE)),
	GUI_CONFIRM("Menü: Bestätigen", Category.GUI, Context.GUI, false, false, InputCode.key(Keys.ENTER)),
	GUI_BACK("Menü: Zurück", Category.GUI, Context.GUI, false, false, InputCode.key(Keys.ESCAPE)),
	GUI_UP("Menü: Hoch", Category.GUI, Context.GUI, false, false, InputCode.key(Keys.UP)),
	GUI_DOWN("Menü: Runter", Category.GUI, Context.GUI, false, false, InputCode.key(Keys.DOWN)),
	GUI_LEFT("Menü: Links", Category.GUI, Context.GUI, false, false, InputCode.key(Keys.LEFT)),
	GUI_RIGHT("Menü: Rechts", Category.GUI, Context.GUI, false, false, InputCode.key(Keys.RIGHT)),
	GUI_NEXT("Menü: Nächstes Element", Category.GUI, Context.GUI, false, false, InputCode.key(Keys.TAB));

	/** Gruppen der Tastenbelegung. */
	public enum Category {
		MOVEMENT("Bewegung"), INTERACTION("Interaktion"), INVENTORY("Inventar"), MISC("Sonstiges"), GUI("Menüs");

		public final String label;

		Category(final String label) {
			this.label = label;
		}
	}

	/**
	 * Gültigkeitsbereich: {@code GAME} nur in der Spielwelt ohne offenen Screen, {@code GUI} nur in Menüs, {@code GLOBAL_GAME}
	 * im Spiel auch bei offenem Spiel-Screen (Pause, HUD), {@code GLOBAL} immer.
	 */
	public enum Context {
		GAME, GUI, GLOBAL_GAME, GLOBAL
	}

	public final String label;
	public final Category category;
	public final Context context;
	public final boolean analog;
	public final boolean rebindable;
	public final InputCode defaultCode;

	GameAction(final String label, final Category category, final Context context, final boolean analog, final boolean rebindable,
		final InputCode defaultCode) {
		this.label = label;
		this.category = category;
		this.context = context;
		this.analog = analog;
		this.rebindable = rebindable;
		this.defaultCode = defaultCode;
	}

	/** Schlüssel in der Konfigurationsdatei ({@code controls.key.<id>}). */
	public String id() {
		return name().toLowerCase(java.util.Locale.ROOT);
	}

	/** Vom grafischen Client umgesetzt (sonst in Menüs als „geplant“ gekennzeichnet). */
	public boolean supported() {
		return this != TOGGLE_PERSPECTIVE;
	}

	public static GameAction hotbar(final int slot) {
		return values()[HOTBAR_1.ordinal() + slot];
	}
}
