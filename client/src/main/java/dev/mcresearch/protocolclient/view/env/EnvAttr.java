package dev.mcresearch.protocolclient.view.env;

/**
 * Die für die Darstellung benötigten Umgebungsattribute von 26.3 (docs/48 §1). Werte als {@code float[]}: Gleitkomma/Winkel/
 * Mondphase Länge 1, RGB Länge 3 (0…1), ARGB Länge 4 in der Reihenfolge r, g, b, a. Vorgaben und Wertebereiche wie in
 * {@code EnvironmentAttributes}; die Quelle der Werte sind die Datendateien (dimension_type, worldgen/biome, timeline) des
 * lokalen Spiel-JARs.
 */
public enum EnvAttr {
	FOG_COLOR("visual/fog_color", Type.RGB, rgb(0x000000), Range.NONE, true),
	FOG_START_DISTANCE("visual/fog_start_distance", Type.FLOAT, new float[]{0f}, Range.NONE, true),
	FOG_END_DISTANCE("visual/fog_end_distance", Type.FLOAT, new float[]{1024f}, Range.NON_NEGATIVE, true),
	SKY_FOG_END_DISTANCE("visual/sky_fog_end_distance", Type.FLOAT, new float[]{512f}, Range.NON_NEGATIVE, true),
	CLOUD_FOG_END_DISTANCE("visual/cloud_fog_end_distance", Type.FLOAT, new float[]{2048f}, Range.NON_NEGATIVE, true),
	WATER_FOG_COLOR("visual/water_fog_color", Type.RGB, rgb(0x050533), Range.NONE, true),
	WATER_FOG_START_DISTANCE("visual/water_fog_start_distance", Type.FLOAT, new float[]{-8f}, Range.NONE, true),
	WATER_FOG_END_DISTANCE("visual/water_fog_end_distance", Type.FLOAT, new float[]{96f}, Range.NON_NEGATIVE, true),
	SKY_COLOR("visual/sky_color", Type.RGB, rgb(0x000000), Range.NONE, true),
	SUNRISE_SUNSET_COLOR("visual/sunrise_sunset_color", Type.ARGB, new float[]{0, 0, 0, 0}, Range.NONE, true),
	CLOUD_COLOR("visual/cloud_color", Type.ARGB, new float[]{0, 0, 0, 0}, Range.NONE, true),
	CLOUD_HEIGHT("visual/cloud_height", Type.FLOAT, new float[]{192.33f}, Range.NONE, true),
	SUN_ANGLE("visual/sun_angle", Type.ANGLE, new float[]{0f}, Range.NONE, true),
	MOON_ANGLE("visual/moon_angle", Type.ANGLE, new float[]{0f}, Range.NONE, true),
	STAR_ANGLE("visual/star_angle", Type.ANGLE, new float[]{0f}, Range.NONE, true),
	MOON_PHASE("visual/moon_phase", Type.MOON_PHASE, new float[]{0f}, Range.NONE, false),
	STAR_BRIGHTNESS("visual/star_brightness", Type.FLOAT, new float[]{0f}, Range.UNIT, true),
	BLOCK_LIGHT_TINT("visual/block_light_tint", Type.RGB, rgb(0xFFD88C), Range.NONE, true),
	SKY_LIGHT_COLOR("visual/sky_light_color", Type.RGB, rgb(0xFFFFFF), Range.NONE, true),
	SKY_LIGHT_FACTOR("visual/sky_light_factor", Type.FLOAT, new float[]{1f}, Range.UNIT, true),
	NIGHT_VISION_COLOR("visual/night_vision_color", Type.RGB, rgb(0x999999), Range.NONE, true),
	AMBIENT_LIGHT_COLOR("visual/ambient_light_color", Type.RGB, rgb(0x000000), Range.NONE, true);

	/** Werttyp; bestimmt Argumentformat, Modifikatoren und Interpolation. */
	public enum Type {
		FLOAT, ANGLE, RGB, ARGB, MOON_PHASE
	}

	enum Range {
		NONE, UNIT, NON_NEGATIVE
	}

	/** Mondphasen in der Reihenfolge der Zeitleiste {@code minecraft:moon}. */
	public static final String[] MOON_PHASES = {"full_moon", "waning_gibbous", "third_quarter", "waning_crescent", "new_moon", "waxing_crescent",
		"first_quarter", "waxing_gibbous"};

	public final String id;
	public final Type type;
	private final float[] defaultValue;
	final Range range;
	/** räumlich interpoliert (Gauß-Abtastung der Biome um die Kamera) statt Biom an der Position */
	public final boolean spatial;

	EnvAttr(final String id, final Type type, final float[] defaultValue, final Range range, final boolean spatial) {
		this.id = "minecraft:" + id;
		this.type = type;
		this.defaultValue = defaultValue;
		this.range = range;
		this.spatial = spatial;
	}

	public float[] defaultValue() {
		return this.defaultValue.clone();
	}

	public static EnvAttr byId(final String id) {
		String full = id.contains(":") ? id : "minecraft:" + id;
		for (EnvAttr a : values()) {
			if (a.id.equals(full)) {
				return a;
			}
		}
		return null;
	}

	/** Wertebereich wie {@code EnvironmentAttribute#sanitizeValue}. */
	float[] sanitize(final float[] v) {
		switch (this.range) {
			case UNIT -> v[0] = Math.max(0f, Math.min(1f, v[0]));
			case NON_NEGATIVE -> v[0] = Math.max(0f, v[0]);
			default -> {
			}
		}
		return v;
	}

	static float[] rgb(final int rgb) {
		return new float[]{(rgb >> 16 & 255) / 255f, (rgb >> 8 & 255) / 255f, (rgb & 255) / 255f};
	}

	/** Interpolation zwischen zwei Werten (Keyframes, Wetterübergang, räumlich): linear je Komponente, Mondphase in Stufen. */
	float[] lerp(final float t, final float[] a, final float[] b) {
		if (this.type == Type.MOON_PHASE) {
			return (t >= 1f ? b : a).clone();
		}
		float[] r = new float[a.length];
		for (int i = 0; i < a.length; i++) {
			r[i] = a[i] + t * (b[i] - a[i]);
		}
		return r;
	}

	/** Interpolation zwischen zwei Client-Ticks; Winkel über den kürzeren Weg, ab 90° Sprung (LerpFunction.ofDegrees(90)). */
	public float[] partialTickLerp(final float t, final float[] a, final float[] b) {
		if (this.type == Type.ANGLE) {
			float delta = wrapDegrees(b[0] - a[0]);
			return new float[]{Math.abs(delta) >= 90f ? b[0] : a[0] + t * delta};
		}
		if (this.type == Type.MOON_PHASE) {
			return (t >= 0f ? b : a).clone();
		}
		return lerp(t, a, b);
	}

	static float wrapDegrees(final float d) {
		float r = d % 360f;
		if (r >= 180f) {
			r -= 360f;
		}
		if (r < -180f) {
			r += 360f;
		}
		return r;
	}
}
