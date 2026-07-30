package eu.rekawek.coffeegb.cli

import eu.rekawek.coffeegb.cli.bundle.DiagnosticBundleConsent
import eu.rekawek.coffeegb.cli.bundle.DiagnosticBundleEntry
import eu.rekawek.coffeegb.cli.bundle.DiagnosticBundleEntryKind
import eu.rekawek.coffeegb.cli.bundle.DiagnosticBundleMetadata
import eu.rekawek.coffeegb.cli.bundle.DiagnosticBundleWriter
import eu.rekawek.coffeegb.cli.bundle.DiagnosticRomMetadata
import eu.rekawek.coffeegb.cli.bundle.DiagnosticSensitiveCategory
import eu.rekawek.coffeegb.cli.codec.CanonicalJson
import eu.rekawek.coffeegb.cli.codec.CanonicalJsonWriter
import eu.rekawek.coffeegb.cli.codec.DeterministicPngEncoder
import eu.rekawek.coffeegb.cli.codec.DeterministicWavEncoder
import eu.rekawek.coffeegb.cli.codec.ExclusiveArtifactWriter
import eu.rekawek.coffeegb.controller.headless.HeadlessBatchResult
import eu.rekawek.coffeegb.controller.headless.HeadlessBatchRunner
import eu.rekawek.coffeegb.controller.headless.HeadlessCaptureOptions
import eu.rekawek.coffeegb.controller.headless.HeadlessExecutionLimit
import eu.rekawek.coffeegb.controller.headless.HeadlessInputTransition
import eu.rekawek.coffeegb.controller.headless.HeadlessReplayRequest
import eu.rekawek.coffeegb.controller.headless.HeadlessRunRequest
import eu.rekawek.coffeegb.controller.headless.HeadlessTerminationReason
import eu.rekawek.coffeegb.controller.replay.ReplayCodec
import eu.rekawek.coffeegb.controller.replay.ReplayCompatibility
import eu.rekawek.coffeegb.controller.replay.ReplayCompatibilityException
import eu.rekawek.coffeegb.controller.replay.ReplayFile
import eu.rekawek.coffeegb.controller.replay.ReplayInitialMode
import eu.rekawek.coffeegb.controller.replay.ReplayLimits
import eu.rekawek.coffeegb.controller.replay.ReplayPlaybackException
import eu.rekawek.coffeegb.controller.state.StateDecodeException
import eu.rekawek.coffeegb.core.Gameboy
import eu.rekawek.coffeegb.core.debug.DebugAddressSpace
import eu.rekawek.coffeegb.core.debug.DebugInspectionRequest
import eu.rekawek.coffeegb.core.debug.DebugMemoryRequest
import eu.rekawek.coffeegb.core.debug.breakpoint.DebugBreakpoint
import eu.rekawek.coffeegb.core.debug.breakpoint.DebugBreakpointId
import eu.rekawek.coffeegb.core.debug.breakpoint.DebugCounterCondition
import eu.rekawek.coffeegb.core.debug.breakpoint.DebugOpcodeCondition
import eu.rekawek.coffeegb.core.debug.breakpoint.DebugPcCondition
import eu.rekawek.coffeegb.core.hardware.HardwareProfileRegistry
import eu.rekawek.coffeegb.core.memory.Bios
import eu.rekawek.coffeegb.core.memory.cart.Rom
import eu.rekawek.coffeegb.core.memory.cart.RomImage
import eu.rekawek.coffeegb.core.memory.cart.RomSourceSnapshot
import java.io.ByteArrayOutputStream
import java.io.IOException
import java.nio.charset.StandardCharsets
import java.nio.file.Files
import java.nio.file.LinkOption
import java.nio.file.Path

/** Production factory kept separate from argument and process-stream ownership. */
object HeadlessCliEngineFactory {
  fun create(): CliExecutionEngine = HeadlessCliEngine
}

/** Controller-backed implementation of the strict, non-Swing CLI execution seam. */
internal object HeadlessCliEngine : CliExecutionEngine {

  override fun execute(request: CliExecutionRequest): CliExecutionOutcome =
      try {
        validateOutputContract(request.command)
        validateOutputTargets(request.command.outputs)
        when (val command = request.command) {
          is CliCommandSpec.Run -> executeRun(command, request.inputScript)
          is CliCommandSpec.Replay -> executeReplay(command)
        }
      } catch (failure: CliEngineFailure) {
        failureOutcome(failure.exitCode, failure.code, failure.safeMessage)
      } catch (failure: IllegalArgumentException) {
        failureOutcome(
            CliExitCode.INVALID_ARGUMENTS,
            "invalid-execution-request",
            "Headless execution request is invalid",
        )
      } catch (failure: InterruptedException) {
        Thread.currentThread().interrupt()
        throw failure
      } catch (_: Exception) {
        failureOutcome(
            CliExitCode.EMULATION_FAILURE,
            "emulation-failure",
            "Headless emulation failed",
        )
      }

  private fun executeRun(
      command: CliCommandSpec.Run,
      inputScript: CgbiInputScript?,
  ): CliExecutionOutcome {
    val configuration = configuration(command, replay = null)
    val inputs =
        inputScript?.records.orEmpty().map { record ->
          HeadlessInputTransition(record.tick, record.playerNumber - 1, record.buttons)
        }
    val capture = captureOptions(command.outputs)
    val inspection = inspection(command.memory)
    val limit =
        when (val bound = command.bound) {
          is ExecutionBound.Ticks -> HeadlessExecutionLimit.Ticks(bound.count)
          is ExecutionBound.Frames -> HeadlessExecutionLimit.Frames(bound.count)
        }
    val rtcEpochMillis =
        command.rtcEpochMillis
            ?: eu.rekawek.coffeegb.controller.replay.ReplayRecordingOptions
                .DEFAULT_RTC_EPOCH_MILLIS
    val result =
        try {
          HeadlessBatchRunner.run(
              configuration,
              HeadlessRunRequest(
                  limit = limit,
                  inputs = inputs,
                  rtcEpochMillis = rtcEpochMillis,
                  breakpoint = breakpoint(command.breakpoint),
                  inspection = inspection,
                  capture = capture,
              ),
          )
        } catch (failure: ReplayCompatibilityException) {
          throw CliEngineFailure(
              CliExitCode.EMULATION_FAILURE,
              "unsupported-cartridge",
              "The cartridge requires nondeterministic services unavailable in headless mode",
              failure,
          )
        }
    val (requestedUnit, requestedCount) =
        when (val bound = command.bound) {
          is ExecutionBound.Ticks -> "ticks" to bound.count
          is ExecutionBound.Frames -> "frames" to bound.count
        }
    return publish(
        command,
        configuration,
        result,
        replay = null,
        replayBytes = null,
        HeadlessReportContext(
            command = "run",
            configuration = configuration,
            requestedUnit = requestedUnit,
            requestedCount = requestedCount,
            inputRecords = inputs.size,
            expectedFullHash = command.expectedFullHash,
        ),
    )
  }

  private fun executeReplay(command: CliCommandSpec.Replay): CliExecutionOutcome {
    val replayBytes = readReplay(command.replay)
    val replay = decodeReplay(replayBytes)
    val configuration = configuration(command, replay)
    val result =
        try {
          HeadlessBatchRunner.replay(
              replay,
              configuration,
              HeadlessReplayRequest(
                  maximumTicks = command.maxTicks,
                  breakpoint = breakpoint(command.breakpoint),
                  inspection = inspection(command.memory),
                  capture = captureOptions(command.outputs),
              ),
          )
        } catch (failure: ReplayCompatibilityException) {
          throw incompatible(failure)
        } catch (failure: ReplayPlaybackException) {
          throw incompatible(failure)
        } catch (failure: StateDecodeException) {
          throw incompatible(failure)
        }
    return publish(
        command,
        configuration,
        result,
        replay,
        replayBytes,
        HeadlessReportContext(
            command = "replay",
            configuration = configuration,
            requestedUnit = "maximum-ticks",
            requestedCount = command.maxTicks,
            inputRecords = replay.inputs.size,
        ),
    )
  }

  private fun publish(
      command: CliCommandSpec,
      configuration: Gameboy.GameboyConfiguration,
      result: HeadlessBatchResult,
      replay: ReplayFile?,
      replayBytes: ByteArray?,
      context: HeadlessReportContext,
  ): CliExecutionOutcome {
    val exitCode = exitCode(result, context.expectedFullHash)
    try {
      val media = encodeMedia(result, command.outputs)
      val report =
          HeadlessReportEncoder.encode(
              result,
              context,
              exitCode,
              media,
              includeMemory = true,
          )
      val bundle =
          command.outputs.bundle?.let {
            encodeBundle(
                command,
                configuration,
                result,
                replay,
                replayBytes,
                context,
                exitCode,
                media,
            )
          }

      command.outputs.screenshot?.let { target ->
        ExclusiveArtifactWriter.write(target, requireNotNull(media.png))
      }
      command.outputs.wav?.let { target ->
        ExclusiveArtifactWriter.write(target, requireNotNull(media.wav))
      }
      command.outputs.json?.let { target -> ExclusiveArtifactWriter.write(target, report) }
      command.outputs.bundle?.let { target ->
        ExclusiveArtifactWriter.write(target, requireNotNull(bundle))
      }

      return CliExecutionOutcome(
          exitCode,
          report.toString(StandardCharsets.US_ASCII),
          terminalDiagnostic(exitCode, result.reason),
      )
    } catch (failure: CliEngineFailure) {
      throw failure
    } catch (failure: Exception) {
      throw CliEngineFailure(
          CliExitCode.EMULATION_FAILURE,
          "artifact-failure",
          "Deterministic evidence could not be encoded or published",
          failure,
      )
    }
  }

  private fun encodeMedia(
      result: HeadlessBatchResult,
      outputs: CliArtifactOutputs,
  ): HeadlessEncodedMedia {
    val needPng = outputs.screenshot != null || outputs.includeMediaInBundle
    val needWav = outputs.wav != null || outputs.includeMediaInBundle
    val png =
        if (needPng) {
          val frame =
              result.latestFrame
                  ?: throw CliEngineFailure(
                      CliExitCode.EMULATION_FAILURE,
                      "frame-unavailable",
                      "No complete frame was produced within the execution bound",
                  )
          DeterministicPngEncoder.encodeRgb8(
              frame.image.width,
              frame.image.height,
              frame.image.copyRgb(),
          )
        } else {
          null
        }
    val wav =
        if (needWav) {
          val pcm =
              result.pcm
                  ?: throw CliEngineFailure(
                      CliExitCode.EMULATION_FAILURE,
                      "audio-unavailable",
                      "Audio capture was unavailable for this execution",
                  )
          DeterministicWavEncoder.encodePcm16Stereo(pcm.bytes, pcm.sampleRate)
        } else {
          null
        }
    return HeadlessEncodedMedia(png, wav)
  }

  private fun encodeBundle(
      command: CliCommandSpec,
      configuration: Gameboy.GameboyConfiguration,
      result: HeadlessBatchResult,
      replay: ReplayFile?,
      replayBytes: ByteArray?,
      context: HeadlessReportContext,
      exitCode: CliExitCode,
      media: HeadlessEncodedMedia,
  ): ByteArray {
    val safeReport =
        HeadlessReportEncoder.encode(
            result,
            context,
            exitCode,
            media,
            includeMemory = false,
            redactRomTitle = true,
        )
    val entries =
        mutableListOf(
            DiagnosticBundleEntry(DiagnosticBundleEntryKind.REPORT_JSON, safeReport),
            DiagnosticBundleEntry(
                DiagnosticBundleEntryKind.LOGS_NDJSON,
                bundleLog(result, exitCode),
            ),
        )
    val sensitive = linkedSetOf<DiagnosticSensitiveCategory>()
    if (command.outputs.includeMediaInBundle) {
      entries +=
          DiagnosticBundleEntry(
              DiagnosticBundleEntryKind.SCREENSHOT_PNG,
              requireNotNull(media.png),
          )
      entries +=
          DiagnosticBundleEntry(
              DiagnosticBundleEntryKind.AUDIO_WAV,
              requireNotNull(media.wav),
          )
      sensitive += DiagnosticSensitiveCategory.MEDIA
    }
    if (command.outputs.includeMemoryInBundle) {
      entries +=
          DiagnosticBundleEntry(
              DiagnosticBundleEntryKind.MEMORY_JSON,
              HeadlessReportEncoder.encodeMemory(result),
          )
      sensitive += DiagnosticSensitiveCategory.MEMORY
    }
    if (command.outputs.includeReplayInBundle) {
      val decoded = requireNotNull(replay)
      val bytes = requireNotNull(replayBytes)
      if (decoded.initialConditions.mode == ReplayInitialMode.EMBEDDED_SESSION_STATE) {
        entries += DiagnosticBundleEntry(DiagnosticBundleEntryKind.RAW_REPLAY, bytes)
        sensitive += DiagnosticSensitiveCategory.RAW_REPLAY
      } else {
        entries += DiagnosticBundleEntry(DiagnosticBundleEntryKind.SAFE_REPLAY, bytes)
        sensitive += DiagnosticSensitiveCategory.REPLAY
      }
    }
    val consent =
        DiagnosticBundleConsent(
            requestedSensitive = sensitive,
            confirmedSensitive =
                if (command.outputs.sensitiveBundleConfirmed) sensitive else emptySet(),
        )
    return DiagnosticBundleWriter.encode(
        bundleMetadata(command, configuration, result, context, exitCode),
        entries,
        consent,
    )
  }

  private fun bundleMetadata(
      command: CliCommandSpec,
      configuration: Gameboy.GameboyConfiguration,
      result: HeadlessBatchResult,
      context: HeadlessReportContext,
      exitCode: CliExitCode,
  ): DiagnosticBundleMetadata {
    val rom = result.rom
    return DiagnosticBundleMetadata(
        applicationVersion = implementationVersion(),
        javaVersion = systemProperty("java.version"),
        javaVendor = systemProperty("java.vendor"),
        javaVmName = systemProperty("java.vm.name"),
        osName = systemProperty("os.name"),
        osVersion = systemProperty("os.version"),
        osArchitecture = systemProperty("os.arch"),
        configuration =
            mapOf(
                "batteryPersistence" to "disabled",
                "bootstrap" to enumName(configuration.bootstrapMode),
                "codeBreakerRumble" to configuration.isCodeBreakerRumble.toString(),
                "infrared" to "disabled",
                "inputRecords" to context.inputRecords.toString(),
                "inputScript" to
                    if (command is CliCommandSpec.Run && command.inputScript != null) "present"
                    else "absent",
                "mealybugDmgBlob" to configuration.isMealybugDmgBlob.toString(),
                "profile" to configuration.hardwareProfile.id(),
                "rtcEpochMillis" to result.rtcEpochMillis.toString(),
                "serial" to "disabled",
                "sgbBorder" to configuration.isDisplaySgbBorder.toString(),
            ),
        rom =
            DiagnosticRomMetadata(
                HeadlessReportEncoder.hex(rom.sha256),
                rom.sizeBytes.toLong(),
                mapOf(
                    "title" to rom.title.ifBlank { "<empty>" },
                    "cgbFlag" to rom.cgbFlag.toString(),
                    "sgbFlag" to rom.sgbFlag.toString(),
                    "cartridgeType" to rom.cartridgeType.toString(),
                    "romSizeCode" to rom.romSizeCode.toString(),
                    "ramSizeCode" to rom.ramSizeCode.toString(),
                    "nintendoLogoValid" to rom.nintendoLogoValid.toString(),
                    "headerChecksumValid" to rom.headerChecksumValid.toString(),
                ),
            ),
        execution =
            mapOf(
                "command" to context.command,
                "status" to reportStatus(exitCode),
                "exitCode" to exitCode.processCode.toString(),
                "executedTicks" to result.position.completedTicks.toString(),
                "frame" to result.position.frame.toString(),
                "framePosition" to result.position.ticksIntoFrame.toString(),
                "fullStateHash" to HeadlessReportEncoder.hex(result.hashes.full),
                "mode" to enumName(result.reason),
                "replayStatus" to
                    (result.replayStatus?.javaClass?.simpleName?.lowercase() ?: "absent"),
                when (context.requestedUnit) {
                  "frames" -> "requestedFrames"
                  "maximum-ticks" -> "maximumTicks"
                  else -> "requestedTicks"
                } to context.requestedCount.toString(),
            ),
    )
  }

  private fun bundleLog(
      result: HeadlessBatchResult,
      exitCode: CliExitCode,
  ): ByteArray =
      CanonicalJsonWriter.encode(
          CanonicalJson.obj(
              "code" to CanonicalJson.string("headless-terminal"),
              "exitCode" to CanonicalJson.number(exitCode.processCode),
              "termination" to CanonicalJson.string(enumName(result.reason)),
              "completedTicks" to CanonicalJson.number(result.position.completedTicks),
          ))

  private fun configuration(
      command: CliCommandSpec,
      replay: ReplayFile?,
  ): Gameboy.GameboyConfiguration {
    val primary = loadRom(command.rom, command.romEntry, "primary ROM")
    val slot = command.slotRom?.let { loadRom(it, command.slotRomEntry, "slot ROM") }
    val configuration =
        Gameboy.GameboyConfiguration(primary)
            .setSupportBatterySave(false)
            .setBatteryStorage(null, null)
            .setSlotRom(slot)

    val selectedProfile =
        when (command.profile) {
          HardwareProfileSelection.AUTO -> configuration.hardwareProfile
          HardwareProfileSelection.REPLAY -> {
            val identity = requireNotNull(replay).identity
            try {
              HardwareProfileRegistry.resolve(identity.canonicalProfileId)
            } catch (failure: IllegalArgumentException) {
              throw incompatible(failure)
            }
          }
          else -> HardwareProfileRegistry.resolve(command.profile.cliName)
        }
    configuration.setHardwareProfile(selectedProfile)

    val selectedBootstrap =
        when (command.bootstrap) {
          BootstrapSelection.SKIP -> Gameboy.BootstrapMode.SKIP
          BootstrapSelection.NORMAL -> Gameboy.BootstrapMode.NORMAL
          BootstrapSelection.FAST_FORWARD -> Gameboy.BootstrapMode.FAST_FORWARD
          BootstrapSelection.REPLAY -> bootstrapFromReplay(requireNotNull(replay))
        }
    if (selectedBootstrap != Gameboy.BootstrapMode.SKIP &&
        !Bios.hasBundledBootRom(selectedProfile)) {
      val exit =
          if (command.bootstrap == BootstrapSelection.REPLAY) {
            CliExitCode.INCOMPATIBLE_REPLAY_STATE
          } else {
            CliExitCode.INVALID_ARGUMENTS
          }
      throw CliEngineFailure(
          exit,
          "bootstrap-unavailable",
          "The selected profile has no bundled boot ROM; use skip bootstrap",
      )
    }
    configuration.setBootstrapMode(selectedBootstrap)
    val displayBorder =
        when (command.sgbBorder) {
          SgbBorderSelection.ON -> true
          SgbBorderSelection.OFF -> false
          SgbBorderSelection.REPLAY ->
              requireNotNull(replay).identity.behaviorFlags and
                  ReplayCompatibility.BEHAVIOR_DISPLAY_SGB_BORDER != 0L
        }
    configuration.setDisplaySgbBorder(displayBorder)
    return configuration
  }

  private fun bootstrapFromReplay(replay: ReplayFile): Gameboy.BootstrapMode =
      when (replay.identity.bootstrapFlags) {
        ReplayCompatibility.BOOTSTRAP_NORMAL -> Gameboy.BootstrapMode.NORMAL
        ReplayCompatibility.BOOTSTRAP_FAST_FORWARD -> Gameboy.BootstrapMode.FAST_FORWARD
        ReplayCompatibility.BOOTSTRAP_SKIP -> Gameboy.BootstrapMode.SKIP
        else ->
            throw CliEngineFailure(
                CliExitCode.INCOMPATIBLE_REPLAY_STATE,
                "replay-bootstrap",
                "Replay bootstrap identity is unsupported",
            )
      }

  private fun loadRom(path: Path, entryName: String?, label: String): Rom =
      try {
        RomSourceSnapshot.open(path).use { source ->
          val image: RomImage =
              if (entryName == null) {
                source.loadSingle()
              } else {
                val matches = source.candidates().filter { it.entryName() == entryName }
                if (!source.isArchive || matches.size != 1) {
                  throw IOException("Archive selection is missing or ambiguous")
                }
                source.load(matches.single().token())
              }
          Rom(image)
        }
      } catch (failure: Exception) {
        throw CliEngineFailure(
            CliExitCode.INVALID_ARGUMENTS,
            "rom-invalid",
            "The $label is missing, unreadable, ambiguous, or invalid",
            failure,
        )
      }

  private fun readReplay(path: Path): ByteArray =
      try {
        readBounded(path, ReplayLimits.MAX_FILE_BYTES)
      } catch (failure: Exception) {
        throw CliEngineFailure(
            CliExitCode.INCOMPATIBLE_REPLAY_STATE,
            "replay-invalid",
            "Replay is missing, unreadable, malformed, or unsupported",
            failure,
        )
      }

  private fun decodeReplay(bytes: ByteArray): ReplayFile =
      try {
        ReplayCodec.decode(bytes)
      } catch (failure: Exception) {
        throw incompatible(failure)
      }

  private fun readBounded(path: Path, maximumBytes: Int): ByteArray {
    val declared = Files.size(path)
    if (declared > maximumBytes) throw IOException("Input exceeds byte limit")
    Files.newInputStream(path).use { input ->
      val output = ByteArrayOutputStream(minOf(declared.coerceAtLeast(0L).toInt(), 8192))
      val buffer = ByteArray(8192)
      var total = 0
      while (true) {
        val read = input.read(buffer)
        if (read < 0) break
        if (read == 0) {
          val byte = input.read()
          if (byte < 0) break
          if (total == maximumBytes) throw IOException("Input exceeds byte limit")
          output.write(byte)
          total++
          continue
        }
        if (read > maximumBytes - total) throw IOException("Input exceeds byte limit")
        output.write(buffer, 0, read)
        total += read
      }
      return output.toByteArray()
    }
  }

  private fun inspection(memory: List<CliMemoryRequest>): DebugInspectionRequest =
      DebugInspectionRequest(
          emptyList(),
          memory.map { request ->
            DebugMemoryRequest(
                DebugAddressSpace.valueOf(request.space.name),
                request.start,
                request.length,
            )
          },
      )

  private fun captureOptions(outputs: CliArtifactOutputs): HeadlessCaptureOptions =
      HeadlessCaptureOptions(
          latestFrame = outputs.screenshot != null || outputs.includeMediaInBundle,
          pcm16 = outputs.wav != null || outputs.includeMediaInBundle,
      )

  private fun breakpoint(value: CliBreakpoint?): DebugBreakpoint? {
    val condition =
        when (value) {
          null -> return null
          is CliBreakpoint.ProgramCounter -> DebugPcCondition.at(value.address)
          is CliBreakpoint.Opcode -> DebugOpcodeCondition.base(value.opcode)
          is CliBreakpoint.CbOpcode -> DebugOpcodeCondition.cb(value.opcode)
          is CliBreakpoint.Tick -> DebugCounterCondition.atMasterTick(value.tick)
          is CliBreakpoint.Frame -> DebugCounterCondition.atFrame(value.frame)
        }
    return DebugBreakpoint(DebugBreakpointId(CLI_BREAKPOINT_ID), true, condition)
  }

  private fun validateOutputTargets(outputs: CliArtifactOutputs) {
    val targets =
        listOfNotNull(outputs.screenshot, outputs.wav, outputs.json, outputs.bundle)
            .map { it.toAbsolutePath().normalize() }
    if (targets.toSet().size != targets.size ||
        targets.indices.any { index ->
          targets.drop(index + 1).any { other ->
            targets[index].startsWith(other) || other.startsWith(targets[index])
          }
        }) {
      throw CliEngineFailure(
          CliExitCode.INVALID_ARGUMENTS,
          "output-path-conflict",
          "Output artifact paths are conflicting",
      )
    }
    targets.forEach { path ->
      if (Files.exists(path, LinkOption.NOFOLLOW_LINKS)) {
        throw CliEngineFailure(
            CliExitCode.EMULATION_FAILURE,
            "output-exists",
            "An output artifact already exists and will not be replaced",
        )
      }
    }
  }

  private fun validateOutputContract(command: CliCommandSpec) {
    val outputs = command.outputs
    val includesSensitive =
        outputs.includeReplayInBundle ||
            outputs.includeMemoryInBundle ||
            outputs.includeMediaInBundle
    if (includesSensitive && (outputs.bundle == null || !outputs.sensitiveBundleConfirmed) ||
        !includesSensitive && outputs.sensitiveBundleConfirmed ||
        outputs.includeReplayInBundle && command !is CliCommandSpec.Replay ||
        outputs.includeMemoryInBundle && command.memory.isEmpty()) {
      throw CliEngineFailure(
          CliExitCode.INVALID_ARGUMENTS,
          "bundle-gate",
          "Sensitive diagnostic evidence requires matching include and confirmation gates",
      )
    }
  }

  private fun exitCode(result: HeadlessBatchResult, expectedFullHash: String?): CliExitCode =
      when {
        result.reason == HeadlessTerminationReason.BREAKPOINT ->
            CliExitCode.BREAKPOINT_REACHED
        result.reason == HeadlessTerminationReason.REPLAY_DIVERGED ->
            CliExitCode.DETERMINISTIC_DIVERGENCE
        result.reason == HeadlessTerminationReason.REPLAY_BUDGET_EXHAUSTED ->
            CliExitCode.EMULATION_FAILURE
        expectedFullHash != null &&
            expectedFullHash != HeadlessReportEncoder.hex(result.hashes.full) ->
            CliExitCode.DETERMINISTIC_DIVERGENCE
        else -> CliExitCode.SUCCESS
      }

  private fun terminalDiagnostic(
      exitCode: CliExitCode,
      reason: HeadlessTerminationReason,
  ): CliDiagnostic? =
      when (exitCode) {
        CliExitCode.BREAKPOINT_REACHED ->
            CliDiagnostic("breakpoint-reached", "Breakpoint reached at an emulation safe point")
        CliExitCode.DETERMINISTIC_DIVERGENCE ->
            CliDiagnostic("deterministic-divergence", "Deterministic state diverged")
        CliExitCode.EMULATION_FAILURE ->
            if (reason == HeadlessTerminationReason.REPLAY_BUDGET_EXHAUSTED) {
              CliDiagnostic(
                  "replay-budget-exhausted",
                  "Replay did not terminate within its execution budget",
              )
            } else {
              CliDiagnostic("emulation-failure", "Headless emulation failed")
            }
        else -> null
      }

  private fun failureOutcome(
      exitCode: CliExitCode,
      code: String,
      message: String,
  ): CliExecutionOutcome {
    val report =
        CanonicalJsonWriter.encodeToString(
            CanonicalJson.obj(
                "schema" to CanonicalJson.string("coffee-gb/headless-report"),
                "version" to CanonicalJson.number(1),
                "status" to CanonicalJson.string("error"),
                "exitCode" to CanonicalJson.number(exitCode.processCode),
                "error" to CanonicalJson.obj("code" to CanonicalJson.string(code)),
            ))
    return CliExecutionOutcome(exitCode, report, CliDiagnostic(code, message))
  }

  private fun incompatible(cause: Throwable): CliEngineFailure =
      CliEngineFailure(
          CliExitCode.INCOMPATIBLE_REPLAY_STATE,
          "replay-incompatible",
          "Replay or embedded state is malformed, unsupported, or incompatible",
          cause,
      )

  private fun implementationVersion(): String =
      HeadlessCliEngine::class.java.`package`.implementationVersion?.takeIf { it.isNotBlank() }
          ?: "development"

  private fun systemProperty(name: String): String =
      System.getProperty(name)?.takeIf { it.isNotBlank() } ?: "unknown"

  private fun reportStatus(exitCode: CliExitCode): String =
      when (exitCode) {
        CliExitCode.SUCCESS -> "completed"
        CliExitCode.BREAKPOINT_REACHED -> "breakpoint"
        CliExitCode.DETERMINISTIC_DIVERGENCE -> "diverged"
        else -> "error"
      }

  private fun enumName(value: Enum<*>): String =
      value.name.lowercase().replace('_', '-')

  private const val CLI_BREAKPOINT_ID = 1L
}

private class CliEngineFailure(
    val exitCode: CliExitCode,
    val code: String,
    val safeMessage: String,
    cause: Throwable? = null,
) : Exception(safeMessage, cause)
