package dev.mcresearch.protocolclient.gl;

import static org.lwjgl.opengl.GL33C.*;

import dev.mcresearch.protocolclient.view.TextureAtlas;
import java.nio.ByteBuffer;
import org.lwjgl.system.MemoryUtil;

/** Hochgeladener Textur-Atlas (nächste Abtastung, keine Mipmaps – scharfe Pixel wie Minecraft). Nur Grafikthread. */
final class TextureAtlasGl implements AutoCloseable {
	private final int texture;
	private final long bytes;

	TextureAtlasGl(final TextureAtlas atlas) {
		int size = atlas.size();
		int[] px = atlas.pixels();
		ByteBuffer buf = MemoryUtil.memAlloc(size * size * 4);
		try {
			for (int c : px) {
				buf.put((byte) (c >> 16)).put((byte) (c >> 8)).put((byte) c).put((byte) (c >>> 24));
			}
			buf.flip();
			this.texture = glGenTextures();
			GlResources.created("texture");
			glBindTexture(GL_TEXTURE_2D, this.texture);
			glTexParameteri(GL_TEXTURE_2D, GL_TEXTURE_MIN_FILTER, GL_NEAREST);
			glTexParameteri(GL_TEXTURE_2D, GL_TEXTURE_MAG_FILTER, GL_NEAREST);
			glTexParameteri(GL_TEXTURE_2D, GL_TEXTURE_WRAP_S, GL_CLAMP_TO_EDGE);
			glTexParameteri(GL_TEXTURE_2D, GL_TEXTURE_WRAP_T, GL_CLAMP_TO_EDGE);
			glTexImage2D(GL_TEXTURE_2D, 0, GL_RGBA8, size, size, 0, GL_RGBA, GL_UNSIGNED_BYTE, buf);
		} finally {
			MemoryUtil.memFree(buf);
		}
		this.bytes = (long) size * size * 4;
	}

	/**
	 * M12 (docs/48 §4): geänderte Animationsbilder eines Client-Ticks ins Atlasrechteck schreiben ({@code glTexSubImage2D});
	 * Meshes bleiben unverändert, die Kosten hängen nur von der Zahl animierter Texturen ab, nicht von sichtbaren Blöcken.
	 */
	int upload(final java.util.List<TextureAtlas.Update> updates) {
		if (updates.isEmpty()) {
			return 0;
		}
		glActiveTexture(GL_TEXTURE0);
		glBindTexture(GL_TEXTURE_2D, this.texture);
		int bytes = 0;
		for (TextureAtlas.Update u : updates) {
			ByteBuffer buf = MemoryUtil.memAlloc(u.width() * u.height() * 4);
			try {
				for (int c : u.argb()) {
					buf.put((byte) (c >> 16)).put((byte) (c >> 8)).put((byte) c).put((byte) (c >>> 24));
				}
				buf.flip();
				glTexSubImage2D(GL_TEXTURE_2D, 0, u.x(), u.y(), u.width(), u.height(), GL_RGBA, GL_UNSIGNED_BYTE, buf);
				bytes += u.width() * u.height() * 4;
			} finally {
				MemoryUtil.memFree(buf);
			}
		}
		return bytes;
	}

	void bind() {
		glActiveTexture(GL_TEXTURE0);
		glBindTexture(GL_TEXTURE_2D, this.texture);
	}

	long bytes() {
		return this.bytes;
	}

	@Override
	public void close() {
		glDeleteTextures(this.texture);
		GlResources.deleted("texture");
	}
}
