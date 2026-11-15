package dev.mcresearch.protocolclient.auth;

/** M13: bekannte Fehlerfälle der Anmeldung mit verständlicher deutscher Meldung (docs/52 §7). */
public enum AuthError {
	EXTERNAL_NOT_ALLOWED("Externe Anmeldeverbindungen sind nicht freigegeben (Schalter --allow-external-auth fehlt)."),
	ENDPOINT_NOT_ALLOWED("Der Anmeldedienst nannte einen nicht freigegebenen Endpunkt; Anmeldung abgebrochen."),
	NO_CLIENT_ID("Keine Client-ID der eigenen Microsoft-App-Registrierung angegeben (--client-id)."),
	USER_CANCELLED("Anmeldung abgebrochen."),
	TIMEOUT("Zeitüberschreitung bei der Anmeldung."),
	STATE_MISMATCH("Ungültige Antwort des Browsers (state stimmt nicht überein); Anmeldung verworfen."),
	OAUTH_ERROR("Microsoft hat die Anmeldung abgelehnt."),
	INVALID_GRANT("Microsoft-Anmeldung ungültig oder abgelaufen; bitte erneut anmelden."),
	NO_XBOX_ACCOUNT("Dieses Microsoft-Konto hat kein Xbox-Profil. Bitte einmal auf xbox.com anmelden und ein Profil anlegen."),
	XBOX_BANNED("Das Xbox-Konto ist gesperrt."),
	XBOX_COUNTRY("Xbox Live ist im Land dieses Kontos nicht verfügbar."),
	XBOX_ADULT_VERIFICATION("Das Konto erfordert eine Altersverifikation (Xbox)."),
	XBOX_CHILD("Kinderkonto: Es muss von einem Erwachsenen zu einer Microsoft-Familie hinzugefügt werden."),
	XBOX_ERROR("Xbox-Live-Anmeldung fehlgeschlagen."),
	APP_NOT_APPROVED("Minecraft Services lehnen die App-Registrierung ab (Invalid app registration). Die Client-ID muss von Mojang freigegeben werden: https://aka.ms/AppRegInfo"),
	NO_MINECRAFT_PROFILE("Für dieses Konto existiert kein Minecraft-Java-Profil (Spiel nicht erworben oder noch kein Name gewählt)."),
	NO_OWNERSHIP("Das Konto besitzt keine Berechtigung für Minecraft: Java Edition."),
	MULTIPLAYER_DISABLED("Mehrspieler ist für dieses Konto deaktiviert (Kontoeinstellungen/Jugendschutz)."),
	MINECRAFT_TOKEN_EXPIRED("Die Minecraft-Sitzung ist abgelaufen; bitte erneut anmelden."),
	INVALID_SESSION("Ungültige Sitzung: Der Sessionserver hat den Nachweis abgelehnt; bitte erneut anmelden."),
	INSUFFICIENT_PRIVILEGES("Fehlende Berechtigung für Mehrspieler (Sessionserver)."),
	USER_BANNED("Das Konto ist für Mehrspieler gesperrt."),
	RATE_LIMITED("Zu viele Anfragen; bitte später erneut versuchen."),
	SERVICE_UNAVAILABLE("Anmeldedienst nicht erreichbar."),
	OFFLINE_IDENTITY("Der Server verlangt eine Online-Mode-Anmeldung; der Offline-Testmodus kann nicht beitreten."),
	PROTOCOL("Unerwartete Antwort eines Anmeldedienstes.");

	private final String message;

	AuthError(final String message) {
		this.message = message;
	}

	public String message() {
		return this.message;
	}
}
