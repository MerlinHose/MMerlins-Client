package dev.mcresearch.protocolclient.gl;

import java.util.Map;
import java.util.TreeMap;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicLong;

/**
 * Zähler lebender OpenGL-Objekte je Art (M7-Leckprüfung, docs/29 §4): erzeugt − freigegeben. Nach dem Schließen aller
 * Renderer muss jeder Zähler 0 sein; GameView meldet den Stand beim Beenden ({@code [GL] live GL objects …}).
 */
final class GlResources {
	private static final Map<String, AtomicLong> LIVE = new ConcurrentHashMap<>();
	private static final Map<String, AtomicLong> CREATED = new ConcurrentHashMap<>();

	private GlResources() {
	}

	static void created(final String kind) {
		LIVE.computeIfAbsent(kind, k -> new AtomicLong()).incrementAndGet();
		CREATED.computeIfAbsent(kind, k -> new AtomicLong()).incrementAndGet();
	}

	static void deleted(final String kind) {
		LIVE.computeIfAbsent(kind, k -> new AtomicLong()).decrementAndGet();
	}

	static long live() {
		return LIVE.values().stream().mapToLong(AtomicLong::get).sum();
	}

	static String summary() {
		Map<String, String> m = new TreeMap<>();
		CREATED.forEach((k, v) -> m.put(k, LIVE.get(k).get() + " live / " + v.get() + " created"));
		return m.toString();
	}
}
