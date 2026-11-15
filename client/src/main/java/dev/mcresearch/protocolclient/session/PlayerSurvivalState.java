package dev.mcresearch.protocolclient.session;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Überlebenszustand des eigenen Spielers (M10, docs/40). Ausschließlich aus Serverpaketen: Gesundheit, Nahrung, Sättigung
 * ({@code set_health}), Maximalgesundheit/Rüstung/Angriffswerte ({@code update_attributes}), Absorption und Lebewesen-Flags
 * (eigene {@code set_entity_data}), Effekte ({@code update/remove_mob_effect}), Schaden ({@code damage_event}), Tod
 * ({@code player_combat_kill}), Erfahrung. Clientseitig vorhergesagt werden nur Darstellungsgrößen: die Angriffsstärke
 * ({@code attackStrengthTicker}, docs/38 §2) und die laufende Benutzung eines Gegenstands (Essen).
 *
 * <p>Alle Methoden synchronisiert (Netzwerk-, Tick-, Render-Thread).
 */
public final class PlayerSurvivalState {
	// Attribut-IDs (Registry minecraft:attribute, docs/34 §6)
	public static final int ATTR_ARMOR = 1, ATTR_ARMOR_TOUGHNESS = 2, ATTR_ATTACK_DAMAGE = 3, ATTR_ATTACK_KNOCKBACK = 4, ATTR_ATTACK_SPEED = 5,
		ATTR_KNOCKBACK_RESISTANCE = 20, ATTR_MAX_ABSORPTION = 22, ATTR_MAX_HEALTH = 23, ATTR_MOVEMENT_SPEED = 26;
	/** Effekt-IDs (Registry minecraft:mob_effect, docs/38 §6). */
	public static final String[] EFFECT_NAMES = {"speed", "slowness", "haste", "mining_fatigue", "strength", "instant_health", "instant_damage",
		"jump_boost", "nausea", "regeneration", "resistance", "fire_resistance", "water_breathing", "invisibility", "blindness", "night_vision",
		"hunger", "weakness", "poison", "wither", "health_boost", "absorption", "saturation", "glowing", "levitation", "luck", "unluck", "slow_falling",
		"conduit_power", "dolphins_grace", "bad_omen", "hero_of_the_village", "darkness", "trial_omen", "raid_omen", "wind_charged", "weaving",
		"oozing", "infested", "breath_of_the_nautilus"};
	public static final int USE_ITEM_COMPLETE = 9;

	/** Aktiver Effekt; {@code remaining} wird je Client-Tick verringert (nur Anzeige), −1 = unendlich. */
	public record Effect(int id, int amplifier, int duration, int flags, int remaining) {
		public String name() {
			return id >= 0 && id < EFFECT_NAMES.length ? EFFECT_NAMES[id] : "effect#" + id;
		}
	}

	/** Verzehrbares Item (consumables-777.txt). */
	public record Consumable(String item, int ticks, int nutrition, float saturation, boolean canAlwaysEat, String animation) {
		public boolean isFood() {
			return this.nutrition > 0 || this.saturation > 0 || this.canAlwaysEat;
		}
	}

	/** Unveränderliche Momentaufnahme für HUD, Terminal und Tests. */
	public record View(float health, float maxHealth, float absorption, int food, float saturation, double armor, double toughness,
		double attackDamage, double attackSpeed, double knockbackResistance, List<Effect> effects, boolean dead, String deathMessage,
		int deaths, int respawnsRequested, float attackStrength, boolean usingItem, int useHand, int useTicksLeft, String lastDamageType,
		long lastDamageTick, int damageEvents, int hurtTicks, int xpLevel, float xpProgress, boolean immediateRespawn, long tick, int air,
		int swingType, int swingHand, float swingProgress, int swings) {
	}

	/** Schwungtyp ({@code SwingAnimationType}): 0 NONE, 1 WHACK, 2 STAB. */
	public static final int SWING_NONE = 0;
	public static final int SWING_WHACK = 1;
	public static final int SWING_STAB = 2;
	private static final int EFFECT_HASTE = 2;
	private static final int EFFECT_MINING_FATIGUE = 3;
	private static final int EFFECT_CONDUIT_POWER = 28;

	/**
	 * Item-Aktionen aus {@code item-actions-777.txt}: abweichende Schwunganimationen ({@code attack_animation}/{@code interact_animation}),
	 * Stichwaffen und haltbare Benutzung ohne Verzehr (Schild, Speere, Dreizack, Fernrohr, Pinsel; {@code useTicks} > 0).
	 */
	private record ItemSwing(int[] attack, int[] interact, boolean piercing, int useTicks) {
	}

	private static volatile Map<String, ItemSwing> swingTable;

	private static volatile Map<String, Consumable> consumables;

	private float health = 20.0F;
	private float maxHealth = 20.0F;
	private float absorption;
	private int food = 20;
	private float saturation = 5.0F;
	private final Map<Integer, Double> attributes = new HashMap<>();
	private final Map<Integer, Effect> effects = new LinkedHashMap<>();
	private boolean dead;
	private String deathMessage = "";
	/** Todesnachricht als Text-Komponente (für die Übersetzung im Grafikclient). */
	private Object deathComponent;
	private int deaths;
	private int respawnsRequested;
	private boolean immediateRespawn;
	private int livingFlags;
	private int air = 300;
	// Vorhersage für die Darstellung
	private int attackStrengthTicker = 1000;
	private boolean usingItem;
	private int useHand;
	private int useTicksLeft;
	private String lastDamageType = "";
	private long lastDamageTick = Long.MIN_VALUE;
	private int damageEvents;
	private int hurtTicks;
	private int xpLevel;
	private float xpProgress;
	private long tick;
	private final ArrayDeque<String> log = new ArrayDeque<>();
	private final Map<String, Integer> cooldowns = new HashMap<>();
	// eigener Armschwung (LivingEntity.SwingState): nur Darstellung, der Server sendet ihn anderen Spielern selbst
	private boolean swinging;
	private int swingType;
	private int swingHand;
	private int swingDuration;
	private int swingTicks;
	private float swingAnim;
	private float swingAnimO;
	private int swings;

	public static Map<String, Consumable> consumables() {
		Map<String, Consumable> c = consumables;
		if (c == null) {
			Map<String, Consumable> m = new HashMap<>();
			try (InputStream in = PlayerSurvivalState.class.getResourceAsStream("/protocol/consumables-777.txt")) {
				if (in == null) {
					throw new IllegalStateException("resource /protocol/consumables-777.txt missing");
				}
				BufferedReader r = new BufferedReader(new InputStreamReader(in, StandardCharsets.UTF_8));
				String line;
				while ((line = r.readLine()) != null) {
					if (line.startsWith("#") || line.isBlank()) {
						continue;
					}
					String[] p = line.split("\t");
					m.put(p[0], new Consumable(p[0], Integer.parseInt(p[1]), Integer.parseInt(p[2]), Float.parseFloat(p[3]), Boolean.parseBoolean(p[4]), p[5]));
				}
			} catch (IOException e) {
				throw new UncheckedIOException(e);
			}
			c = Map.copyOf(m);
			consumables = c;
		}
		return c;
	}

	// ================= Serverpakete =================

	synchronized void onHealth(final float h, final int f, final float s) {
		if (h < this.health) {
			this.hurtTicks = 10;
		}
		this.health = h;
		this.food = f;
		this.saturation = s;
		event(String.format(java.util.Locale.ROOT, "set_health %.2f food %d sat %.2f", h, f, s));
		if (h > 0.0F && this.dead) {
			// Wiederbelebt (Server setzt Gesundheit nach respawn)
			this.dead = false;
		} else if (h <= 0.0F && !this.dead) {
			// isDeadOrDying() hängt in Vanilla nur an der Gesundheit; nach erneutem Login im Todeszustand kommt kein player_combat_kill
			this.dead = true;
			this.usingItem = false;
			this.deathMessage = ""; // player_combat_kill setzt die Nachricht gleich danach, falls er kommt
			this.deathComponent = null;
			event("dead by set_health (no death message)");
		}
	}

	synchronized void onAttributes(final Map<Integer, Double> values) {
		this.attributes.putAll(values);
		Double mh = values.get(ATTR_MAX_HEALTH);
		if (mh != null) {
			this.maxHealth = mh.floatValue();
		}
	}

	/** Eigene Datenfelder: 8 Lebewesen-Flags (0x01 benutzt Gegenstand), 17 Absorption. */
	synchronized void onOwnData(final int index, final Object value) {
		if (index == 1 && value instanceof Integer a) {
			this.air = a; // Luftvorrat (Entity-Datenfeld 1, max. 300)
		} else if (index == 17 && value instanceof Float f) {
			this.absorption = f;
		} else if (index == 8 && value instanceof Byte b) {
			int old = this.livingFlags;
			this.livingFlags = b & 0xFF;
			// LivingEntity#onSyncedDataUpdated: beendet der Server die Benutzung (z. B. Schild durch eine Axt deaktiviert), endet sie auch hier
			if ((old & 1) != 0 && (this.livingFlags & 1) == 0 && this.usingItem) {
				this.usingItem = false;
				event("use stopped by the server (living flags)");
			}
		}
	}

	/** {@code cooldown}: Abklingzeit einer Item-Gruppe (Standard: Item-ID), 0 entfernt sie ({@code ItemCooldowns}). */
	synchronized void onCooldown(final String group, final int duration) {
		if (duration <= 0) {
			this.cooldowns.remove(group);
		} else {
			this.cooldowns.put(group, duration);
		}
		event("cooldown " + group + " " + duration);
	}

	/** {@code ItemCooldowns#isOnCooldown} mit der Item-ID als Gruppe ({@code use_cooldown}-Gruppen werden nicht ausgewertet). */
	public synchronized boolean onCooldown(final String item) {
		return item != null && this.cooldowns.getOrDefault(item, 0) > 0;
	}

	synchronized void onEffect(final int id, final int amplifier, final int duration, final int flags) {
		this.effects.put(id, new Effect(id, amplifier, duration, flags, duration));
		event("effect + " + (id >= 0 && id < EFFECT_NAMES.length ? EFFECT_NAMES[id] : id) + " amp " + amplifier + " dur " + duration);
	}

	synchronized void onEffectRemoved(final int id) {
		this.effects.remove(id);
		event("effect - " + (id >= 0 && id < EFFECT_NAMES.length ? EFFECT_NAMES[id] : id));
	}

	synchronized void onDamage(final String type) {
		this.lastDamageType = type;
		this.lastDamageTick = this.tick;
		this.damageEvents++;
		this.hurtTicks = 10;
		event("damage_event " + type);
	}

	synchronized void onEntityEvent(final int ev) {
		if (ev == USE_ITEM_COMPLETE) {
			this.usingItem = false;
			event("use_item_complete");
		}
	}

	synchronized void onDeath(final String message, final Object component) {
		this.dead = true; // kann schon durch set_health 0 gesetzt sein
		this.deathMessage = message;
		this.deathComponent = component;
		this.deaths++;
		this.usingItem = false;
		event("death: " + message);
	}

	synchronized void onRespawnPacket() {
		this.effects.clear(); // der Server sendet weiterbestehende Effekte neu
		this.usingItem = false;
		this.absorption = 0;
		this.air = 300;
		event("respawn packet");
	}

	synchronized void onImmediateRespawn(final boolean immediate) {
		this.immediateRespawn = immediate;
	}

	synchronized void onExperience(final float progress, final int level) {
		this.xpProgress = progress;
		this.xpLevel = level;
	}

	synchronized void respawnRequested() {
		this.respawnsRequested++;
		event("client_command PERFORM_RESPAWN");
	}

	// ================= Vorhersage (Darstellung) =================

	/** {@code resetAttackStrengthTicker}: Angriff, Fehlschlag, Hotbar-Wechsel. */
	synchronized void resetAttackStrength() {
		this.attackStrengthTicker = 0;
	}

	synchronized void startUsing(final int hand, final int ticks) {
		this.usingItem = true;
		this.useHand = hand;
		this.useTicksLeft = ticks;
		event("start using hand " + hand + " for " + ticks + " ticks");
	}

	synchronized void stopUsing(final String why) {
		if (this.usingItem) {
			this.usingItem = false;
			event("stop using: " + why);
		}
	}

	synchronized boolean usingItem() {
		return this.usingItem;
	}

	/** Ein Client-Tick: Angriffsstärke zählt hoch, Benutzung und Effekt-Restdauer zählen herunter. */
	synchronized void tick() {
		this.tick++;
		this.attackStrengthTicker++;
		this.cooldowns.replaceAll((k, v) -> v - 1);
		this.cooldowns.values().removeIf(v -> v <= 0);
		this.swingAnimO = this.swingAnim;
		if (this.swinging) {
			if (this.swingDuration > 0) {
				this.swingAnim = Math.min((float) this.swingTicks / this.swingDuration, 1.0F);
			}
			if (this.swingTicks++ > this.swingDuration) {
				this.swinging = false;
				this.swingAnim = 0.0F;
			}
		} else {
			this.swingAnim = 0.0F;
		}
		if (this.hurtTicks > 0) {
			this.hurtTicks--;
		}
		if (this.usingItem && this.useTicksLeft > 0) {
			this.useTicksLeft--;
			if (this.useTicksLeft == 0) {
				this.usingItem = false; // LocalPlayer#completeUsingItem: clientseitig fertig, der Server bestätigt mit entity_event 9
			}
		}
		for (Map.Entry<Integer, Effect> e : this.effects.entrySet()) {
			Effect x = e.getValue();
			if (x.remaining() > 0) {
				e.setValue(new Effect(x.id(), x.amplifier(), x.duration(), x.flags(), x.remaining() - 1));
			}
		}
	}

	/** {@code getCurrentItemAttackStrengthDelay}: 20 / attack_speed Ticks. */
	public synchronized float attackDelay() {
		double speed = this.attributes.getOrDefault(ATTR_ATTACK_SPEED, 4.0);
		return (float) (1.0 / speed * 20.0);
	}

	/** {@code getAttackStrengthScale(a)}. */
	public synchronized float attackStrength(final float partialTick) {
		return Math.clamp((this.attackStrengthTicker + partialTick) / attackDelay(), 0.0F, 1.0F);
	}

	/** Attributwert (vom Server, inkl. Modifikatoren) oder Standardwert. */
	public synchronized double attribute(final int id, final double def) {
		return this.attributes.getOrDefault(id, def);
	}

	/** Verstärker eines aktiven Effekts oder −1. */
	public synchronized int effectAmplifier(final int id) {
		Effect e = this.effects.get(id);
		return e == null ? -1 : e.amplifier();
	}

	public synchronized Object deathComponent() {
		return this.deathComponent;
	}

	public synchronized float health() {
		return this.health;
	}

	public synchronized int food() {
		return this.food;
	}

	public synchronized boolean dead() {
		return this.dead;
	}

	private static Map<String, ItemSwing> swingTable() {
		Map<String, ItemSwing> t = swingTable;
		if (t == null) {
			Map<String, ItemSwing> m = new HashMap<>();
			try (InputStream in = PlayerSurvivalState.class.getResourceAsStream("/protocol/item-actions-777.txt")) {
				if (in == null) {
					throw new IllegalStateException("resource /protocol/item-actions-777.txt missing");
				}
				BufferedReader r = new BufferedReader(new InputStreamReader(in, StandardCharsets.UTF_8));
				String line;
				while ((line = r.readLine()) != null) {
					if (line.startsWith("#") || line.isBlank()) {
						continue;
					}
					String[] p = line.split("\t");
					ItemSwing old = m.getOrDefault(p[0], new ItemSwing(null, null, false, 0));
					int[] a = {switch (p[2]) {
						case "none" -> SWING_NONE;
						case "stab" -> SWING_STAB;
						default -> SWING_WHACK;
					}, Integer.parseInt(p[3])};
					m.put(p[0], switch (p[1]) {
						case "attack" -> new ItemSwing(a, old.interact(), old.piercing(), old.useTicks());
						case "interact" -> new ItemSwing(old.attack(), a, old.piercing(), old.useTicks());
						case "use" -> new ItemSwing(old.attack(), old.interact(), old.piercing(), Integer.parseInt(p[3]));
						default -> new ItemSwing(old.attack(), old.interact(), true, old.useTicks());
					});
				}
			} catch (IOException e) {
				throw new UncheckedIOException(e);
			}
			t = Map.copyOf(m);
			swingTable = t;
		}
		return t;
	}

	/** {@code ItemStack#getAttackAnimation}/{@code getInteractAnimation}: {Typ, Dauer}; Standard WHACK, 6 Ticks. */
	public static int[] swingAnimation(final String item, final boolean attack) {
		ItemSwing s = item == null ? null : swingTable().get(item);
		int[] a = s == null ? null : attack ? s.attack() : s.interact();
		return a == null ? new int[]{SWING_WHACK, 6} : a.clone();
	}

	/** Haltbare Benutzung ohne Verzehr ({@code Item#getUseDuration}, z. B. Schild 72000): Ticks, 0 = keine. */
	public static int holdUseTicks(final String item) {
		ItemSwing s = item == null ? null : swingTable().get(item);
		return s == null ? 0 : s.useTicks();
	}

	/** Item mit {@code piercing_weapon} (Speere): Vanilla greift über {@code piercingAttack} an, nicht über {@code attack}. */
	public static boolean piercingWeapon(final String item) {
		ItemSwing s = item == null ? null : swingTable().get(item);
		return s != null && s.piercing();
	}

	/**
	 * {@code LivingEntity#swing} → {@code SwingState#startIfAble} mit {@code getModifiedSwingDuration}: ein laufender Schwung wird erst nach
	 * seiner halben Dauer neu gestartet. Eile/Meereskraft verkürzen, Abbaulähmung verlängert die Dauer.
	 */
	public synchronized boolean swing(final int hand, final String item, final boolean attack) {
		int[] a = swingAnimation(item, attack);
		int duration = modifiedSwingDuration(a[1]);
		if (this.swinging && this.swingTicks <= this.swingDuration / 2 && this.swingTicks > 0) {
			return false;
		}
		if (!(this.swinging && this.swingType == a[0] && this.swingDuration == duration && this.swingHand == hand)) {
			this.swingAnim = 0.0F;
			this.swingAnimO = 0.0F;
		}
		this.swinging = true;
		this.swingType = a[0];
		this.swingHand = hand;
		this.swingDuration = duration;
		this.swingTicks = 0;
		this.swings++;
		return true;
	}

	private int modifiedSwingDuration(final int duration) {
		int haste = effectAmplifier(EFFECT_HASTE), conduit = effectAmplifier(EFFECT_CONDUIT_POWER);
		if (haste >= 0 || conduit >= 0) {
			return duration - (1 + Math.max(Math.max(haste, conduit), 0));
		}
		int fatigue = effectAmplifier(EFFECT_MINING_FATIGUE);
		return fatigue >= 0 ? duration + (1 + fatigue) * 2 : duration;
	}

	/** {@code SwingState#getAnimation}: 0..1 im Schwung, 0 sonst. */
	private float swingProgress(final float pt) {
		return this.swingAnim >= this.swingAnimO ? this.swingAnimO + (this.swingAnim - this.swingAnimO) * pt
			: this.swingAnimO + (this.swingAnim + 1.0F - this.swingAnimO) * pt;
	}

	public synchronized boolean invulnerableOrCanEat(final boolean creative, final boolean canAlwaysEat) {
		return creative || canAlwaysEat || this.food < 20;
	}

	private void event(final String s) {
		this.log.addLast(this.tick + " " + s);
		while (this.log.size() > 200) {
			this.log.removeFirst();
		}
	}

	public synchronized List<String> log() {
		return new ArrayList<>(this.log);
	}

	public synchronized View view(final float partialTick) {
		return new View(this.health, this.maxHealth, this.absorption, this.food, this.saturation, this.attributes.getOrDefault(ATTR_ARMOR, 0.0),
			this.attributes.getOrDefault(ATTR_ARMOR_TOUGHNESS, 0.0), this.attributes.getOrDefault(ATTR_ATTACK_DAMAGE, 1.0),
			this.attributes.getOrDefault(ATTR_ATTACK_SPEED, 4.0), this.attributes.getOrDefault(ATTR_KNOCKBACK_RESISTANCE, 0.0),
			List.copyOf(this.effects.values()), this.dead, this.deathMessage, this.deaths, this.respawnsRequested, attackStrength(partialTick), this.usingItem,
			this.useHand, this.useTicksLeft, this.lastDamageType, this.lastDamageTick, this.damageEvents, this.hurtTicks, this.xpLevel, this.xpProgress,
			this.immediateRespawn, this.tick, this.air, this.swinging ? this.swingType : SWING_NONE, this.swingHand, swingProgress(partialTick), this.swings);
	}
}
