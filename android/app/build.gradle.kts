import com.android.build.api.artifact.SingleArtifact
import java.io.File
import java.nio.charset.StandardCharsets
import java.util.zip.ZipFile

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
  }

  lint {
    abortOnError = true
    checkReleaseBuilds = true
  }
}

dependencies {
  implementation("eu.rekawek.coffeegb:controller:$coffeeGbVersion")
  implementation("androidx.camera:camera-camera2:1.6.1")
  implementation("androidx.camera:camera-core:1.6.1")
  implementation("androidx.camera:camera-lifecycle:1.6.1")
  implementation("androidx.lifecycle:lifecycle-process:2.11.0")
  testImplementation("junit:junit:4.13.2")
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

tasks.named("preBuild") {
  dependsOn(verifyAndroidPortability, reportAndroidDependencyGraph)
}
tasks.named("check") {
  dependsOn(verifyAndroidPortability, verifyPortabilityFixture, reportAndroidDependencyGraph)
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
