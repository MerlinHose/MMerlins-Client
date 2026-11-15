package dev.mcresearch.protocolclient.auth;

import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.function.Consumer;

/**
 * M13 Phase I: Zustandsmodell der grafischen Anmeldung (docs/52 §8), unabhängig von Swing/LWJGL und dadurch ohne
 * Fenster testbar. Die Oberfläche zeigt ausschließlich {@link Snapshot}: Zustand, Statustext, Spielername, Fehlermeldung
 * und welche Schaltflächen aktiv sind. Tokens erreichen die Oberfläche nie (nur die {@link LoginIdentity} hält sie).
 */
public final class LoginModel {
	public enum State {
		SIGNED_OUT, SIGNING_IN, SIGNED_IN, ERROR
	}

	/** Anzeigezustand (keine sensitiven Daten). */
	public record Snapshot(State state, String mode, String status, String playerName, String error, boolean canConnect, boolean canCancel,
		boolean canLogout, boolean canSignIn) {
	}

	/** Führt eine Anmeldung aus (Produktion: Microsoft über {@code LoginFactory}; Tests: Attrappe). */
	@FunctionalInterface
	public interface Authenticator {
		LoginIdentity login(Consumer<String> status, AuthCancellation cancel) throws AuthException;
	}

	private final List<Consumer<Snapshot>> listeners = new CopyOnWriteArrayList<>();
	private State state = State.SIGNED_OUT;
	private String mode = "offline";
	private String status = "Nicht angemeldet";
	private String error = "";
	private LoginIdentity identity;
	private AuthCancellation running;
	private Thread worker;
	private Runnable onLogout = () -> { };

	public synchronized Snapshot snapshot() {
		boolean busy = this.state == State.SIGNING_IN;
		return new Snapshot(this.state, this.mode, this.status, this.identity == null ? "" : this.identity.name(), this.error,
			!busy && this.identity != null, busy, !busy && this.identity != null && !this.mode.equals("offline"), !busy);
	}

	public void addListener(final Consumer<Snapshot> l) {
		this.listeners.add(l);
		l.accept(snapshot());
	}

	private void fire() {
		Snapshot s = snapshot();
		for (Consumer<Snapshot> l : this.listeners) {
			l.accept(s);
		}
	}

	/** Offline-Testmodus mit frei gewähltem Namen (bisheriges Verhalten, keine Netzwerkanmeldung). */
	public void useOffline(final String name) {
		synchronized (this) {
			if (this.state == State.SIGNING_IN) {
				return;
			}
			discardIdentity();
			try {
				this.identity = new OfflineIdentity(name);
				this.mode = "offline";
				this.state = State.SIGNED_IN;
				this.status = "Offline-Testmodus";
				this.error = "";
			} catch (IllegalArgumentException e) {
				this.state = State.ERROR;
				this.status = "Nicht angemeldet";
				this.error = "Ungültiger Name: 3–16 Zeichen A–Z, a–z, 0–9, _";
			}
		}
		fire();
	}

	/**
	 * Startet eine Anmeldung im Hintergrund. Läuft bereits eine, wird der Aufruf ignoriert (kein Mehrfach-Login).
	 * Eine bestehende Anmeldung wird vorher abgemeldet.
	 */
	public void signIn(final String newMode, final Authenticator auth) {
		synchronized (this) {
			if (this.state == State.SIGNING_IN) {
				return;
			}
			discardIdentity();
			this.mode = newMode;
			this.state = State.SIGNING_IN;
			this.status = "Anmeldung läuft …";
			this.error = "";
			AuthCancellation cancel = new AuthCancellation();
			this.running = cancel;
			this.worker = Thread.ofPlatform().name("login").daemon(true).start(() -> runLogin(auth, cancel));
		}
		fire();
	}

	private void runLogin(final Authenticator auth, final AuthCancellation cancel) {
		LoginIdentity id = null;
		AuthException failure = null;
		RuntimeException bug = null;
		try {
			id = auth.login(s -> {
				synchronized (this) {
					if (this.running == cancel) {
						this.status = s;
					}
				}
				fire();
			}, cancel);
		} catch (AuthException e) {
			failure = e;
		} catch (RuntimeException e) {
			bug = e;
		}
		synchronized (this) {
			if (this.running != cancel) {
				return; // überholt (abgebrochen und neu gestartet)
			}
			this.running = null;
			if (cancel.cancelled()) {
				this.state = State.SIGNED_OUT;
				this.status = "Anmeldung abgebrochen";
				this.error = "";
				if (id != null) { // Anmeldung kam trotz Abbruch zustande: sofort verwerfen
					this.identity = id;
					discardIdentity();
				}
			} else if (id != null) {
				this.identity = id;
				this.state = State.SIGNED_IN;
				this.status = "Angemeldet";
				this.error = "";
			} else {
				this.state = State.ERROR;
				this.status = "Nicht angemeldet";
				this.error = failure != null ? failure.userMessage() : "Interner Fehler: " + (bug == null ? "?" : bug.getClass().getSimpleName());
			}
		}
		fire();
	}

	/** Bricht eine laufende Anmeldung ab (Browser-Wartezeit, Device-Code-Abfrage, laufende HTTP-Anfrage danach). */
	public void cancel() {
		AuthCancellation c;
		synchronized (this) {
			c = this.running;
			if (c == null) {
				return;
			}
			this.status = "Abbruch …";
		}
		c.cancel();
		fire();
	}

	/** Abmelden: Tokens verwerfen, zurück in den Ausgangszustand. */
	public void logout() {
		synchronized (this) {
			if (this.state == State.SIGNING_IN) {
				return;
			}
			discardIdentity();
			this.state = State.SIGNED_OUT;
			this.status = "Abgemeldet";
			this.error = "";
		}
		fire();
	}

	/** Zusätzliche Abmeldeaktion (z. B. Refresh-Token des Microsoft-Logins verwerfen). */
	public synchronized void onLogout(final Runnable r) {
		this.onLogout = r;
	}

	private void discardIdentity() {
		if (this.identity != null) {
			this.identity.logout();
		}
		if (this.identity != null && !(this.identity instanceof OfflineIdentity)) {
			this.onLogout.run();
		}
		this.identity = null;
	}

	/** Identität für „Verbinden“; null, wenn nicht angemeldet. */
	public synchronized LoginIdentity identity() {
		return this.state == State.SIGNED_IN ? this.identity : null;
	}

	/** Für Tests: auf das Ende des Anmelde-Threads warten. */
	public void awaitIdle(final long millis) throws InterruptedException {
		Thread w;
		synchronized (this) {
			w = this.worker;
		}
		if (w != null) {
			w.join(millis);
		}
	}
}
