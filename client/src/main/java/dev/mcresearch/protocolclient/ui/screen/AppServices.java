package dev.mcresearch.protocolclient.ui.screen;

import dev.mcresearch.protocolclient.app.ConnectionManager;
import dev.mcresearch.protocolclient.app.ServerEntry;
import dev.mcresearch.protocolclient.app.ServerList;
import dev.mcresearch.protocolclient.app.ServerPinger;
import dev.mcresearch.protocolclient.settings.SettingsManager;
import java.util.List;

/**
 * Was die Menüs von der Anwendung brauchen (docs/59 §2). Die Grafikschicht implementiert es; Tests verwenden eine Attrappe.
 * Screens rufen nie OpenGL, GLFW oder Netzwerkcode direkt auf.
 */
public interface AppServices {
	SettingsManager settings();

	ServerList servers();

	ServerPinger pinger();

	ConnectionManager connections();

	/** Verbindung zu {@code e} aufbauen und den Verbindungsbildschirm zeigen. */
	void connect(ServerEntry e);

	/** Verbindung trennen; {@code toMainMenu} = direkt ins Hauptmenü, sonst Meldung „Verbindung getrennt“. */
	void disconnect(boolean toMainMenu);

	/** Pausenmenü schließen, zurück ins Spiel. */
	void resumeGame();

	/** Spielwelt aktiv (Verbindung im Spielzustand). */
	boolean inGame();

	/** Anwendung regulär beenden. */
	void quit();

	/** Vollbildauflösungen des Bildschirms, z. B. „1920x1080@60“ (leer, wenn unbekannt). */
	List<String> fullscreenModes();

	/** Grafik- und Systeminfo für „Über Merlin Client“. */
	String platformInfo();
}
