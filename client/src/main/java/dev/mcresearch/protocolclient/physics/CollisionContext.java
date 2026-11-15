package dev.mcresearch.protocolclient.physics;

/**
 * Kontext einer Kollisionsabfrage ({@code EntityCollisionContext}): Unterkante der Entity, Schleichen, Fallstrecke.
 * Wirkt nur auf Gerüst und Pulverschnee (docs/21 §6).
 */
public record CollisionContext(double entityBottom, boolean descending, double fallDistance) {
	/** {@code EntityCollisionContext#isAbove}: Unterkante &gt; y + maxY − 1e−5F. */
	public boolean isAbove(final double shapeMaxY, final int blockY) {
		return this.entityBottom > blockY + shapeMaxY - 1.0E-5F;
	}
}
