package dev.mcresearch.protocolclient.ui;

/** Rechteck in logischen GUI-Einheiten (Ursprung oben links). */
public record Rect(double x, double y, double w, double h) {
	public static final Rect EMPTY = new Rect(0, 0, 0, 0);

	public boolean contains(final double px, final double py) {
		return px >= this.x && py >= this.y && px < this.x + this.w && py < this.y + this.h;
	}

	public double right() {
		return this.x + this.w;
	}

	public double bottom() {
		return this.y + this.h;
	}

	public double centerX() {
		return this.x + this.w / 2;
	}

	public double centerY() {
		return this.y + this.h / 2;
	}

	public Rect inset(final double d) {
		return new Rect(this.x + d, this.y + d, Math.max(0, this.w - 2 * d), Math.max(0, this.h - 2 * d));
	}

	public Rect offset(final double dx, final double dy) {
		return new Rect(this.x + dx, this.y + dy, this.w, this.h);
	}

	/** true, wenn {@code r} vollständig in diesem Rechteck liegt (Toleranz für Rundung). */
	public boolean encloses(final Rect r) {
		final double e = 1e-6;
		return r.x >= this.x - e && r.y >= this.y - e && r.right() <= right() + e && r.bottom() <= bottom() + e;
	}

	public boolean intersects(final Rect r) {
		return r.x < right() && r.right() > this.x && r.y < bottom() && r.bottom() > this.y;
	}

	public Rect intersection(final Rect r) {
		double x0 = Math.max(this.x, r.x), y0 = Math.max(this.y, r.y);
		double x1 = Math.min(right(), r.right()), y1 = Math.min(bottom(), r.bottom());
		return x1 <= x0 || y1 <= y0 ? new Rect(x0, y0, 0, 0) : new Rect(x0, y0, x1 - x0, y1 - y0);
	}
}
