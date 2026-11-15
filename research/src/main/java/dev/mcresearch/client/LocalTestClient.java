package dev.mcresearch.client;

import com.mojang.blaze3d.platform.InputConstants;
import java.util.Hashtable;
import javax.naming.NoInitialContextException;
import javax.naming.spi.NamingManager;
import net.fabricmc.api.ClientModInitializer;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents;
import net.minecraft.client.KeyMapping;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.screens.inventory.InventoryScreen;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.util.Mth;
import net.minecraft.world.inventory.ContainerInput;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.HitResult;
import net.minecraft.world.phys.Vec3;
import org.apache.logging.log4j.Level;
import org.apache.logging.log4j.core.config.Configurator;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Laufzeittest-Treiber für den lokalen Vanilla-Server (docs/10-laufzeittest.md).
 *
 * <p>Nur aktiv mit {@code -Dmcresearch.localtest=true} (Gradle-Task {@code runLocalTest}).
 * Ohne diese Property tut die Klasse nichts, {@code runClient} bleibt unverändert.
 *
 * <p>Eingaben werden auf demselben Weg eingespeist wie von {@code KeyboardHandler#keyPress} bzw.
 * {@code MouseHandler#onButton}: {@code KeyMapping.set(key, state)} und {@code KeyMapping.click(key)}.
 * Die eigentliche Verarbeitung erfolgt unverändert durch {@code Minecraft#handleKeybinds},
 * {@code KeyboardInput#tick} und {@code LocalPlayer#sendChanges}.
 */
public final class LocalTestClient implements ClientModInitializer {
	private static final Logger LOG = LoggerFactory.getLogger("mc-research/localtest");
	private static final String P = "[LOCALTEST] ";

	private enum Step {
		WAIT_JOIN, SETTLE, MOVE_FORWARD, MOVE_JUMP, WAIT_ITEMS, HOTBAR, AIM_PLACE_DIRT, PLACE_DIRT, CHECK_DIRT,
		SELECT_TORCH, AIM_TORCH, PLACE_TORCH, CHECK_TORCH, BREAK_TORCH, CHECK_BROKEN, PICKUP, SWAP_OFFHAND, SWAP_BACK,
		DROP, OPEN_INV, CLICK_1, CLICK_2, CLOSE_INV, WAIT_TELEPORT, AFTER_TELEPORT, DISCONNECT, WAIT_KICK, WAIT_EXIT, DONE
	}

	private Step step = Step.WAIT_JOIN;
	private int ticksInStep;
	private int totalTicks;
	private Vec3 mark;
	private BlockPos dirtPos;
	private BlockPos torchPos;
	private int torchCountBefore;
	private float placePitch = 40.0F;

	@Override
	public void onInitializeClient() {
		if (!Boolean.getBoolean("mcresearch.localtest")) {
			return;
		}

		LOG.info(P + "Local test driver enabled");
		enablePacketLogging();
		blockJndiDns();
		ClientTickEvents.END_CLIENT_TICK.register(this::onEndTick);
	}

	/** Aktiviert die vorhandenen DEBUG-Ausgaben von PacketDecoder/PacketEncoder ("IN:"/"OUT:") → logs/debug.log. */
	private static void enablePacketLogging() {
		Configurator.setLevel("net.minecraft.network.PacketDecoder", Level.DEBUG);
		Configurator.setLevel("net.minecraft.network.PacketEncoder", Level.DEBUG);
		Configurator.setLevel("net.minecraft.network.Connection", Level.DEBUG);
		LOG.info(P + "Packet DEBUG logging enabled for PacketDecoder/PacketEncoder/Connection");
	}

	/**
	 * Verhindert die SRV-DNS-Abfrage "_minecraft._tcp.localhost" (ServerRedirectHandler#createDnsSrvRedirectHandler),
	 * damit kein externer DNS-Server kontaktiert wird. Der Handler fällt dadurch auf ServerRedirectHandler.EMPTY zurück.
	 */
	static void blockJndiDns() {
		try {
			NamingManager.setInitialContextFactoryBuilder((Hashtable<?, ?> env) -> environment -> {
				throw new NoInitialContextException("JNDI disabled by mc-research local test (no external DNS)");
			});
			LOG.info(P + "JNDI (SRV-DNS) disabled for this run");
		} catch (Exception e) {
			LOG.warn(P + "Could not disable JNDI", e);
		}
	}

	private void onEndTick(final Minecraft mc) {
		totalTicks++;
		if (step == Step.DONE) {
			return;
		}

		LocalPlayer player = mc.player;
		ClientLevel level = mc.level;
		if (step == Step.WAIT_KICK && (player == null || level == null || mc.getConnection() == null)) {
			LOG.info(P + "KICKED by server, screen={} title={}",
				mc.gui.screen() == null ? "none" : mc.gui.screen().getClass().getSimpleName(),
				mc.gui.screen() == null ? "-" : mc.gui.screen().getTitle().getString());
			go(Step.WAIT_EXIT);
			return;
		}
		if (step != Step.WAIT_JOIN && step != Step.WAIT_EXIT && (player == null || level == null)) {
			LOG.warn(P + "player/level lost in step {}", step);
			go(Step.WAIT_EXIT);
			return;
		}

		ticksInStep++;
		switch (step) {
			case WAIT_JOIN -> {
				if (player != null && level != null && mc.gui.screen() == null) {
					mc.options.pauseOnLostFocus = false;
					int used = 0;
					for (int i = 0; i < player.getInventory().getContainerSize(); i++) {
						if (!player.getInventory().getItem(i).isEmpty()) {
							used++;
						}
					}
					LOG.info(P + "JOINED name={} pos={} chunks={} windowActive={} mouseGrabbed={} nonEmptySlots={}",
						player.getGameProfile().name(), fmt(player.position()), chunks(mc), mc.isWindowActive(), mc.mouseHandler.isMouseGrabbed(), used);
					if (used > 0) {
						LOG.warn(P + "inventory not empty at join (persisted player data?) - block/inventory results may be invalid");
					}
					go(Step.SETTLE);
				} else if (ticksInStep % 100 == 0) {
					LOG.info(P + "waiting for join ({} ticks), screen={}", ticksInStep,
						mc.gui.screen() == null ? "none" : mc.gui.screen().getClass().getSimpleName());
				}
			}
			case SETTLE -> {
				if (ticksInStep == 1 || ticksInStep % 20 == 0) {
					LOG.info(P + "SETTLE t={} pos={} onGround={} chunks={}", ticksInStep, fmt(player.position()), player.onGround(), chunks(mc));
				}
				if (ticksInStep >= 100 && player.onGround()) {
					mark = player.position();
					player.setXRot(0.0F);
					LOG.info(P + "MOVE start pos={} yRot={}", fmt(mark), player.getYRot());
					press(mc.options.keyUp, true);
					go(Step.MOVE_FORWARD);
				} else if (ticksInStep >= 400) {
					LOG.warn(P + "player not on ground after 400 ticks, continuing anyway");
					mark = player.position();
					press(mc.options.keyUp, true);
					go(Step.MOVE_FORWARD);
				}
			}
			case MOVE_FORWARD -> {
				if (ticksInStep >= 30) {
					press(mc.options.keyUp, false);
					LOG.info(P + "MOVE forward done pos={} horizDist={}", fmt(player.position()), horiz(mark, player.position()));
					press(mc.options.keyJump, true);
					mark = player.position();
					go(Step.MOVE_JUMP);
				}
			}
			case MOVE_JUMP -> {
				if (ticksInStep == 2) {
					press(mc.options.keyJump, false);
				}
				if (ticksInStep == 6) {
					LOG.info(P + "JUMP peak-ish y={} (start y={})", fmt1(player.getY()), fmt1(mark.y));
				}
				if (ticksInStep >= 30) {
					LOG.info(P + "READY_FOR_ITEMS pos={} (console: give dirt + torch)", fmt(player.position()));
					go(Step.WAIT_ITEMS);
				}
			}
			case WAIT_ITEMS -> {
				int dirt = count(player, Items.DIRT);
				int torch = count(player, Items.TORCH);
				if (dirt >= 4 && torch >= 4 && slotOf(player, Items.DIRT) != slotOf(player, Items.TORCH)) {
					LOG.info(P + "ITEMS received dirt={} torch={} slots: dirt@{} torch@{}", dirt, torch, slotOf(player, Items.DIRT), slotOf(player, Items.TORCH));
					go(Step.HOTBAR);
				} else if (ticksInStep >= 1200) {
					LOG.warn(P + "no items after 60s, skipping block/inventory tests");
					go(Step.WAIT_TELEPORT);
				}
			}
			case HOTBAR -> {
				int dirtSlot = slotOf(player, Items.DIRT);
				if (ticksInStep == 1) {
					int other = dirtSlot == 8 ? 7 : 8;
					LOG.info(P + "HOTBAR select slot {} then dirt slot {}", other, dirtSlot);
					click(mc.options.keyHotbarSlots[other]);
				} else if (ticksInStep == 5) {
					click(mc.options.keyHotbarSlots[dirtSlot]);
				} else if (ticksInStep == 10) {
					LOG.info(P + "HOTBAR selected={} item={}", player.getInventory().getSelectedSlot(), player.getMainHandItem().getItem());
					go(Step.AIM_PLACE_DIRT);
				}
			}
			case AIM_PLACE_DIRT -> {
				// Blick schräg nach unten, bis das Fadenkreuz die Oberseite eines Blocks in 1,5–4 m Entfernung trifft.
				player.setXRot(placePitch);
				if (ticksInStep % 2 == 0) {
					HitResult hit = mc.hitResult;
					if (hit instanceof BlockHitResult bh && hit.getType() == HitResult.Type.BLOCK && bh.getDirection() == Direction.UP
						&& level.getBlockState(bh.getBlockPos().above()).isAir()
						&& level.getBlockState(bh.getBlockPos().above(2)).isAir()
						&& player.getEyePosition().distanceTo(hit.getLocation()) > 2.0) {
						dirtPos = bh.getBlockPos().above();
						LOG.info(P + "AIM dirt target={} pitch={} hitDist={}", dirtPos.toShortString(), placePitch, fmt1(player.getEyePosition().distanceTo(hit.getLocation())));
						click(mc.options.keyUse);
						go(Step.PLACE_DIRT);
					} else {
						placePitch += 5.0F;
						if (placePitch > 85.0F) {
							placePitch = 40.0F;
							player.setYRot(player.getYRot() + 45.0F);
						}
						if (ticksInStep > 200) {
							LOG.warn(P + "no suitable placement target found, skipping block tests");
							go(Step.SWAP_OFFHAND);
						}
					}
				}
			}
			case PLACE_DIRT -> {
				if (ticksInStep >= 10) {
					go(Step.CHECK_DIRT);
				}
			}
			case CHECK_DIRT -> {
				Block b = level.getBlockState(dirtPos).getBlock();
				LOG.info(P + "PLACE dirt at {} -> clientBlock={} dirtLeft={}", dirtPos.toShortString(), b, count(player, Items.DIRT));
				go(Step.SELECT_TORCH);
			}
			case SELECT_TORCH -> {
				if (ticksInStep == 1) {
					click(mc.options.keyHotbarSlots[slotOf(player, Items.TORCH)]);
				} else if (ticksInStep >= 5) {
					go(Step.AIM_TORCH);
				}
			}
			case AIM_TORCH -> {
				lookAt(player, Vec3.atBottomCenterOf(dirtPos).add(0, 1.0, 0));
				if (ticksInStep >= 3) {
					HitResult hit = mc.hitResult;
					LOG.info(P + "AIM torch hit={} pos={} face={}", hit == null ? null : hit.getType(),
						hit instanceof BlockHitResult bh ? bh.getBlockPos().toShortString() : "-", hit instanceof BlockHitResult bh2 ? bh2.getDirection() : "-");
					torchPos = dirtPos.above();
					click(mc.options.keyUse);
					go(Step.PLACE_TORCH);
				}
			}
			case PLACE_TORCH -> {
				if (ticksInStep >= 10) {
					go(Step.CHECK_TORCH);
				}
			}
			case CHECK_TORCH -> {
				Block b = level.getBlockState(torchPos).getBlock();
				torchCountBefore = count(player, Items.TORCH);
				LOG.info(P + "PLACE torch at {} -> clientBlock={} torchLeft={}", torchPos.toShortString(), b, torchCountBefore);
				go(Step.BREAK_TORCH);
			}
			case BREAK_TORCH -> {
				lookAt(player, Vec3.atCenterOf(torchPos).add(0, -0.2, 0));
				if (ticksInStep == 3) {
					HitResult hit = mc.hitResult;
					LOG.info(P + "BREAK torch aim hit={} pos={}", hit == null ? null : hit.getType(),
						hit instanceof BlockHitResult bh ? bh.getBlockPos().toShortString() : "-");
					click(mc.options.keyAttack); // startAttack -> instant break (destroyTime 0) in survival
				}
				if (ticksInStep >= 15) {
					go(Step.CHECK_BROKEN);
				}
			}
			case CHECK_BROKEN -> {
				LOG.info(P + "BREAK torch -> clientBlock={} torchCount={}", level.getBlockState(torchPos).getBlock(), count(player, Items.TORCH));
				mark = player.position();
				lookAt(player, Vec3.atBottomCenterOf(torchPos));
				player.setXRot(0.0F);
				press(mc.options.keyUp, true);
				go(Step.PICKUP);
			}
			case PICKUP -> {
				lookAt(player, Vec3.atBottomCenterOf(torchPos));
				player.setXRot(0.0F);
				int now = count(player, Items.TORCH);
				if (now > torchCountBefore || ticksInStep >= 40 || horiz(Vec3.atBottomCenterOf(torchPos), player.position()) < 0.6) {
					press(mc.options.keyUp, false);
					LOG.info(P + "PICKUP after {} ticks torchCount {} -> {} pos={}", ticksInStep, torchCountBefore, now, fmt(player.position()));
					if (ticksInStep >= 40 || now > torchCountBefore) {
						go(Step.SWAP_OFFHAND);
					}
				}
			}
			case SWAP_OFFHAND -> {
				if (ticksInStep == 1) {
					LOG.info(P + "SWAP_OFFHAND main={} off={}", player.getMainHandItem().getItem(), player.getOffhandItem().getItem());
					click(mc.options.keySwapOffhand);
				} else if (ticksInStep >= 10) {
					LOG.info(P + "SWAP_OFFHAND done main={} off={}", player.getMainHandItem().getItem(), player.getOffhandItem().getItem());
					go(Step.SWAP_BACK);
				}
			}
			case SWAP_BACK -> {
				if (ticksInStep == 1) {
					click(mc.options.keySwapOffhand);
				} else if (ticksInStep >= 10) {
					LOG.info(P + "SWAP_BACK done main={} off={}", player.getMainHandItem().getItem(), player.getOffhandItem().getItem());
					go(Step.DROP);
				}
			}
			case DROP -> {
				if (ticksInStep == 1) {
					player.setXRot(-30.0F);
					LOG.info(P + "DROP one of {} (count {})", player.getMainHandItem().getItem(), player.getMainHandItem().getCount());
					click(mc.options.keyDrop);
				} else if (ticksInStep >= 10) {
					LOG.info(P + "DROP done main={} count={}", player.getMainHandItem().getItem(), player.getMainHandItem().getCount());
					go(Step.OPEN_INV);
				}
			}
			case OPEN_INV -> {
				if (ticksInStep == 1) {
					click(mc.options.keyInventory);
				} else if (ticksInStep >= 10) {
					LOG.info(P + "INVENTORY screen={} containerId={}", mc.gui.screen() == null ? "none" : mc.gui.screen().getClass().getSimpleName(), player.inventoryMenu.containerId);
					go(Step.CLICK_1);
				}
			}
			case CLICK_1 -> {
				if (ticksInStep == 1) {
					// Gleicher Aufruf wie AbstractContainerScreen bei Linksklick auf Slot 36 (Hotbar 0)
					int slot = 36 + slotOf(player, Items.DIRT);
					LOG.info(P + "CONTAINER pickup slot {} ({})", slot, player.inventoryMenu.getSlot(slot).getItem());
					mc.gameMode.handleContainerInput(player.inventoryMenu.containerId, slot, 0, ContainerInput.PICKUP, player);
				} else if (ticksInStep >= 5) {
					go(Step.CLICK_2);
				}
			}
			case CLICK_2 -> {
				if (ticksInStep == 1) {
					LOG.info(P + "CONTAINER place into slot 9, carried={}", player.inventoryMenu.getCarried());
					mc.gameMode.handleContainerInput(player.inventoryMenu.containerId, 9, 0, ContainerInput.PICKUP, player);
				} else if (ticksInStep >= 5) {
					LOG.info(P + "CONTAINER slot9={} carried={}", player.inventoryMenu.getSlot(9).getItem(), player.inventoryMenu.getCarried());
					go(Step.CLOSE_INV);
				}
			}
			case CLOSE_INV -> {
				if (ticksInStep == 1 && mc.gui.screen() instanceof InventoryScreen) {
					mc.gui.screen().onClose();
				} else if (ticksInStep >= 5) {
					LOG.info(P + "INVENTORY closed screen={} -> READY_FOR_TELEPORT pos={} chunks={}",
						mc.gui.screen() == null ? "none" : mc.gui.screen().getClass().getSimpleName(), fmt(player.position()), chunks(mc));
					mark = player.position();
					go(Step.WAIT_TELEPORT);
				}
			}
			case WAIT_TELEPORT -> {
				if (mark == null) {
					mark = player.position();
				}
				if (horiz(mark, player.position()) > 100) {
					LOG.info(P + "TELEPORTED pos={} chunksNow={}", fmt(player.position()), chunks(mc));
					go(Step.AFTER_TELEPORT);
				} else if (ticksInStep >= 1200) {
					LOG.warn(P + "no teleport after 60s");
					go(Step.DISCONNECT);
				}
			}
			case AFTER_TELEPORT -> {
				if (ticksInStep % 20 == 0) {
					LOG.info(P + "AFTER_TELEPORT t={} chunks={} pos={}", ticksInStep, chunks(mc), fmt(player.position()));
				}
				if (ticksInStep >= 200) {
					go(Step.DISCONNECT);
				}
			}
			case DISCONNECT -> {
				// Flag-Datei im Laufverzeichnis wählt den serverseitigen Disconnect (Konsole: kick) statt des clientseitigen.
				if (java.nio.file.Files.exists(mc.gameDirectory.toPath().resolve("localtest-kick.flag"))) {
					LOG.info(P + "READY_FOR_KICK (waiting for server-side disconnect)");
					go(Step.WAIT_KICK);
				} else {
					LOG.info(P + "DISCONNECT via Minecraft#disconnectFromWorld (same as pause menu)");
					mc.disconnectFromWorld(ClientLevel.DEFAULT_QUIT_MESSAGE);
					go(Step.WAIT_EXIT);
				}
			}
			case WAIT_KICK -> {
				if (ticksInStep >= 1200) {
					LOG.warn(P + "no kick after 60s, disconnecting client-side");
					go(Step.DISCONNECT);
					new java.io.File(mc.gameDirectory, "localtest-kick.flag").delete();
				}
			}
			case WAIT_EXIT -> {
				if (ticksInStep == 40) {
					LOG.info(P + "screen after disconnect={} -> stopping client",
						mc.gui.screen() == null ? "none" : mc.gui.screen().getClass().getSimpleName());
					step = Step.DONE;
					mc.stop();
				}
			}
			default -> {
			}
		}
	}

	private void go(final Step next) {
		LOG.info(P + "STEP {} -> {} (tick {})", step, next, totalTicks);
		step = next;
		ticksInStep = 0;
	}

	/** Wie KeyboardHandler#keyPress / MouseHandler#onButton: Taste setzen und bei Druck den Klickzähler erhöhen. */
	private static void press(final KeyMapping mapping, final boolean down) {
		InputConstants.Key key = InputConstants.getKey(mapping.saveString());
		KeyMapping.set(key, down);
		if (down) {
			KeyMapping.click(key);
		}
	}

	private static void click(final KeyMapping mapping) {
		press(mapping, true);
		press(mapping, false);
	}

	private static void lookAt(final LocalPlayer player, final Vec3 target) {
		Vec3 d = target.subtract(player.getEyePosition());
		double h = Math.sqrt(d.x * d.x + d.z * d.z);
		player.setYRot(Mth.wrapDegrees((float) (Mth.atan2(d.z, d.x) * (180.0 / Math.PI)) - 90.0F));
		player.setXRot((float) -(Mth.atan2(d.y, h) * (180.0 / Math.PI)));
	}

	private static int count(final LocalPlayer player, final Item item) {
		int n = 0;
		for (int i = 0; i < player.getInventory().getContainerSize(); i++) {
			if (player.getInventory().getItem(i).getItem() == item) {
				n += player.getInventory().getItem(i).getCount();
			}
		}
		return n;
	}

	private static int slotOf(final LocalPlayer player, final Item item) {
		for (int i = 0; i < 9; i++) {
			if (player.getInventory().getItem(i).getItem() == item) {
				return i;
			}
		}
		return 0;
	}

	private static int chunks(final Minecraft mc) {
		return mc.level == null ? -1 : mc.level.getChunkSource().getLoadedChunksCount();
	}

	private static double horiz(final Vec3 a, final Vec3 b) {
		return Math.sqrt((a.x - b.x) * (a.x - b.x) + (a.z - b.z) * (a.z - b.z));
	}

	private static String fmt(final Vec3 v) {
		return String.format("(%.2f, %.2f, %.2f)", v.x, v.y, v.z);
	}

	private static String fmt1(final double d) {
		return String.format("%.2f", d);
	}
}
