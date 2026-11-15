import java.io.PrintWriter;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import net.minecraft.SharedConstants;
import net.minecraft.client.color.block.BlockColors;
import net.minecraft.client.color.block.BlockTintSource;
import net.minecraft.client.renderer.BiomeColors;
import net.minecraft.client.renderer.block.BlockAndTintGetter;
import net.minecraft.core.BlockPos;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.server.Bootstrap;
import net.minecraft.world.level.CardinalLighting;
import net.minecraft.world.level.ColorResolver;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.lighting.LevelLightEngine;
import net.minecraft.world.level.material.FluidState;
import net.minecraft.world.level.material.Fluids;

/**
 * Analysewerkzeug M12 (docs/47 §4.3): Tönungsquellen je Blockzustand aus {@code BlockColors#createDefault} des unveränderten
 * Spiel-JARs. Jede Quelle wird mit einem Proben-Getter ausgewertet, der für die Farbauflöser Gras/Laub/trockenes Laub/Wasser
 * Kennwerte liefert und je Position unterscheidet – so ergibt sich, welcher Biomwert (an welcher Position) oder welche feste
 * Farbe eine Fläche mit {@code tintindex} bekommt. Der Client enthält nur die erzeugte Tabelle.
 *
 * <pre>java -cp "&lt;minecraft-merged-26.3.jar&gt;;&lt;Bibliotheken&gt;" analysis/tools/TintExtractor.java &lt;tints-777.txt&gt;</pre>
 *
 * Format: {@code T stateId layer kind [rgb]} mit kind = grass | grass_below (oberer Teil doppelter Pflanzen) | foliage |
 * dry_foliage | water | constant (rgb hex, Farbe in der Welt) | none (-1, ungetönt).
 */
public final class TintExtractor {
	private static final int GRASS = 0x010101;
	private static final int FOLIAGE = 0x020202;
	private static final int DRY = 0x030303;
	private static final int WATER = 0x040404;
	private static final BlockPos PROBE = new BlockPos(1000, 64, 1000);

	public static void main(final String[] args) throws Exception {
		SharedConstants.tryDetectVersion();
		Bootstrap.bootStrap();
		BlockColors colors = BlockColors.createDefault();
		BlockAndTintGetter probe = new BlockAndTintGetter() {
			@Override
			public CardinalLighting cardinalLighting() {
				return CardinalLighting.DEFAULT;
			}

			@Override
			public LevelLightEngine getLightEngine() {
				return LevelLightEngine.EMPTY;
			}

			@Override
			public int getBlockTint(final BlockPos pos, final ColorResolver resolver) {
				int base = resolver == BiomeColors.GRASS_COLOR_RESOLVER ? GRASS : resolver == BiomeColors.FOLIAGE_COLOR_RESOLVER ? FOLIAGE
					: resolver == BiomeColors.DRY_FOLIAGE_COLOR_RESOLVER ? DRY : resolver == BiomeColors.WATER_COLOR_RESOLVER ? WATER : 0x0F0F0F;
				return 0xFF000000 | base | (pos.getY() - PROBE.getY() + 8) << 20;
			}

			@Override
			public BlockEntity getBlockEntity(final BlockPos pos) {
				return null;
			}

			@Override
			public BlockState getBlockState(final BlockPos pos) {
				return Blocks.AIR.defaultBlockState();
			}

			@Override
			public FluidState getFluidState(final BlockPos pos) {
				return Fluids.EMPTY.defaultFluidState();
			}

			@Override
			public int getHeight() {
				return 384;
			}

			@Override
			public int getMinY() {
				return -64;
			}
		};
		int lines = 0;
		try (PrintWriter w = new PrintWriter(Files.newBufferedWriter(Path.of(args[0]), StandardCharsets.UTF_8))) {
			w.println("# M12 tint sources (TintExtractor, BlockColors#createDefault of the unmodified 26.3 game jar)");
			for (BlockState state : Block.BLOCK_STATE_REGISTRY) {
				List<BlockTintSource> sources = colors.getTintSources(state);
				for (int layer = 0; layer < sources.size(); layer++) {
					int c = sources.get(layer).colorInWorld(state, probe, PROBE);
					int low = c & 0x0F0F0F;
					int dy = (c >> 20 & 15) - 8;
					String kind;
					if ((c & 0xFF000000) == 0xFF000000 && (low == GRASS || low == FOLIAGE || low == DRY || low == WATER) && (c & 0x0F0000) == (low & 0x0F0000)
						&& c != -1) {
						kind = low == GRASS ? (dy == -1 ? "grass_below" : "grass") : low == FOLIAGE ? "foliage" : low == DRY ? "dry_foliage" : "water";
					} else if (c == -1) {
						kind = "none";
					} else {
						kind = "constant\t" + String.format("%06x", c & 0xFFFFFF);
					}
					w.println("T\t" + Block.getId(state) + "\t" + layer + "\t" + kind + "\t# " + BuiltInRegistries.BLOCK.getKey(state.getBlock()));
					lines++;
				}
			}
		}
		System.out.println(lines + " tint lines -> " + args[0]);
	}
}
