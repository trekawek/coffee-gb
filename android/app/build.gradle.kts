import com.android.build.api.artifact.SingleArtifact
import java.io.File
import java.nio.charset.StandardCharsets
import java.util.zip.ZipFile
import java.util.zip.ZipInputStream

plugins {
  id("com.android.application")
}

val coffeeGbVersion = gradle.extra["coffeeGbVersion"] as String

private val forbiddenSourceReferences = linkedMapOf(
    "java.awt." to "java.awt",
    "javax.swing." to "javax.swing",
    "javax.sound." to "javax.sound",
    "org.jline." to "JLine",
    "org.opencv." to "OpenCV",
    "io.github.libsdl" to "SDL",
)
private val forbiddenBytecodeReferences = linkedMapOf(
    "java/awt/" to "java.awt",
    "javax/swing/" to "javax.swing",
    "javax/sound/" to "javax.sound",
    "org/jline/" to "JLine",
    "org/opencv/" to "OpenCV",
    "io/github/libsdl" to "SDL",
)
// DEX type descriptors start with L. The source/classpath verifier above intentionally scans
// broader JVM internal names; APK inspection must not mistake an unrelated string for a class.
private val forbiddenApkReferences = forbiddenBytecodeReferences.mapKeys { (reference, _) ->
  "L$reference"
}
private val forbiddenApkEntrySuffixes = listOf(
    ".gb",
    ".gbc",
    ".sgb",
    ".rom",
    ".7z",
    ".rar",
    ".sav",
    ".cgbstate",
    ".jks",
    ".keystore",
)
private val forbiddenZipEntrySuffixes = listOf(
    ".gb",
    ".gbc",
    ".sgb",
    ".rom",
    ".sav",
    ".cgbstate",
)

private fun portabilityViolations(sourceFiles: Collection<File>, classpath: Collection<File>): List<String> {
  val violations = mutableListOf<String>()
  sourceFiles.filter(File::isFile).sorted().forEach { source ->
    val contents = source.readText()
    forbiddenSourceReferences.forEach { (needle, label) ->
      if (contents.contains(needle)) {
        violations += "${source.path} references forbidden $label API"
      }
    }
  }
  classpath.filter(File::exists).sorted().forEach { entry ->
    if (entry.isDirectory) {
      entry.walkTopDown().filter { it.isFile && it.extension == "class" }.forEach { classFile ->
        val contents = String(classFile.readBytes(), StandardCharsets.ISO_8859_1)
        forbiddenBytecodeReferences.forEach { (needle, label) ->
          if (contents.contains(needle)) {
            violations += "${classFile.path} references forbidden $label API"
          }
        }
      }
    } else if (entry.extension == "jar") {
      ZipFile(entry).use { jar ->
        val entries = jar.entries()
        while (entries.hasMoreElements()) {
          val classEntry = entries.nextElement()
          if (!classEntry.isDirectory && classEntry.name.endsWith(".class")) {
            val contents = String(jar.getInputStream(classEntry).readBytes(), StandardCharsets.ISO_8859_1)
            forbiddenBytecodeReferences.forEach { (needle, label) ->
              if (contents.contains(needle)) {
                violations += "${entry.name}!/${classEntry.name} references forbidden $label API"
              }
            }
          }
        }
      }
    }
  }
  return violations
}

private val forbiddenPermissions = listOf(
    "android.permission.INTERNET",
    "android.permission.RECORD_AUDIO",
    "android.permission.MANAGE_EXTERNAL_STORAGE",
    "android.permission.READ_EXTERNAL_STORAGE",
    "android.permission.WRITE_EXTERNAL_STORAGE",
    "android.permission.READ_MEDIA_AUDIO",
    "android.permission.READ_MEDIA_IMAGES",
    "android.permission.READ_MEDIA_VIDEO",
)

private fun sensitivePermissions(contents: String): List<String> = forbiddenPermissions.filter { permission ->
  Regex("""<uses-permission[^>]+android:name=[\"']${Regex.escape(permission)}[\"']""")
      .containsMatchIn(contents)
}

android {
  namespace = "eu.rekawek.coffeegb.android"
  compileSdk = 36

  defaultConfig {
    applicationId = "eu.rekawek.coffeegb.android"
    minSdk = 26
    targetSdk = 36
    versionCode = 1
    versionName = coffeeGbVersion
    testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
  }

  buildTypes {
    release {
      isMinifyEnabled = true
      isShrinkResources = true
      proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"), "proguard-rules.pro")
    }
  }

  compileOptions {
    sourceCompatibility = JavaVersion.VERSION_17
    targetCompatibility = JavaVersion.VERSION_17
    isCoreLibraryDesugaringEnabled = true
  }

  lint {
    abortOnError = true
    checkReleaseBuilds = true
  }
}

dependencies {
  coreLibraryDesugaring("com.android.tools:desugar_jdk_libs:2.1.5")
  implementation("eu.rekawek.coffeegb:controller:$coffeeGbVersion")
  implementation("androidx.camera:camera-camera2:1.6.1")
  implementation("androidx.camera:camera-core:1.6.1")
  implementation("androidx.camera:camera-lifecycle:1.6.1")
  implementation("androidx.lifecycle:lifecycle-process:2.11.0")
  testImplementation("junit:junit:4.13.2")
  androidTestImplementation("androidx.test:core:1.7.0")
  androidTestImplementation("androidx.test.ext:junit:1.3.0")
  androidTestImplementation("androidx.test:runner:1.7.0")
}

// AGP creates variant configurations after this script is evaluated. Keep the lookup lazy so the
// verification tasks see the real debug runtime rather than creating a shadow configuration.
val debugRuntimeClasspath = providers.provider {
  configurations.getByName("debugRuntimeClasspath")
}
val verifyAndroidPortability = tasks.register("verifyAndroidPortability") {
  group = "verification"
  description = "Rejects desktop APIs and libraries from Android production code and runtime."
  val productionSources = fileTree("src/main") { include("**/*.java", "**/*.kt") }
  inputs.files(productionSources, debugRuntimeClasspath)
  doLast {
    val violations = portabilityViolations(productionSources.files, debugRuntimeClasspath.get().files)
    check(violations.isEmpty()) { violations.joinToString(separator = "\n") }
  }
}

val verifyPortabilityFixture = tasks.register("verifyAndroidPortabilityFixture") {
  group = "verification"
  description = "Proves the portability scanner rejects a deliberate AWT import."
  val fixture = layout.projectDirectory.file("src/testFixtures/java/ForbiddenDesktopApi.java")
  inputs.file(fixture)
  doLast {
    val violations = portabilityViolations(listOf(fixture.asFile), emptyList())
    check(violations.singleOrNull()?.contains("java.awt") == true) {
      "The Android portability scanner failed to reject its deliberate java.awt fixture: $violations"
    }
  }
}

val reportAndroidDependencyGraph = tasks.register("reportAndroidDependencyGraph") {
  group = "verification"
  description = "Writes the Android debug runtime graph after portability validation."
  val report = layout.buildDirectory.file("reports/android-dependencies.txt")
  inputs.files(debugRuntimeClasspath)
  outputs.file(report)
  doLast {
    val resolved = debugRuntimeClasspath.get().files.sortedBy { it.name }
    val violations = portabilityViolations(emptyList(), resolved)
    check(violations.isEmpty()) { violations.joinToString(separator = "\n") }
    report.get().asFile.apply {
      parentFile.mkdirs()
      writeText(
          buildString {
            appendLine("Coffee GB Android debug runtime dependency graph")
            appendLine("Forbidden desktop API scan: clean")
            resolved.forEach { appendLine(it.absolutePath) }
          }
      )
    }
  }
}
val reportAndroidLicenseInventory = tasks.register("reportAndroidLicenseInventory") {
  group = "verification"
  description = "Writes the resolved Android runtime component inventory for release review."
  val report = layout.buildDirectory.file("reports/android-license-inventory.txt")
  inputs.files(debugRuntimeClasspath)
  outputs.file(report)
  doLast {
    val artifacts = debugRuntimeClasspath.get().resolvedConfiguration.resolvedArtifacts
        .sortedBy { it.moduleVersion.id.toString() }
    report.get().asFile.apply {
      parentFile.mkdirs()
      writeText(
          buildString {
            appendLine("Coffee GB Android runtime component inventory")
            appendLine("Review each component's upstream license before a distribution release.")
            artifacts.forEach { appendLine("${it.moduleVersion.id} (${it.file.name})") }
          }
      )
    }
  }
}
val verifyDebugApkContents = tasks.register("verifyDebugApkContents") {
  group = "verification"
  description = "Rejects desktop APIs, game data, developer paths, and signing material from the debug APK."
  val apk = layout.buildDirectory.file("outputs/apk/debug/app-debug.apk")
  val report = layout.buildDirectory.file("reports/android-apk-contents.txt")
  dependsOn("assembleDebug")
  inputs.file(apk)
  outputs.file(report)
  doLast {
    val packageFile = apk.get().asFile
    val entries = mutableListOf<String>()
    val forbiddenPayload = mutableListOf<String>()
    val forbiddenZipEntries = mutableListOf<String>()
    ZipFile(packageFile).use { archive ->
      archive.entries().asSequence().forEach { entry ->
        entries += entry.name
        if (!entry.isDirectory && entry.name.lowercase().endsWith(".zip")) {
          ZipInputStream(archive.getInputStream(entry)).use { nested ->
            var nestedEntry = nested.nextEntry
            while (nestedEntry != null) {
              if (!nestedEntry.isDirectory && forbiddenZipEntrySuffixes.any { suffix ->
                    nestedEntry.name.lowercase().endsWith(suffix)
                  }) {
                forbiddenZipEntries += "${entry.name}!/${nestedEntry.name}"
              }
              nested.closeEntry()
              nestedEntry = nested.nextEntry
            }
          }
        }
        if (!entry.isDirectory) {
          val payload = String(archive.getInputStream(entry).readBytes(), StandardCharsets.ISO_8859_1)
          forbiddenApkReferences.forEach { (needle, label) ->
            if (payload.contains(needle)) {
              forbiddenPayload += "${entry.name}: $label"
            }
          }
          listOf("/home/", "/Users/", "C:\\Users\\", "/tmp/", ".keystore", ".jks").forEach { needle ->
            if (payload.contains(needle)) {
              forbiddenPayload += "${entry.name}: $needle"
            }
          }
        }
      }
    }
    entries.sort()
    val forbiddenEntries = entries.filter { entry ->
      forbiddenApkEntrySuffixes.any { suffix -> entry.lowercase().endsWith(suffix) }
    }
    check(forbiddenEntries.isEmpty()) {
      "Debug APK contains forbidden game data or signing material: $forbiddenEntries"
    }
    check(forbiddenZipEntries.isEmpty()) {
      "Debug APK contains a ZIP resource with forbidden game data: $forbiddenZipEntries"
    }
    check(forbiddenPayload.isEmpty()) {
      "Debug APK contains forbidden desktop APIs, developer paths, or signing material: $forbiddenPayload"
    }
    report.get().asFile.apply {
      parentFile.mkdirs()
      writeText(buildString {
        appendLine("Coffee GB Android debug APK content verification")
        appendLine("Desktop APIs, game data, developer paths, and signing material: clean")
        entries.forEach(::appendLine)
      })
    }
  }
}

tasks.named("preBuild") {
  dependsOn(verifyAndroidPortability, reportAndroidDependencyGraph, reportAndroidLicenseInventory)
}
tasks.named("check") {
  dependsOn(verifyAndroidPortability, verifyPortabilityFixture, reportAndroidDependencyGraph,
      reportAndroidLicenseInventory, verifyDebugApkContents)
}

androidComponents {
  onVariants(selector().withBuildType("debug")) { variant ->
    val mergedManifest = variant.artifacts.get(SingleArtifact.MERGED_MANIFEST)
    val verifyPermissions = tasks.register("verifyDebugPermissions") {
      group = "verification"
      description = "Rejects sensitive and broad-storage permissions from the merged debug manifest."
      inputs.file(mergedManifest)
      doLast {
        val found = sensitivePermissions(mergedManifest.get().asFile.readText())
        check(found.isEmpty()) { "Merged Android manifest declares forbidden permissions: $found" }
      }
    }
    tasks.named("check") { dependsOn(verifyPermissions) }
    tasks.configureEach {
      if (name == "assembleDebug") {
        dependsOn(verifyPermissions)
      }
    }
  }
}
