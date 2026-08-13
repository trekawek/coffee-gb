package eu.rekawek.coffeegb.swing

import javax.swing.SwingUtilities

/**
 * Small EDT-owned fold for shell state. Emulator/controller state remains authoritative; feature
 * coordinators publish only the summaries this shell needs.
 */
internal class DesktopUiCoordinator(
    initial: DesktopPresentation,
    private val render: (DesktopPresentation) -> Unit,
    private val edtCheck: () -> Boolean = SwingUtilities::isEventDispatchThread,
) {
  private var stagedPauseSupport = initial.commands.pauseSupported
  private var current = normalize(initial)

  fun current(): DesktopPresentation = current

  fun publish() {
    requireEdt()
    render(current)
  }

  fun update(transform: (DesktopPresentation) -> DesktopPresentation) {
    requireEdt()
    val next = normalize(transform(current))
    if (next == current) return
    current = next
    render(next)
  }

  fun opening(target: String, cancellable: Boolean) {
    require(target.isNotBlank())
    sessionTask("Opening $target…", cancellable)
  }

  fun sessionTask(message: String, cancellable: Boolean) {
    require(message.isNotBlank())
    update {
      it.copy(
          task = DesktopSessionTask(message, cancellable),
          commands = it.commands.copy(sessionBusy = true),
          persistentStatus = message.removeSuffix("…"),
          statusRecoveryCommand = null,
      )
    }
  }

  fun opened(gameTitle: String, sessionGeneration: Long? = null) {
    require(gameTitle.isNotBlank())
    update {
      it.copy(
          gameTitle = gameTitle,
          // Capability metadata arrives as a separate controller event.  Never carry it from a
          // replaced ROM while that event is still in flight.
          batterySaveActive = false,
          sessionGeneration = sessionGeneration ?: it.sessionGeneration,
          task = null,
          commands =
              it.commands.copy(
                  gameLoaded = true,
                  sessionBusy = false,
                  pauseSupported = stagedPauseSupport,
                  paused = false,
                  loadableStateSlots = emptySet(),
              ),
          persistentStatus = "$gameTitle is running",
          presentedFramesPerSecond = null,
          statusRecoveryCommand = null,
      )
    }
  }

  /** Applies mapper-derived menu metadata without consulting a save file or filesystem state. */
  fun sessionMetadata(batterySaveActive: Boolean, sessionGeneration: Long?) {
    // Metadata is emitted immediately after EmulationStarted, but it can still be queued behind
    // a later ROM replacement on the Swing EDT. It belongs only to the active generation.
    if (sessionGeneration != current.sessionGeneration) return
    update {
      it.copy(
        batterySaveActive = batterySaveActive,
      )
    }
  }

  fun openingFinished(message: String? = null) {
    update {
      it.copy(
          task = null,
          commands = it.commands.copy(sessionBusy = false),
          persistentStatus =
              message ?: it.gameTitle?.let { title -> "$title is running" } ?: "Ready",
          statusRecoveryCommand = null,
      )
    }
  }

  fun stopped() {
    stagedPauseSupport = false
    update {
      it.copy(
          gameTitle = null,
          batterySaveActive = false,
          sessionGeneration = null,
          playTimeNanos = 0,
          task = null,
          commands =
              it.commands.copy(
                  gameLoaded = false,
                  sessionBusy = false,
                  pauseSupported = false,
                  paused = false,
                  stateCommandsAvailable = false,
                  stateBrowserAvailable = false,
                  loadableStateSlots = emptySet(),
                  fullscreen = false,
              ),
          persistentStatus = "Ready",
          presentedFramesPerSecond = null,
          statusRecoveryCommand = null,
      )
    }
  }

  fun savingBeforeQuit(message: String) {
    update {
      it.copy(
          task = DesktopSessionTask(message, cancellable = false),
          commands = it.commands.copy(sessionBusy = true),
          persistentStatus = message,
          statusRecoveryCommand = null,
      )
    }
  }

  fun pauseSupport(enabled: Boolean) {
    stagedPauseSupport = enabled
    update { it.copy(commands = it.commands.copy(pauseSupported = enabled)) }
  }

  fun paused(paused: Boolean) =
      update {
        it.copy(
            commands = it.commands.copy(paused = paused),
            persistentStatus =
                if (paused) "Paused"
                else it.gameTitle?.let { title -> "$title is running" } ?: "Ready",
            presentedFramesPerSecond = if (paused) null else it.presentedFramesPerSecond,
            statusRecoveryCommand = null,
        )
      }

  fun stateAvailability(quick: Boolean, browser: Boolean) =
      update {
        it.copy(
            commands =
                it.commands.copy(
                    stateCommandsAvailable = quick,
                    stateBrowserAvailable = browser,
                    loadableStateSlots =
                        if (quick) it.commands.loadableStateSlots else emptySet(),
                ))
      }

  fun stateSlotLoadAvailability(slot: Int, available: Boolean) {
    require(slot in 0..9)
    update {
      val slots =
          if (available) it.commands.loadableStateSlots + slot
          else it.commands.loadableStateSlots - slot
      it.copy(commands = it.commands.copy(loadableStateSlots = slots))
    }
  }

  fun muted(muted: Boolean) = update { it.copy(commands = it.commands.copy(muted = muted)) }

  fun fullscreen(fullscreen: Boolean) =
      update { it.copy(commands = it.commands.copy(fullscreen = fullscreen)) }

  fun displaySettings(display: eu.rekawek.coffeegb.controller.properties.ApplicationSettings.Display) =
      update {
        it.copy(
            commands =
                it.commands.copy(
                    fullscreen = display.fullscreen,
                    exactWindowScaleOne =
                        display.scalingMode ==
                            eu.rekawek.coffeegb.controller.properties.ApplicationSettings
                                .DisplayScalingMode.EXPLICIT && display.explicitScale == 1,
                ))
      }

  fun commandBarVisible(visible: Boolean) =
      update { it.copy(commands = it.commands.copy(commandBarVisible = visible)) }

  fun stateSlot(slot: Int) {
    require(slot in 0..9)
    update { it.copy(commands = it.commands.copy(stateSlot = slot)) }
  }

  fun netplaySummary(summary: String) {
    require(summary.isNotBlank())
    update { it.copy(netplaySummary = summary) }
  }

  fun presentedFramesPerSecond(value: Double?) {
    require(value == null || (value.isFinite() && value >= 0))
    update { it.copy(presentedFramesPerSecond = value) }
  }

  fun warning(message: String, recoveryCommand: DesktopCommand? = null) {
    require(message.isNotBlank())
    update {
      it.copy(
          notice = DesktopNotice(message, recoveryCommand),
      )
    }
  }

  fun clearNotice() = update { it.copy(notice = null, statusRecoveryCommand = null) }

  private fun normalize(value: DesktopPresentation): DesktopPresentation {
    val loaded = value.gameTitle != null
    return value.copy(
        batterySaveActive = value.batterySaveActive && loaded,
        sessionGeneration = value.sessionGeneration.takeIf { loaded },
        playTimeNanos = value.playTimeNanos.takeIf { loaded } ?: 0,
        commands =
            value.commands.copy(
                gameLoaded = loaded,
                paused = value.commands.paused && loaded,
                pauseSupported = value.commands.pauseSupported && loaded,
                stateCommandsAvailable = value.commands.stateCommandsAvailable && loaded,
                stateBrowserAvailable = value.commands.stateBrowserAvailable && loaded,
                loadableStateSlots =
                    value.commands.loadableStateSlots.takeIf {
                      loaded && value.commands.stateCommandsAvailable
                    } ?: emptySet(),
            ))
  }

  private fun requireEdt() {
    check(edtCheck()) { "Desktop presentation must be updated on the Event Dispatch Thread" }
  }
}
