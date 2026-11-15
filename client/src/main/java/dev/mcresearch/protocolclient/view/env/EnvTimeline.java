package dev.mcresearch.protocolclient.view.env;

import java.util.ArrayList;
import java.util.EnumMap;
import java.util.List;
import java.util.Map;

/**
 * Eine Zeitleiste ({@code data/minecraft/timeline/*.json}): je Attribut eine Keyframe-Spur, deren Argument zur Uhrzeit der
 * zugehörigen Weltuhr abgetastet und mit dem Modifikator auf den bisherigen Wert angewendet wird (docs/48 §1.3).
 *
 * <p>Abtastung wie in 26.3: Mit Periode wird die Zeit modulo Periode genommen und vor dem ersten bzw. nach dem letzten Keyframe
 * über die Periodengrenze zum jeweils anderen Ende interpoliert; zwischen zwei Keyframes wird der Anteil mit der Easing-Funktion
 * der Spur verformt und das Argument linear gemischt.
 */
public final class EnvTimeline {
	record Keyframe(int ticks, float[] value) {
	}

	record Segment(float[] from, int fromTicks, float[] to, int toTicks) {
	}

	/** Eine Spur: Modifikator-Operation, Easing, gebackene Segmente. */
	static final class Track {
		final EnvAttr attr;
		final EnvModifier.Op op;
		final Easing easing;
		final List<Segment> segments;
		final Integer period;

		Track(final EnvAttr attr, final EnvModifier.Op op, final Easing easing, final List<Keyframe> keys, final Integer period) {
			this.attr = attr;
			this.op = op;
			this.easing = easing;
			this.period = period;
			List<Segment> s = new ArrayList<>();
			if (keys.size() == 1) {
				s.add(new Segment(keys.getFirst().value(), 0, keys.getFirst().value(), 0));
				this.segments = List.copyOf(s);
				return;
			}
			if (period != null) {
				Keyframe first = keys.getFirst();
				Keyframe last = keys.getLast();
				s.add(new Segment(last.value(), last.ticks() - period, first.value(), first.ticks()));
				addInner(keys, s);
				s.add(new Segment(last.value(), last.ticks(), first.value(), first.ticks() + period));
			} else {
				addInner(keys, s);
			}
			this.segments = List.copyOf(s);
		}

		private static void addInner(final List<Keyframe> keys, final List<Segment> out) {
			for (int i = 0; i < keys.size() - 1; i++) {
				out.add(new Segment(keys.get(i).value(), keys.get(i).ticks(), keys.get(i + 1).value(), keys.get(i + 1).ticks()));
			}
		}

		float[] sampleArgument(final long ticks) {
			long t = this.period != null ? Math.floorMod(ticks, this.period) : ticks;
			Segment seg = this.segments.getLast();
			for (Segment s : this.segments) {
				if (t < s.toTicks()) {
					seg = s;
					break;
				}
			}
			if (t <= seg.fromTicks()) {
				return seg.from().clone();
			}
			if (t >= seg.toTicks()) {
				return seg.to().clone();
			}
			float alpha = (float) (t - seg.fromTicks()) / (seg.toTicks() - seg.fromTicks());
			return EnvModifier.lerpArgument(this.attr, this.easing.apply(alpha), seg.from(), seg.to());
		}

		float[] apply(final float[] base, final long ticks) {
			return new EnvModifier(this.op, sampleArgument(ticks)).apply(this.attr, base);
		}
	}

	private final String name;
	private final String clock;
	private final Map<EnvAttr, Track> tracks;

	private EnvTimeline(final String name, final String clock, final Map<EnvAttr, Track> tracks) {
		this.name = name;
		this.clock = clock;
		this.tracks = tracks;
	}

	public String name() {
		return this.name;
	}

	public String clock() {
		return this.clock;
	}

	/** Wendet die Spur dieses Attributs an (falls vorhanden). */
	float[] apply(final EnvAttr attr, final float[] base, final long clockTicks) {
		Track t = this.tracks.get(attr);
		return t == null ? base : t.apply(base, clockTicks);
	}

	boolean has(final EnvAttr attr) {
		return this.tracks.containsKey(attr);
	}

	@SuppressWarnings("unchecked")
	static EnvTimeline parse(final String name, final Map<String, Object> json) {
		String clock = json.get("clock") instanceof String c ? c : "minecraft:overworld";
		Integer period = json.get("period_ticks") instanceof Number n ? n.intValue() : null;
		Map<EnvAttr, Track> tracks = new EnumMap<>(EnvAttr.class);
		if (json.get("tracks") instanceof Map<?, ?> tm) {
			for (Map.Entry<?, ?> e : tm.entrySet()) {
				EnvAttr attr = EnvAttr.byId(String.valueOf(e.getKey()));
				if (attr == null) {
					continue; // nicht darstellungsrelevant (Spielmechanik, Klang)
				}
				Map<String, Object> t = (Map<String, Object>) e.getValue();
				EnvModifier.Op op = t.get("modifier") instanceof String m ? EnvModifier.Op.byName(m) : EnvModifier.Op.OVERRIDE;
				Easing easing = Easing.parse(t.get("ease"));
				List<Keyframe> keys = new ArrayList<>();
				for (Object k : (List<Object>) t.get("keyframes")) {
					Map<String, Object> km = (Map<String, Object>) k;
					keys.add(new Keyframe(((Number) km.get("ticks")).intValue(), EnvModifier.parseArgument(attr, op, km.get("value"))));
				}
				tracks.put(attr, new Track(attr, op, easing, keys, period));
			}
		}
		return new EnvTimeline(name, clock, tracks);
	}

	/** Easing einer Spur: {@code linear} (Vorgabe), {@code constant}, Kubik-Bézier und die gängigen benannten Kurven. */
	public interface Easing {
		Easing LINEAR = x -> x;
		Easing CONSTANT = x -> 0f;

		float apply(float x);

		@SuppressWarnings("unchecked")
		static Easing parse(final Object json) {
			if (json == null) {
				return LINEAR;
			}
			if (json instanceof Map<?, ?> m && m.get("cubic_bezier") instanceof List<?> l) {
				return new CubicBezier(((Number) l.get(0)).floatValue(), ((Number) l.get(1)).floatValue(), ((Number) l.get(2)).floatValue(),
					((Number) l.get(3)).floatValue());
			}
			String s = String.valueOf(json);
			s = s.startsWith("minecraft:") ? s.substring(10) : s;
			return switch (s) {
				case "linear" -> LINEAR;
				case "constant" -> CONSTANT;
				case "in_quad" -> x -> x * x;
				case "out_quad" -> x -> 1 - (1 - x) * (1 - x);
				case "in_out_quad" -> x -> x < 0.5f ? 2 * x * x : 1 - (float) Math.pow(-2 * x + 2, 2) / 2;
				case "in_cubic" -> x -> x * x * x;
				case "out_cubic" -> x -> 1 - (float) Math.pow(1 - x, 3);
				case "in_out_cubic" -> x -> x < 0.5f ? 4 * x * x * x : 1 - (float) Math.pow(-2 * x + 2, 3) / 2;
				case "in_sine" -> x -> 1 - (float) Math.cos(x * Math.PI / 2);
				case "out_sine" -> x -> (float) Math.sin(x * Math.PI / 2);
				case "in_out_sine" -> x -> -((float) Math.cos(Math.PI * x) - 1) / 2;
				default -> throw new IllegalArgumentException("unsupported easing " + json);
			};
		}
	}

	/**
	 * Kubische Bézierkurve mit P0 = (0,0), P3 = (1,1) und den Kontrollpunkten (x1,y1), (x2,y2): zu x wird der Kurvenparameter
	 * per Newton-Verfahren (Rückfall Bisektion) bestimmt und y ausgewertet – das übliche Verfahren für CSS-artige Easings.
	 */
	record CubicBezier(float x1, float y1, float x2, float y2) implements Easing {
		private static double curve(final double t, final double p1, final double p2) {
			double u = 1 - t;
			return 3 * u * u * t * p1 + 3 * u * t * t * p2 + t * t * t;
		}

		private static double slope(final double t, final double p1, final double p2) {
			double u = 1 - t;
			return 3 * u * u * p1 + 6 * u * t * (p2 - p1) + 3 * t * t * (1 - p2);
		}

		@Override
		public float apply(final float x) {
			if (x <= 0f) {
				return 0f;
			}
			if (x >= 1f) {
				return 1f;
			}
			double t = x;
			for (int i = 0; i < 8; i++) {
				double err = curve(t, this.x1, this.x2) - x;
				if (Math.abs(err) < 1e-7) {
					return (float) curve(t, this.y1, this.y2);
				}
				double d = slope(t, this.x1, this.x2);
				if (Math.abs(d) < 1e-6) {
					break;
				}
				t -= err / d;
				if (t < 0 || t > 1) {
					break;
				}
			}
			double lo = 0;
			double hi = 1;
			t = x;
			for (int i = 0; i < 60; i++) {
				double v = curve(t, this.x1, this.x2);
				if (Math.abs(v - x) < 1e-9) {
					break;
				}
				if (v < x) {
					lo = t;
				} else {
					hi = t;
				}
				t = (lo + hi) / 2;
			}
			return (float) curve(t, this.y1, this.y2);
		}
	}
}
