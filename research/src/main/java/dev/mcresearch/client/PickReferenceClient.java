package dev.mcresearch.client;

import java.io.IOException;
import java.io.PrintWriter;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import net.fabricmc.api.ClientModInitializer;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents;
import net.minecraft.client.Minecraft;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.core.BlockPos;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.HitResult;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Vanilla-Referenz für die Blockauswahl (M7, docs/29 §1): wertet für jede {@code pick}-Zeile aus
 * {@code analysis/tools/m7-picks.txt} die unveränderte Vanilla-Auswahl {@code LocalPlayer#raycastHitResult} aus und
 * schreibt das Ergebnis im gemeinsamen Vergleichsformat {@code name hit|miss blockX blockY blockZ face x y z inside blockRange entityRange [state]}.
 *
 * <p>Nur aktiv mit {@code -Dmcresearch.pickref=<Datei>} (Gradle-Task {@code runPickRef}). Für jede Zeile werden Position
 * und Blickrichtung des Spielers nur innerhalb dieses Tick-Callbacks gesetzt ({@code xo = x}, damit
 * {@code getEyePosition(1.0F)} exakt die Zielposition liefert) und danach wiederhergestellt; Bewegungspakete entstehen
 * dabei nicht (sie werden im Spieler-Tick gesendet, vor {@code END_CLIENT_TICK}). Der Server sieht den Spieler nur am
 * Messplatz, den die Konsole per {@code tp} setzt.
 */
public final class PickReferenceClient implements ClientModInitializer {
	private static final Logger LOG = LoggerFactory.getLogger("mc-research/pickref");
	private static final String P = "[PICKREF] ";
	private static final int SETTLE_TICKS = 60;

	private record Pick(String name, double x, double y, double z, float yaw, float pitch) {
	}

	private final List<Pick> picks = new ArrayList<>();
	private Path out;
	private int ticks;
	private int readyTicks;
	private boolean done;

	@Override
	public void onInitializeClient() {
		String file = System.getProperty("mcresearch.pickref");
		if (file == null || file.isBlank()) {
			return;
		}
		try {
			for (String line : Files.readAllLines(Path.of(file), StandardCharsets.UTF_8)) {
				String[] t = line.trim().split("\\s+");
				if (t.length == 7 && t[0].equals("pick")) {
					this.picks.add(new Pick(t[1], Double.parseDouble(t[2]), Double.parseDouble(t[3]), Double.parseDouble(t[4]), Float.parseFloat(t[5]),
						Float.parseFloat(t[6])));
				}
			}
		} catch (IOException e) {
			throw new IllegalStateException("cannot read " + file, e);
		}
		this.out = Path.of(System.getProperty("mcresearch.pickref.out", "pickref.txt"));
		LocalTestClient.blockJndiDns(); // keine SRV-DNS-Abfrage
		LOG.info(P + "enabled with {} picks from {}", this.picks.size(), file);
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
			LOG.info(P + "JOINED as {} at ({}, {}, {})", player.getGameProfile().name(), player.getX(), player.getY(), player.getZ());
		}
		if (this.ticks < 40) {
			return;
		}
		// Messplatz (Konsole: tp … 0 100 -27.5 → Server zentriert x auf 0,5) erreicht und alle Zielchunks geladen?
		boolean placed = Math.abs(player.getX() - 0.5) < 0.01 && Math.abs(player.getY() - 100) < 0.01 && Math.abs(player.getZ() + 27.5) < 0.01;
		boolean loaded = this.picks.stream().allMatch(p -> mc.level.hasChunkAt(BlockPos.containing(p.x(), p.y(), p.z() - 5)));
		this.readyTicks = placed && loaded && mc.gui.screen() == null ? this.readyTicks + 1 : 0;
		if (this.readyTicks < SETTLE_TICKS) {
			return;
		}
		this.done = true;
		LOG.info(P + "MEASURE gamemode {} blockRange {} entityRange {}", mc.gameMode.getPlayerMode(), player.blockInteractionRange(),
			player.entityInteractionRange());
		double x0 = player.getX(), y0 = player.getY(), z0 = player.getZ();
		double xo0 = player.xo, yo0 = player.yo, zo0 = player.zo;
		float yaw0 = player.getYRot(), pitch0 = player.getXRot(), yawO0 = player.yRotO, pitchO0 = player.xRotO;
		try (PrintWriter w = new PrintWriter(Files.newBufferedWriter(this.out, StandardCharsets.UTF_8))) {
			w.println("# Vanilla LocalPlayer#raycastHitResult: name hit|miss blockX blockY blockZ face x y z inside blockRange entityRange state");
			for (Pick p : this.picks) {
				player.setPos(p.x(), p.y(), p.z());
				player.xo = p.x();
				player.yo = p.y();
				player.zo = p.z();
				player.setYRot(p.yaw());
				player.yRotO = p.yaw();
				player.setXRot(p.pitch());
				player.xRotO = p.pitch();
				HitResult r = player.raycastHitResult(1.0F, player);
				w.println(format(mc, p.name(), r));
			}
		} catch (IOException e) {
			LOG.error(P + "cannot write {}", this.out, e);
		} finally {
			player.setPos(x0, y0, z0);
			player.xo = xo0;
			player.yo = yo0;
			player.zo = zo0;
			player.setYRot(yaw0);
			player.yRotO = yawO0;
			player.setXRot(pitch0);
			player.xRotO = pitchO0;
		}
		LOG.info(P + "DONE {} picks -> {}", this.picks.size(), this.out.toAbsolutePath());
		mc.stop();
	}

	private static String format(final Minecraft mc, final String name, final HitResult r) {
		if (r instanceof BlockHitResult b) {
			BlockPos pos = b.getBlockPos();
			return String.join(" ", name, b.getType() == HitResult.Type.MISS ? "miss" : "hit", Integer.toString(pos.getX()), Integer.toString(pos.getY()),
				Integer.toString(pos.getZ()), b.getDirection().getSerializedName(), Double.toString(b.getLocation().x), Double.toString(b.getLocation().y),
				Double.toString(b.getLocation().z), Boolean.toString(b.isInside()), Double.toString(mc.player.blockInteractionRange()), Double.toString(mc.player.entityInteractionRange()),
				mc.level.getBlockState(pos).toString().replace(' ', '_'));
		}
		if (r instanceof net.minecraft.world.phys.EntityHitResult e) {
			// M9: Entity-Treffer im Format des eigenen Terminalbefehls epicks (docs/37 §2)
			return String.join(" ", name, "entity", e.getEntity().getUUID().toString(), e.getEntity().typeHolder().getRegisteredName(),
				Double.toString(e.getLocation().x), Double.toString(e.getLocation().y), Double.toString(e.getLocation().z),
				Double.toString(mc.player.blockInteractionRange()), Double.toString(mc.player.entityInteractionRange()));
		}
		return String.format(Locale.ROOT, "%s entity %s", name, r);
	}
}
