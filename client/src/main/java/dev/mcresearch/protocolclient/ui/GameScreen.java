package dev.mcresearch.protocolclient.ui;

/**
 * Vom Spiel gezeichneter und bedienter Bildschirm (Inventar/Container aus M8, Todesbildschirm aus M10), im
 * {@link ScreenManager} registriert für Eingabesperre, Fokus und Escape. Maus und Tasten gehen weiter an die bewährten
 * Spielklassen ({@link #handledByGame()}).
 */
public final class GameScreen extends Screen {
	private final String name;
	private final String title;
	private final Runnable onBack;

	public GameScreen(final String name, final String title, final Runnable onBack) {
		this.name = name;
		this.title = title;
		this.onBack = onBack;
	}

	@Override
	public String name() {
		return this.name;
	}

	@Override
	public String title() {
		return this.title;
	}

	@Override
	protected void init() {
	}

	@Override
	protected void layout(final double w, final double h) {
	}

	@Override
	public boolean handledByGame() {
		return true;
	}

	@Override
	public void onBack() {
		this.onBack.run();
	}

	@Override
	public void render(final UiCanvas c, final UiContext ctx) {
		// zeichnet das Spiel selbst (InventoryScreen, SurvivalHud)
	}
}
