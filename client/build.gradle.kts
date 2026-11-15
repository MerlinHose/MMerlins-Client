plugins {
	java
	application
}

group = "dev.mcresearch"
version = "0.1.0"

java {
	toolchain {
		languageVersion = JavaLanguageVersion.of(25)
	}
}

repositories {
	mavenCentral()
}

dependencies {
	// Laufzeit: ausschließlich das JDK. Nur Tests verwenden JUnit.
	testImplementation(platform("org.junit:junit-bom:5.12.2"))
	testImplementation("org.junit.jupiter:junit-jupiter")
	testRuntimeOnly("org.junit.platform:junit-platform-launcher")
}

application {
	mainClass = "dev.mcresearch.protocolclient.Main"
}

// ---------------------------------------------------------------------------------------------------------------------
// M6: optionaler grafischer Client (docs/24). Eigener Source-Set "render" mit LWJGL; "main" bleibt ausschließlich JDK.
// LWJGL 3.4.3: aktuelle stabile Version laut Maven-Central-Metadaten (release 3.4.3, 2026-08-23), über die BOM gepinnt.
// ---------------------------------------------------------------------------------------------------------------------
val lwjglVersion = "3.4.3"
val lwjglNatives = "natives-windows"

sourceSets {
	create("render") {
		compileClasspath += sourceSets.main.get().output
		runtimeClasspath += sourceSets.main.get().output
	}
}

dependencies {
	"renderImplementation"(platform("org.lwjgl:lwjgl-bom:$lwjglVersion"))
	"renderImplementation"("org.lwjgl:lwjgl")
	"renderImplementation"("org.lwjgl:lwjgl-glfw")
	"renderImplementation"("org.lwjgl:lwjgl-opengl")
	"renderRuntimeOnly"("org.lwjgl:lwjgl::$lwjglNatives")
	"renderRuntimeOnly"("org.lwjgl:lwjgl-glfw::$lwjglNatives")
	"renderRuntimeOnly"("org.lwjgl:lwjgl-opengl::$lwjglNatives")
}

/** Lokale Minecraft-26.3-Ressourcen (nur lesend, nur für den persönlichen Test; werden nicht in Artefakte kopiert). */
fun localMinecraftAssets(): String {
	val dir = file("../research/.gradle/loom-cache/minecraftMaven/net/minecraft")
	return dir.walkTopDown().maxDepth(3)
		.firstOrNull { it.isFile && it.name.startsWith("minecraft-merged") && it.name.endsWith("-26.3.jar") }?.absolutePath ?: ""
}

tasks.register<JavaExec>("runGraphical") {
	group = "application"
	description = "Grafischer Client (LWJGL/OpenGL 3.3), docs/24. Argumente: -Pgargs=\"--username X --render-distance 2 …\""
	classpath = sourceSets["render"].runtimeClasspath
	mainClass = "dev.mcresearch.protocolclient.gl.GraphicalMain"
	workingDir = layout.projectDirectory.asFile
	// Speichergrenzen (docs/24 §8): Heap für Welt, Mesh-Puffer; native Puffer werden nach dem Upload sofort freigegeben
	maxHeapSize = "768m"
	// native Zugriffe von LWJGL ausdrücklich erlauben (sonst Warnung ab Java 24); direkter Speicher begrenzt
	jvmArgs("-XX:MaxDirectMemorySize=256m", "--enable-native-access=ALL-UNNAMED")
	val assets = localMinecraftAssets()
	val extra = (project.findProperty("gargs") as String?)?.split(" ")?.filter { it.isNotBlank() } ?: emptyList()
	args(listOf("--assets", assets) + extra)
}

tasks.withType<JavaCompile>().configureEach {
	options.release = 25
	options.encoding = "UTF-8"
	options.compilerArgs.addAll(listOf("-Xlint:all", "-Werror"))
}

tasks.test {
	// Speichergrenze der Test-JVM (docs/23 §6); Integrationstests halten höchstens eine Sitzung mit wenigen Chunks
	maxHeapSize = "512m"
	useJUnitPlatform {
		// Integrationstests gegen den lokalen Server laufen nur mit -PintegrationTest
		if (!project.hasProperty("integrationTest")) {
			excludeTags("integration")
		}
	}
	systemProperty("mcclient.integration", project.hasProperty("integrationTest"))
	// Optional: Verzeichnis des lokalen Testservers für serverseitige Beobachtung (-PserverDir=../server)
	systemProperty("mcclient.expectedThreshold", (project.findProperty("expectedThreshold") as String?) ?: "")
	systemProperty("mcclient.serverDir", (project.findProperty("serverDir") as String?)?.let { file(it).absolutePath } ?: "")
	systemProperty("mcclient.course", file((project.findProperty("course") as String?) ?: "../analysis/tools/m5-course.txt").absolutePath)
	// M11: Ausgabedateien des Physikvergleichs (Standard m5 → build/m5-own-trace.txt, build/m5-physics-report.txt)
	systemProperty("mcclient.physicsLabel", (project.findProperty("physicsLabel") as String?) ?: "m5")
	systemProperty("mcclient.physicsReference", (project.findProperty("physref") as String?)?.let { file(it).absolutePath } ?: "")
	testLogging {
		events("passed", "skipped", "failed")
		exceptionFormat = org.gradle.api.tasks.testing.logging.TestExceptionFormat.FULL
	}
}

tasks.named<JavaExec>("run") {
	// eigener Client: Weltmodell bei Sichtweite 2 braucht wenige MB (docs/23 §6)
	maxHeapSize = "256m"
	workingDir = layout.projectDirectory.asFile
	standardInput = System.`in`
}
