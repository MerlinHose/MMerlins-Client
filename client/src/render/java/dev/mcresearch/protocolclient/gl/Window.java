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
	private final boolean[] buttons = new boolean[8];
	private final int[] clicks = new int[8];
	private double scroll;
	/** M8: Bildschirm (Inventar/Container) offen – Klicks gehören dann dem Fenster, sie fangen die Maus nicht. */
	private volatile boolean screenOpen;
	private final java.util.ArrayDeque<MouseEvent> mouseEvents = new java.util.ArrayDeque<>();
	/** M15: Tastenereignisse (Drücken, Wiederholung) und Zeichen für Menüs; Mausrad seit dem letzten Abholen durch die Menüs. */
	private final java.util.ArrayDeque<KeyEvent> keyEvents = new java.util.ArrayDeque<>();
	private final java.util.ArrayDeque<Integer> chars = new java.util.ArrayDeque<>();
	private double uiScroll;
	private int windowedX = Integer.MIN_VALUE;
	private int windowedY;
	private int windowedW;
	private int windowedH;
	private String displayMode = "WINDOWED";
	private boolean grabAllowed = true;

	/** GUI-Test ({@code --test-no-grab}): Maus nie wirklich fangen; Fensterfokus gilt als gegeben. */
	public void setGrabAllowed(final boolean allowed) {
		this.grabAllowed = allowed;
	}

	public boolean grabAllowed() {
		return this.grabAllowed;
	}

	/** M15: gedrückte oder wiederholte Taste mit GLFW-Modifikatoren. */
	public record KeyEvent(int key, boolean repeat, int mods) {
	}

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
			if (action == GLFW_PRESS || action == GLFW_REPEAT) {
				this.keyEvents.addLast(new KeyEvent(key, action == GLFW_REPEAT, mods));
				while (this.keyEvents.size() > 64) {
					this.keyEvents.removeFirst();
				}
			}
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
			if (button < 0 || button > 7) {
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
		glfwSetScrollCallback(this.handle, (w, dx, dy) -> {
			this.scroll += dy;
			this.uiScroll += dy;
		});
		glfwSetCharCallback(this.handle, (w, codepoint) -> {
			this.chars.addLast(codepoint);
			while (this.chars.size() > 64) {
				this.chars.removeFirst();
			}
		});
		glfwSetWindowSizeLimits(this.handle, 320, 240, GLFW_DONT_CARE, GLFW_DONT_CARE);
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
		applySimulated();
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
		if (!this.grabAllowed) {
			// GUI-Test: spielintern gefangen, Systemzeiger bleibt frei (die Maus des Nutzers wird nicht festgehalten)
			this.lastMouseX = Double.NaN;
			this.mouseDx = 0;
			this.mouseDy = 0;
			return;
		}
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
		return !this.grabAllowed || glfwGetWindowAttrib(this.handle, GLFW_FOCUSED) == GLFW_TRUE;
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

	// ---------------------------------------------------------------- M15: Menüs, Anzeigemodus

	/**
	 * GUI-Test (M15): Taste wie vom Betriebssystem gemeldet einspeisen – derselbe Weg wie der GLFW-Rückruf (Tastenzustand,
	 * „gedrückt in diesem Bild“, Menü-Ereignis). Echte Betriebssystem-Eingaben prüft zusätzlich analysis/tools/m15-realinput.ps1.
	 */
	public void simulateKey(final int key, final boolean press) {
		this.simulated.addLast(new int[]{key, press ? 1 : 0});
	}

	private final java.util.ArrayDeque<int[]> simulated = new java.util.ArrayDeque<>();

	/** GUI-Test: Mausereignis (Framebuffer-Pixel) in dieselbe Warteschlange wie echte Klicks bei freier Maus. */
	public void injectMouse(final boolean press, final int button, final double x, final double y) {
		this.mouseEvents.addLast(new MouseEvent(press, button, x, y, 0, System.nanoTime()));
	}

	/** GUI-Test: eingegebenes Zeichen wie vom Zeichen-Rückruf. */
	public void injectChar(final int codepoint) {
		this.chars.addLast(codepoint);
	}

	/** Eingespeiste Tasten nach dem Abholen der echten Ereignisse anwenden (gelten im nächsten Bild wie echte). */
	private void applySimulated() {
		for (int[] e; (e = this.simulated.poll()) != null;) {
			int key = e[0];
			boolean press = e[1] == 1;
			if (press) {
				this.keyEvents.addLast(new KeyEvent(key, false, 0));
			}
			if (key >= 0 && key <= GLFW_KEY_LAST) {
				this.keys[key] = press;
				if (press) {
					this.pressedThisFrame[key] = true;
				}
			}
		}
	}

	/** Tastenereignisse seit dem letzten Aufruf (für Menüs). */
	public java.util.List<KeyEvent> consumeKeyEvents() {
		java.util.List<KeyEvent> l = new java.util.ArrayList<>(this.keyEvents);
		this.keyEvents.clear();
		return l;
	}

	/** Eingegebene Zeichen (Unicode) seit dem letzten Aufruf. */
	public java.util.List<Integer> consumeChars() {
		java.util.List<Integer> l = new java.util.ArrayList<>(this.chars);
		this.chars.clear();
		return l;
	}

	/** Mausrad für Menüs seit dem letzten Aufruf (unabhängig vom Spielzähler). */
	public double consumeUiScroll() {
		double d = this.uiScroll;
		this.uiScroll = 0;
		return d;
	}

	public void setVsync(final boolean vsync) {
		checkThread();
		glfwSwapInterval(vsync ? 1 : 0);
	}

	public String clipboard() {
		String s = glfwGetClipboardString(this.handle);
		return s == null ? "" : s;
	}

	public void setClipboard(final String s) {
		glfwSetClipboardString(this.handle, s);
	}

	/** Vollbildmodi des Hauptbildschirms als „BxH@Hz“, größte zuerst, ohne Doppelte. */
	public java.util.List<String> videoModes() {
		long monitor = glfwGetPrimaryMonitor();
		java.util.LinkedHashSet<String> out = new java.util.LinkedHashSet<>();
		if (monitor == MemoryUtil.NULL) {
			return java.util.List.of();
		}
		org.lwjgl.glfw.GLFWVidMode.Buffer modes = glfwGetVideoModes(monitor);
		if (modes == null) {
			return java.util.List.of();
		}
		java.util.List<int[]> list = new java.util.ArrayList<>();
		for (int i = 0; i < modes.limit(); i++) {
			org.lwjgl.glfw.GLFWVidMode m = modes.get(i);
			if (m.width() >= 640 && m.height() >= 480) {
				list.add(new int[]{m.width(), m.height(), m.refreshRate()});
			}
		}
		list.sort((a, b) -> a[0] != b[0] ? b[0] - a[0] : a[1] != b[1] ? b[1] - a[1] : b[2] - a[2]);
		for (int[] m : list) {
			out.add(m[0] + "x" + m[1] + "@" + m[2]);
		}
		return java.util.List.copyOf(out);
	}

	public String displayMode() {
		return this.displayMode;
	}

	/**
	 * Anzeigemodus: {@code WINDOWED} (Fenster, letzte Größe), {@code FULLSCREEN} (exklusiv, {@code resolution} „BxH@Hz“ oder „auto“),
	 * {@code BORDERLESS} (Vollbild in Desktopauflösung). Liefert eine Beschreibung des Ergebnisses.
	 */
	public String setDisplayMode(final String mode, final String resolution) {
		checkThread();
		long monitor = glfwGetPrimaryMonitor();
		if (monitor == MemoryUtil.NULL || mode.equals(this.displayMode) && !mode.equals("FULLSCREEN")) {
			return this.displayMode;
		}
		if (this.displayMode.equals("WINDOWED")) {
			int[] x = new int[1], y = new int[1], ww = new int[1], wh = new int[1];
			glfwGetWindowPos(this.handle, x, y);
			glfwGetWindowSize(this.handle, ww, wh);
			this.windowedX = x[0];
			this.windowedY = y[0];
			this.windowedW = ww[0];
			this.windowedH = wh[0];
		}
		org.lwjgl.glfw.GLFWVidMode desktop = glfwGetVideoMode(monitor);
		switch (mode) {
			case "FULLSCREEN" -> {
				int w = desktop.width(), h = desktop.height(), hz = desktop.refreshRate();
				java.util.regex.Matcher m = java.util.regex.Pattern.compile("(\\d+)x(\\d+)(?:@(\\d+))?").matcher(resolution);
				if (m.matches()) {
					w = Integer.parseInt(m.group(1));
					h = Integer.parseInt(m.group(2));
					hz = m.group(3) == null ? GLFW_DONT_CARE : Integer.parseInt(m.group(3));
				}
				glfwSetWindowMonitor(this.handle, monitor, 0, 0, w, h, hz);
			}
			case "BORDERLESS" -> glfwSetWindowMonitor(this.handle, monitor, 0, 0, desktop.width(), desktop.height(), desktop.refreshRate());
			default -> {
				int w = this.windowedW > 0 ? this.windowedW : 1280, h = this.windowedH > 0 ? this.windowedH : 720;
				int x = this.windowedX != Integer.MIN_VALUE ? this.windowedX : (desktop.width() - w) / 2;
				int y = this.windowedX != Integer.MIN_VALUE ? this.windowedY : (desktop.height() - h) / 2;
				glfwSetWindowMonitor(this.handle, MemoryUtil.NULL, x, y, w, h, GLFW_DONT_CARE);
			}
		}
		this.displayMode = mode;
		this.resized = true;
		return mode;
	}

	/** Fenstergröße in Bildschirmpunkten (nur im Fenstermodus wirksam). */
	public void setWindowSize(final int w, final int h) {
		checkThread();
		if (this.displayMode.equals("WINDOWED")) {
			glfwSetWindowSize(this.handle, w, h);
		}
	}

	/** Fenstergröße in Bildschirmpunkten (nicht Framebuffer). */
	public int[] windowSize() {
		int[] ww = new int[1], wh = new int[1];
		glfwGetWindowSize(this.handle, ww, wh);
		return new int[]{ww[0], wh[0]};
	}

	/** Inhaltsskalierung des Betriebssystems (DPI, 1,0 = 100 %). */
	public float contentScale() {
		float[] x = new float[1], y = new float[1];
		glfwGetWindowContentScale(this.handle, x, y);
		return x[0];
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
