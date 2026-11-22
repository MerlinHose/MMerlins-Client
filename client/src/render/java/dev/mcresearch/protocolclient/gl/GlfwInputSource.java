package dev.mcresearch.protocolclient.gl;

import dev.mcresearch.protocolclient.input.InputCode;
import dev.mcresearch.protocolclient.input.InputSource;
import dev.mcresearch.protocolclient.input.Keys;

/**
 * Tastatur und Maus über GLFW als Eingabequelle des Merlin Input Systems (docs/60 §4). Liest den Zustand des {@link Window};
 * Klicks, Mausrad und Mausbewegung werden je Bild in {@link #poll()} abgeholt. Klicks und Bewegung zählen wie bisher nur bei
 * gefangener Maus.
 */
final class GlfwInputSource implements InputSource {
	private final Window window;
	private final int[] clicks = new int[8];
	private double wheelUp;
	private double wheelDown;
	private double dx;
	private double dy;

	GlfwInputSource(final Window window) {
		this.window = window;
	}

	@Override
	public String id() {
		return "glfw-keyboard-mouse";
	}

	@Override
	public boolean handles(final InputCode.Device d) {
		return d == InputCode.Device.KEYBOARD || d == InputCode.Device.MOUSE_BUTTON || d == InputCode.Device.MOUSE_WHEEL
			|| d == InputCode.Device.MOUSE_AXIS;
	}

	@Override
	public void poll() {
		for (int b = 0; b < this.clicks.length; b++) {
			this.clicks[b] = this.window.consumeClicks(b);
		}
		double s = this.window.consumeScroll();
		this.wheelUp = Math.max(0, s);
		this.wheelDown = Math.max(0, -s);
		double[] d = this.window.consumeMouseDelta();
		this.dx = d[0];
		this.dy = d[1];
	}

	@Override
	public boolean down(final InputCode c) {
		return switch (c.device()) {
			case KEYBOARD -> Keys.valid(c.code()) && this.window.isKeyDown(c.code());
			case MOUSE_BUTTON -> c.code() >= 0 && c.code() < 8 && this.window.isButtonDown(c.code());
			default -> false;
		};
	}

	@Override
	public int presses(final InputCode c) {
		return switch (c.device()) {
			case KEYBOARD -> Keys.valid(c.code()) && this.window.wasPressed(c.code()) ? 1 : 0;
			case MOUSE_BUTTON -> c.code() >= 0 && c.code() < 8 ? this.clicks[c.code()] : 0;
			case MOUSE_WHEEL -> c.code() == InputCode.WHEEL_UP ? (this.wheelUp > 0 ? 1 : 0) : (this.wheelDown > 0 ? 1 : 0);
			default -> 0;
		};
	}

	@Override
	public double analog(final InputCode c) {
		return switch (c.device()) {
			case MOUSE_WHEEL -> c.code() == InputCode.WHEEL_UP ? this.wheelUp : this.wheelDown;
			case MOUSE_AXIS -> c.code() == InputCode.AXIS_X ? this.dx : this.dy;
			default -> 0;
		};
	}
}
