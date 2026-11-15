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
import net.minecraft.client.KeyMapping;
import net.minecraft.client.Minecraft;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.phys.EntityHitResult;
import net.minecraft.world.phys.HitResult;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * M10-Referenz (docs/41 §7): bedient den unveränderten Vanilla-Client wie ein Spieler über dessen eigene Eingabewege und protokolliert
 * Angriffsstärke und Ziel je Angriff. Angriffe laufen über {@code KeyMapping.click} der Angriffstaste → {@code Minecraft#handleKeybinds}
 * → {@code startAttack}; der Blick wird über die Spielerrotation gesetzt, Sprünge über {@code jumpFromGround}.
 *
 * <p>Nur aktiv mit {@code -Dmcresearch.combatref=<Datei>} (Gradle-Task {@code runCombatRef}). Skriptzeilen:
 * {@code slot <0-8>}, {@code waitfull}, {@code lookat x y z}, {@code attack <name>}, {@code jumpfall x y z}, {@code wait <ticks>},
 * {@code done}. Wartet vor dem Start, bis der Spieler am Messplatz (0,5/100/−27,5) steht und die Gegenstände angekommen sind.
 */
public final class CombatReferenceClient implements ClientModInitializer {
	private static final Logger LOG = LoggerFactory.getLogger("mc-research/combatref");
	private static final String P = "[COMBATREF] ";
	private static final int SETTLE_TICKS = 60;

	private final List<String[]> steps = new ArrayList<>();
	private final List<String> results = new ArrayList<>();
	private Path out;
	private int ticks;
	private int readyTicks;
	private int index;
	private int waitTicks;
	private boolean started;
	private boolean jumped;
	private boolean done;

	@Override
	public void onInitializeClient() {
		String file = System.getProperty("mcresearch.combatref");
		if (file == null || file.isBlank()) {
			return;
		}
		try {
			for (String line : Files.readAllLines(Path.of(file), StandardCharsets.UTF_8)) {
				String t = line.trim();
				if (!t.isEmpty() && !t.startsWith("#") && t.startsWith("ref ")) {
					this.steps.add(t.substring(4).trim().split("\\s+"));
				}
			}
		} catch (IOException e) {
			throw new IllegalStateException("cannot read " + file, e);
		}
		this.out = Path.of(System.getProperty("mcresearch.combatref.out", "combatref.txt"));
		LocalTestClient.blockJndiDns();
		LOG.info(P + "enabled with {} steps from {}", this.steps.size(), file);
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
			LOG.info(P + "JOINED as {}", player.getGameProfile().name());
		}
		if (!this.started) {
			boolean placed = Math.abs(player.getX() - 0.5) < 0.01 && Math.abs(player.getY() - 100) < 0.01 && Math.abs(player.getZ() + 27.5) < 0.01;
			boolean armed = !player.getInventory().getItem(1).isEmpty() && !player.getInventory().getItem(2).isEmpty();
			this.readyTicks = this.ticks > 40 && placed && armed && mc.gui.screen() == null ? this.readyTicks + 1 : 0;
			if (this.readyTicks < SETTLE_TICKS) {
				return;
			}
			this.started = true;
			LOG.info(P + "START gamemode {}", mc.gameMode.getPlayerMode());
		}
		if (this.waitTicks > 0) {
			this.waitTicks--;
			return;
		}
		if (this.index >= this.steps.size()) {
			finish(mc);
			return;
		}
		String[] s = this.steps.get(this.index);
		switch (s[0]) {
			case "slot" -> {
				player.getInventory().setSelectedSlot(Integer.parseInt(s[1]));
				this.index++;
			}
			case "waitfull" -> {
				if (player.getAttackStrengthScale(0.0F) >= 1.0F) {
					this.index++;
				}
			}
			case "wait" -> {
				this.waitTicks = Integer.parseInt(s[1]);
				this.index++;
			}
			case "lookat" -> {
				look(player, Double.parseDouble(s[1]), Double.parseDouble(s[2]), Double.parseDouble(s[3]));
				this.index++;
			}
			case "attack" -> {
				// Stärke beim Tastendruck; die Taste wird im nächsten Tick in handleKeybinds verarbeitet (startAttack)
				HitResult hit = mc.hitResult;
				Entity target = hit instanceof EntityHitResult e ? e.getEntity() : null;
				String line = String.format(Locale.ROOT, "ATTACK %s strength=%.4f ticker-target=%s", s[1], player.getAttackStrengthScale(0.0F),
					target == null ? "none" : target.getType().toShortString() + "#" + target.getId());
				this.results.add(line);
				LOG.info(P + line);
				KeyMapping.click(mc.options.keyAttack.getDefaultKey());
				this.index++;
			}
			case "jumpfall" -> {
				double x = Double.parseDouble(s[1]), y = Double.parseDouble(s[2]), z = Double.parseDouble(s[3]);
				if (!this.jumped) {
					player.jumpFromGround();
					this.jumped = true;
				}
				look(player, x, y, z);
				if (!player.onGround() && player.getDeltaMovement().y < 0) {
					this.jumped = false;
					this.index++;
				}
			}
			case "done" -> finish(mc);
			default -> throw new IllegalStateException("unknown combatref step " + s[0]);
		}
	}

	private static void look(final LocalPlayer player, final double x, final double y, final double z) {
		double dx = x - player.getX(), dy = y - player.getEyeY(), dz = z - player.getZ();
		float yaw = (float) (Math.toDegrees(Math.atan2(dz, dx)) - 90.0);
		float pitch = (float) -Math.toDegrees(Math.atan2(dy, Math.sqrt(dx * dx + dz * dz)));
		player.setYRot(yaw);
		player.yRotO = yaw;
		player.setXRot(pitch);
		player.xRotO = pitch;
	}

	private void finish(final Minecraft mc) {
		this.done = true;
		try (PrintWriter w = new PrintWriter(Files.newBufferedWriter(this.out, StandardCharsets.UTF_8))) {
			for (String r : this.results) {
				w.println(r);
			}
		} catch (IOException e) {
			LOG.error(P + "cannot write {}", this.out, e);
		}
		LOG.info(P + "DONE {} attacks -> {}", this.results.size(), this.out.toAbsolutePath());
		mc.stop();
	}
}
