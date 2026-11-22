package dev.mcresearch.protocolclient.input;

import java.util.ArrayList;
import java.util.EnumMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Belegung Tastatur/Maus → Aktion (docs/60 §5). Nicht frei belegbare Aktionen (Umsehen, Pause, Menütasten) behalten immer ihre
 * Vorgabe. Konflikte werden wie in Vanilla erkannt und angezeigt, aber nicht verboten (zwei Aktionen auf einer Taste lösen
 * beide aus); Konflikte mit festen Tasten werden abgelehnt.
 */
public final class KeyBindings {
	private final EnumMap<GameAction, InputCode> codes = new EnumMap<>(GameAction.class);

	public KeyBindings() {
		for (GameAction a : GameAction.values()) {
			this.codes.put(a, a.defaultCode);
		}
	}

	public InputCode get(final GameAction a) {
		return this.codes.get(a);
	}

	/** Ergebnis einer Belegungsänderung. */
	public enum Change {
		OK, NOT_REBINDABLE, RESERVED, UNSUPPORTED_DEVICE
	}

	/** Belegt {@code a} mit {@code code}; feste Tasten (Esc) und Achsen sind nicht erlaubt. */
	public Change set(final GameAction a, final InputCode code) {
		if (!a.rebindable) {
			return Change.NOT_REBINDABLE;
		}
		if (code.device() == InputCode.Device.MOUSE_AXIS) {
			return Change.UNSUPPORTED_DEVICE;
		}
		if (code.device() == InputCode.Device.KEYBOARD && code.code() == Keys.ESCAPE) {
			return Change.RESERVED;
		}
		this.codes.put(a, code);
		return Change.OK;
	}

	public void reset(final GameAction a) {
		this.codes.put(a, a.defaultCode);
	}

	public void resetAll() {
		for (GameAction a : GameAction.values()) {
			reset(a);
		}
	}

	public boolean isDefault(final GameAction a) {
		return this.codes.get(a).equals(a.defaultCode);
	}

	/** Überschneiden sich die Gültigkeitsbereiche (dann ist dieselbe Taste ein Konflikt)? */
	static boolean overlap(final GameAction.Context a, final GameAction.Context b) {
		if (a == GameAction.Context.GUI || b == GameAction.Context.GUI) {
			return a == b || a == GameAction.Context.GLOBAL || b == GameAction.Context.GLOBAL;
		}
		return true;
	}

	/** Alle Konflikte: Eingabecode → Aktionen (≥ 2), in Aufzählungsreihenfolge. Nicht belegte Aktionen zählen nicht. */
	public Map<InputCode, List<GameAction>> conflicts() {
		Map<InputCode, List<GameAction>> out = new LinkedHashMap<>();
		GameAction[] all = GameAction.values();
		for (int i = 0; i < all.length; i++) {
			InputCode c = this.codes.get(all[i]);
			if (c.isNone() || c.device() == InputCode.Device.MOUSE_AXIS) {
				continue;
			}
			for (int j = i + 1; j < all.length; j++) {
				if (c.equals(this.codes.get(all[j])) && overlap(all[i].context, all[j].context)) {
					List<GameAction> l = out.computeIfAbsent(c, k -> new ArrayList<>());
					if (!l.contains(all[i])) {
						l.add(all[i]);
					}
					if (!l.contains(all[j])) {
						l.add(all[j]);
					}
				}
			}
		}
		return out;
	}

	/** Aktionen, die mit {@code a} dieselbe Eingabe teilen. */
	public List<GameAction> conflictsOf(final GameAction a) {
		List<GameAction> out = new ArrayList<>();
		for (List<GameAction> l : conflicts().values()) {
			if (l.contains(a)) {
				for (GameAction o : l) {
					if (o != a) {
						out.add(o);
					}
				}
			}
		}
		return out;
	}

	/** Alle Aktionen, die {@code code} auslöst. */
	public List<GameAction> actionsFor(final InputCode code) {
		List<GameAction> out = new ArrayList<>();
		for (Map.Entry<GameAction, InputCode> e : this.codes.entrySet()) {
			if (e.getValue().equals(code)) {
				out.add(e.getKey());
			}
		}
		return out;
	}

	/** Für die Konfigurationsdatei: nur frei belegbare Aktionen. */
	public Map<String, String> toProperties() {
		Map<String, String> out = new LinkedHashMap<>();
		for (GameAction a : GameAction.values()) {
			if (a.rebindable) {
				out.put("controls.key." + a.id(), this.codes.get(a).serialize());
			}
		}
		return out;
	}

	public KeyBindings copy() {
		KeyBindings k = new KeyBindings();
		k.codes.putAll(this.codes);
		return k;
	}
}
