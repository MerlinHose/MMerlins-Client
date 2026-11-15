import io.netty.buffer.ByteBuf;
import io.netty.buffer.Unpooled;
import net.minecraft.network.LpVec3;
import net.minecraft.world.phys.Vec3;

/**
 * Analysewerkzeug (M9, docs/34 §2): erzeugt mit dem unveränderten Server-JAR Referenzbytes für {@code LpVec3} als Orakel für
 * EntityPacketDecoderTest (es wird nur die Ausgabe verwendet, kein Code übernommen).
 * java -cp "<server-26.3.jar>;<libraries>" analysis/tools/LpVecCheck.java
 */
public final class LpVecCheck {
	public static void main(final String[] args) {
		double[][] vs = {{0, 0, 0}, {0.1, -0.2, 0.05}, {0, -0.0784000015258789, 0}, {1.5, 0.25, -3.75}, {-7.0, 2.0, 0.5}, {12.0, -40.0, 3.0}};
		for (double[] v : vs) {
			ByteBuf b = Unpooled.buffer();
			LpVec3.write(b, new Vec3(v[0], v[1], v[2]));
			byte[] bytes = new byte[b.readableBytes()];
			b.getBytes(0, bytes);
			Vec3 back = LpVec3.read(b);
			StringBuilder hex = new StringBuilder();
			for (byte x : bytes) {
				hex.append(String.format("%02x", x & 0xFF));
			}
			System.out.println("LPVEC " + v[0] + " " + v[1] + " " + v[2] + " " + hex + " " + back.x + " " + back.y + " " + back.z);
		}
	}
}
