package dev.mcresearch.protocolclient.ui;

/**
 * Farben des Merlin-Menüstils (eigene Gestaltung, keine Minecraft-Grafiken): dunkle, leicht bläuliche Flächen, helle Schrift,
 * Akzentfarbe für Fokus und aktive Elemente. {@link #HIGH_CONTRAST} ist die Barrierefreiheitsvariante.
 */
public record Theme(int background, int backgroundBottom, int panel, int panelBorder, int button, int buttonHover, int buttonPressed,
	int buttonDisabled, int text, int textDim, int textDisabled, int accent, int focus, int error, int warning, int ok, int overlay,
	int tooltipBg, int fieldBg) {

	public static final Theme DEFAULT = new Theme(0xFF15202B, 0xFF0B1118, 0xE01B2530, 0xFF3A4A5C, 0xFF2E3B4A, 0xFF3F5468, 0xFF24303C,
		0xFF20262D, 0xFFF2F5F8, 0xFFA9B6C3, 0xFF67727D, 0xFF5FB4FF, 0xFFFFD25F, 0xFFFF6B6B, 0xFFFFB84D, 0xFF7ED67E, 0xA0000000,
		0xF0101820, 0xFF0E151C);

	public static final Theme HIGH_CONTRAST = new Theme(0xFF000000, 0xFF000000, 0xF0000000, 0xFFFFFFFF, 0xFF000000, 0xFF1E3A8A,
		0xFF000000, 0xFF000000, 0xFFFFFFFF, 0xFFFFFFFF, 0xFFB0B0B0, 0xFF66CCFF, 0xFFFFFF00, 0xFFFF5555, 0xFFFFCC00, 0xFF55FF55,
		0xC0000000, 0xFF000000, 0xFF000000);
}
