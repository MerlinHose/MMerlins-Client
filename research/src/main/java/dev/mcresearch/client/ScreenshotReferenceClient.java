package dev.mcresearch.client;

import com.mojang.blaze3d.platform.NativeImage;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import net.fabricmc.api.ClientModInitializer;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents;
import net.minecraft.client.Minecraft;
import net.minecraft.client.Screenshot;
import net.minecraft.client.player.LocalPlayer;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * M12-Bildreferenz (docs/49 §3): nimmt im unveränderten Vanilla-Client Vergleichsbilder an festen Kamerapositionen auf. Das
 * Rendering bleibt vollständig Vanilla; dieser Beobachter wartet nur, bis der Spieler (vom Orchestrierungsskript über die
 * Serverkonsole per {@code tp} platziert) an der Szenenposition steht und alle Sektionen gebaut sind, und liest dann das
 * fertige Bild über {@link Screenshot#takeScreenshot} aus.
 *
 * <p>Nur aktiv mit {@code -Dmcresearch.shotref=<Szenendatei>} (Gradle-Task {@code runShotRef}). Szenenzeilen:
 * {@code scene <name> <x> <y> <z> <yaw> <pitch> <settleTicks>}. Ablauf je Szene: {@code [SHOTREF] WAIT name} im Log →
 * Skript schickt Aufbau + tp → Position erreicht, {@code settleTicks} ruhig → Bild → {@code [SHOTREF] SHOT name}.
 * HUD ausgeblendet wie mit F1 ({@code Hud#toggle}).
 */
public final class ScreenshotReferenceClient implements ClientModInitializer {
	private static final Logger LOG = LoggerFactory.getLogger("mc-research/shotref");
	private static final String P = "[SHOTREF] ";

	private record Scene(String name, double x, double y, double z, float yaw, float pitch, int settle) {
	}

	private final List<Scene> scenes = new ArrayList<>();
	private Path outDir;
	private int ticks;
	private int index;
	private int readyTicks;
	private boolean announced;
	private boolean capturing;
	private boolean done;

	@Override
	public void onInitializeClient() {
		String file = System.getProperty("mcresearch.shotref");
		if (file == null || file.isBlank()) {
			return;
		}
		try {
			for (String line : Files.readAllLines(Path.of(file), StandardCharsets.UTF_8)) {
				String[] t = line.trim().split("\\s+");
				if (t.length >= 8 && t[0].equals("scene")) {
					this.scenes.add(new Scene(t[1], Double.parseDouble(t[2]), Double.parseDouble(t[3]), Double.parseDouble(t[4]), Float.parseFloat(t[5]),
						Float.parseFloat(t[6]), Integer.parseInt(t[7])));
				}
			}
		} catch (IOException e) {
			throw new IllegalStateException("cannot read " + file, e);
		}
		this.outDir = Path.of(System.getProperty("mcresearch.shotref.out", "shots"));
		LocalTestClient.blockJndiDns();
		LOG.info(P + "enabled with {} scenes from {}", this.scenes.size(), file);
		ClientTickEvents.END_CLIENT_TICK.register(this::onEndTick);
	}

	private void onEndTick(final Minecraft mc) {
		LocalPlayer player = mc.player;
		if (this.done || player == null || mc.level == null) {
			return;
		}
		this.ticks++;
		if (this.ticks == 40) {
			mc.options.pauseOnLostFocus = false;
			mc.mouseHandler.releaseMouse();
			if (!mc.gui.hud.isHidden()) {
				mc.gui.hud.toggle();
			}
			LOG.info(P + "JOINED as {} window {}x{}", player.getGameProfile().name(), mc.getWindow().getWidth(), mc.getWindow().getHeight());
		}
		if (this.ticks < 40 || this.capturing) {
			return;
		}
		if (this.index >= this.scenes.size()) {
			this.done = true;
			LOG.info(P + "DONE {} scenes -> {}", this.scenes.size(), this.outDir.toAbsolutePath());
			mc.stop();
			return;
		}
		Scene s = this.scenes.get(this.index);
		if (!this.announced) {
			this.announced = true;
			this.readyTicks = 0;
			LOG.info(P + "WAIT {}", s.name());
			return;
		}
		boolean placed = Math.abs(player.getX() - s.x()) < 0.01 && Math.abs(player.getY() - s.y()) < 0.01 && Math.abs(player.getZ() - s.z()) < 0.01;
		boolean still = player.getDeltaMovement().horizontalDistanceSqr() < 1e-6;
		this.readyTicks = placed && still && mc.levelRenderer.hasRenderedAllSections() && mc.gui.screen() == null ? this.readyTicks + 1 : 0;
		if (this.readyTicks < s.settle()) {
			return;
		}
		// Blick exakt wie in der Szene (tp setzt ihn; Maus ist freigegeben, ändert ihn also nicht)
		player.setYRot(s.yaw());
		player.yRotO = s.yaw();
		player.setXRot(s.pitch());
		player.xRotO = s.pitch();
		this.capturing = true;
		Path file = this.outDir.resolve(s.name() + ".png");
		try {
			Files.createDirectories(this.outDir);
		} catch (IOException e) {
			LOG.error(P + "cannot create {}", this.outDir, e);
		}
		// Beobachtung (nur lesen): Lightmap-Eingaben des letzten Bildes und Helligkeitsoption
		var lm = mc.gameRenderer.gameRenderState().lightmapRenderState;
		LOG.info(P + "LIGHTMAP {} skyFactor={} blockFactor={} skyLightColor={} ambient={} blockTint={} brightness={} gammaOption={} nightVision={} darkness={}",
			s.name(), lm.skyFactor, lm.blockFactor, lm.skyLightColor, lm.ambientColor, lm.blockLightTint, lm.brightness, mc.options.gamma().get(),
			lm.nightVisionEffectIntensity, lm.darknessEffectScale);
		net.minecraft.core.BlockPos eye = net.minecraft.core.BlockPos.containing(player.getX(), player.getEyeY(), player.getZ());
		net.minecraft.core.BlockPos ground = net.minecraft.core.BlockPos.containing(player.getX(), player.getY() + 0.5, player.getZ() + 3);
		LOG.info(P + "LIGHT {} eye sky={} block={} ground+3z sky={} block={} skyDarken={}", s.name(),
			mc.level.getBrightness(net.minecraft.world.level.LightLayer.SKY, eye), mc.level.getBrightness(net.minecraft.world.level.LightLayer.BLOCK, eye),
			mc.level.getBrightness(net.minecraft.world.level.LightLayer.SKY, ground), mc.level.getBrightness(net.minecraft.world.level.LightLayer.BLOCK, ground),
			mc.level.getSkyDarken());
		// Beobachtung der Färbungsstufen: Biom, gemittelte Grasfarbe und Block am Boden vor dem Spieler
		net.minecraft.core.BlockPos floor = ground.below();
		LOG.info(P + "TINT {} floor={} {} {} block={} biome={} grassAvg={} foliageAvg={}", s.name(), floor.getX(), floor.getY(), floor.getZ(),
			mc.level.getBlockState(floor), mc.level.getBiome(floor).unwrapKey().map(Object::toString).orElse("?"),
			String.format(Locale.ROOT, "%06x", net.minecraft.client.renderer.BiomeColors.getAverageGrassColor(mc.level, floor) & 0xFFFFFF),
			String.format(Locale.ROOT, "%06x", net.minecraft.client.renderer.BiomeColors.getAverageFoliageColor(mc.level, floor) & 0xFFFFFF));
		long gameTime = mc.level.getGameTime();
		float rain = mc.level.getRainLevel(1.0F);
		float thunder = mc.level.getThunderLevel(1.0F);
		Screenshot.takeScreenshot(mc.gameRenderer.mainRenderTarget(), (NativeImage image) -> {
			try (image) {
				image.writeToFile(file);
				LOG.info(P + "SHOT {} {} pos={} {} {} rot={} {} gameTime={} rain={} thunder={}", s.name(), file.toAbsolutePath(),
					fmt(player.getX()), fmt(player.getY()), fmt(player.getZ()), player.getYRot(), player.getXRot(), gameTime, rain, thunder);
			} catch (IOException e) {
				LOG.error(P + "cannot write {}", file, e);
			} finally {
				this.index++;
				this.announced = false;
				this.capturing = false;
			}
		});
	}

	private static String fmt(final double v) {
		return String.format(Locale.ROOT, "%.4f", v);
	}
}
