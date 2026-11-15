package dev.mcresearch.protocolclient.auth;

import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;

/** M13: Abbruch einer laufenden Anmeldung (Oberfläche „Abbrechen“, Programmende). Thread-sicher, einmalig. */
public final class AuthCancellation {
	private volatile boolean cancelled;
	private final List<Runnable> listeners = new CopyOnWriteArrayList<>();

	public void cancel() {
		if (this.cancelled) {
			return;
		}
		this.cancelled = true;
		for (Runnable r : this.listeners) {
			r.run();
		}
	}

	public boolean cancelled() {
		return this.cancelled;
	}

	/** Registriert eine Abbruchaktion; läuft sofort, wenn bereits abgebrochen. */
	public void onCancel(final Runnable r) {
		this.listeners.add(r);
		if (this.cancelled) {
			r.run();
		}
	}

	public void throwIfCancelled() throws AuthException {
		if (this.cancelled) {
			throw new AuthException(AuthError.USER_CANCELLED, "");
		}
	}
}
