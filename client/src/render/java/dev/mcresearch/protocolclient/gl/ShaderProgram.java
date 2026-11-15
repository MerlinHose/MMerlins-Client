package dev.mcresearch.protocolclient.gl;

import static org.lwjgl.opengl.GL33C.*;

import dev.mcresearch.protocolclient.view.Mat4;
import java.util.HashMap;
import java.util.Map;

/** Vertex- + Fragment-Shader-Programm (GLSL 330 core). Nur auf dem Grafikthread verwenden. */
public final class ShaderProgram implements AutoCloseable {
	private final int program;
	private final Map<String, Integer> uniforms = new HashMap<>();

	public ShaderProgram(final String vertexSource, final String fragmentSource) {
		int vs = compile(GL_VERTEX_SHADER, vertexSource);
		int fs = compile(GL_FRAGMENT_SHADER, fragmentSource);
		this.program = glCreateProgram();
		glAttachShader(this.program, vs);
		glAttachShader(this.program, fs);
		glLinkProgram(this.program);
		glDeleteShader(vs);
		glDeleteShader(fs);
		if (glGetProgrami(this.program, GL_LINK_STATUS) != GL_TRUE) {
			String log = glGetProgramInfoLog(this.program);
			glDeleteProgram(this.program);
			throw new IllegalStateException("shader link failed: " + log);
		}
		GlResources.created("program");
	}

	private static int compile(final int type, final String source) {
		int s = glCreateShader(type);
		glShaderSource(s, source);
		glCompileShader(s);
		if (glGetShaderi(s, GL_COMPILE_STATUS) != GL_TRUE) {
			String log = glGetShaderInfoLog(s);
			glDeleteShader(s);
			throw new IllegalStateException((type == GL_VERTEX_SHADER ? "vertex" : "fragment") + " shader compile failed: " + log);
		}
		return s;
	}

	public void use() {
		glUseProgram(this.program);
	}

	private int location(final String name) {
		return this.uniforms.computeIfAbsent(name, n -> glGetUniformLocation(this.program, n));
	}

	public void set(final String name, final Mat4 m) {
		glUniformMatrix4fv(location(name), false, m.toArray());
	}

	public void set(final String name, final float x, final float y, final float z) {
		glUniform3f(location(name), x, y, z);
	}

	public void set(final String name, final float x, final float y, final float z, final float w) {
		glUniform4f(location(name), x, y, z, w);
	}

	public void set(final String name, final float x, final float y) {
		glUniform2f(location(name), x, y);
	}

	public void set(final String name, final float v) {
		glUniform1f(location(name), v);
	}

	public void setInt2(final String name, final int x, final int y) {
		glUniform2i(location(name), x, y);
	}

	public void set(final String name, final int v) {
		glUniform1i(location(name), v);
	}

	@Override
	public void close() {
		glDeleteProgram(this.program);
		GlResources.deleted("program");
	}
}
