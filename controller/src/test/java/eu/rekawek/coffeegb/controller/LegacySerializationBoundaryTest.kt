package eu.rekawek.coffeegb.controller

import eu.rekawek.coffeegb.controller.state.DetachedStateAdapter
import eu.rekawek.coffeegb.controller.state.StateCodec
import eu.rekawek.coffeegb.core.Gameboy
import eu.rekawek.coffeegb.core.events.EventBusImpl
import eu.rekawek.coffeegb.core.ir.InfraredEndpoint
import eu.rekawek.coffeegb.core.memento.Memento
import eu.rekawek.coffeegb.core.memory.cart.Rom
import eu.rekawek.coffeegb.core.serial.SerialEndpoint
import eu.rekawek.coffeegb.core.state.ComponentState
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.Paths
import java.util.Comparator
import kotlin.io.path.isRegularFile
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import org.junit.Test

class LegacySerializationBoundaryTest {

  @Test
  fun productionSourceEnforcesTheSingleLocalImporterBoundary() {
    val root = repositoryRoot()
    val sourceRoots =
        listOf(
            root.resolve("core/src/main"),
            root.resolve("controller/src/main"),
            root.resolve("swing/src/main"),
        )
    val sources =
        sourceRoots.flatMap { sourceRoot ->
          Files.walk(sourceRoot).use { paths ->
            paths
                .filter { it.isRegularFile() && (it.toString().endsWith(".java") || it.toString().endsWith(".kt")) }
                .toList()
          }
        }
    val texts = sources.associateWith(Files::readString)

    assertFalse(texts.values.any { "ObjectOutputStream" in it }, "Production has no legacy writer")
    assertEquals(
        setOf(root.resolve("controller/src/main/java/eu/rekawek/coffeegb/controller/LegacySnapshotImporter.kt")),
        texts.filterValues {
          "import java.io.ObjectInputStream" in it || ") : ObjectInputStream(" in it
        }.keys,
    )
    assertEquals(
        setOf(
            root.resolve("controller/src/main/java/eu/rekawek/coffeegb/controller/LegacySnapshotImporter.kt"),
            root.resolve("controller/src/main/java/eu/rekawek/coffeegb/controller/LegacySerializationPreflight.kt"),
            root.resolve("controller/src/main/java/eu/rekawek/coffeegb/controller/SnapshotManager.kt"),
        ),
        texts.filterValues { "LegacySnapshotImporter" in it }.keys,
        "Only local snapshot dispatch and its bounded preflight may reach the importer",
    )
    listOf("saveToMemento", "restoreFromMemento", "Originator").forEach { forbidden ->
      assertFalse(
          texts.values.any { Regex("\\b${Regex.escape(forbidden)}\\b").containsMatchIn(it) },
          "Production must not expose the retired $forbidden API",
      )
    }

    val serializableDeclarations =
        texts
            .filterValues {
              "import java.io.Serializable" in it ||
                  Regex(
                          "\\b(?:implements|extends)[^{\\n]*\\b(?:java\\.io\\.)?Serializable\\b")
                      .containsMatchIn(it)
            }
            .keys
    assertEquals(
        setOf(
            root.resolve("core/src/main/java/eu/rekawek/coffeegb/core/memento/Memento.java"),
            root.resolve("core/src/main/java/eu/rekawek/coffeegb/core/genie/Patch.java"),
            root.resolve("core/src/main/java/eu/rekawek/coffeegb/core/gpu/Gpu.java"),
            root.resolve("core/src/main/java/eu/rekawek/coffeegb/core/gpu/phase/PixelTransfer.java"),
        ),
        serializableDeclarations,
        "Only the pinned importer marker and four released embedded value types stay serializable",
    )

    val coreSources = texts.filterKeys { it.startsWith(root.resolve("core/src/main")) }
    var compatibilityRecords = 0
    coreSources.forEach { (path, source) ->
      if (path.endsWith("memento/Memento.java")) return@forEach
      compatibilityRecords += Regex(Regex.escape(COMPATIBILITY_MARKER)).findAll(source).count()
      val liveSource =
          stripCompatibilityRecords(source)
              .replace("import eu.rekawek.coffeegb.core.memento.Memento;", "")
      assertFalse(
          Regex("\\bMemento\\s*<").containsMatchIn(liveSource),
          "Live owner still refers to Memento after importer-only records are removed: $path",
      )
      assertFalse(
          "eu.rekawek.coffeegb.core.memento" in liveSource,
          "Live owner still depends on the historical compatibility package: $path",
      )
    }
    assertEquals(87, compatibilityRecords)
    assertEquals(91, StateTypeRegistry.recordClasses.size)
    assertEquals(91, StateTypeRegistry.legacyRecordClasses.size)
    assertTrue(
        StateTypeRegistry.recordClasses.take(87).all(ComponentState::class.java::isAssignableFrom))
    assertTrue(
        StateTypeRegistry.legacyRecordClasses.take(87).all(Memento::class.java::isAssignableFrom))
    assertEquals(
        StateTypeRegistry.recordClassNames.takeLast(4),
        StateTypeRegistry.legacyRecordClassNames.takeLast(4),
        "The four shared immutable leaf values retain stable v1 IDs and legacy descriptors",
    )

    val networkSource =
        texts
            .filterKeys {
              it.startsWith(
                  root.resolve("controller/src/main/java/eu/rekawek/coffeegb/controller/network"))
            }
            .values
            .joinToString("\n")
    listOf(
            "LegacySnapshotImporter",
            "LegacySerializationPreflight",
            "ObjectInputStream",
            "ObjectOutputStream",
            "Memento<",
            "CGBN",
        )
        .forEach { forbidden ->
          assertFalse(forbidden in networkSource, "Network source must not reach $forbidden")
        }
  }

  @Test
  fun normalCaptureRewindBootSessionAndPortableDiskPathsDoNotInvokeImporter() {
    var imports = 0
    LegacySnapshotImporter.importObserver = { imports++ }
    val directory = Files.createTempDirectory("coffee-gb-no-legacy-runtime")
    try {
      val romFile =
          directory.resolve("normal.gb").toFile().also {
            it.writeBytes(Paths.get("src/test/resources/roms/cpu_instrs.gb").toFile().readBytes())
          }
      val configuration =
          Gameboy.GameboyConfiguration(Rom(romFile))
              .setBootstrapMode(Gameboy.BootstrapMode.SKIP)
      val bus = EventBusImpl()
      val gameboy = configuration.build()
      gameboy.init(bus, SerialEndpoint.NULL_ENDPOINT, InfraredEndpoint.NULL_ENDPOINT, null)
      try {
        repeat(128) { gameboy.tick() }
        val detached = DetachedStateAdapter.capture(gameboy)
        DetachedStateAdapter.apply(gameboy, detached)
        val portable = StateCodec.encode(StateCodec.capture(configuration, gameboy))
        StateCodec.decodeAndApply(portable, configuration, gameboy)

        val rewind = RewindManager()
        rewind.record(gameboy)
        repeat(64) { gameboy.tick() }
        assertTrue(rewind.rewindOneStep(gameboy))

        val boot = gameboy.saveBootState()
        gameboy.restoreBootState(boot)

        val snapshots = SnapshotManager(configuration)
        snapshots.saveSnapshot(0, gameboy)
        assertTrue(snapshots.loadSnapshot(0, gameboy))
      } finally {
        gameboy.stop()
        gameboy.close()
        bus.close()
      }

      val session = Session(configuration, EventBusImpl(), null)
      try {
        val sessionState = session.captureDetachedState()
        session.restoreDetachedState(sessionState)
      } finally {
        session.close()
      }
      assertEquals(0, imports)
    } finally {
      LegacySnapshotImporter.importObserver = null
      Files.walk(directory).use { paths ->
        paths.sorted(Comparator.reverseOrder()).forEach(Files::deleteIfExists)
      }
    }
  }

  private fun repositoryRoot(): Path {
    var candidate = Paths.get("").toAbsolutePath().normalize()
    while (!Files.isDirectory(candidate.resolve("core")) ||
        !Files.isDirectory(candidate.resolve("controller"))) {
      candidate = candidate.parent ?: error("Unable to locate repository root")
    }
    return candidate
  }

  private fun stripCompatibilityRecords(source: String): String {
    var result = source
    while (true) {
      val marker = result.indexOf(COMPATIBILITY_MARKER)
      if (marker < 0) return result
      val open = result.indexOf('{', marker)
      check(open >= 0) { "Importer compatibility record has no body" }
      var depth = 0
      var end = open
      while (end < result.length) {
        when (result[end]) {
          '{' -> depth++
          '}' -> {
            depth--
            if (depth == 0) break
          }
        }
        end++
      }
      check(depth == 0) { "Importer compatibility record has an unterminated body" }
      val declaration = result.substring(marker, open)
      check(
          Regex("\\brecord\\s+\\w+(?:Memento|Momento)\\s*\\(").containsMatchIn(declaration) &&
              "implements Memento<" in declaration) {
        "Importer compatibility marker must guard one historical data record"
      }
      check(result.substring(open + 1, end).isBlank()) {
        "Importer compatibility records must remain data-only"
      }
      result = result.removeRange(marker, end + 1)
    }
  }

  private companion object {
    const val COMPATIBILITY_MARKER =
        "/** Importer-only compatibility record for released local snapshots. */"
  }
}
