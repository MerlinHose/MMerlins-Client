package dev.mcresearch.protocolclient.session;

import dev.mcresearch.protocolclient.protocol.packet.Clientbound;

/**
 * Clientseitiger Spielerzustand (docs/15 §5).
 *
 * <p>Unterscheidet zwischen der vom Client <b>behaupteten</b> Position ({@link #x()}…) und der zuletzt vom Server
 * <b>gesetzten</b> Position ({@link #serverX()}…, aus {@code player_position}). Ob der Server eine Behauptung
 * akzeptiert, zeigt sich daran, dass keine Korrektur folgt (und serverseitig z. B. per {@code /data get entity}).
 */
public final class PlayerState {
	private double x;
	private double y;
	private double z;
	private float yRot;
	private float xRot;
	private boolean onGround;
	private boolean horizontalCollision;
	private double velocityX;
	private double velocityY;
	private double velocityZ;
	private boolean positioned;
	private double serverX;
	private double serverY;
	private double serverZ;
	private int serverPositionUpdates;

	/** {@code player_position} wie im Vanilla-Client anwenden: Relative-Bits addieren, sonst absolut übernehmen. */
	public synchronized void apply(final Clientbound.PlayerPosition p) {
		int rel = p.relatives();
		this.x = (rel & Clientbound.PlayerPosition.REL_X) != 0 ? this.x + p.x() : p.x();
		this.y = (rel & Clientbound.PlayerPosition.REL_Y) != 0 ? this.y + p.y() : p.y();
		this.z = (rel & Clientbound.PlayerPosition.REL_Z) != 0 ? this.z + p.z() : p.z();
		this.yRot = (rel & Clientbound.PlayerPosition.REL_Y_ROT) != 0 ? this.yRot + p.yRot() : p.yRot();
		this.xRot = clampPitch((rel & Clientbound.PlayerPosition.REL_X_ROT) != 0 ? this.xRot + p.xRot() : p.xRot());
		// Geschwindigkeit wie PositionMoveRotation#calculateAbsolute (DELTA-Bits relativ; ROTATE_DELTA nicht modelliert)
		this.velocityX = (rel & Clientbound.PlayerPosition.REL_DELTA_X) != 0 ? this.velocityX + p.dx() : p.dx();
		this.velocityY = (rel & Clientbound.PlayerPosition.REL_DELTA_Y) != 0 ? this.velocityY + p.dy() : p.dy();
		this.velocityZ = (rel & Clientbound.PlayerPosition.REL_DELTA_Z) != 0 ? this.velocityZ + p.dz() : p.dz();
		this.positioned = true;
		this.serverX = this.x;
		this.serverY = this.y;
		this.serverZ = this.z;
		this.serverPositionUpdates++;
	}

	/** {@code player_rotation}: absolute oder relative Rotation (wie {@code ClientPacketListener#handleRotatePlayer}). */
	public synchronized void applyRotation(final Clientbound.PlayerRotation r) {
		this.yRot = r.relativeY() ? this.yRot + r.yRot() : r.yRot();
		this.xRot = clampPitch(r.relativeX() ? this.xRot + r.xRot() : r.xRot());
	}

	static float clampPitch(final float pitch) {
		return Math.max(-90f, Math.min(90f, pitch));
	}

	synchronized void setPosition(final double nx, final double ny, final double nz) {
		this.x = nx;
		this.y = ny;
		this.z = nz;
	}

	synchronized void setRotation(final float yaw, final float pitch) {
		this.yRot = yaw;
		this.xRot = clampPitch(pitch);
	}

	synchronized void setGroundState(final boolean ground, final boolean collision, final double vy) {
		this.onGround = ground;
		this.horizontalCollision = collision;
		this.velocityY = vy;
	}

	/** Ergebnis eines Physik-Ticks übernehmen. */
	synchronized void setPhysics(final double nx, final double ny, final double nz, final double nvx, final double nvy, final double nvz,
		final boolean ground, final boolean collision) {
		this.x = nx;
		this.y = ny;
		this.z = nz;
		this.velocityX = nvx;
		this.velocityY = nvy;
		this.velocityZ = nvz;
		this.onGround = ground;
		this.horizontalCollision = collision;
	}

	public synchronized double velocityX() {
		return this.velocityX;
	}

	public synchronized double velocityZ() {
		return this.velocityZ;
	}

	public synchronized double x() {
		return this.x;
	}

	public synchronized double y() {
		return this.y;
	}

	public synchronized double z() {
		return this.z;
	}

	public synchronized float yRot() {
		return this.yRot;
	}

	public synchronized float xRot() {
		return this.xRot;
	}

	public synchronized boolean onGround() {
		return this.onGround;
	}

	public synchronized boolean horizontalCollision() {
		return this.horizontalCollision;
	}

	public synchronized double velocityY() {
		return this.velocityY;
	}

	public synchronized boolean positioned() {
		return this.positioned;
	}

	public synchronized double serverX() {
		return this.serverX;
	}

	public synchronized double serverY() {
		return this.serverY;
	}

	public synchronized double serverZ() {
		return this.serverZ;
	}

	/** Anzahl empfangener {@code player_position}-Pakete (Spawn + Korrekturen + Teleports). */
	public synchronized int serverPositionUpdates() {
		return this.serverPositionUpdates;
	}

	@Override
	public synchronized String toString() {
		return String.format(java.util.Locale.ROOT, "(%.3f, %.3f, %.3f) yaw=%.1f pitch=%.1f onGround=%s", this.x, this.y, this.z, this.yRot, this.xRot, this.onGround);
	}
}
