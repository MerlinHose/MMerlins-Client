package dev.mcresearch.protocolclient.entity;

import dev.mcresearch.protocolclient.physics.Aabb;
import dev.mcresearch.protocolclient.world.ItemStack;
import java.util.HashMap;
import java.util.Map;
import java.util.UUID;

/**
 * Zustand einer Entity im Client (M9, docs/35 §3). Getrennt in
 * <ul>
 *   <li><b>bestätigten Zustand</b> – ausschließlich aus Serverpaketen: Position (Basis der relativen Deltas), Rotationen,
 *       onGround, Geschwindigkeit, Datenfelder, Attribute, Ausrüstung, Passagiere;</li>
 *   <li><b>Darstellung</b> – {@link EntityInterpolator}; ändert den bestätigten Zustand nie.</li>
 * </ul>
 * Zugriff nur unter der Sperre des {@link EntityManager}.
 */
public final class ClientEntity {
	public static final int SLOT_MAINHAND = 0, SLOT_OFFHAND = 1, SLOT_FEET = 2, SLOT_LEGS = 3, SLOT_CHEST = 4, SLOT_HEAD = 5, SLOT_BODY = 6,
		SLOT_SADDLE = 7;
	/** Attribut-ID {@code minecraft:scale} (registries.json). */
	public static final int ATTRIBUTE_SCALE = 30;
	public static final int ATTRIBUTE_MAX_HEALTH = 23;
	public static final int ATTRIBUTE_MOVEMENT_SPEED = 26;

	final int id;
	final UUID uuid;
	final EntityTypes.Type type;
	final EntityInterpolator display;
	/** M14: in diesem Tick schon vor dem eigenen Fahrzeug interpoliert ({@link EntityManager#tickBeforeOwnVehicle()}). */
	boolean earlyTicked;
	final long spawnTick;
	// ---- bestätigter Zustand ----
	double x, y, z;
	/** Basis der relativen Deltas ({@code VecDeltaCodec}); weicht nach {@code teleport_entity} von x/y/z ab (docs/34 §3.2). */
	double baseX, baseY, baseZ;
	float yRot, xRot, headYRot;
	boolean onGround;
	double motionX, motionY, motionZ;
	final Map<Integer, Object> data = new HashMap<>();
	final Map<Integer, Double> attributes = new HashMap<>();
	final ItemStack[] equipment = new ItemStack[8];
	int[] passengers = new int[0];
	int vehicle = -1;
	// ---- Ereignisse für Animationen (Client-Tick, in dem sie ankamen) ----
	long swingTick = Long.MIN_VALUE;
	int swingHand;
	long hurtTick = Long.MIN_VALUE;
	int lastEvent = -1;
	// M10: Schadensereignisse (damage_event) und davon vom eigenen Spieler verursachte (serverbestätigte Treffer)
	int damageEvents;
	int confirmedHits;
	int lastDamageType = -1;
	long updates;
	/** M14: clientseitige Animationszustände (Laufen, Flügel, Steigen, Aufblähen …), docs/55 §4. */
	final EntityAnimationState anim = new EntityAnimationState();
	/** M14: Position kommt aus der eigenen Fahrzeugphysik (Serverbewegungen werden ignoriert, bis abgestiegen). */
	boolean localControl;

	ClientEntity(final int id, final UUID uuid, final EntityTypes.Type type, final long spawnTick) {
		this.id = id;
		this.uuid = uuid;
		this.type = type;
		this.spawnTick = spawnTick;
		String sn = type.shortName();
		// M14: Boote/Flöße/Loren wie Vanilla mit LinearInterpolationHandler (3 Schritte, neues Ziel startet neu)
		boolean linear = sn.endsWith("_boat") || sn.endsWith("_raft") || sn.endsWith("minecart");
		this.display = new EntityInterpolator(Math.min(3, Math.max(1, type.updateInterval())), linear);
		java.util.Arrays.fill(this.equipment, ItemStack.EMPTY);
	}

	public int id() {
		return this.id;
	}

	public UUID uuid() {
		return this.uuid;
	}

	public EntityTypes.Type type() {
		return this.type;
	}

	public double x() {
		return this.x;
	}

	public double y() {
		return this.y;
	}

	public double z() {
		return this.z;
	}

	public float yRot() {
		return this.yRot;
	}

	public float xRot() {
		return this.xRot;
	}

	public float headYRot() {
		return this.headYRot;
	}

	public boolean onGround() {
		return this.onGround;
	}

	public EntityInterpolator display() {
		return this.display;
	}

	public ItemStack equipment(final int slot) {
		return this.equipment[slot];
	}

	public Object data(final int index) {
		return this.data.get(index);
	}

	public Map<Integer, Object> dataView() {
		return java.util.Collections.unmodifiableMap(this.data);
	}

	public Double attribute(final int id) {
		return this.attributes.get(id);
	}

	public int vehicle() {
		return this.vehicle;
	}

	public int[] passengers() {
		return this.passengers.clone();
	}

	// ---- abgeleitete Werte aus den Datenfeldern (Indizes docs/34 §5) ----

	public int sharedFlags() {
		return this.data.get(0) instanceof Byte b ? b & 0xFF : 0;
	}

	public boolean invisible() {
		return (sharedFlags() & 0x20) != 0;
	}

	public int pose() {
		return this.data.get(6) instanceof Integer p ? p : 0;
	}

	public String customName() {
		return this.data.get(2) instanceof String s ? s : null;
	}

	/** Lebewesen: alle Kategorien außer {@code misc}; aus {@code misc} nur diese Typen (Liste aus entities-777.txt geprüft). */
	private static final java.util.Set<String> LIVING_MISC = java.util.Set.of("minecraft:player", "minecraft:armor_stand", "minecraft:villager",
		"minecraft:iron_golem", "minecraft:snow_golem", "minecraft:copper_golem", "minecraft:mannequin");

	public boolean living() {
		return !"misc".equals(this.type.category()) || LIVING_MISC.contains(this.type.name());
	}

	public boolean isPlayer() {
		return "minecraft:player".equals(this.type.name());
	}

	public boolean isItem() {
		return "minecraft:item".equals(this.type.name());
	}

	public Float health() {
		return living() && this.data.get(9) instanceof Float f ? f : null;
	}

	/** Baby-Flag (Index 16 bei AgeableMob und Zombie); nur für die umgesetzten Typen ausgewertet (M14: weitere Tiere, Dorfbewohner, Ertrunkener). */
	public boolean baby() {
		return BABY_TYPES.contains(this.type.shortName()) && Boolean.TRUE.equals(this.data.get(16));
	}

	/** Typen mit Baby-Flag an Index 16 (AgeableMob: DATA_BABY_ID; Zombie: eigenes DATA_BABY_ID ebenfalls 16), docs/54 §3. */
	static final java.util.Set<String> BABY_TYPES = java.util.Set.of("pig", "cow", "sheep", "zombie", "chicken", "wolf", "cat", "ocelot", "horse", "donkey",
		"mule", "skeleton_horse", "zombie_horse", "rabbit", "bee", "villager", "drowned", "squid", "glow_squid"); // Tintenfische: AgeableWaterCreature

	/** Schaf: Wollbyte Index 18 (Bits 0–3 Farbe, 0x10 geschoren). */
	public int sheepWool() {
		return this.data.get(18) instanceof Byte b ? b & 0xFF : 0;
	}

	/** Item-Entity: Gegenstand (Index 8). */
	public ItemStack item() {
		return this.data.get(8) instanceof ItemStack s ? s : ItemStack.EMPTY;
	}

	/** Spieler: sichtbare Skin-Teile (Index 16). */
	public int skinParts() {
		return (isPlayer() || "minecraft:mannequin".equals(this.type.name())) && this.data.get(16) instanceof Byte b ? b & 0xFF : 0x7F; // M14: auch Mannequin (Avatar)
	}

	public boolean isArmorStand() {
		return "minecraft:armor_stand".equals(this.type.name());
	}

	/** Rüstungsständer: Index 15 (0x01 klein, 0x04 Arme, 0x08 ohne Bodenplatte, 0x10 Markierung). */
	public int armorStandFlags() {
		return isArmorStand() && this.data.get(15) instanceof Byte b ? b & 0xFF : 0;
	}

	public double scale() {
		Double s = this.attributes.get(ATTRIBUTE_SCALE);
		return s == null ? 1.0 : s;
	}

	/** Breite und Höhe nach Typ, Pose, Skalierung und Alter ({@code LivingEntity#getDimensions}, docs/34 §11). */
	public float[] dimensions() {
		float w = this.type.width();
		float h = this.type.height();
		if (isPlayer()) {
			switch (pose()) {
				case 2 -> { // schlafend: fest
					return new float[]{0.2F, 0.2F};
				}
				case 5 -> h = 1.5F;
				case 1, 3, 4 -> h = 0.6F;
				case 7 -> {
					return new float[]{0.2F, 0.2F};
				}
				default -> {
				}
			}
		}
		boolean fixed = this.type.fixed();
		if (isArmorStand()) {
			if ((armorStandFlags() & 0x10) != 0) {
				return new float[]{0.0F, 0.0F}; // Markierung: feste Maße 0 × 0 (ArmorStand MARKER_DIMENSIONS)
			}
			if ((armorStandFlags() & 0x01) != 0) {
				w *= 0.5F; // klein: halbe Typmaße
				h *= 0.5F;
			}
		} else if (baby()) {
			// eigene Baby-Maße (getDefaultDimensions-Überschreibungen in 26.3, Tabelle B aus EntityExtractor), sonst Typmaße × Baby-Skalierung
			float[] b = EntityTypes.get().babyDimensions(this.type.shortName());
			if (b == null) {
				float f = switch (this.type.shortName()) {
					case "skeleton_horse", "zombie_horse" -> 0.7F; // ebenfalls eigene Felder; Werte in Tabelle B
					default -> 0.5F; // getAgeScale (z. B. Biene), Esel/Maultier: Typmaße × 0,5 (AbstractChestedHorse)
				};
				b = new float[]{w * f, h * f};
			}
			w = b[0];
			h = b[1];
		}
		if (living() && !fixed) {
			float f = (float) scale();
			w *= f;
			h *= f;
		}
		return new float[]{w, h};
	}

	/** Hitbox an einer Position ({@code EntityDimensions#makeBoundingBox}). */
	public Aabb boundingBox(final double px, final double py, final double pz) {
		float[] d = dimensions();
		double hw = d[0] / 2.0F;
		return new Aabb(px - hw, py, pz - hw, px + hw, py + d[1], pz + hw);
	}

	/** Auswählbar ({@code isPickable}): Lebewesen; Spieler nur, wenn sie selbst nicht Zuschauer sind (Spielerliste); Items nicht. */
	public boolean pickable(final int ownGameMode) {
		if (isArmorStand()) {
			return (armorStandFlags() & 0x10) == 0; // Markierungen sind nicht auswählbar
		}
		String t = this.type.shortName();
		if (t.endsWith("_boat") || t.endsWith("_raft") || t.equals("minecart") || t.endsWith("_minecart")) {
			return true; // M14: Fahrzeuge sind auswählbar (AbstractBoat/VehicleEntity#isPickable), Einsteigen per Rechtsklick
		}
		if (isItem() || !living()) {
			return false;
		}
		return !(isPlayer() && ownGameMode == 3);
	}
}
