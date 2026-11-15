import java.io.PrintWriter;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import net.minecraft.SharedConstants;
import net.minecraft.core.particles.ParticleType;
import net.minecraft.core.particles.SimpleParticleType;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.server.Bootstrap;

/**
 * Analysewerkzeug M12 (docs/48 §7): Netzwerk-IDs der Partikeltypen ({@code BuiltInRegistries.PARTICLE_TYPE}) aus dem
 * unveränderten Server-JAR. Format: {@code P id name simple|data} – {@code simple} = ohne Zusatzdaten im Paket.
 *
 * <pre>java -cp "server/versions/26.3/server-26.3.jar;…" analysis/tools/ParticleTypeExtractor.java &lt;particles-777.txt&gt;</pre>
 */
public final class ParticleTypeExtractor {
	public static void main(final String[] args) throws Exception {
		SharedConstants.tryDetectVersion();
		Bootstrap.bootStrap();
		int n = 0;
		try (PrintWriter w = new PrintWriter(Files.newBufferedWriter(Path.of(args[0]), StandardCharsets.UTF_8))) {
			w.println("# M12 particle types (ParticleTypeExtractor, unmodified server 26.3): P id name simple|data");
			for (ParticleType<?> t : BuiltInRegistries.PARTICLE_TYPE) {
				w.println("P\t" + BuiltInRegistries.PARTICLE_TYPE.getId(t) + "\t" + BuiltInRegistries.PARTICLE_TYPE.getKey(t) + "\t"
					+ (t instanceof SimpleParticleType ? "simple" : "data"));
				n++;
			}
		}
		System.out.println(n + " particle types -> " + args[0]);
	}
}
