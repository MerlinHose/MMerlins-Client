package dev.mcresearch.protocolclient.physics;

import dev.mcresearch.protocolclient.world.BlockRegistry;
import java.util.HashMap;
import java.util.Map;

/** Blockwelt für Physiktests: Luft, gesetzte Blöcke; außerhalb von ±{@code loadedRadius} gilt „nicht geladen“. */
final class TestWorld implements BlockView {
	static final BlockRegistry REG = BlockRegistry.get();
	static final int AIR = 0;
	static final int STONE = 1;

	private final Map<Long, Integer> blocks = new HashMap<>();
	private final int loadedRadius;

	TestWorld(final int loadedRadius) {
		this.loadedRadius = loadedRadius;
	}

	TestWorld() {
		this(1000);
	}

	static int id(final String name, final String props) {
		Map<String, String> m = new HashMap<>();
		if (!props.isEmpty()) {
			for (String kv : props.split(",")) {
				String[] p = kv.split("=");
				m.put(p[0], p[1]);
			}
		}
		return REG.idOf(name, m);
	}

	private static long key(final int x, final int y, final int z) {
		return ((long) x & 0x3FFFFFF) << 38 | ((long) z & 0x3FFFFFF) << 12 | (y & 0xFFFL);
	}

	TestWorld set(final int x, final int y, final int z, final int state) {
		this.blocks.put(key(x, y, z), state);
		return this;
	}

	TestWorld fill(final int x1, final int y1, final int z1, final int x2, final int y2, final int z2, final int state) {
		for (int x = Math.min(x1, x2); x <= Math.max(x1, x2); x++) {
			for (int y = Math.min(y1, y2); y <= Math.max(y1, y2); y++) {
				for (int z = Math.min(z1, z2); z <= Math.max(z1, z2); z++) {
					set(x, y, z, state);
				}
			}
		}
		return this;
	}

	/** Steinboden mit Oberkante y = top + 1 über dem Quadrat ±r. */
	TestWorld floor(final int top, final int r) {
		return fill(-r, top, -r, r, top, r, STONE);
	}

	@Override
	public int stateAt(final int x, final int y, final int z) {
		if (Math.abs(x) > this.loadedRadius || Math.abs(z) > this.loadedRadius) {
			return UNLOADED;
		}
		return this.blocks.getOrDefault(key(x, y, z), AIR);
	}

	PlayerPhysics player(final double x, final double y, final double z, final float yaw) {
		PlayerPhysics p = new PlayerPhysics(new CollisionWorld(this, ShapeRegistry.get()), REG);
		p.setPosition(x, y, z, 0, 0, 0);
		p.setYRot(yaw);
		return p;
	}
}
