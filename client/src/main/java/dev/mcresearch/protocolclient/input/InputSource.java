package dev.mcresearch.protocolclient.input;

/**
 * Eine Eingabequelle (docs/60 §4). Heute: Tastatur und Maus über GLFW. Für M17 vorgesehen: eine input4j-Quelle für Gamepads,
 * die {@link InputCode.Device#GAMEPAD_BUTTON} und {@link InputCode.Device#GAMEPAD_AXIS} liefert und über
 * {@link InputManager#register} eingehängt wird – ohne Änderung an Spiellogik oder Menüs.
 */
public interface InputSource {
	/** Kurzname für Diagnose („keyboard-mouse“, später „input4j“). */
	String id();

	/** Liefert diese Quelle Werte für {@code device}? */
	boolean handles(InputCode.Device device);

	/** Wird einmal je Bild vor den Abfragen aufgerufen (Zustand übernehmen, Zähler für dieses Bild festhalten). */
	void poll();

	/** Element ist gehalten. */
	boolean down(InputCode code);

	/** Anzahl der Betätigungen seit dem letzten {@link #poll()}. */
	int presses(InputCode code);

	/**
	 * Analoger Wert seit dem letzten {@link #poll()}: Mausachse in Bildschirmpixeln, Mausrad in Rastungen (≥ 0 je Richtung),
	 * Gamepad-Achse −1…1.
	 */
	double analog(InputCode code);
}
