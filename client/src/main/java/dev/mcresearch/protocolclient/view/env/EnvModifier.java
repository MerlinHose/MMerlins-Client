package dev.mcresearch.protocolclient.view.env;

import java.util.Map;

/**
 * Ein Attributmodifikator ({@code {"modifier": op, "argument": …}} oder ein direkter Wert = {@code override}), docs/48 §1.2.
 * Argumente als {@code float[]}: Farbe/Zahl wie der Attributwert; {@code alpha_blend} auf Zahlen {value, alpha};
 * {@code blend_to_gray} {brightness, factor}. Eigene Umsetzung der in 26.3 definierten Operationen.
 */
public record EnvModifier(Op op, float[] argument) {
	public enum Op {
		OVERRIDE, ALPHA_BLEND, ADD, SUBTRACT, MULTIPLY, BLEND_TO_GRAY, MINIMUM, MAXIMUM;

		static Op byName(final String name) {
			return switch (name) {
				case "override" -> OVERRIDE;
				case "alpha_blend" -> ALPHA_BLEND;
				case "add" -> ADD;
				case "subtract" -> SUBTRACT;
				case "multiply" -> MULTIPLY;
				case "blend_to_gray" -> BLEND_TO_GRAY;
				case "minimum" -> MINIMUM;
				case "maximum" -> MAXIMUM;
				default -> throw new IllegalArgumentException("unsupported modifier " + name);
			};
		}
	}

	public EnvModifier {
		argument = argument.clone();
	}

	public static EnvModifier override(final float[] value) {
		return new EnvModifier(Op.OVERRIDE, value);
	}

	/** Wendet den Modifikator auf {@code subject} an (neues Array). */
	public float[] apply(final EnvAttr attr, final float[] subject) {
		float[] a = this.argument;
		return switch (this.op) {
			case OVERRIDE -> a.clone();
			case MULTIPLY -> {
				float[] r = subject.clone();
				for (int i = 0; i < Math.min(r.length, a.length); i++) {
					r[i] *= a[i];
				}
				yield r;
			}
			case ADD, SUBTRACT -> {
				float[] r = subject.clone();
				boolean color = attr.type == EnvAttr.Type.RGB || attr.type == EnvAttr.Type.ARGB;
				int n = color ? 3 : 1;
				for (int i = 0; i < n; i++) {
					r[i] = this.op == Op.ADD ? r[i] + a[i] : r[i] - a[i];
					if (color) {
						r[i] = Math.max(0f, Math.min(1f, r[i]));
					}
				}
				yield r;
			}
			case MINIMUM -> new float[]{Math.min(subject[0], a[0])};
			case MAXIMUM -> new float[]{Math.max(subject[0], a[0])};
			case ALPHA_BLEND -> alphaBlend(attr, subject, a);
			case BLEND_TO_GRAY -> {
				// Graustufe (0,3/0,59/0,11) × Helligkeit, dann linear um factor dorthin
				float g = (subject[0] * 0.3f + subject[1] * 0.59f + subject[2] * 0.11f) * a[0];
				float[] r = subject.clone();
				for (int i = 0; i < 3; i++) {
					r[i] = subject[i] + a[1] * (g - subject[i]);
				}
				yield r;
			}
		};
	}

	private static float[] alphaBlend(final EnvAttr attr, final float[] dst, final float[] src) {
		switch (attr.type) {
			case FLOAT, ANGLE -> {
				return new float[]{dst[0] + src[1] * (src[0] - dst[0])}; // {value, alpha}
			}
			case RGB -> {
				float sa = src[3];
				if (sa == 0f) {
					return dst.clone();
				}
				return new float[]{dst[0] + (src[0] - dst[0]) * sa, dst[1] + (src[1] - dst[1]) * sa, dst[2] + (src[2] - dst[2]) * sa};
			}
			case ARGB -> {
				float da = dst[3];
				float sa = src[3];
				if (sa == 1f) {
					return src.clone();
				}
				if (sa == 0f) {
					return dst.clone();
				}
				float alpha = sa + da * (1f - sa);
				float[] r = new float[4];
				for (int i = 0; i < 3; i++) {
					r[i] = (src[i] * sa + dst[i] * (alpha - sa)) / alpha;
				}
				r[3] = alpha;
				return r;
			}
			default -> {
				return dst.clone();
			}
		}
	}

	/** Argument-Interpolation zwischen Keyframes (Komponenten linear, Mondphase in Stufen). */
	static float[] lerpArgument(final EnvAttr attr, final float t, final float[] a, final float[] b) {
		if (attr.type == EnvAttr.Type.MOON_PHASE) {
			return (t >= 1f ? b : a).clone();
		}
		float[] r = new float[a.length];
		for (int i = 0; i < a.length; i++) {
			r[i] = a[i] + t * (b[i] - a[i]);
		}
		return r;
	}

	// ----- JSON -----

	/** Liest einen Attributeintrag (direkter Wert oder Modifikatorobjekt). */
	@SuppressWarnings("unchecked")
	static EnvModifier parse(final EnvAttr attr, final Object json) {
		if (json instanceof Map<?, ?> m && m.containsKey("modifier")) {
			Op op = Op.byName(String.valueOf(m.get("modifier")));
			return new EnvModifier(op, parseArgument(attr, op, m.get("argument")));
		}
		return new EnvModifier(Op.OVERRIDE, parseValue(attr, json));
	}

	/** Argument passend zu Operation und Attributtyp (Farbcodecs wie STRING_RGB_VEC3_COLOR/STRING_ARGB_VEC4_COLOR). */
	@SuppressWarnings("unchecked")
	static float[] parseArgument(final EnvAttr attr, final Op op, final Object json) {
		if (op == Op.BLEND_TO_GRAY) {
			Map<String, Object> m = (Map<String, Object>) json;
			return new float[]{num(m.get("brightness")), num(m.get("factor"))};
		}
		if (op == Op.ALPHA_BLEND) {
			if (attr.type == EnvAttr.Type.FLOAT || attr.type == EnvAttr.Type.ANGLE) {
				Map<String, Object> m = (Map<String, Object>) json;
				return new float[]{num(m.get("value")), m.containsKey("alpha") ? num(m.get("alpha")) : 1f};
			}
			return argb(json);
		}
		if (attr.type == EnvAttr.Type.ARGB) {
			// ADD/SUBTRACT nehmen RGB, MULTIPLY/OVERRIDE ARGB
			return op == Op.ADD || op == Op.SUBTRACT ? rgbValue(json) : argb(json);
		}
		return parseValue(attr, json);
	}

	static float[] parseValue(final EnvAttr attr, final Object json) {
		return switch (attr.type) {
			case FLOAT, ANGLE -> new float[]{num(json)};
			case RGB -> rgbValue(json);
			case ARGB -> argb(json);
			case MOON_PHASE -> {
				String s = String.valueOf(json);
				s = s.startsWith("minecraft:") ? s.substring(10) : s;
				for (int i = 0; i < EnvAttr.MOON_PHASES.length; i++) {
					if (EnvAttr.MOON_PHASES[i].equals(s)) {
						yield new float[]{i};
					}
				}
				throw new IllegalArgumentException("unknown moon phase " + json);
			}
		};
	}

	private static float num(final Object o) {
		if (o instanceof Number n) {
			return n.floatValue();
		}
		throw new IllegalArgumentException("number expected: " + o);
	}

	@SuppressWarnings("unchecked")
	static float[] rgbValue(final Object json) {
		if (json instanceof String s && s.startsWith("#")) {
			int c = (int) Long.parseLong(s.substring(1), 16);
			return new float[]{(c >> 16 & 255) / 255f, (c >> 8 & 255) / 255f, (c & 255) / 255f};
		}
		if (json instanceof java.util.List<?> l && l.size() >= 3) {
			return new float[]{num(l.get(0)), num(l.get(1)), num(l.get(2))};
		}
		throw new IllegalArgumentException("rgb colour expected: " + json);
	}

	static float[] argb(final Object json) {
		if (json instanceof String s && s.startsWith("#")) {
			String h = s.substring(1);
			long c = Long.parseLong(h, 16);
			float a = h.length() > 6 ? (c >> 24 & 255) / 255f : 1f;
			return new float[]{(c >> 16 & 255) / 255f, (c >> 8 & 255) / 255f, (c & 255) / 255f, a};
		}
		if (json instanceof java.util.List<?> l && l.size() >= 4) {
			return new float[]{num(l.get(1)), num(l.get(2)), num(l.get(3)), num(l.get(0))};
		}
		throw new IllegalArgumentException("argb colour expected: " + json);
	}
}
