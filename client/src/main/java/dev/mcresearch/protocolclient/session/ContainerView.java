package dev.mcresearch.protocolclient.session;

import dev.mcresearch.protocolclient.world.ItemStack;
import java.util.Arrays;
import java.util.HashMap;
import java.util.Map;

/**
 * Ein geöffneter Container (M8, docs/31 §2): Menü-ID und -Typ aus {@code open_screen}, eigene Plätze (Truhe, Ofen …), Cursor,
 * stateId und Datenwerte. Die Spielerplätze am Ende des Menüs (27 Inventar + 9 Hotbar) gehören zum Spielerinventar und werden
 * dorthin abgebildet ({@link PlayerInventory}). Nur unter der Sperre von {@link PlayerInventory} verwenden.
 */
public final class ContainerView {
	/** Bekannte Menütypen (Register {@code minecraft:menu}, Protokoll 777, reports/registries.json). */
	public enum Kind {
		GENERIC_9X1(0, 9), GENERIC_9X2(1, 18), GENERIC_9X3(2, 27), GENERIC_9X4(3, 36), GENERIC_9X5(4, 45), GENERIC_9X6(5, 54), GENERIC_3X3(6, 9),
		CRAFTING(12, 10), FURNACE(14, 3), BLAST_FURNACE(10, 3), SMOKER(22, 3), HOPPER(16, 5), SHULKER_BOX(20, 27), OTHER(-1, -1);

		final int menuId;
		/** Anzahl eigener Plätze vor den 36 Spielerplätzen; −1 = aus container_set_content (Gesamtzahl − 36). */
		final int ownSlots;

		Kind(final int menuId, final int ownSlots) {
			this.menuId = menuId;
			this.ownSlots = ownSlots;
		}

		static Kind of(final int menuId) {
			for (Kind k : values()) {
				if (k.menuId == menuId) {
					return k;
				}
			}
			return OTHER;
		}

		public int rows() {
			return switch (this) {
				case GENERIC_9X1 -> 1;
				case GENERIC_9X2 -> 2;
				case GENERIC_9X3, SHULKER_BOX -> 3;
				case GENERIC_9X4 -> 4;
				case GENERIC_9X5 -> 5;
				case GENERIC_9X6 -> 6;
				default -> 0;
			};
		}

		public boolean isFurnace() {
			return this == FURNACE || this == BLAST_FURNACE || this == SMOKER;
		}
	}

	private static final String[] MENU_NAMES = {"generic_9x1", "generic_9x2", "generic_9x3", "generic_9x4", "generic_9x5", "generic_9x6", "generic_3x3",
		"crafter_3x3", "anvil", "beacon", "blast_furnace", "brewing_stand", "crafting", "enchantment", "furnace", "grindstone", "hopper", "lectern", "loom",
		"merchant", "shulker_box", "smithing", "smoker", "cartography_table", "stonecutter"};

	final int id;
	final int menuType;
	final Kind kind;
	final String title;
	ItemStack[] own;
	/** Gesamtzahl der Menüplätze; −1, solange kein Inhalt empfangen wurde und der Typ sie nicht festlegt. */
	int total;
	int stateId;
	ItemStack cursor = ItemStack.EMPTY;
	boolean contentReceived;
	final Map<Integer, Integer> data = new HashMap<>();

	ContainerView(final int id, final int menuType, final String title) {
		this.id = id;
		this.menuType = menuType;
		this.kind = Kind.of(menuType);
		this.title = title;
		int ownSlots = this.kind.ownSlots;
		this.own = new ItemStack[Math.max(0, ownSlots)];
		Arrays.fill(this.own, ItemStack.EMPTY);
		this.total = ownSlots < 0 ? -1 : ownSlots + 36;
	}

	public static String menuName(final int menuType) {
		return menuType >= 0 && menuType < MENU_NAMES.length ? "minecraft:" + MENU_NAMES[menuType] : "#" + menuType;
	}

	/** Erster Menüplatz der Spielerplätze (Gesamtzahl − 36); bei Menüs mit weniger als 36 Plätzen keine Spielerplätze. */
	int playerStart() {
		return this.total >= 36 ? this.total - 36 : Math.max(this.total, 0);
	}

	boolean hasPlayerSlots() {
		return this.total >= 36;
	}

	void resizeForTotal(final int newTotal) {
		this.total = newTotal;
		int ownCount = playerStart();
		if (ownCount != this.own.length) {
			this.own = Arrays.copyOf(this.own, ownCount);
			for (int i = 0; i < ownCount; i++) {
				if (this.own[i] == null) {
					this.own[i] = ItemStack.EMPTY;
				}
			}
		}
	}

	public int id() {
		return this.id;
	}

	public Kind kind() {
		return this.kind;
	}

	public String title() {
		return this.title;
	}

	public String menuName() {
		return menuName(this.menuType);
	}
}
