package dev.mcresearch.protocolclient.physics;

/**
 * Spielerposen mit Hitbox und Augenhöhe (docs/42 §2). Breite immer 0,6F; Höhe und Augenhöhe je Pose als {@code float}
 * wie die Vanilla-Abmessungen. Die Protokoll-Ordinalzahl ist die von {@code Pose} (Entity-Metadaten Index 6).
 */
public enum PlayerPose {
	STANDING(1.8F, 1.62F, 0),
	CROUCHING(1.5F, 1.27F, 5),
	SWIMMING(0.6F, 0.4F, 3);

	public static final float WIDTH = 0.6F;

	private final float height;
	private final float eyeHeight;
	private final int protocolId;

	PlayerPose(final float height, final float eyeHeight, final int protocolId) {
		this.height = height;
		this.eyeHeight = eyeHeight;
		this.protocolId = protocolId;
	}

	public float height() {
		return this.height;
	}

	public float eyeHeight() {
		return this.eyeHeight;
	}

	public int protocolId() {
		return this.protocolId;
	}

	/** Hitbox an (x, y, z): halbe Breite und Höhe als {@code float}, dann {@code double}-Addition. */
	public Aabb box(final double x, final double y, final double z) {
		float w = WIDTH / 2.0F;
		float h = this.height;
		return new Aabb(x - w, y, z - w, x + w, y + h, z + w);
	}
}
