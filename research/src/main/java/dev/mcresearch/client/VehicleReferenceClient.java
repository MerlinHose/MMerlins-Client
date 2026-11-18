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
import net.minecraft.world.entity.Entity;
import net.minecraft.world.phys.Vec3;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * M14 Fahrzeugreferenz (docs/58 §4): Der unveränderte Vanilla-Client fährt die Fahrzeugszenarien aus
 * {@code analysis/tools/m14-vehicle-course.txt} (Zeilen {@code vehicle <name> <typ> <x> <y> <z> <gier> <eingaben…>}). Das Skript
 * {@code m14-vehicle-run.sh} beschwört je Szenario das Fahrzeug (Tag m14v) und setzt den Spieler mit {@code /ride} darauf; dieser
 * Client wartet auf das Aufsitzen, lässt {@value #SETTLE_TICKS} Ticks ohne Eingabe verstreichen und spielt dann die Tastenfolge.
 * Je Tick wird der Zustand des Fahrzeugs (Position, Geschwindigkeit, Gier, Bodenkontakt) protokolliert. Nur Beobachtung und
 * Tastensimulation, keine Änderung am Spielverhalten.
 */
public final class VehicleReferenceClient implements ClientModInitializer {
	private static final Logger LOG = LoggerFactory.getLogger("mc-research/vehicleref");
	private static final String P = "[VEHREF] ";
	static final int SETTLE_TICKS = 40;
	private static final int MOUNT_TIMEOUT_TICKS = 20 * 30;

	private record Scenario(String name, String type, double x, double y, double z, float yaw, List<String> inputs) {
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

	@Override
	public void onInitializeClient() {
		String course = System.getProperty("mcresearch.vehicleref");
		if (course == null || course.isBlank()) {
			return;
		}
		try {
			for (String line : Files.readAllLines(Path.of(course), StandardCharsets.UTF_8)) {
				String[] t = line.trim().split("\\s+");
				if (t.length >= 8 && t[0].equals("vehicle")) {
					List<String> inputs = new ArrayList<>();
					for (int i = 7; i < t.length; i++) {
						String[] seg = t[i].split(":");
						for (int n = 0; n < Integer.parseInt(seg[1]); n++) {
							inputs.add(seg[0]);
						}
					}
					this.scenarios.add(new Scenario(t[1], t[2], Double.parseDouble(t[3]), Double.parseDouble(t[4]), Double.parseDouble(t[5]),
						Float.parseFloat(t[6]), inputs));
				}
			}
			Path out = Path.of(System.getProperty("mcresearch.vehicleref.out", "vehref-trace.txt"));
			this.trace = new PrintWriter(Files.newBufferedWriter(out, StandardCharsets.UTF_8), true);
			this.trace.println("# Vanilla-Fahrzeugreferenz: scenario k input x y z vx vy vz yRot onGround horizontalCollision playerYRot");
		} catch (IOException e) {
			throw new IllegalStateException("cannot read course " + course, e);
		}
		LocalTestClient.blockJndiDns();
		LOG.info(P + "enabled with {} scenarios from {}", this.scenarios.size(), course);
		ClientTickEvents.END_CLIENT_TICK.register(this::onEndTick);
	}

	private void onEndTick(final Minecraft mc) {
		LocalPlayer player = mc.player;
		this.ticksInPhase++;
		switch (this.phase) {
			case WAIT_JOIN -> {
				if (player != null && mc.level != null && mc.gui.screen() == null && this.ticksInPhase > 40) {
					mc.options.pauseOnLostFocus = false;
					mc.mouseHandler.releaseMouse();
					LOG.info(P + "JOINED as {}", player.getGameProfile().name());
					announce();
				}
			}
			case READY -> {
				Scenario s = this.scenarios.get(this.index);
				Entity v = player == null ? null : player.getVehicle();
				if (v != null && Math.abs(v.getX() - s.x()) < 1.0E-6 && Math.abs(v.getZ() - s.z()) < 1.0E-6) {
					LOG.info(P + "MOUNTED {} on {} at {}", s.name(), v.getType().builtInRegistryHolder().key().identifier(), fmt(v.position()));
					player.setYRot(s.yaw());
					player.setXRot(0.0F);
					go(Phase.SETTLE);
				} else if (this.ticksInPhase > MOUNT_TIMEOUT_TICKS) {
					LOG.warn(P + "not mounted for {} – skipping", s.name());
					go(Phase.NEXT);
				}
			}
			case SETTLE -> {
				Entity v = player.getVehicle();
				if (v == null) {
					LOG.warn(P + "dismounted while settling");
					go(Phase.NEXT);
				} else if (this.ticksInPhase >= SETTLE_TICKS) {
					Scenario s = this.scenarios.get(this.index);
					LOG.info(P + "START {} at {} yaw={}", s.name(), fmt(v.position()), v.getYRot());
					this.k = 1;
					keys(mc, s.inputs().getFirst());
					go(Phase.RUN);
				}
			}
			case RUN -> {
				Scenario s = this.scenarios.get(this.index);
				Entity v = player.getVehicle();
				if (v == null) {
					keys(mc, "-");
					LOG.warn(P + "dismounted during {}", s.name());
					go(Phase.NEXT);
					return;
				}
				Vec3 d = v.getDeltaMovement();
				this.trace.println(String.join(" ", s.name(), Integer.toString(this.k), s.inputs().get(this.k - 1), Double.toString(v.getX()),
					Double.toString(v.getY()), Double.toString(v.getZ()), Double.toString(d.x), Double.toString(d.y), Double.toString(d.z),
					Float.toString(v.getYRot()), Boolean.toString(v.onGround()), Boolean.toString(v.horizontalCollision), Float.toString(player.getYRot())));
				if (this.k < s.inputs().size()) {
					keys(mc, s.inputs().get(this.k));
					this.k++;
				} else {
					keys(mc, "-");
					LOG.info(P + "END {} at {}", s.name(), fmt(v.position()));
					go(Phase.NEXT);
				}
			}
			case NEXT -> {
				if (this.ticksInPhase >= 20) { // Serverbeobachtung, Absteigen, Entfernen durch das Skript
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
			LOG.info(P + "DONE all {} scenarios", this.scenarios.size());
			this.trace.close();
			go(Phase.DONE);
			Minecraft.getInstance().stop();
			return;
		}
		Scenario s = this.scenarios.get(this.index);
		LOG.info(P + "READY {} {} {} {} {} {}", s.name(), s.type(), s.x(), s.y(), s.z(), s.yaw());
		go(Phase.READY);
	}

	private void go(final Phase next) {
		this.phase = next;
		this.ticksInPhase = 0;
	}

	private static void keys(final Minecraft mc, final String keys) {
		set(mc.options.keyUp, keys.indexOf('f') >= 0);
		set(mc.options.keyDown, keys.indexOf('b') >= 0);
		set(mc.options.keyLeft, keys.indexOf('l') >= 0);
		set(mc.options.keyRight, keys.indexOf('r') >= 0);
		set(mc.options.keyJump, keys.indexOf('j') >= 0);
		set(mc.options.keyShift, keys.indexOf('s') >= 0);
	}

	private static void set(final KeyMapping mapping, final boolean down) {
		KeyMapping.set(InputConstants.getKey(mapping.saveString()), down);
	}

	private static String fmt(final Vec3 v) {
		return String.format(Locale.ROOT, "(%.6f, %.6f, %.6f)", v.x, v.y, v.z);
	}
}
