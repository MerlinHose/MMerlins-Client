package dev.mcresearch.protocolclient.gl;

import static org.lwjgl.glfw.GLFW.*;
import static org.lwjgl.opengl.GL33C.*;

import java.awt.image.BufferedImage;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.file.Files;
import java.nio.file.Path;
import javax.imageio.ImageIO;
import org.lwjgl.glfw.GLFWErrorCallback;
import org.lwjgl.opengl.GL;
import org.lwjgl.system.MemoryUtil;

/**
 * GLFW-Fenster mit OpenGL-3.3-Core-Kontext (docs/24 §2). Alle Methoden nur auf dem Grafikthread (dem Thread, der das
 * Fenster erzeugt hat) aufrufen.
 */
public final class Window implements AutoCloseable {
	private final long handle;
	private final Thread owner;
	private int width;
	private int height;
	private boolean resized = true;
	private boolean mouseCaptured;
	private double mouseDx;
	private double mouseDy;
	private double lastMouseX = Double.NaN;
	private double lastMouseY = Double.NaN;
	private final boolean[] keys = new boolean[GLFW_KEY_LAST + 1];
	private final boolean[] pressedThisFrame = new boolean[GLFW_KEY_LAST + 1];
	private final String glInfo;
	private final boolean[] buttons = new boolean[3];
	private final int[] clicks = new int[3];
	private double scroll;
	/** M8: Bildschirm (Inventar/Container) offen – Klicks gehören dann dem Fenster, sie fangen die Maus nicht. */
	private volatile boolean screenOpen;
	private final java.util.ArrayDeque<MouseEvent> mouseEvents = new java.util.ArrayDeque<>();

	/** Maustaste gedrückt/losgelassen bei freier Maus (Position in Framebuffer-Pixeln, GLFW-Modifikatoren). */
	public record MouseEvent(boolean press, int button, double x, double y, int mods, long nanos) {
		public boolean shift() {
			return (this.mods & GLFW_MOD_SHIFT) != 0;
		}

		public boolean control() {
			return (this.mods & GLFW_MOD_CONTROL) != 0;
		}
	}

	public Window(final String title, final int width, final int height, final boolean vsync) {
		GLFWErrorCallback.createPrint(System.err).set();
		if (!glfwInit()) {
			throw new IllegalStateException("GLFW initialisation failed");
		}
		glfwDefaultWindowHints();
		glfwWindowHint(GLFW_CONTEXT_VERSION_MAJOR, 3);
		glfwWindowHint(GLFW_CONTEXT_VERSION_MINOR, 3);
		glfwWindowHint(GLFW_OPENGL_PROFILE, GLFW_OPENGL_CORE_PROFILE);
		glfwWindowHint(GLFW_OPENGL_FORWARD_COMPAT, GLFW_TRUE);
		glfwWindowHint(GLFW_RESIZABLE, GLFW_TRUE);
		glfwWindowHint(GLFW_VISIBLE, GLFW_FALSE);
		this.handle = glfwCreateWindow(width, height, title, MemoryUtil.NULL, MemoryUtil.NULL);
		if (this.handle == MemoryUtil.NULL) {
			glfwTerminate();
			throw new IllegalStateException("Could not create a window with an OpenGL 3.3 core context (GPU/driver?)");
		}
		this.owner = Thread.currentThread();
		glfwMakeContextCurrent(this.handle);
		GL.createCapabilities();
		glfwSwapInterval(vsync ? 1 : 0);
		int[] fw = new int[1];
		int[] fh = new int[1];
		glfwGetFramebufferSize(this.handle, fw, fh);
		this.width = fw[0];
		this.height = fh[0];
		glfwSetFramebufferSizeCallback(this.handle, (w, nw, nh) -> {
			this.width = nw;
			this.height = nh;
			this.resized = true;
		});
		glfwSetKeyCallback(this.handle, (w, key, scancode, action, mods) -> {
			if (key >= 0 && key <= GLFW_KEY_LAST) {
				if (action == GLFW_PRESS) {
					this.keys[key] = true;
					this.pressedThisFrame[key] = true;
				} else if (action == GLFW_RELEASE) {
					this.keys[key] = false;
				}
			}
		});
		glfwSetCursorPosCallback(this.handle, (w, x, y) -> {
			if (this.mouseCaptured && !Double.isNaN(this.lastMouseX)) {
				this.mouseDx += x - this.lastMouseX;
				this.mouseDy += y - this.lastMouseY;
			}
			this.lastMouseX = x;
			this.lastMouseY = y;
		});
		glfwSetMouseButtonCallback(this.handle, (w, button, action, mods) -> {
			if (button == GLFW_MOUSE_BUTTON_LEFT && action == GLFW_PRESS && !this.mouseCaptured && !this.screenOpen) {
				setMouseCaptured(true); // Klick zum Fangen der Maus zählt nicht als Angriff (wie Vanilla MouseHandler#onButton)
				return;
			}
			if (button < 0 || button > 2) {
				return;
			}
			if (action == GLFW_PRESS) {
				this.buttons[button] = true;
				if (this.mouseCaptured) {
					this.clicks[button]++;
				}
			} else if (action == GLFW_RELEASE) {
				this.buttons[button] = false;
			}
			if (!this.mouseCaptured && (action == GLFW_PRESS || action == GLFW_RELEASE)) {
				// M8: Mausereignisse für Bildschirme (Inventar) mit Position und Umschalttasten, in Framebuffer-Pixeln
				double[] fx = new double[1], fy = new double[1];
				glfwGetCursorPos(w, fx, fy);
				double sx = framebufferScaleX(), sy = framebufferScaleY();
				this.mouseEvents.addLast(new MouseEvent(action == GLFW_PRESS, button, fx[0] * sx, fy[0] * sy, mods, System.nanoTime()));
				while (this.mouseEvents.size() > 64) {
					this.mouseEvents.removeFirst();
				}
			}
		});
		glfwSetScrollCallback(this.handle, (w, dx, dy) -> this.scroll += dy);
		this.glInfo = glGetString(GL_VENDOR) + " | " + glGetString(GL_RENDERER) + " | OpenGL " + glGetString(GL_VERSION);
		glfwShowWindow(this.handle);
	}

	private void checkThread() {
		if (Thread.currentThread() != this.owner) {
			throw new IllegalStateException("OpenGL/GLFW call outside the graphics thread: " + Thread.currentThread().getName());
		}
	}

	public String glInfo() {
		return this.glInfo;
	}

	public boolean shouldClose() {
		return glfwWindowShouldClose(this.handle);
	}

	public void requestClose() {
		glfwSetWindowShouldClose(this.handle, true);
	}

	/** Puffer tauschen, Ereignisse abholen; vorher gelesene Einmal-Tasten zurücksetzen. */
	public void endFrame() {
		checkThread();
		glfwSwapBuffers(this.handle);
		java.util.Arrays.fill(this.pressedThisFrame, false);
		glfwPollEvents();
	}

	/** Größenänderung seit dem letzten Aufruf (setzt den Viewport). */
	public boolean consumeResize() {
		if (this.resized) {
			this.resized = false;
			glViewport(0, 0, Math.max(1, this.width), Math.max(1, this.height));
			return true;
		}
		return false;
	}

	public int width() {
		return Math.max(1, this.width);
	}

	public int height() {
		return Math.max(1, this.height);
	}

	public boolean isKeyDown(final int key) {
		return this.keys[key];
	}

	/** Taste wurde seit dem letzten Frame gedrückt (Umschalter wie F3, Escape). */
	public boolean wasPressed(final int key) {
		return this.pressedThisFrame[key];
	}

	public void setMouseCaptured(final boolean captured) {
		checkThread();
		this.mouseCaptured = captured;
		glfwSetInputMode(this.handle, GLFW_CURSOR, captured ? GLFW_CURSOR_DISABLED : GLFW_CURSOR_NORMAL);
		if (captured && glfwRawMouseMotionSupported()) {
			glfwSetInputMode(this.handle, GLFW_RAW_MOUSE_MOTION, GLFW_TRUE);
		}
		this.lastMouseX = Double.NaN;
		this.mouseDx = 0;
		this.mouseDy = 0;
	}

	/** Mausereignisse seit dem letzten Aufruf (nur bei freier Maus). */
	public java.util.List<MouseEvent> consumeMouseEvents() {
		java.util.List<MouseEvent> l = new java.util.ArrayList<>(this.mouseEvents);
		this.mouseEvents.clear();
		return l;
	}

	/** Mausposition in Framebuffer-Pixeln. */
	public double[] cursorPosition() {
		double[] fx = new double[1], fy = new double[1];
		glfwGetCursorPos(this.handle, fx, fy);
		return new double[]{fx[0] * framebufferScaleX(), fy[0] * framebufferScaleY()};
	}

	private double framebufferScaleX() {
		int[] ww = new int[1], wh = new int[1];
		glfwGetWindowSize(this.handle, ww, wh);
		return ww[0] == 0 ? 1.0 : (double) this.width / ww[0];
	}

	private double framebufferScaleY() {
		int[] ww = new int[1], wh = new int[1];
		glfwGetWindowSize(this.handle, ww, wh);
		return wh[0] == 0 ? 1.0 : (double) this.height / wh[0];
	}

	/** Lage des Fensterinhalts auf dem Bildschirm (x, y, Breite, Höhe in Bildschirmpunkten) und Framebuffer-Größe. */
	public int[] screenRect() {
		int[] x = new int[1], y = new int[1], ww = new int[1], wh = new int[1];
		glfwGetWindowPos(this.handle, x, y);
		glfwGetWindowSize(this.handle, ww, wh);
		return new int[]{x[0], y[0], ww[0], wh[0], this.width, this.height};
	}

	public void setScreenOpen(final boolean open) {
		this.screenOpen = open;
	}

	public boolean focused() {
		return glfwGetWindowAttrib(this.handle, GLFW_FOCUSED) == GLFW_TRUE;
	}

	public boolean shiftDown() {
		return this.keys[GLFW_KEY_LEFT_SHIFT] || this.keys[GLFW_KEY_RIGHT_SHIFT];
	}

	public boolean controlDown() {
		return this.keys[GLFW_KEY_LEFT_CONTROL] || this.keys[GLFW_KEY_RIGHT_CONTROL];
	}

	/** Maustaste (0 links, 1 rechts, 2 Mitte) gedrückt. */
	public boolean isButtonDown(final int button) {
		return this.buttons[button];
	}

	/** Klicks seit dem letzten Aufruf (nur bei gefangener Maus). */
	public int consumeClicks(final int button) {
		int n = this.clicks[button];
		this.clicks[button] = 0;
		return n;
	}

	/** Mausrad seit dem letzten Aufruf (positiv = nach oben). */
	public double consumeScroll() {
		double d = this.scroll;
		this.scroll = 0;
		return d;
	}

	public boolean mouseCaptured() {
		return this.mouseCaptured;
	}

	/** Mausbewegung seit dem letzten Aufruf (nur bei gefangener Maus). */
	public double[] consumeMouseDelta() {
		double[] d = {this.mouseDx, this.mouseDy};
		this.mouseDx = 0;
		this.mouseDy = 0;
		return d;
	}

	public void setTitle(final String title) {
		glfwSetWindowTitle(this.handle, title);
	}

	/** Bildschirmfoto des aktuellen Hintergrundpuffers als PNG (für den Testbericht). */
	public void screenshot(final Path file) throws IOException {
		checkThread();
		int w = width();
		int h = height();
		ByteBuffer buf = MemoryUtil.memAlloc(w * h * 4);
		try {
			glPixelStorei(GL_PACK_ALIGNMENT, 1);
			glReadPixels(0, 0, w, h, GL_RGBA, GL_UNSIGNED_BYTE, buf);
			BufferedImage img = new BufferedImage(w, h, BufferedImage.TYPE_INT_RGB);
			for (int y = 0; y < h; y++) {
				for (int x = 0; x < w; x++) {
					int i = ((h - 1 - y) * w + x) * 4;
					img.setRGB(x, y, (buf.get(i) & 255) << 16 | (buf.get(i + 1) & 255) << 8 | (buf.get(i + 2) & 255));
				}
			}
			Files.createDirectories(file.toAbsolutePath().getParent());
			ImageIO.write(img, "png", file.toFile());
		} finally {
			MemoryUtil.memFree(buf);
		}
	}

	@Override
	public void close() {
		org.lwjgl.glfw.Callbacks.glfwFreeCallbacks(this.handle);
		glfwDestroyWindow(this.handle);
		glfwTerminate();
		GLFWErrorCallback cb = glfwSetErrorCallback(null);
		if (cb != null) {
			cb.free();
		}
	}
}
