package dev.mcresearch.protocolclient.world;

import java.util.Map;
import java.util.Set;

/**
 * Ein Item-Stapel wie vom Server gemeldet (docs/28 §3): Item-ID, Anzahl und der Komponenten-Patch gegenüber den
 * Standardkomponenten des Items ({@code DataComponentPatch}: gesetzte Werte und entfernte Typen). Unveränderlich.
 *
 * @param components dekodierte gesetzte Komponenten (Name → Wert: Integer, Float, Boolean, String, NBT-Objekt, Map, List,
 *                   {@link #UNIT})
 * @param removed    Namen entfernter Komponenten (z. B. {@code minecraft:tool} entfernt)
 */
public record ItemStack(int itemId, int count, Map<String, Object> components, Set<String> removed) {
	public static final ItemStack EMPTY = new ItemStack(-1, 0, Map.of(), Set.of());
	/** Wert der Komponenten ohne Daten ({@code Unit}). */
	public static final Object UNIT = new Object() {
		@Override
		public String toString() {
			return "unit";
		}
	};

	public ItemStack {
		components = Map.copyOf(components);
		removed = Set.copyOf(removed);
		if (count < 0) {
			throw new IllegalArgumentException("negative count");
		}
	}

	public boolean isEmpty() {
		return this.count <= 0 || this.itemId < 0;
	}

	/** Gleiches Item mit gleichen Komponenten ({@code ItemStack#isSameItemSameComponents}), Anzahl egal. */
	public boolean sameItemSameComponents(final ItemStack o) {
		if (isEmpty() || o.isEmpty()) {
			return isEmpty() && o.isEmpty();
		}
		return this.itemId == o.itemId && this.components.equals(o.components) && this.removed.equals(o.removed);
	}

	/** Wirksame Werkzeugkomponente: Standard des Items, außer der Patch entfernt sie. */
	public ItemRegistry.Tool tool(final ItemRegistry items) {
		if (isEmpty() || this.removed.contains("minecraft:tool")) {
			return null;
		}
		return items.tool(this.itemId);
	}

	public String describe(final ItemRegistry items) {
		if (isEmpty()) {
			return "empty";
		}
		StringBuilder sb = new StringBuilder();
		sb.append(this.count).append("× ").append(items.isValid(this.itemId) ? items.name(this.itemId) : "#" + this.itemId);
		if (this.components.containsKey("minecraft:damage")) {
			sb.append(" damage=").append(this.components.get("minecraft:damage"));
		}
		int other = this.components.size() - (this.components.containsKey("minecraft:damage") ? 1 : 0);
		if (other > 0 || !this.removed.isEmpty()) {
			sb.append(" (+").append(other).append(" components");
			if (!this.removed.isEmpty()) {
				sb.append(", −").append(this.removed.size());
			}
			sb.append(')');
		}
		return sb.toString();
	}
}
