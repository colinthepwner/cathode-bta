import java.util.zip.ZipFile
import com.google.gson.Gson
import com.smushytaco.lwjgl_gradle.Preset
import java.nio.file.Files
import java.nio.file.StandardCopyOption
import java.nio.file.FileSystemException
plugins {
	alias(libs.plugins.loom)
	alias(libs.plugins.lwjgl)
    java
}
val modVersion: Provider<String> = providers.gradleProperty("mod_version")
val modGroup: Provider<String> = providers.gradleProperty("mod_group")
val modName: Provider<String> = providers.gradleProperty("mod_name")

val javaVersion: Provider<Int> = libs.versions.java.map { it.toInt() }

base.archivesName = modName
group = modGroup.get()
version = modVersion.get()

class AccountsJson(val accounts: List<Account>)
class Account(val profile: Profile, val ygg: YGG)
class YGG(val token: String)
class Profile(val name: String, val id: String)

val prismAccountsFile = providers.provider {
	val explicit = providers.gradleProperty("prism.accounts.file").orNull
	if (explicit != null) {
		val explicitFile = File(explicit)
		if (explicitFile.exists()) return@provider explicitFile
	}

	val home = System.getProperty("user.home")

	val candidates = buildList {

		System.getenv("APPDATA")?.let { add(File(it, "PrismLauncher/accounts.json")) }
		System.getenv("HOMEPATH")?.let { add(File(it, "scoop/persist/prismlauncher/accounts.json")) }

		val xdgDataHome = System.getenv("XDG_DATA_HOME")
		if (xdgDataHome != null) {
			add(File(xdgDataHome, "PrismLauncher/accounts.json"))
		} else {
			add(File(home, ".local/share/PrismLauncher/accounts.json"))
		}

		add(File(home, ".var/app/org.prismlauncher.PrismLauncher/data/PrismLauncher/accounts.json"))

		add(File(home, "Library/Application Support/PrismLauncher/accounts.json"))
	}
	candidates.firstOrNull(File::exists)
}

loom {
    customMinecraftMetadata.set("https://downloads.betterthanadventure.net/bta-client/${libs.versions.btaChannel.get()}/${libs.versions.bta.get()}/manifest.json")
	runs {
		prismAccountsFile.orNull?.let { file ->
			val account: Provider<Account> = providers.fileContents(layout.file(providers.provider { file }))
				.asText
				.map { jsonStr ->
					val accountNumber = (providers.gradleProperty("prism.accounts.number").orNull?.toInt() ?: 1) - 1
					val accounts = Gson().fromJson(jsonStr, AccountsJson::class.java).accounts
					accounts.getOrNull(accountNumber.coerceIn(0, accounts.size - 1))
						?: error("No PrismLauncher accounts found in ${file.absolutePath}")
				}
			register("clientAuth") {
				inherit(getByName("client"))
				configName = "Minecraft Client (Auth)"
				val acc = account.get()
				programArgs("--username", acc.profile.name, "--uuid", acc.profile.id, "--session", acc.ygg.token)
			}
		}
	}
}

sourceSets {
	main {
		java.srcDir("src/jcef/java")
	}
}

repositories {
    mavenCentral()
    maven("https://maven.fabricmc.net/") { name = "Fabric" }
    maven("https://maven.thesignalumproject.net/infrastructure") { name = "SignalumMavenInfrastructure" }
    maven("https://maven.thesignalumproject.net/releases") { name = "SignalumMavenReleases" }
	maven("https://maven.thesignalumproject.net/nightly") { name = "SignalumMavenNightly" }
    ivy("https://github.com/Better-than-Adventure") {
        patternLayout { artifact("[organisation]/releases/download/[revision]/[module]-bta-[revision].jar") }
        metadataSources { artifact() }
    }
    ivy("https://downloads.betterthanadventure.net/bta-client/${libs.versions.btaChannel.get()}/") {
        patternLayout { artifact("/v[revision]/client.jar") }
        metadataSources { artifact() }
    }
    ivy("https://downloads.betterthanadventure.net/bta-server/${libs.versions.btaChannel.get()}/") {
        patternLayout { artifact("/v[revision]/server.jar") }
        metadataSources { artifact() }
    }
    ivy("https://piston-data.mojang.com") {
        patternLayout { artifact("v1/[organisation]/[revision]/[module].jar") }
        metadataSources { artifact() }
    }
}
lwjgl {
	version = libs.versions.lwjgl
	implementation(Preset.MINIMAL_OPENGL)
}
dependencies {
    minecraft("::${libs.versions.bta.get()}")

	runtimeOnly(libs.clientJar)
	implementation(libs.loader)

	implementation(libs.halplibe)
	include(libs.halplibe)
	implementation(libs.modMenu)
	implementation(libs.legacyLwjgl)

	implementation(libs.slf4jApi)
	implementation(libs.guava)
	implementation(libs.log4j.slf4j2.impl)
	implementation(libs.log4j.core)
	implementation(libs.log4j.api)
	implementation(libs.log4j.api12)
	implementation(libs.gson)

	implementation("net.java.dev.jna:jna:5.14.0")
	include("net.java.dev.jna:jna:5.14.0")

	compileOnly("org.lwjgl:lwjgl-stb:${libs.versions.lwjgl.get()}")

}
java {
	toolchain {
		languageVersion = javaVersion.map { JavaLanguageVersion.of(it) }
		vendor = JvmVendorSpec.ADOPTIUM
	}
	sourceCompatibility = JavaVersion.toVersion(javaVersion.get())
	targetCompatibility = JavaVersion.toVersion(javaVersion.get())
	withSourcesJar()
}
val licenseFile = run {
	val rootLicense = layout.projectDirectory.file("LICENSE")
	val parentLicense = layout.projectDirectory.file("../LICENSE")
	when {
		rootLicense.asFile.exists() -> {
			logger.lifecycle("Using LICENSE from project root: {}", rootLicense.asFile)
			rootLicense
		}
		parentLicense.asFile.exists() -> {
			logger.lifecycle("Using LICENSE from parent directory: {}", parentLicense.asFile)
			parentLicense
		}
		else -> {
			logger.warn("No LICENSE file found in project or parent directory.")
			null
		}
	}
}
tasks {
	withType<JavaCompile>().configureEach {
		options.encoding = "UTF-8"
		options.compilerArgs.addAll(listOf("-Xmaxerrs", "3000"))
		sourceCompatibility = javaVersion.get().toString()
		targetCompatibility = javaVersion.get().toString()
		if (javaVersion.get() > 8) options.release = javaVersion
	}
	withType<UpdateDaemonJvm>().configureEach {
		languageVersion = libs.versions.gradleJava.map { JavaLanguageVersion.of(it.toInt()) }
		vendor = JvmVendorSpec.ADOPTIUM
	}
	withType<JavaExec>().configureEach { defaultCharacterEncoding = "UTF-8" }
	withType<Javadoc>().configureEach { options.encoding = "UTF-8" }
	withType<Test>().configureEach { defaultCharacterEncoding = "UTF-8" }
	withType<Jar>().configureEach {
		licenseFile?.let {
			from(it) {
				rename { original -> "${original}_${archiveBaseName.get()}" }
			}
		}
	}
	processResources {
		val resourceMap = mapOf(
			"version" to modVersion.get(),
			"fabricloader" to libs.versions.loader.get(),
			"halplibe" to libs.versions.halplibe.get(),
			"java" to libs.versions.java.get(),
			"modmenu" to libs.versions.modMenu.get()
		)
		inputs.properties(resourceMap)
		filesMatching("fabric.mod.json") { expand(resourceMap) }
		filesMatching("**/*.mixins.json") { expand(resourceMap.filterKeys { it == "java" }) }
	}
}

configurations.configureEach { exclude(group = "org.lwjgl.lwjgl") }

val modsDeployDir: Provider<String> = providers.gradleProperty("mods.deploy.dir").orElse("")
val modsDeployTimeout: Provider<String> = providers.gradleProperty("mods.deploy.timeoutSeconds").orElse("0")

val deployToMods by tasks.registering {
	description = "Copies the remapped mod jar into the game's mods folder (waits for the game to close if it's locked)."
	group = "loom"

	val jarFileProvider = layout.buildDirectory.file("libs/${modName.get()}-${modVersion.get()}.jar")
	val destDirProvider = modsDeployDir
	val timeoutProvider = modsDeployTimeout
	doLast {
		if (destDirProvider.get().isBlank()) return@doLast
		val destDir = File(destDirProvider.get())
		if (!destDir.isDirectory) {
			logger.warn("[deployToMods] mods folder not found, skipping: $destDir")
			return@doLast
		}
		val src = jarFileProvider.get().asFile
		if (!src.isFile) {
			logger.warn("[deployToMods] built jar not found, skipping: $src")
			return@doLast
		}
		val target = destDir.resolve(src.name)
		val timeoutMs = (timeoutProvider.get().toLongOrNull() ?: 0L) * 1000L
		val deadline = if (timeoutMs > 0L) System.currentTimeMillis() + timeoutMs else Long.MAX_VALUE
		val pollMs = 3000L
		var notified = false
		var elapsed = 0L
		while (true) {
			try {
				Files.copy(src.toPath(), target.toPath(), StandardCopyOption.REPLACE_EXISTING)
				logger.lifecycle("[deployToMods] deployed ${src.name} -> $destDir")
				break
			} catch (e: FileSystemException) {

				if (System.currentTimeMillis() >= deadline) {
					logger.warn("[deployToMods] timed out waiting for ${target.name} to unlock; not deployed.")
					break
				}
				if (!notified) {
					logger.lifecycle("[deployToMods] ${target.name} is locked (game running?) — waiting for the game to close to deploy... (Ctrl+C to skip)")
					notified = true
				} else if (elapsed % 30000L == 0L) {
					logger.lifecycle("[deployToMods] still waiting for the game to close...")
				}
				Thread.sleep(pollMs)
				elapsed += pollMs
			}
		}
	}
}

tasks.named("build") { finalizedBy(deployToMods) }

tasks.register("printRuntimeClasspath") {
	val cp = sourceSets["main"].runtimeClasspath
	doLast { println(cp.asPath) }
}

val coreNames = listOf("snes9x", "parallel_n64", "fceumm", "genesis_plus_gx", "mgba")
val coreLicences = mapOf(
	"snes9x" to "https://raw.githubusercontent.com/snes9xgit/snes9x/master/LICENSE",
	"fceumm" to "https://raw.githubusercontent.com/libretro/libretro-fceumm/master/Copying",
	"genesis_plus_gx" to "https://raw.githubusercontent.com/libretro/Genesis-Plus-GX/master/LICENSE.txt",
	"mgba" to "https://raw.githubusercontent.com/libretro/mgba/master/LICENSE",

	"parallel_n64" to "https://raw.githubusercontent.com/libretro/libretro-fceumm/master/Copying"
)
val coresCache = layout.buildDirectory.dir("cores-cache")
val fetchCores by tasks.registering {
	description = "Downloads the bundled libretro cores and their licences (once; cached)."
	outputs.dir(coresCache)
	doLast {
		val out = coresCache.get().asFile.resolve("btemu/cores")
		out.mkdirs()
		for (c in coreNames) {
			val dll = out.resolve("${c}_libretro.dll")
			if (!dll.isFile) {
				logger.lifecycle("[fetchCores] downloading $c")
				val zip = File(temporaryDir, "$c.zip")
				uri("https://buildbot.libretro.com/nightly/windows/x86_64/latest/${c}_libretro.dll.zip").toURL().openStream().use { i -> zip.outputStream().use { i.copyTo(it) } }
				ZipFile(zip).use { z ->
					val e = z.entries().asSequence().first { it.name.endsWith(".dll") }
					z.getInputStream(e).use { i -> dll.outputStream().use { i.copyTo(it) } }
				}
			}
			val lic = out.resolve("${c}_LICENSE.txt")
			if (!lic.isFile) uri(coreLicences.getValue(c)).toURL().openStream().use { i -> lic.outputStream().use { i.copyTo(it) } }
		}
		out.resolve("NOTICE.txt").writeText(
			"Emulator cores bundled with this mod are separate programs from the libretro project, " +
			"distributed under their own licences (the *_LICENSE.txt files here).\n" +
			"Sources:\n" +
			"  snes9x          https://github.com/libretro/snes9x\n" +
			"  parallel_n64    https://github.com/libretro/parallel-n64\n" +
			"  fceumm          https://github.com/libretro/libretro-fceumm\n" +
			"  genesis_plus_gx https://github.com/libretro/Genesis-Plus-GX\n" +
			"  mgba            https://github.com/libretro/mgba\n" +
			"Binaries: https://buildbot.libretro.com/nightly/windows/x86_64/latest/\n")
	}
}
tasks.named<ProcessResources>("processResources") {
	dependsOn(fetchCores)
	from(coresCache)
}

val proguardTool: Configuration by configurations.creating
dependencies { proguardTool("com.guardsquare:proguard-base:7.7.0") }

val obfuscateJar by tasks.registering(JavaExec::class) {
	description = "Obfuscates the release jar with ProGuard (in place)."
	group = "build"
	val jarTask = tasks.named<org.gradle.jvm.tasks.Jar>("jar")
	dependsOn(jarTask)
	val inJar = jarTask.flatMap { it.archiveFile }
	val outJar = layout.buildDirectory.file("obf/${modName.get()}-${modVersion.get()}.jar")
	inputs.file(inJar)
	inputs.file("proguard.pro")
	outputs.file(outJar)
	classpath = proguardTool
	mainClass.set("proguard.ProGuard")
	val libs = configurations.getByName("compileClasspath")
	doFirst {
		outJar.get().asFile.parentFile.mkdirs()
		val javaHome = javaLauncher.get().metadata.installationPath.asFile
		val argsList = mutableListOf(
			"-injars", inJar.get().asFile.absolutePath,
			"-outjars", outJar.get().asFile.absolutePath,
			"-libraryjars", File(javaHome, "jmods").absolutePath + "(!**.jar;!module-info.class)",
			"@" + file("proguard.pro").absolutePath
		)
		for (f in libs.files) if (f.exists()) { argsList += "-libraryjars"; argsList += f.absolutePath }
		args = argsList
	}
	doLast {

		Files.copy(outJar.get().asFile.toPath(), inJar.get().asFile.toPath(), StandardCopyOption.REPLACE_EXISTING)
		logger.lifecycle("[obfuscateJar] obfuscated ${inJar.get().asFile.name}")
	}
}
tasks.named("build") { dependsOn(obfuscateJar) }
deployToMods { mustRunAfter(obfuscateJar) }
