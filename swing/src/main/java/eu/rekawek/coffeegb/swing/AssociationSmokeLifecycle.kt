package eu.rekawek.coffeegb.swing

import eu.rekawek.coffeegb.core.memory.cart.RomOrigin
import java.nio.charset.StandardCharsets
import java.nio.file.Files
import java.nio.file.LinkOption
import java.nio.file.Path
import java.nio.file.StandardOpenOption
import java.util.Locale
import java.util.concurrent.atomic.AtomicBoolean

internal const val ASSOCIATION_SMOKE_MARKER_ENV = "COFFEE_GB_ASSOCIATION_SMOKE_MARKER"
internal const val ASSOCIATION_SMOKE_ROM_ENV = "COFFEE_GB_ASSOCIATION_SMOKE_ROM"
internal const val ASSOCIATION_SMOKE_ROM_TITLE = "COFFEE-CI-SMOKE"

internal data class AssociationSmokeConfiguration(
    val marker: Path,
    val expectedRom: Path,
    val expectedSource: RomOpenSource,
) {
  val shutdownMarker: Path = marker.resolveSibling("${marker.fileName}.shutdown")
}

internal fun associationSmokeConfiguration(
    environment: Map<String, String>,
    osName: String,
): AssociationSmokeConfiguration? {
  val markerText =
      environment[ASSOCIATION_SMOKE_MARKER_ENV]?.takeIf(String::isNotBlank) ?: return null
  val romText =
      environment[ASSOCIATION_SMOKE_ROM_ENV]?.takeIf(String::isNotBlank)
          ?: throw IllegalArgumentException(
              "$ASSOCIATION_SMOKE_ROM_ENV is required with $ASSOCIATION_SMOKE_MARKER_ENV")
  val marker = Path.of(markerText)
  val expectedRom = Path.of(romText)
  require(marker.isAbsolute) { "$ASSOCIATION_SMOKE_MARKER_ENV must be an absolute path" }
  require(expectedRom.isAbsolute) { "$ASSOCIATION_SMOKE_ROM_ENV must be an absolute path" }
  val expectedSource =
      when {
        osName.lowercase(Locale.ROOT).startsWith("mac") ||
            osName.lowercase(Locale.ROOT).startsWith("darwin") ->
            RomOpenSource.DESKTOP_OPEN_FILE
        osName.lowercase(Locale.ROOT).startsWith("linux") ||
            osName.lowercase(Locale.ROOT).startsWith("windows") ->
            RomOpenSource.INITIAL_ARGUMENT
        else -> throw IllegalArgumentException(
            "$ASSOCIATION_SMOKE_MARKER_ENV is unsupported on operating system $osName")
      }
  return AssociationSmokeConfiguration(
      marker.normalize(),
      expectedRom.normalize(),
      expectedSource,
  )
}

/**
 * Turns the unified ROM-open service's correlated terminal acknowledgement into installed-package
 * evidence. Failed, cancelled, stale, or superseded requests cannot produce an [Opened] update at
 * this seam.
 */
internal class AssociationSmokeLifecycle(
    private val configuration: AssociationSmokeConfiguration,
    private val completed: () -> Unit,
    private val failed: (Exception) -> Unit,
    private val isRegularFile: (Path) -> Boolean = {
      Files.isRegularFile(it, LinkOption.NOFOLLOW_LINKS)
    },
) {
  private val claimed = AtomicBoolean()
  private val shutdownClaimed = AtomicBoolean()

  fun observe(update: RomOpenUpdate) {
    val opened = update as? RomOpenUpdate.Opened ?: return
    if (!isExpectedAssociationOpen(opened) || !claimed.compareAndSet(false, true)) {
      return
    }
    val evidence =
        buildString {
          appendLine("Coffee GB association open OK")
          appendLine("source=${opened.source.name}")
          appendLine("rom=${opened.recentPath}")
          appendLine("origin=${opened.origin.containerPath().orElseThrow()}")
          appendLine("title=${opened.title}")
          appendLine("pid=${ProcessHandle.current().pid()}")
        }
    writeAssociationSmokeEvidence(
        configuration.marker,
        evidence,
        publishWhen = { isRegularFile(configuration.expectedRom) },
    ) { result ->
      when (result) {
        AssociationEvidenceWriteResult.Published -> completed()
        AssociationEvidenceWriteResult.Ignored -> Unit
        is AssociationEvidenceWriteResult.Failed -> failed(result.failure)
      }
    }
  }

  /** Records that the normal bounded desktop shutdown committed before process exit. */
  fun recordNormalShutdown(completed: () -> Unit) {
    if (!claimed.get() || !shutdownClaimed.compareAndSet(false, true)) {
      failed(IllegalStateException("Association smoke shutdown has no unique opened request"))
      return
    }
    val evidence =
        buildString {
          appendLine("Coffee GB association shutdown OK")
          appendLine("pid=${ProcessHandle.current().pid()}")
        }
    writeAssociationSmokeEvidence(configuration.shutdownMarker, evidence) { result ->
      when (result) {
        AssociationEvidenceWriteResult.Published -> completed()
        AssociationEvidenceWriteResult.Ignored ->
            failed(IllegalStateException("Association shutdown evidence was unexpectedly ignored"))
        is AssociationEvidenceWriteResult.Failed -> failed(result.failure)
      }
    }
  }

  private fun isExpectedAssociationOpen(opened: RomOpenUpdate.Opened): Boolean {
    if (opened.source != configuration.expectedSource ||
        opened.title != ASSOCIATION_SMOKE_ROM_TITLE) {
      return false
    }
    val exactPath = opened.recentPath.toAbsolutePath().normalize()
    if (exactPath != opened.recentPath || exactPath.toString().contains('\n') ||
        exactPath.toString().contains('\r') || exactPath != configuration.expectedRom) {
      return false
    }
    if (opened.origin.kind() != RomOrigin.Kind.DIRECT_FILE) {
      return false
    }
    val originPath = opened.origin.containerPath().orElse(null) ?: return false
    if (originPath != exactPath) {
      return false
    }
    val extension =
        exactPath.fileName
            ?.toString()
            ?.substringAfterLast('.', missingDelimiterValue = "")
            ?.lowercase(Locale.ROOT)
    return extension in setOf("gb", "gbc", "rom")
  }
}

internal sealed interface AssociationEvidenceWriteResult {
  data object Published : AssociationEvidenceWriteResult

  data object Ignored : AssociationEvidenceWriteResult

  data class Failed(val failure: Exception) : AssociationEvidenceWriteResult
}

internal fun writeAssociationSmokeEvidence(
    marker: Path,
    evidence: String,
    publishWhen: () -> Boolean = { true },
    completed: (AssociationEvidenceWriteResult) -> Unit,
): Thread {
  require(evidence.isNotBlank()) { "Association smoke evidence must not be blank" }
  val worker =
      Thread(
          {
            val shouldPublish =
                runCatching(publishWhen).getOrElse { failure ->
                  completed(
                      AssociationEvidenceWriteResult.Failed(
                          if (failure is Exception) failure else IllegalStateException(failure)))
                  return@Thread
                }
            if (!shouldPublish) {
              completed(AssociationEvidenceWriteResult.Ignored)
              return@Thread
            }
            val failure =
                runCatching {
                      val parent =
                          checkNotNull(marker.parent) {
                            "Association smoke marker must have a parent"
                          }
                      requireNonSymlinkDirectoryChain(parent)
                      check(!Files.exists(marker, LinkOption.NOFOLLOW_LINKS)) {
                        "Association smoke marker already exists"
                      }
                      Files.newByteChannel(
                              marker,
                              setOf(
                                  StandardOpenOption.CREATE_NEW,
                                  StandardOpenOption.WRITE,
                                  LinkOption.NOFOLLOW_LINKS,
                              ),
                          )
                          .use { channel ->
                            val bytes = StandardCharsets.UTF_8.encode(evidence)
                            while (bytes.hasRemaining()) {
                              channel.write(bytes)
                            }
                          }
                    }
                    .exceptionOrNull()
                    ?.let {
                      if (it is Exception) it else IllegalStateException(it)
                    }
            completed(
                failure?.let(AssociationEvidenceWriteResult::Failed)
                    ?: AssociationEvidenceWriteResult.Published)
          },
          "coffee-gb-association-smoke-evidence",
      )
  worker.isDaemon = false
  worker.start()
  return worker
}

private fun requireNonSymlinkDirectoryChain(directory: Path) {
  val absolute = directory.toAbsolutePath().normalize()
  val root = checkNotNull(absolute.root) { "Association smoke marker requires an absolute root" }
  check(Files.isDirectory(root, LinkOption.NOFOLLOW_LINKS)) {
    "Association smoke marker root is not a directory"
  }
  var cursor = root
  for (part in root.relativize(absolute)) {
    cursor = cursor.resolve(part)
    check(Files.isDirectory(cursor, LinkOption.NOFOLLOW_LINKS)) {
      "Association smoke marker path has a missing, non-directory, or symbolic-link parent"
    }
  }
}
