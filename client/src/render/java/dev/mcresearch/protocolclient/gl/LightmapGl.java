package dev.mcresearch.protocolclient.gl;

import static org.lwjgl.opengl.GL33C.*;

import dev.mcresearch.protocolclient.view.Lightmap;
import java.nio.ByteBuffer;
import org.lwjgl.system.MemoryUtil;

/**
 * Lightmap-Textur 16 × 16 (docs/47 §1) auf Texturstufe 2 mit linearer Filterung (Zwischenwerte des weichen Lichts). Inhalt aus
 * {@link Lightmap#texture}; Aktualisierung nur bei geänderten Eingaben. Nur Grafikthread.
 */
final class LightmapGl implements AutoCloseable {
	static final int UNIT = 2;
	private final int texture;
	private Lightmap.Params last;
	private long updates;

	LightmapGl() {
		this.texture = glGenTextures();
		GlResources.created("texture");
		glActiveTexture(GL_TEXTURE0 + UNIT);
		glBindTexture(GL_TEXTURE_2D, this.texture);
		glTexParameteri(GL_TEXTURE_2D, GL_TEXTURE_MIN_FILTER, GL_LINEAR);
		glTexParameteri(GL_TEXTURE_2D, GL_TEXTURE_MAG_FILTER, GL_LINEAR);
		glTexParameteri(GL_TEXTURE_2D, GL_TEXTURE_WRAP_S, GL_CLAMP_TO_EDGE);
		glTexParameteri(GL_TEXTURE_2D, GL_TEXTURE_WRAP_T, GL_CLAMP_TO_EDGE);
		upload(Lightmap.texture(Lightmap.Params.noon()), true);
		glActiveTexture(GL_TEXTURE0);
	}

	private void upload(final byte[] rgba, final boolean allocate) {
		ByteBuffer buf = MemoryUtil.memAlloc(rgba.length);
		try {
			buf.put(rgba).flip();
			if (allocate) {
				glTexImage2D(GL_TEXTURE_2D, 0, GL_RGBA8, 16, 16, 0, GL_RGBA, GL_UNSIGNED_BYTE, buf);
			} else {
				glTexSubImage2D(GL_TEXTURE_2D, 0, 0, 0, 16, 16, GL_RGBA, GL_UNSIGNED_BYTE, buf);
			}
		} finally {
			MemoryUtil.memFree(buf);
		}
	}

	/** Neu berechnen und hochladen, wenn sich die Eingaben geändert haben. */
	void update(final Lightmap.Params p) {
		if (p.equals(this.last) || this.last != null && sameValues(p, this.last)) {
			return;
		}
		this.last = p;
		glActiveTexture(GL_TEXTURE0 + UNIT);
		glBindTexture(GL_TEXTURE_2D, this.texture);
		upload(Lightmap.texture(p), false);
		glActiveTexture(GL_TEXTURE0);
		this.updates++;
	}

	private static boolean sameValues(final Lightmap.Params a, final Lightmap.Params b) {
		return a.skyFactor() == b.skyFactor() && a.blockFactor() == b.blockFactor() && a.nightVision() == b.nightVision() && a.darkness() == b.darkness()
			&& a.brightness() == b.brightness() && java.util.Arrays.equals(a.blockLightTint(), b.blockLightTint())
			&& java.util.Arrays.equals(a.skyLightColor(), b.skyLightColor()) && java.util.Arrays.equals(a.ambientColor(), b.ambientColor())
			&& java.util.Arrays.equals(a.nightVisionColor(), b.nightVisionColor());
	}

	void bind() {
		glActiveTexture(GL_TEXTURE0 + UNIT);
		glBindTexture(GL_TEXTURE_2D, this.texture);
		glActiveTexture(GL_TEXTURE0);
	}

	long updates() {
		return this.updates;
	}

	Lightmap.Params last() {
		return this.last;
	}

	@Override
	public void close() {
		glDeleteTextures(this.texture);
		GlResources.deleted("texture");
	}
}
