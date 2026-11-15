package dev.mcresearch.protocolclient.physics;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Bewegungsrelevante Spielerattribute (docs/42 §3): Grundwert und Modifikatoren je Attribut wie {@code AttributeInstance}.
 * <ul>
 *   <li>{@code update_attributes} ersetzt Grundwert und alle Modifikatoren eines Attributs ({@code ClientPacketListener#handleUpdateAttributes}).</li>
 *   <li>Der Client setzt den Sprint-Modifikator {@code minecraft:sprinting} (+0,3F, ADD_MULTIPLIED_TOTAL) selbst, wenn er das Sprinten
 *       beginnt oder beendet ({@code LivingEntity#setSprinting}); ein späteres Serverpaket ersetzt ihn wieder.</li>
 *   <li>Wert: ADD_VALUE, dann ADD_MULTIPLIED_BASE, dann ADD_MULTIPLIED_TOTAL; danach auf den Bereich des Attributs begrenzt.</li>
 * </ul>
 * IDs: Reihenfolge des Registers {@code minecraft:attribute} in 26.3.
 */
public final class MovementAttributes {
	public static final int AIR_DRAG_MODIFIER = 0;
	public static final int BOUNCINESS = 9;
	public static final int FRICTION_MODIFIER = 17;
	public static final int GRAVITY = 18;
	public static final int JUMP_STRENGTH = 19;
	public static final int MOVEMENT_EFFICIENCY = 25;
	public static final int MOVEMENT_SPEED = 26;
	public static final int SNEAKING_SPEED = 31;
	public static final int STEP_HEIGHT = 33;
	public static final int WATER_MOVEMENT_EFFICIENCY = 37;
	public static final String SPRINTING_MODIFIER = "minecraft:sprinting";
	public static final double SPRINTING_AMOUNT = 0.3F;

	public record Modifier(String id, double amount, int operation) {
	}

	private record Range(double base, double min, double max) {
	}

	/** Grundwerte des Spielers und Bereiche ({@code Attributes}, {@code Player#createAttributes}). */
	private static final Map<Integer, Range> DEFAULTS = Map.of(
		AIR_DRAG_MODIFIER, new Range(1.0, 0.0, 2048.0),
		BOUNCINESS, new Range(0.0, 0.0, 1.0),
		FRICTION_MODIFIER, new Range(1.0, 0.0, 2048.0),
		GRAVITY, new Range(0.08, -1.0, 1.0),
		JUMP_STRENGTH, new Range(0.42F, 0.0, 32.0),
		MOVEMENT_EFFICIENCY, new Range(0.0, 0.0, 1.0),
		MOVEMENT_SPEED, new Range(0.1F, 0.0, 1024.0),
		SNEAKING_SPEED, new Range(0.3, 0.0, 1.0),
		STEP_HEIGHT, new Range(0.6, 0.0, 10.0),
		WATER_MOVEMENT_EFFICIENCY, new Range(0.0, 0.0, 1.0));

	private static final class Instance {
		double base;
		final LinkedHashMap<String, Modifier> modifiers = new LinkedHashMap<>();
	}

	private final Map<Integer, Instance> instances = new HashMap<>();

	public MovementAttributes() {
		for (Map.Entry<Integer, Range> e : DEFAULTS.entrySet()) {
			Instance i = new Instance();
			i.base = e.getValue().base();
			this.instances.put(e.getKey(), i);
		}
	}

	public static boolean relevant(final int id) {
		return DEFAULTS.containsKey(id);
	}

	/** Serverstand eines Attributs übernehmen (ersetzt Grundwert und Modifikatoren, auch einen lokalen Sprint-Modifikator). */
	public synchronized void setFromServer(final int id, final double base, final List<Modifier> modifiers) {
		Instance i = this.instances.get(id);
		if (i == null) {
			return;
		}
		i.base = base;
		i.modifiers.clear();
		for (Modifier m : modifiers) {
			i.modifiers.putIfAbsent(m.id(), m);
		}
	}

	/** Testzugang/Altschnittstelle: Bewegungsgeschwindigkeit als Grundwert ohne Modifikatoren. */
	public synchronized void setMovementSpeed(final double speed) {
		Instance i = this.instances.get(MOVEMENT_SPEED);
		i.base = speed;
		i.modifiers.clear();
	}

	/** {@code LivingEntity#setSprinting}: Modifikator entfernen und bei Sprinten neu hinzufügen. */
	public synchronized void setSprinting(final boolean sprinting) {
		Instance i = this.instances.get(MOVEMENT_SPEED);
		i.modifiers.remove(SPRINTING_MODIFIER);
		if (sprinting) {
			i.modifiers.put(SPRINTING_MODIFIER, new Modifier(SPRINTING_MODIFIER, SPRINTING_AMOUNT, 2));
		}
	}

	public synchronized boolean hasModifier(final int id, final String modifier) {
		Instance i = this.instances.get(id);
		return i != null && i.modifiers.containsKey(modifier);
	}

	/** {@code AttributeInstance#calculateValue} mit Bereichsbegrenzung ({@code RangedAttribute#sanitizeValue}). */
	public synchronized double value(final int id) {
		Instance i = this.instances.get(id);
		Range r = DEFAULTS.get(id);
		if (i == null) {
			throw new IllegalArgumentException("attribute " + id);
		}
		double b = i.base;
		List<Modifier> all = new ArrayList<>(i.modifiers.values());
		for (Modifier m : all) {
			if (m.operation() == 0) {
				b += m.amount();
			}
		}
		double result = b;
		for (Modifier m : all) {
			if (m.operation() == 1) {
				result += b * m.amount();
			}
		}
		for (Modifier m : all) {
			if (m.operation() == 2) {
				result *= 1.0 + m.amount();
			}
		}
		return Double.isNaN(result) ? r.min() : Math.max(r.min(), Math.min(r.max(), result));
	}
}
