package dev.mcresearch.client;

import com.mojang.blaze3d.platform.InputConstants;
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
import net.minecraft.world.phys.Vec3;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Vanilla-Referenz für M5 (docs/23): führt die Szenarien aus {@code analysis/tools/m5-course.txt} mit dem
 * unveränderten Vanilla-Client aus und protokolliert den {@code LocalPlayer}-Zustand nach jedem Tick.
 *
 * <p>Nur aktiv mit {@code -Dmcresearch.physicsref=<Kursdatei>} (Gradle-Task {@code runPhysicsRef}); sonst ohne Wirkung.
 * Eingaben laufen wie bei {@link LocalTestClient} über {@code KeyMapping.set}: Tasten, die am Ende von Tick n gesetzt
 * werden, liest {@code KeyboardInput#tick} in Tick n+1. Die Teleports zum Szenariostart führt die Serverkonsole aus
 * ({@code analysis/tools/m5-reference.sh} reagiert auf die Logzeile {@code READY}).
 */
public final class PhysicsReferenceClient implements ClientModInitializer {
	private static final Logger LOG = LoggerFactory.getLogger("mc-research/physicsref");
	private static final String P = "[PHYSREF] ";
	private static final int SETTLE_TICKS = 20;
	private static final int TELEPORT_TIMEOUT_TICKS = 20 * 60;

	/** M11: {@code pitch} = Startneigung, {@code pitches} je Tick (null = unverändert, sonst Neigung ab diesem Tick). */
	private record Scenario(String name, double x, double y, double z, float yaw, List<String> inputs, float pitch, List<Float> pitches) {
	}

	private enum Phase {
		WAIT_JOIN, READY, SETTLE, RUN, NEXT, DONE
	}

	private final List<Scenario> scenarios = new ArrayList<>();
	private PrintWriter trace;
	private Phase phase = Phase.WAIT_JOIN;
	private int index;
	private int ticksInPhase;
	private int k;
	private boolean sawStaging;
	private int respawnCooldown;
	private int deaths;

	@Override
	public void onInitializeClient() {
		String course = System.getProperty("mcresearch.physicsref");
		if (course == null || course.isBlank()) {
			return;
		}
		try {
			for (String line : Files.readAllLines(Path.of(course), StandardCharsets.UTF_8)) {
				String[] t = line.trim().split("\\s+");
				if (t.length >= 7 && t[0].equals("scenario")) {
					List<String> inputs = new ArrayList<>();
					List<Float> pitches = new ArrayList<>();
					for (int i = 6; i < t.length; i++) {
						String[] at = t[i].split("@");
						String[] seg = at[0].split(":");
						for (int n = 0; n < Integer.parseInt(seg[1]); n++) {
							inputs.add(seg[0]);
							pitches.add(n == 0 && at.length > 1 ? Float.valueOf(at[1]) : null);
						}
					}
					String[] yp = t[5].split("/");
					this.scenarios.add(new Scenario(t[1], Double.parseDouble(t[2]), Double.parseDouble(t[3]), Double.parseDouble(t[4]),
						Float.parseFloat(yp[0]), inputs, yp.length > 1 ? Float.parseFloat(yp[1]) : 0.0F, pitches));
				}
			}
			Path out = Path.of(System.getProperty("mcresearch.physicsref.out", "physref-trace.txt"));
			this.trace = new PrintWriter(Files.newBufferedWriter(out, StandardCharsets.UTF_8), true);
			this.trace.println("# Vanilla-Referenz: scenario k input x y z vx vy vz onGround horizontalCollision verticalCollision yRot"
				+ " | M11: pose sprinting crouching shift minorHorizontalCollision fallDistance bbHeight eyeHeight inWater underWater swimming"
				+ " waterHeight lavaHeight inLava onClimbable xRot");
		} catch (IOException e) {
			throw new IllegalStateException("cannot read course " + course, e);
		}
		LocalTestClient.blockJndiDns(); // keine SRV-DNS-Abfrage (kein externer DNS-Server)
		LOG.info(P + "enabled with {} scenarios from {}", this.scenarios.size(), course);
		ClientTickEvents.END_CLIENT_TICK.register(this::onEndTick);
	}

	private void onEndTick(final Minecraft mc) {
		LocalPlayer player = mc.player;
		this.ticksInPhase++;
		// Tod (z. B. aus einem früheren Lauf gespeichert): wie DeathScreen ("Respawn"): respawn() + Bildschirm schließen
		if (this.respawnCooldown > 0) {
			this.respawnCooldown--;
		}
		if (player != null && player.isDeadOrDying() && this.respawnCooldown == 0) {
			this.deaths++;
			LOG.warn(P + "DIED (health {}) in phase {}{} – respawning", player.getHealth(), this.phase,
				this.phase == Phase.RUN ? " during " + this.scenarios.get(this.index).name() + " – trace of this scenario is invalid" : "");
			player.respawn();
			mc.gui.setScreen(null);
			this.respawnCooldown = 40;
			return;
		}
		switch (this.phase) {
			case WAIT_JOIN -> {
				if (player != null && mc.level != null && mc.gui.screen() == null && this.ticksInPhase > 40) {
					mc.options.pauseOnLostFocus = false;
					mc.mouseHandler.releaseMouse(); // keine Mausdrehung während der Messung
					LOG.info(P + "JOINED as {} at {}", player.getGameProfile().name(), fmt(player.position()));
					announce();
				}
			}
			case READY -> {
				Scenario s = this.scenarios.get(this.index);
				// Handshake: Zwischenpunkt 1 Block hinter dem Start (fester Boden, stabil beobachtbar) → STAGED → Skript teleportiert zum
				// Start. Beweist, dass der Start-Teleport wirklich angekommen ist, auch wenn das vorige Szenario am Startpunkt endete.
				if (!this.sawStaging && player != null && Math.abs(player.getX() - s.x()) < 1e-9 && Math.abs(player.getY() - s.y()) < 1e-9
					&& Math.abs(player.getZ() - (s.z() - 1.0)) < 1e-9) {
					this.sawStaging = true;
					LOG.info(P + "STAGED {}", s.name());
				}
				if (this.sawStaging && Math.abs(player.getX() - s.x()) < 1e-9 && Math.abs(player.getY() - s.y()) < 1e-9
					&& Math.abs(player.getZ() - s.z()) < 1e-9 && player.getYRot() == s.yaw() && player.getXRot() == s.pitch()) {
					go(Phase.SETTLE);
				} else if (this.ticksInPhase > TELEPORT_TIMEOUT_TICKS) {
					LOG.warn(P + "no teleport for {} – skipping", s.name());
					go(Phase.NEXT);
				}
			}
			case SETTLE -> {
				if (this.ticksInPhase >= SETTLE_TICKS && player.onGround() && player.getDeltaMovement().horizontalDistanceSqr() == 0.0) {
					Scenario s = this.scenarios.get(this.index);
					LOG.info(P + "START {} at {} yaw={} v={}", s.name(), fmt(player.position()), player.getYRot(), player.getDeltaMovement());
					this.k = 1;
					keys(mc, s.inputs().getFirst());
					look(player, s.pitches().getFirst());
					go(Phase.RUN);
				}
			}
			case RUN -> {
				Scenario s = this.scenarios.get(this.index);
				Vec3 v = player.getDeltaMovement();
				this.trace.println(String.join(" ", s.name(), Integer.toString(this.k), s.inputs().get(this.k - 1), Double.toString(player.getX()),
					Double.toString(player.getY()), Double.toString(player.getZ()), Double.toString(v.x), Double.toString(v.y), Double.toString(v.z),
					Boolean.toString(player.onGround()), Boolean.toString(player.horizontalCollision), Boolean.toString(player.verticalCollision),
					Float.toString(player.getYRot()),
					// M11 (docs/42 §6): Bewegungszustand je Tick
					player.getPose().name(), Boolean.toString(player.isSprinting()), Boolean.toString(player.isCrouching()),
					Boolean.toString(player.isShiftKeyDown()), Boolean.toString(player.minorHorizontalCollision), Double.toString(player.fallDistance),
					Float.toString(player.getBbHeight()), Float.toString(player.getEyeHeight()), Boolean.toString(player.isInWater()),
					Boolean.toString(player.isUnderWater()), Boolean.toString(player.isSwimming()),
					Double.toString(player.getFluidHeight(net.minecraft.tags.FluidTags.WATER)), Double.toString(player.getFluidHeight(net.minecraft.tags.FluidTags.LAVA)),
					Boolean.toString(player.isInLava()), Boolean.toString(player.onClimbable()), Float.toString(player.getXRot())));
				if (this.k < s.inputs().size()) {
					keys(mc, s.inputs().get(this.k));
					look(player, s.pitches().get(this.k));
					this.k++;
				} else {
					keys(mc, "-");
					LOG.info(P + "END {} at {}", s.name(), fmt(player.position()));
					go(Phase.NEXT);
				}
			}
			case NEXT -> {
				if (this.ticksInPhase >= 10) { // Server-Beobachtung (/data get) durch das Skript
					this.index++;
					announce();
				}
			}
			case DONE -> {
			}
		}
	}

	private void announce() {
		if (this.index >= this.scenarios.size()) {
			LOG.info(P + "DONE all {} scenarios, deaths {}", this.scenarios.size(), this.deaths);
			this.trace.close();
			go(Phase.DONE);
			Minecraft.getInstance().stop(); // Messung beendet: Client schließen (trennt die Verbindung)
			return;
		}
		Scenario s = this.scenarios.get(this.index);
		this.sawStaging = false;
		LOG.info(P + "READY {} {} {} {} {} {}", s.name(), s.x(), s.y(), s.z(), s.yaw(), s.pitch());
		go(Phase.READY);
	}

	private void go(final Phase next) {
		this.phase = next;
		this.ticksInPhase = 0;
	}

	/** M11: Blickneigung für den nächsten Tick setzen (wie eine Mausbewegung zwischen zwei Ticks). */
	private static void look(final LocalPlayer player, final Float pitch) {
		if (pitch != null) {
			player.setXRot(pitch);
		}
	}

	private static void keys(final Minecraft mc, final String keys) {
		set(mc.options.keyUp, keys.indexOf('f') >= 0);
		set(mc.options.keyDown, keys.indexOf('b') >= 0);
		set(mc.options.keyLeft, keys.indexOf('l') >= 0);
		set(mc.options.keyRight, keys.indexOf('r') >= 0);
		set(mc.options.keyJump, keys.indexOf('j') >= 0);
		// M11: s = Schleichen (Shift), p = Sprinttaste
		set(mc.options.keyShift, keys.indexOf('s') >= 0);
		set(mc.options.keySprint, keys.indexOf('p') >= 0);
	}

	private static void set(final KeyMapping mapping, final boolean down) {
		KeyMapping.set(InputConstants.getKey(mapping.saveString()), down);
	}

	private static String fmt(final Vec3 v) {
		return String.format(Locale.ROOT, "(%.6f, %.6f, %.6f)", v.x, v.y, v.z);
	}
}
