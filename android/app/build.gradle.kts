import com.android.build.api.artifact.SingleArtifact
import java.io.File
import java.nio.charset.StandardCharsets
import java.util.zip.ZipFile
import java.util.zip.ZipInputStream

plugins {
  id("com.android.application")
}

val coffeeGbVersion = gradle.extra["coffeeGbVersion"] as String

private val requiredBaselineProfileClassNames = listOf(
    "eu.rekawek.coffeegb.core.Gameboy",
    "eu.rekawek.coffeegb.core.cpu.Cpu",
    "eu.rekawek.coffeegb.core.cpu.Cpu\$PerformanceEpochBus",
    "eu.rekawek.coffeegb.core.gpu.Gpu",
    "eu.rekawek.coffeegb.core.gpu.StatRegister",
    "eu.rekawek.coffeegb.core.sound.Sound",
    "eu.rekawek.coffeegb.core.timer.Timer",
    "eu.rekawek.coffeegb.core.serial.SerialPort",
)
private val expectedBaselineProfileRules = requiredBaselineProfileClassNames.map { className ->
  "HPL${className.replace('.', '/')};->**(**)**"
}
private val packagedBaselineProfileEntries = listOf(
    "assets/dexopt/baseline.prof",
    "assets/dexopt/baseline.profm",
)

private val forbiddenSourceReferences = linkedMapOf(
    "java.awt." to "java.awt",
    "javax.swing." to "javax.swing",
    "javax.sound." to "javax.sound",
    "org.jline." to "JLine",
    "org.opencv." to "OpenCV",
    "io.github.libsdl" to "SDL",
    ".readAllBytes()" to "Java 9 InputStream.readAllBytes()",
    ".readNBytes(" to "Java 9 InputStream.readNBytes()",
    "Thread.onSpinWait()" to "Java 9 Thread.onSpinWait()",
    ".isRecord" to "Java 16 Class.isRecord()",
    ".recordComponents" to "Java 16 Class.getRecordComponents()",
)
private val forbiddenBytecodeReferences = linkedMapOf(
    "java/awt/" to "java.awt",
    "javax/swing/" to "javax.swing",
    "javax/sound/" to "javax.sound",
    "org/jline/" to "JLine",
    "org/opencv/" to "OpenCV",
    "io/github/libsdl" to "SDL",
)
private val forbiddenCoffeeGbBytecodeReferences = linkedMapOf(
    "isRecord" to "Java 16 Class.isRecord()",
    "getRecordComponents" to "Java 16 Class.getRecordComponents()",
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

// These runtime types cross a portable-state reflection boundary by binary name. A release APK
// may optimize their implementations, but renaming any reachable entry makes ROM-switch autosave
// fail only after R8, which debug/instrumentation builds cannot reproduce.
private val releaseStableStateClassNames = listOf(
    "eu.rekawek.coffeegb.core.serial.SerialEndpoint\$1",
    "eu.rekawek.coffeegb.core.serial.Peer2PeerSerialEndpoint",
    "eu.rekawek.coffeegb.core.serial.GameboyPrinterSerialEndpoint",
    "eu.rekawek.coffeegb.core.serial.GpsReceiverSerialEndpoint",
    "eu.rekawek.coffeegb.core.serial.BarcodeBoySerialEndpoint",
    "eu.rekawek.coffeegb.core.serial.mobile.MobileAdapterSerialEndpoint",
    "eu.rekawek.coffeegb.core.genie.Genie\$GameGeniePatchState",
    "eu.rekawek.coffeegb.core.gpu.Gpu\$PendingPpuWriteState",
    "eu.rekawek.coffeegb.core.cpu.Cpu\$State",
    "eu.rekawek.coffeegb.core.gpu.Mode",
    "eu.rekawek.coffeegb.core.genie.Genie\$GenieMemento",
    "eu.rekawek.coffeegb.core.genie.GameGeniePatch",
)

private fun r8ClassMappings(mappingFile: File): Map<String, String> =
    mappingFile.useLines { lines ->
      lines.mapNotNull { line ->
        if (line.isBlank() || line.first().isWhitespace() || !line.endsWith(':')) {
          return@mapNotNull null
        }
        val separator = line.indexOf(" -> ")
        if (separator < 0) {
          return@mapNotNull null
        }
        line.substring(0, separator) to line.substring(separator + 4, line.length - 1)
      }.toMap()
    }

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
    val references =
        if (entry.name.startsWith("core-") || entry.name.startsWith("controller-")) {
          forbiddenBytecodeReferences + forbiddenCoffeeGbBytecodeReferences
        } else {
          forbiddenBytecodeReferences
        }
    if (entry.isDirectory) {
      entry.walkTopDown().filter { it.isFile && it.extension == "class" }.forEach { classFile ->
        val contents = String(classFile.readBytes(), StandardCharsets.ISO_8859_1)
        references.forEach { (needle, label) ->
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
            references.forEach { (needle, label) ->
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

  buildFeatures {
    buildConfig = true
  }

  defaultConfig {
    applicationId = "eu.rekawek.coffeegb.android"
    minSdk = 26
    targetSdk = 36
    versionCode = 1
    versionName = coffeeGbVersion
    testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
    buildConfigField("boolean", "DIAGNOSTICS_ENABLED", "false")
  }

  buildTypes {
    release {
      isMinifyEnabled = true
      isShrinkResources = true
      proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"), "proguard-rules.pro")
    }
    create("benchmark") {
      initWith(getByName("release"))
      matchingFallbacks += listOf("release")
      // Benchmark artifacts are installed and alternated by the matrix runner. Use the
      // machine-local Android debug key so every locally built parent/candidate benchmark
      // artifact has a stable, non-secret certificate. Release remains intentionally unsigned
      // here; the release packaging workflow owns its signing policy.
      signingConfig = signingConfigs.getByName("debug")
      buildConfigField("boolean", "DIAGNOSTICS_ENABLED", "true")
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
  implementation("eu.rekawek.coffeegb:ui-portable:$coffeeGbVersion")
  implementation("androidx.camera:camera-camera2:1.6.1")
  implementation("androidx.camera:camera-core:1.6.1")
  implementation("androidx.camera:camera-lifecycle:1.6.1")
  implementation("androidx.lifecycle:lifecycle-process:2.11.0")
  implementation("androidx.profileinstaller:profileinstaller:1.4.1")
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
val coreLibraryDesugaringClasspath = providers.provider {
  configurations.getByName("coreLibraryDesugaring")
}
val baselineProfileSource = layout.projectDirectory.file("src/main/baseline-prof.txt")
val verifyBaselineProfileSource = tasks.register("verifyBaselineProfileSource") {
  group = "verification"
  description = "Requires the exact reviewed HP-only Android baseline profile."
  inputs.file(baselineProfileSource)
  doLast {
    val expected = expectedBaselineProfileRules.joinToString(separator = "\n", postfix = "\n")
    val actual = baselineProfileSource.asFile.readText()
    check(actual == expected) {
      "src/main/baseline-prof.txt must contain exactly the eight unique reviewed HP rules " +
          "in order, with no S flags."
    }
  }
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
  inputs.files(debugRuntimeClasspath, coreLibraryDesugaringClasspath)
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
            appendLine("Android core-library desugaring:")
            coreLibraryDesugaringClasspath.get().files.sortedBy { it.name }
                .forEach { appendLine(it.absolutePath) }
          }
      )
    }
  }
}
val reportAndroidLicenseInventory = tasks.register("reportAndroidLicenseInventory") {
  group = "verification"
  description = "Writes the resolved Android runtime component inventory for release review."
  val report = layout.buildDirectory.file("reports/android-license-inventory.txt")
  inputs.files(debugRuntimeClasspath, coreLibraryDesugaringClasspath)
  outputs.file(report)
  doLast {
    val artifacts = (debugRuntimeClasspath.get().resolvedConfiguration.resolvedArtifacts +
        coreLibraryDesugaringClasspath.get().resolvedConfiguration.resolvedArtifacts)
        .distinctBy { it.moduleVersion.id.toString() }
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
  dependsOn(verifyBaselineProfileSource, verifyAndroidPortability, reportAndroidDependencyGraph,
      reportAndroidLicenseInventory)
}
tasks.named("check") {
  dependsOn(verifyBaselineProfileSource, verifyAndroidPortability, verifyPortabilityFixture,
      reportAndroidDependencyGraph, reportAndroidLicenseInventory, verifyDebugApkContents)
}

androidComponents {
  // AGP does not create unit-test artifacts for a non-debuggable release-like variant by
  // default. Keep benchmark non-debuggable/minified, but enable its isolated JVM test component
  // so BuildConfig.DIAGNOSTICS_ENABLED=true is exercised instead of silently returning from the
  // benchmark-only accounting/run-control tests.
  beforeVariants(selector().withBuildType("benchmark")) { variantBuilder ->
    // AGP 9.3 also exposes deprecated duplicate accessors on VariantBuilder itself. The
    // non-deprecated component capability is HasUnitTestBuilder; use it explicitly so Kotlin DSL
    // resolution does not select the ambiguous VariantBuilder property.
    (variantBuilder as com.android.build.api.variant.HasUnitTestBuilder).enableUnitTest = true
  }
  listOf("release", "benchmark").forEach { buildType ->
    onVariants(selector().withBuildType(buildType)) { variant ->
      val apkDirectory = variant.artifacts.get(SingleArtifact.APK)
      val mapping = variant.artifacts.get(SingleArtifact.OBFUSCATION_MAPPING_FILE)
      val builtArtifactsLoader = variant.artifacts.getBuiltArtifactsLoader()
      val taskSuffix = variant.name.substring(0, 1).uppercase() + variant.name.substring(1)
      val verifyPackagedProfile = tasks.register(
          "verify${taskSuffix}BaselineProfilePackaging"
      ) {
        group = "verification"
        description = "Verifies $buildType APK baseline profiles and profiled R8 classes."
        dependsOn(verifyBaselineProfileSource)
        inputs.dir(apkDirectory)
        inputs.file(mapping)
        doLast {
          val builtArtifacts = checkNotNull(builtArtifactsLoader.load(apkDirectory.get())) {
            "AGP did not publish $buildType APK metadata."
          }
          check(builtArtifacts.elements.isNotEmpty()) {
            "AGP published no $buildType APKs to verify."
          }
          val classMappings = r8ClassMappings(mapping.get().asFile)
          val missingMappings = requiredBaselineProfileClassNames.filterNot(
              classMappings::containsKey
          )
          check(missingMappings.isEmpty()) {
            "$buildType R8 mapping omitted baseline-profile classes: $missingMappings"
          }
          builtArtifacts.elements.forEach { element ->
            val apk = file(element.outputFile)
            check(apk.isFile) { "$buildType APK is missing: ${apk.absolutePath}" }
            ZipFile(apk).use { archive ->
              packagedBaselineProfileEntries.forEach { entryName ->
                val entry = archive.getEntry(entryName)
                check(entry != null && !entry.isDirectory && entry.size > 0L) {
                  "${apk.name} is missing non-empty $entryName"
                }
              }
              val dexPayloads = archive.entries().asSequence()
                  .filter { entry ->
                    !entry.isDirectory && entry.name.matches(Regex("classes[0-9]*\\.dex"))
                  }
                  .map { entry ->
                    String(archive.getInputStream(entry).readBytes(), StandardCharsets.ISO_8859_1)
                  }
                  .toList()
              check(dexPayloads.isNotEmpty()) { "${apk.name} contains no DEX payload." }
              requiredBaselineProfileClassNames.forEach { originalName ->
                val mappedName = classMappings.getValue(originalName)
                val descriptor = "L${mappedName.replace('.', '/')};"
                check(dexPayloads.any { payload -> payload.contains(descriptor) }) {
                  "${apk.name} DEX omitted profiled class $originalName ($descriptor)."
                }
              }
            }
            val installProfiles = apk.parentFile.resolve("baselineProfiles")
                .walkTopDown()
                .filter { candidate -> candidate.isFile && candidate.extension == "dm" }
                .toList()
            check(installProfiles.size == 2) {
              "${apk.name} requires exactly two API-ranged install profiles: $installProfiles"
            }
            installProfiles.forEach { profile ->
              check(profile.nameWithoutExtension == apk.nameWithoutExtension) {
                "${profile.name} must share the APK stem ${apk.nameWithoutExtension}"
              }
              ZipFile(profile).use { archive ->
                val entries = archive.entries().asSequence()
                    .filterNot { entry -> entry.isDirectory }
                    .map { entry -> entry.name to entry.size }
                    .toList()
                check(entries.size == 2
                    && entries.map { entry -> entry.first }.toSet()
                        == setOf("primary.prof", "primary.profm")
                    && entries.all { entry -> entry.second > 0L }) {
                  "${profile.name} must contain only non-empty primary.prof and primary.profm"
                }
              }
            }
          }
        }
      }
      tasks.named("check") { dependsOn(verifyPackagedProfile) }
      tasks.configureEach {
        if (name == "assemble$taskSuffix") {
          finalizedBy(verifyPackagedProfile)
        }
      }
    }
  }
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
  onVariants(selector().withBuildType("release")) { variant ->
    val mapping = variant.artifacts.get(SingleArtifact.OBFUSCATION_MAPPING_FILE)
    val verifyStateNames = tasks.register("verifyReleaseStateReflectionNames") {
      group = "verification"
      description = "Rejects R8 renaming at portable-state reflection boundaries."
      inputs.file(mapping)
      doLast {
        val classMappings = mapping.get().asFile.useLines { lines ->
          lines.mapNotNull { line ->
            if (line.isBlank() || line.first().isWhitespace() || !line.endsWith(':')) {
              return@mapNotNull null
            }
            val separator = line.indexOf(" -> ")
            if (separator < 0) {
              return@mapNotNull null
            }
            line.substring(0, separator) to line.substring(separator + 4, line.length - 1)
          }.toMap()
        }
        val missing = releaseStableStateClassNames.filterNot(classMappings::containsKey)
        check(missing.isEmpty()) {
          "Release mapping omitted portable-state boundary classes: $missing"
        }
        val renamed = releaseStableStateClassNames.mapNotNull { original ->
          classMappings[original]?.takeIf { it != original }?.let { "$original -> $it" }
        }
        check(renamed.isEmpty()) {
          "R8 renamed portable-state boundary classes: $renamed"
        }
      }
    }
    tasks.configureEach {
      if (name == "assembleRelease") {
        dependsOn(verifyStateNames)
      }
    }
  }
}
