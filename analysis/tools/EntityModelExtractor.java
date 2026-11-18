import java.io.OutputStreamWriter;
import java.io.PrintWriter;
import java.lang.reflect.Field;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.zip.GZIPOutputStream;
import net.minecraft.SharedConstants;
import net.minecraft.client.model.geom.LayerDefinitions;
import net.minecraft.client.model.geom.ModelLayerLocation;
import net.minecraft.client.model.geom.PartPose;
import net.minecraft.client.model.geom.builders.CubeDefinition;
import net.minecraft.client.model.geom.builders.CubeDeformation;
import net.minecraft.client.model.geom.builders.LayerDefinition;
import net.minecraft.client.model.geom.builders.MaterialDefinition;
import net.minecraft.client.model.geom.builders.MeshDefinition;
import net.minecraft.client.model.geom.builders.PartDefinition;
import net.minecraft.client.model.geom.builders.UVPair;
import net.minecraft.core.Direction;
import net.minecraft.server.Bootstrap;
import org.joml.Vector3fc;

/**
 * Analysewerkzeug M14 (docs/55 §2): Faktentabelle „Modell-Layer → Teilbaum, Posen, Quader“ aus dem unveränderten, lokal
 * vorhandenen 26.3-Client-JAR (Loom, gemappt), analog zu {@code ShapeExtractor}/{@code RenderFactsExtractor}. Ausgegeben werden
 * nur Zahlen (Drehpunkte, Winkel, Quadergrößen, Texturversätze); Aufbau, Abwicklung und Animation im Client sind eigener Code.
 *
 * <pre>
 * java -cp "&lt;minecraft-merged-deobf-26.3.jar&gt;;server/libraries/...;joml.jar" analysis/tools/EntityModelExtractor.java &lt;entitymodels.txt.gz&gt;
 * </pre>
 *
 * Format (Tab-getrennt, Pfade mit '/' ab der Wurzel "root"):
 * <pre>
 *   L  layer                texW texH
 *   P  layer  path          x y z  xRot yRot zRot  xScale yScale zScale
 *   C  layer  path          u v  minX minY minZ  w h d  growX growY growZ  mirror(0/1)  texScaleU texScaleV  faces
 *      faces: Bitmaske der sichtbaren Seiten in Direction-Reihenfolge (unten, oben, Nord, Süd, West, Ost), 63 = alle
 * </pre>
 */
public final class EntityModelExtractor {
	private static Object get(final Object o, final Class<?> c, final String name) throws ReflectiveOperationException {
		Field f = c.getDeclaredField(name);
		f.setAccessible(true);
		return f.get(o);
	}

	private static String f(final float v) {
		if (v == (long) v) {
			return Long.toString((long) v);
		}
		return Float.toString(v);
	}

	public static void main(final String[] args) throws Exception {
		SharedConstants.tryDetectVersion();
		Bootstrap.bootStrap();
		Map<ModelLayerLocation, LayerDefinition> roots = LayerDefinitions.createRoots();
		Map<String, LayerDefinition> sorted = new TreeMap<>();
		roots.forEach((k, v) -> sorted.put(k.model() + "#" + k.layer(), v));
		int parts = 0;
		int cubes = 0;
		try (PrintWriter out = new PrintWriter(new OutputStreamWriter(new GZIPOutputStream(Files.newOutputStream(Path.of(args[0]))), StandardCharsets.UTF_8))) {
			out.println("# Entity-Modell-Layer Minecraft 26.3 / Protokoll 777 - Quelle: EntityModelExtractor (Client-JAR, Laufzeit, nur Zahlen)");
			for (Map.Entry<String, LayerDefinition> e : sorted.entrySet()) {
				String layer = e.getKey();
				MeshDefinition mesh = (MeshDefinition) get(e.getValue(), LayerDefinition.class, "mesh");
				MaterialDefinition mat = (MaterialDefinition) get(e.getValue(), LayerDefinition.class, "material");
				out.println("L\t" + layer + "\t" + get(mat, MaterialDefinition.class, "xTexSize") + "\t" + get(mat, MaterialDefinition.class, "yTexSize"));
				List<Object[]> stack = new ArrayList<>();
				stack.add(new Object[]{"root", mesh.getRoot()});
				while (!stack.isEmpty()) {
					Object[] top = stack.removeLast();
					String path = (String) top[0];
					PartDefinition p = (PartDefinition) top[1];
					PartPose pose = (PartPose) get(p, PartDefinition.class, "partPose");
					out.println("P\t" + layer + "\t" + path + "\t" + f(pose.x()) + "\t" + f(pose.y()) + "\t" + f(pose.z()) + "\t" + f(pose.xRot()) + "\t"
						+ f(pose.yRot()) + "\t" + f(pose.zRot()) + "\t" + f(pose.xScale()) + "\t" + f(pose.yScale()) + "\t" + f(pose.zScale()));
					parts++;
					@SuppressWarnings("unchecked")
					List<CubeDefinition> cl = (List<CubeDefinition>) get(p, PartDefinition.class, "cubes");
					for (CubeDefinition c : cl) {
						UVPair tc = (UVPair) get(c, CubeDefinition.class, "texCoord");
						UVPair ts = (UVPair) get(c, CubeDefinition.class, "texScale");
						Vector3fc o = (Vector3fc) get(c, CubeDefinition.class, "origin");
						Vector3fc d = (Vector3fc) get(c, CubeDefinition.class, "dimensions");
						CubeDeformation g = (CubeDeformation) get(c, CubeDefinition.class, "grow");
						boolean mirror = (Boolean) get(c, CubeDefinition.class, "mirror");
						@SuppressWarnings("unchecked")
						Set<Direction> faces = (Set<Direction>) get(c, CubeDefinition.class, "visibleFaces");
						int mask = 0;
						for (Direction dir : faces) {
							mask |= 1 << dir.get3DDataValue();
						}
						out.println("C\t" + layer + "\t" + path + "\t" + f(tc.u()) + "\t" + f(tc.v()) + "\t" + f(o.x()) + "\t" + f(o.y()) + "\t" + f(o.z()) + "\t"
							+ f(d.x()) + "\t" + f(d.y()) + "\t" + f(d.z()) + "\t" + f((Float) get(g, CubeDeformation.class, "growX")) + "\t" + f((Float) get(g, CubeDeformation.class, "growY")) + "\t"
							+ f((Float) get(g, CubeDeformation.class, "growZ")) + "\t" + (mirror ? 1 : 0)
							+ "\t" + f(ts.u()) + "\t" + f(ts.v()) + "\t" + mask);
						cubes++;
					}
					@SuppressWarnings("unchecked")
					Collection<Map.Entry<String, PartDefinition>> ch = new TreeMap<>((Map<String, PartDefinition>) get(p, PartDefinition.class, "children"))
						.descendingMap().entrySet();
					for (Map.Entry<String, PartDefinition> c : ch) {
						stack.add(new Object[]{path + "/" + c.getKey(), c.getValue()});
					}
				}
			}
		}
		System.out.println("layers=" + sorted.size() + " parts=" + parts + " cubes=" + cubes);
		if (args.length > 1) {
			animations(Path.of(args[1]));
		}
	}

	/**
	 * Keyframe-Animationen (statische {@code AnimationDefinition}-Felder der Klassen in {@code client.animation.definitions}):
	 * <pre>
	 *   A  Klasse.FELD  länge(s)  looping(0/1)
	 *   K  Klasse.FELD  knochen  ziel(P/R/S)  zeit(s)  preX preY preZ  postX postY postZ  interp(L/C)
	 * </pre>
	 * Werte bereits umgerechnet wie im Spiel (Grad → Bogenmaß, y der Position negiert, Skalierung − 1).
	 */
	private static void animations(final Path out) throws Exception {
		String[] classes = {"RabbitAnimation", "BabyRabbitAnimation"};
		int n = 0;
		try (PrintWriter w = new PrintWriter(Files.newBufferedWriter(out, StandardCharsets.UTF_8))) {
			w.println("# Keyframe-Animationen Minecraft 26.3 - Quelle: EntityModelExtractor (Client-JAR, Laufzeit, nur Zahlen)");
			for (String cn : classes) {
				Class<?> c = Class.forName("net.minecraft.client.animation.definitions." + cn);
				for (Field f : c.getDeclaredFields()) {
					if (f.getType() != net.minecraft.client.animation.AnimationDefinition.class) {
						continue;
					}
					f.setAccessible(true);
					var def = (net.minecraft.client.animation.AnimationDefinition) f.get(null);
					String name = cn + "." + f.getName();
					w.println("A\t" + name + "\t" + f(def.lengthInSeconds()) + "\t" + (def.looping() ? 1 : 0));
					for (var e : new TreeMap<>(def.boneAnimations()).entrySet()) {
						for (var ch : e.getValue()) {
							var t = ch.target();
							String target = t == net.minecraft.client.animation.AnimationChannel.Targets.POSITION ? "P"
								: t == net.minecraft.client.animation.AnimationChannel.Targets.ROTATION ? "R"
								: t == net.minecraft.client.animation.AnimationChannel.Targets.SCALE ? "S" : "?";
							for (var k : ch.keyframes()) {
								String in = k.interpolation() == net.minecraft.client.animation.AnimationChannel.Interpolations.LINEAR ? "L"
									: k.interpolation() == net.minecraft.client.animation.AnimationChannel.Interpolations.CATMULLROM ? "C" : "?";
								w.println("K\t" + name + "\t" + e.getKey() + "\t" + target + "\t" + f(k.timestamp()) + "\t" + f(k.preTarget().x()) + "\t"
									+ f(k.preTarget().y()) + "\t" + f(k.preTarget().z()) + "\t" + f(k.postTarget().x()) + "\t" + f(k.postTarget().y()) + "\t"
									+ f(k.postTarget().z()) + "\t" + in);
								n++;
							}
						}
					}
				}
			}
		}
		System.out.println("keyframes=" + n);
	}
}
