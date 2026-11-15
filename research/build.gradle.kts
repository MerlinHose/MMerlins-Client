plugins {
	id("net.fabricmc.fabric-loom")
	`maven-publish`
}

repositories {
	// Add repositories to retrieve artifacts from in here.
	// You should only use this when depending on other mods because
	// Loom adds the essential maven repositories to download Minecraft and libraries from automatically.
	// See https://docs.gradle.org/current/userguide/declaring_repositories.html
	// for more information about repositories.
}

dependencies {
	// To change the versions see the gradle.properties file
	minecraft("com.mojang:minecraft:${providers.gradleProperty("minecraft_version").get()}")
	implementation("net.fabricmc:fabric-loader:${providers.gradleProperty("loader_version").get()}")

	// Fabric API. This is technically optional, but you probably want it anyway.
	implementation("net.fabricmc.fabric-api:fabric-api:${providers.gradleProperty("fabric_api_version").get()}")
}

// Laufzeittest gegen den lokalen Vanilla-Server (docs/10-laufzeittest.md).
// Eigener Task "runLocalTest" mit eigenem Laufverzeichnis; "runClient" bleibt unverändert.
// Alle Mojang-/HTTP-Dienste werden auf den toten lokalen Port 127.0.0.1:9 gelenkt (kein externer Kontakt).
loom {
	runs {
		register("localTest") {
			client()
			configName = "Local Test Client (localhost:25565)"
			runDir("run-localtest")
			vmArgs(
				"-Dmcresearch.localtest=true",
				"-Dminecraft.api.discovery.host=http://127.0.0.1:9/discovery",
				"-Dhttp.proxyHost=127.0.0.1", "-Dhttp.proxyPort=9",
				"-Dhttps.proxyHost=127.0.0.1", "-Dhttps.proxyPort=9",
				"-Dhttp.nonProxyHosts=localhost|127.*|[::1]"
			)
			programArgs(
				"--quickPlayMultiplayer", "localhost:25565",
				"--offlineDeveloperMode",
				"--username", "ResearchBot",
				"--proxyHost", "127.0.0.1", "--proxyPort", "9"
			)
		}
		// M5-Physikreferenz (docs/23): Vanilla-Client führt die Szenarien aus analysis/tools/m5-course.txt aus.
		register("physicsRef") {
			client()
			configName = "Physics Reference Client (localhost:25565)"
			runDir("run-physicsref")
			vmArgs(
				// Speichergrenze des Minecraft-Clients (ohne Angabe: ¼ des RAM), docs/23 §6
				"-Xms512M", "-Xmx" + (project.findProperty("physicsRefXmx") ?: "2G"),
				"-Dmcresearch.physicsref=" + rootProject.file((project.findProperty("physicsRefCourse") as String?) ?: "../analysis/tools/m5-course.txt").absolutePath,
				"-Dmcresearch.physicsref.out=physref-trace.txt",
				"-Dminecraft.api.discovery.host=http://127.0.0.1:9/discovery",
				"-Dhttp.proxyHost=127.0.0.1", "-Dhttp.proxyPort=9",
				"-Dhttps.proxyHost=127.0.0.1", "-Dhttps.proxyPort=9",
				"-Dhttp.nonProxyHosts=localhost|127.*|[::1]"
			)
			programArgs(
				"--quickPlayMultiplayer", "localhost:25565",
				"--offlineDeveloperMode",
				"--username", "ResearchRef",
				"--proxyHost", "127.0.0.1", "--proxyPort", "9"
			)
		}
		register("combatRef") {
			client()
			configName = "Combat Reference Client (localhost:25565)"
			runDir("run-combatref")
			vmArgs(
				"-Xms512M", "-Xmx" + (project.findProperty("combatRefXmx") ?: "2G"),
				"-Dmcresearch.combatref=" + rootProject.file((project.findProperty("combatRefFile") ?: "../analysis/tools/m10-vanilla.txt").toString()).absolutePath,
				"-Dmcresearch.combatref.out=combatref.txt",
				"-Dminecraft.api.discovery.host=http://127.0.0.1:9/discovery",
				"-Dhttp.proxyHost=127.0.0.1", "-Dhttp.proxyPort=9",
				"-Dhttps.proxyHost=127.0.0.1", "-Dhttps.proxyPort=9",
				"-Dhttp.nonProxyHosts=localhost|127.*|[::1]"
			)
			programArgs(
				"--quickPlayMultiplayer", "localhost:25565",
				"--offlineDeveloperMode",
				"--username", "ResearchRef",
				"--proxyHost", "127.0.0.1", "--proxyPort", "9"
			)
		}
		// M12-Bildreferenz (docs/49 §3): Vergleichsbilder an festen Kamerapositionen, Fenster 1280×720, Optionen aus run-shotref/options.txt
		register("shotRef") {
			client()
			configName = "Screenshot Reference Client (localhost:25565)"
			runDir("run-shotref")
			vmArgs(
				"-Xms512M", "-Xmx" + (project.findProperty("shotRefXmx") ?: "2G"),
				"-Dmcresearch.shotref=" + rootProject.file((project.findProperty("shotRefFile") ?: "../analysis/tools/m12-scenes.txt").toString()).absolutePath,
				"-Dmcresearch.shotref.out=" + ((project.findProperty("shotRefOut") as String?) ?: "shots"),
				"-Dminecraft.api.discovery.host=http://127.0.0.1:9/discovery",
				"-Dhttp.proxyHost=127.0.0.1", "-Dhttp.proxyPort=9",
				"-Dhttps.proxyHost=127.0.0.1", "-Dhttps.proxyPort=9",
				"-Dhttp.nonProxyHosts=localhost|127.*|[::1]"
			)
			programArgs(
				"--quickPlayMultiplayer", "localhost:25565",
				"--offlineDeveloperMode",
				"--username", "ResearchRef",
				"--width", "1280", "--height", "720",
				"--proxyHost", "127.0.0.1", "--proxyPort", "9"
			)
		}
		register("pickRef") {
			client()
			configName = "Pick Reference Client (localhost:25565)"
			runDir("run-pickref")
			vmArgs(
				// Speichergrenze des Minecraft-Clients (ohne Angabe: ¼ des RAM), docs/23 §6
				"-Xms512M", "-Xmx" + (project.findProperty("pickRefXmx") ?: "2G"),
				"-Dmcresearch.pickref=" + rootProject.file((project.findProperty("pickRefFile") ?: "../analysis/tools/m7-picks.txt").toString()).absolutePath,
				"-Dmcresearch.pickref.out=pickref.txt",
				"-Dminecraft.api.discovery.host=http://127.0.0.1:9/discovery",
				"-Dhttp.proxyHost=127.0.0.1", "-Dhttp.proxyPort=9",
				"-Dhttps.proxyHost=127.0.0.1", "-Dhttps.proxyPort=9",
				"-Dhttp.nonProxyHosts=localhost|127.*|[::1]"
			)
			programArgs(
				"--quickPlayMultiplayer", "localhost:25565",
				"--offlineDeveloperMode",
				"--username", "ResearchRef",
				"--proxyHost", "127.0.0.1", "--proxyPort", "9"
			)
		}
	}
}

tasks.processResources {
	val version = version
	inputs.property("version", version)

	filesMatching("fabric.mod.json") {
		expand("version" to version)
	}
}

tasks.withType<JavaCompile>().configureEach {
	options.release = 25
}

java {
	// Loom will automatically attach sourcesJar to a RemapSourcesJar task and to the "build" task
	// if it is present.
	// If you remove this line, sources will not be generated.
	withSourcesJar()

	sourceCompatibility = JavaVersion.VERSION_25
	targetCompatibility = JavaVersion.VERSION_25
}

tasks.jar {
	val projectName = project.name
	inputs.property("projectName", projectName)

	from("LICENSE") {
		rename { "${it}_$projectName" }
	}
}

// configure the maven publication
publishing {
	publications {
		register<MavenPublication>("mavenJava") {
			from(components["java"])
		}
	}

	// See https://docs.gradle.org/current/userguide/publishing_maven.html for information on how to set up publishing.
	repositories {
		// Add repositories to publish to here.
		// Notice: This block does NOT have the same function as the block in the top level.
		// The repositories here will be used for publishing your artifact, not for
		// retrieving dependencies.
	}
}
