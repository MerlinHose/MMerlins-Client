package dev.mcresearch.protocolclient.gl;

import static org.lwjgl.opengl.GL33C.*;

import dev.mcresearch.protocolclient.view.MeshBuilder;
import java.nio.IntBuffer;
import org.lwjgl.system.MemoryUtil;

/**
 * Ein hochgeladenes Mesh (VAO + VBO) im Vertexformat von {@link MeshBuilder} (M12: 28 Byte mit Lichtkoordinaten). Der native Zwischenpuffer wird direkt nach
 * dem Upload freigegeben (docs/24 §8). Erzeugen, Zeichnen und Löschen nur auf dem Grafikthread.
 */
public final class GpuMesh implements AutoCloseable {
	private final int vao;
	private final int vbo;
	private final int vertexCount;
	private final long bytes;
	private boolean closed;

	public GpuMesh(final int[] vertexData) {
		this.vertexCount = vertexData.length / MeshBuilder.INTS_PER_VERTEX;
		this.bytes = (long) vertexData.length * 4;
		this.vao = glGenVertexArrays();
		this.vbo = glGenBuffers();
		GlResources.created("mesh");
		glBindVertexArray(this.vao);
		glBindBuffer(GL_ARRAY_BUFFER, this.vbo);
		IntBuffer buf = MemoryUtil.memAllocInt(vertexData.length);
		try {
			buf.put(vertexData).flip();
			glBufferData(GL_ARRAY_BUFFER, buf, GL_STATIC_DRAW);
		} finally {
			MemoryUtil.memFree(buf);
		}
		int stride = MeshBuilder.BYTES_PER_VERTEX;
		glVertexAttribPointer(0, 3, GL_FLOAT, false, stride, 0L);
		glEnableVertexAttribArray(0);
		glVertexAttribPointer(1, 2, GL_FLOAT, false, stride, 12L);
		glEnableVertexAttribArray(1);
		glVertexAttribPointer(2, 4, GL_UNSIGNED_BYTE, true, stride, 20L);
		glEnableVertexAttribArray(2);
		// M12: Lichtkoordinaten (Block, Himmel je 0…240) als ganze Zahlen wie UV2
		glVertexAttribIPointer(3, 2, GL_UNSIGNED_SHORT, stride, 24L);
		glEnableVertexAttribArray(3);
		glBindVertexArray(0);
	}

	/** M12: Vertexdaten gleicher Länge ersetzen (Neusortierung durchscheinender Flächen). */
	public void update(final int[] vertexData) {
		if (vertexData.length * 4L != this.bytes) {
			throw new IllegalArgumentException("size mismatch");
		}
		glBindBuffer(GL_ARRAY_BUFFER, this.vbo);
		IntBuffer buf = MemoryUtil.memAllocInt(vertexData.length);
		try {
			buf.put(vertexData).flip();
			glBufferSubData(GL_ARRAY_BUFFER, 0L, buf);
		} finally {
			MemoryUtil.memFree(buf);
		}
		glBindBuffer(GL_ARRAY_BUFFER, 0);
	}

	public void draw() {
		if (this.vertexCount > 0) {
			glBindVertexArray(this.vao);
			glDrawArrays(GL_TRIANGLES, 0, this.vertexCount);
		}
	}

	public int vertexCount() {
		return this.vertexCount;
	}

	public long bytes() {
		return this.bytes;
	}

	@Override
	public void close() {
		if (!this.closed) {
			this.closed = true;
			glDeleteBuffers(this.vbo);
			glDeleteVertexArrays(this.vao);
			GlResources.deleted("mesh");
		}
	}
}
