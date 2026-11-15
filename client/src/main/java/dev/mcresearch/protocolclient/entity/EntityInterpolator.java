package dev.mcresearch.protocolclient.entity;

import java.util.ArrayDeque;

/**
 * Darstellungs-Interpolation einer Entity (M9, docs/35 §4). Eigener Entwurf, nicht der Vanilla-Algorithmus.
 *
 * <ul>
 *   <li>Ziele werden als Schritte (Position, Rotation, Dauer in Ticks) in eine Warteschlange gestellt und je Client-Tick
 *       abgearbeitet; gezeichnet wird zwischen dem vorigen und dem aktuellen Tick-Zustand ({@link #renderX(float)} …).</li>
 *   <li><b>Verspätete Updates</b> (Rückstand größer als zwei Intervalle) werden schneller abgearbeitet; ab
 *       {@link #MAX_BACKLOG_TICKS} springt die Darstellung auf das letzte Ziel.</li>
 *   <li><b>Fehlende Daten:</b> keine Extrapolation – ohne neue Ziele bleibt die Entity am letzten bestätigten Punkt.</li>
 *   <li><b>Teleport:</b> {@link #snap} setzt alles sofort, ohne Zwischenbilder.</li>
 *   <li>Kopfdrehung unabhängig vom Körper, über {@link #HEAD_TICKS} Ticks.</li>
 * </ul>
 * Rein deterministisch: gleiche Folge von Aufrufen ergibt gleiche Zustände (Unit-Test {@code EntityInterpolatorTest}).
 */
public final class EntityInterpolator {
	public static final int MAX_BACKLOG_TICKS = 40;
	public static final int HEAD_TICKS = 3;

	private record Step(double x, double y, double z, float yRot, float xRot, int ticks) {
	}

	private final int interpolationTicks;
	private final ArrayDeque<Step> queue = new ArrayDeque<>();
	private double x, y, z, prevX, prevY, prevZ;
	private float yRot, xRot, prevYRot, prevXRot;
	private float head, prevHead, headTarget;
	private int headTicksLeft;
	/** Anfang des laufenden Schritts und bereits verstrichene Ticks darin. */
	private double fromX, fromY, fromZ;
	private float fromYRot, fromXRot;
	private double progress;
	private int queuedTicks;
	/** Letztes Ziel (Ende der Warteschlange) – Basis für reine Rotationsziele. */
	private double lastX, lastY, lastZ;
	private float lastYRot, lastXRot;
	private long snaps;
	private long catchUps;

	/** @param interpolationTicks Ticks je linearem Ziel (Typ-Aktualisierungsintervall, begrenzt auf 1–3) */
	public EntityInterpolator(final int interpolationTicks) {
		this.interpolationTicks = Math.max(1, interpolationTicks);
	}

	/** Sofort auf den Zustand setzen (Spawn, Teleport, zu großer Abstand, Überlauf). */
	public void snap(final double nx, final double ny, final double nz, final float nyRot, final float nxRot) {
		this.queue.clear();
		this.queuedTicks = 0;
		this.progress = 0;
		this.x = this.prevX = this.fromX = this.lastX = nx;
		this.y = this.prevY = this.fromY = this.lastY = ny;
		this.z = this.prevZ = this.fromZ = this.lastZ = nz;
		this.yRot = this.prevYRot = this.fromYRot = this.lastYRot = nyRot;
		this.xRot = this.prevXRot = this.fromXRot = this.lastXRot = nxRot;
		this.snaps++;
	}

	public void snapHead(final float h) {
		this.head = this.prevHead = this.headTarget = h;
		this.headTicksLeft = 0;
	}

	/** Ein Ziel mit eigener Dauer ({@code ticks} ≤ 0 → Standarddauer des Typs). */
	public void addTarget(final double nx, final double ny, final double nz, final float nyRot, final float nxRot, final int ticks) {
		int t = ticks <= 0 ? this.interpolationTicks : ticks;
		if (this.queue.isEmpty()) {
			// neuer Abschnitt beginnt an der aktuell dargestellten Stelle
			this.fromX = this.x;
			this.fromY = this.y;
			this.fromZ = this.z;
			this.fromYRot = this.yRot;
			this.fromXRot = this.xRot;
			this.progress = 0;
		}
		this.queue.addLast(new Step(nx, ny, nz, nyRot, nxRot, t));
		this.queuedTicks += t;
		this.lastX = nx;
		this.lastY = ny;
		this.lastZ = nz;
		this.lastYRot = nyRot;
		this.lastXRot = nxRot;
		if (this.queuedTicks > MAX_BACKLOG_TICKS) {
			// zu großer Rückstand (z. B. Paketstau): auf das letzte Ziel springen statt Sekunden hinterherzulaufen
			float h = this.head;
			snap(nx, ny, nz, nyRot, nxRot);
			this.head = h;
		}
	}

	/** Reine Rotation: Position bleibt das letzte Ziel. */
	public void addRotation(final float nyRot, final float nxRot) {
		addTarget(this.lastX, this.lastY, this.lastZ, nyRot, nxRot, 0);
	}

	/** Reine Position: Rotation bleibt die des letzten Ziels. */
	public void addPosition(final double nx, final double ny, final double nz, final int ticks) {
		addTarget(nx, ny, nz, this.lastYRot, this.lastXRot, ticks);
	}

	public void setHeadTarget(final float h) {
		this.headTarget = h;
		this.headTicksLeft = HEAD_TICKS;
	}

	/** Ein Client-Tick: vorigen Zustand merken, Warteschlange um 1 (oder schneller bei Rückstand) abarbeiten. */
	public void tick() {
		this.prevX = this.x;
		this.prevY = this.y;
		this.prevZ = this.z;
		this.prevYRot = this.yRot;
		this.prevXRot = this.xRot;
		this.prevHead = this.head;
		if (this.headTicksLeft > 0) {
			this.head += wrapDegrees(this.headTarget - this.head) / this.headTicksLeft;
			this.headTicksLeft--;
		}
		if (this.queue.isEmpty()) {
			return;
		}
		double advance = 1.0;
		double remaining = this.queuedTicks - this.progress;
		if (remaining > 2 * this.interpolationTicks) {
			advance = remaining / (2.0 * this.interpolationTicks);
			this.catchUps++;
		}
		this.progress += advance;
		while (!this.queue.isEmpty() && this.progress >= this.queue.peekFirst().ticks()) {
			Step s = this.queue.removeFirst();
			this.progress -= s.ticks();
			this.queuedTicks -= s.ticks();
			this.fromX = s.x();
			this.fromY = s.y();
			this.fromZ = s.z();
			this.fromYRot = s.yRot();
			this.fromXRot = s.xRot();
		}
		if (this.queue.isEmpty()) {
			this.progress = 0;
			this.x = this.fromX;
			this.y = this.fromY;
			this.z = this.fromZ;
			this.yRot = this.fromYRot;
			this.xRot = this.fromXRot;
			return;
		}
		Step s = this.queue.peekFirst();
		double a = this.progress / s.ticks();
		this.x = this.fromX + (s.x() - this.fromX) * a;
		this.y = this.fromY + (s.y() - this.fromY) * a;
		this.z = this.fromZ + (s.z() - this.fromZ) * a;
		this.yRot = (float) (this.fromYRot + wrapDegrees(s.yRot() - this.fromYRot) * a);
		this.xRot = (float) (this.fromXRot + (s.xRot() - this.fromXRot) * a);
	}

	public static float wrapDegrees(final float d) {
		float r = d % 360.0F;
		if (r >= 180.0F) {
			r -= 360.0F;
		}
		if (r < -180.0F) {
			r += 360.0F;
		}
		return r;
	}

	public double x() {
		return this.x;
	}

	public double y() {
		return this.y;
	}

	public double z() {
		return this.z;
	}

	public float yRot() {
		return this.yRot;
	}

	public float xRot() {
		return this.xRot;
	}

	public float head() {
		return this.head;
	}

	public double renderX(final float pt) {
		return this.prevX + (this.x - this.prevX) * pt;
	}

	public double renderY(final float pt) {
		return this.prevY + (this.y - this.prevY) * pt;
	}

	public double renderZ(final float pt) {
		return this.prevZ + (this.z - this.prevZ) * pt;
	}

	public float renderYRot(final float pt) {
		return this.prevYRot + wrapDegrees(this.yRot - this.prevYRot) * pt;
	}

	public float renderXRot(final float pt) {
		return this.prevXRot + (this.xRot - this.prevXRot) * pt;
	}

	public float renderHead(final float pt) {
		return this.prevHead + wrapDegrees(this.head - this.prevHead) * pt;
	}

	/** Bewegt sich die Darstellung gerade (für Laufanimation): Weg im letzten Tick. */
	public double horizontalSpeed() {
		double dx = this.x - this.prevX;
		double dz = this.z - this.prevZ;
		return Math.sqrt(dx * dx + dz * dz);
	}

	public int queuedTicks() {
		return this.queuedTicks;
	}

	public boolean idle() {
		return this.queue.isEmpty();
	}

	public long snaps() {
		return this.snaps;
	}

	public long catchUps() {
		return this.catchUps;
	}
}
