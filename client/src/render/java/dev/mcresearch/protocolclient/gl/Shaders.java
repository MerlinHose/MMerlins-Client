package dev.mcresearch.protocolclient.gl;

/** Eigene GLSL-330-core-Shader (docs/24 §3). */
final class Shaders {
	private Shaders() {
	}

	/**
	 * Weltgeometrie (M12, docs/47 §1, docs/48 §5): Position relativ zur Kamera (uOffset = Sektionsursprung − Kamera); Vertexfarbe
	 * (AO × Richtung × Tönung) × Lightmap an den Lichtkoordinaten (Block, Himmel je 0…240, Texelmitte wie sample_lightmap);
	 * Atlas-Textur; Nebel = Maximum aus Umgebungsnebel (kugelförmiger Abstand) und Sichtweitennebel (zylindrisch:
	 * max(Abstand xz, |y|)), Farbe uFogColor.
	 */
	static final String WORLD_VERTEX = """
		#version 330 core
		layout(location = 0) in vec3 aPos;
		layout(location = 1) in vec2 aUv;
		layout(location = 2) in vec4 aColor;
		layout(location = 3) in ivec2 aLight;
		uniform mat4 uViewProj;
		uniform vec3 uOffset;
		uniform sampler2D uLightmap;
		out vec2 vUv;
		out vec4 vColor;
		out float vSpherical;
		out float vCylindrical;
		void main() {
		    vec3 p = aPos + uOffset;
		    gl_Position = uViewProj * vec4(p, 1.0);
		    vUv = aUv;
		    vec2 luv = clamp(vec2(aLight) / 256.0 + 0.5 / 16.0, vec2(0.5 / 16.0), vec2(15.5 / 16.0));
		    vColor = aColor * texture(uLightmap, luv);
		    vSpherical = length(p);
		    vCylindrical = max(length(p.xz), abs(p.y));
		}
		""";

	static final String WORLD_FRAGMENT = """
		#version 330 core
		in vec2 vUv;
		in vec4 vColor;
		in float vSpherical;
		in float vCylindrical;
		uniform sampler2D uAtlas;
		uniform int uTextured;
		uniform float uAlphaCutoff;
		uniform vec4 uFogColor;
		uniform float uFogEnvStart;
		uniform float uFogEnvEnd;
		uniform float uFogRdStart;
		uniform float uFogRdEnd;
		out vec4 fragColor;
		float linearFog(float d, float s, float e) {
		    if (d <= s) return 0.0;
		    if (d >= e) return 1.0;
		    return (d - s) / (e - s);
		}
		void main() {
		    vec4 c = (uTextured == 1 ? texture(uAtlas, vUv) : vec4(1.0)) * vColor;
		    if (c.a < uAlphaCutoff) discard;
		    float fog = max(linearFog(vSpherical, uFogEnvStart, uFogEnvEnd), linearFog(vCylindrical, uFogRdStart, uFogRdEnd));
		    fragColor = vec4(mix(c.rgb, uFogColor.rgb, fog * uFogColor.a), c.a);
		}
		""";

	/** Bildschirm-Overlay (Text als Textur), Koordinaten in Pixeln. */
	static final String OVERLAY_VERTEX = """
		#version 330 core
		layout(location = 0) in vec3 aPos;
		layout(location = 1) in vec2 aUv;
		layout(location = 2) in vec4 aColor;
		uniform vec2 uScreen;
		out vec2 vUv;
		void main() {
		    gl_Position = vec4(aPos.x / uScreen.x * 2.0 - 1.0, 1.0 - aPos.y / uScreen.y * 2.0, 0.0, 1.0);
		    vUv = aUv;
		}
		""";

	static final String OVERLAY_FRAGMENT = """
		#version 330 core
		in vec2 vUv;
		uniform sampler2D uTex;
		out vec4 fragColor;
		void main() {
		    fragColor = texture(uTex, vUv);
		}
		""";
}
