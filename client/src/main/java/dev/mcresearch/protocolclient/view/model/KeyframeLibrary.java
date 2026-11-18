package dev.mcresearch.protocolclient.view.model;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * M14 (docs/55 §4): Keyframe-Animationen aus {@code render/entityanimations-777.txt} ({@code EntityModelExtractor}). Eigene
 * Auswertung: Zeit in Sekunden (bei Schleifen modulo Länge), vorheriger Schlüssel = letzter mit Zeit &lt; t, linear oder
 * Catmull-Rom (Interpolationsart des Folgeschlüssels), Ergebnis wird zur Pose addiert (Drehung, Verschiebung, Skalierung − 1).
 */
public final class KeyframeLibrary {
	record Key(float time, float[] pre, float[] post, boolean catmull) {
	}

	record Channel(String bone, char target, Key[] keys) {
	}

	record Animation(String name, float length, boolean looping, List<Channel> channels) {
	}

	private static volatile KeyframeLibrary instance;
	private final Map<String, Animation> animations;

	private KeyframeLibrary(final Map<String, Animation> animations) {
		this.animations = Map.copyOf(animations);
	}

	public static KeyframeLibrary get() {
		KeyframeLibrary l = instance;
		if (l == null) {
			synchronized (KeyframeLibrary.class) {
				l = instance;
				if (l == null) {
					try (InputStream in = KeyframeLibrary.class.getResourceAsStream("/render/entityanimations-777.txt")) {
						if (in == null) {
							throw new IllegalStateException("render/entityanimations-777.txt missing");
						}
						l = parse(in);
					} catch (IOException e) {
						throw new UncheckedIOException(e);
					}
					instance = l;
				}
			}
		}
		return l;
	}

	public boolean has(final String name) {
		return this.animations.containsKey(name);
	}

	static KeyframeLibrary parse(final InputStream in) throws IOException {
		Map<String, float[]> heads = new LinkedHashMap<>();
		Map<String, Map<String, List<Key>>> keys = new HashMap<>();
		BufferedReader r = new BufferedReader(new InputStreamReader(in, StandardCharsets.UTF_8));
		String line;
		while ((line = r.readLine()) != null) {
			if (line.isEmpty() || line.startsWith("#")) {
				continue;
			}
			String[] f = line.split("\t");
			if (f[0].equals("A")) {
				heads.put(f[1], new float[]{Float.parseFloat(f[2]), Float.parseFloat(f[3])});
			} else if (f[0].equals("K")) {
				// gleiche (Knochen, Ziel) können mehrfach vorkommen (mehrere Kanäle); neuer Kanal, wenn die Zeit zurückspringt
				String ch = f[2] + "\t" + f[3];
				Map<String, List<Key>> m = keys.computeIfAbsent(f[1], k -> new LinkedHashMap<>());
				Key k = new Key(Float.parseFloat(f[4]), new float[]{Float.parseFloat(f[5]), Float.parseFloat(f[6]), Float.parseFloat(f[7])},
					new float[]{Float.parseFloat(f[8]), Float.parseFloat(f[9]), Float.parseFloat(f[10])}, f[11].equals("C"));
				int n = 0;
				while (true) {
					String key = ch + "\t" + n;
					List<Key> list = m.get(key);
					if (list == null || list.getLast().time() < k.time()) {
						m.computeIfAbsent(key, x -> new ArrayList<>()).add(k);
						break;
					}
					n++;
				}
			}
		}
		Map<String, Animation> out = new HashMap<>();
		for (Map.Entry<String, float[]> h : heads.entrySet()) {
			List<Channel> chans = new ArrayList<>();
			for (Map.Entry<String, List<Key>> c : keys.getOrDefault(h.getKey(), Map.of()).entrySet()) {
				String[] p = c.getKey().split("\t");
				chans.add(new Channel(p[0], p[1].charAt(0), c.getValue().toArray(new Key[0])));
			}
			out.put(h.getKey(), new Animation(h.getKey(), h.getValue()[0], h.getValue()[1] != 0, List.copyOf(chans)));
		}
		return new KeyframeLibrary(out);
	}

	/** Addiert die Animation {@code name} zum Zeitpunkt {@code seconds} (seit Start) zur Pose; unbekannte Teile werden übergangen. */
	public void apply(final String name, final ModelPose p, final float seconds) {
		Animation a = this.animations.get(name);
		if (a == null) {
			return;
		}
		float t = a.looping() ? seconds % a.length() : seconds;
		float[] v = new float[3];
		for (Channel c : a.channels()) {
			int part = p.part(c.bone());
			if (part < 0) {
				continue;
			}
			sample(c.keys(), t, v);
			switch (c.target()) {
				case 'R' -> {
					p.xRot[part] += v[0];
					p.yRot[part] += v[1];
					p.zRot[part] += v[2];
				}
				case 'P' -> {
					p.x[part] += v[0];
					p.y[part] += v[1];
					p.z[part] += v[2];
				}
				case 'S' -> {
					p.xScale[part] += v[0];
					p.yScale[part] += v[1];
					p.zScale[part] += v[2];
				}
				default -> {
				}
			}
		}
	}

	static void sample(final Key[] k, final float t, final float[] out) {
		int firstAfter = k.length;
		for (int i = 0; i < k.length; i++) {
			if (t <= k[i].time()) {
				firstAfter = i;
				break;
			}
		}
		int prev = Math.max(0, firstAfter - 1);
		int next = Math.min(k.length - 1, prev + 1);
		float alpha = next == prev ? 0 : Math.clamp((t - k[prev].time()) / (k[next].time() - k[prev].time()), 0.0F, 1.0F);
		if (k[next].catmull()) {
			float[] p0 = k[Math.max(0, prev - 1)].post();
			float[] p1 = k[prev].post();
			float[] p2 = k[next].post();
			float[] p3 = k[Math.min(k.length - 1, next + 1)].post();
			for (int i = 0; i < 3; i++) {
				out[i] = catmullRom(alpha, p0[i], p1[i], p2[i], p3[i]);
			}
		} else {
			float[] a = k[prev].post();
			float[] b = k[next].pre();
			for (int i = 0; i < 3; i++) {
				out[i] = a[i] + (b[i] - a[i]) * alpha;
			}
		}
	}

	/** Catmull-Rom-Spline durch p1 → p2 (Tangenten aus p0 und p3). */
	static float catmullRom(final float a, final float p0, final float p1, final float p2, final float p3) {
		return 0.5F * (2.0F * p1 + (p2 - p0) * a + (2.0F * p0 - 5.0F * p1 + 4.0F * p2 - p3) * a * a + (3.0F * p1 - p0 - 3.0F * p2 + p3) * a * a * a);
	}
}
