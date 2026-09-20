package eu.rekawek.coffeegb.swing

import eu.rekawek.coffeegb.controller.Controller.EmulationStoppedEvent
import eu.rekawek.coffeegb.controller.events.register
import eu.rekawek.coffeegb.controller.state.StateUxSessionEvent
import eu.rekawek.coffeegb.controller.state.bess.BessLoadRequestEvent
import eu.rekawek.coffeegb.controller.state.bess.BessOperationCompletedEvent
import eu.rekawek.coffeegb.controller.state.bess.BessOperationFailedEvent
import eu.rekawek.coffeegb.controller.state.bess.BessSaveRequestEvent
import eu.rekawek.coffeegb.core.events.EventBus
import java.nio.file.Files
import java.nio.file.Path

/** Rechecks session ownership after each modal dialog before sending a BESS request. */
internal class BessStateDesktopController(
    private val eventBus: EventBus,
    private val isAvailable: () -> Boolean,
    private val chooseFile: (load: Boolean) -> Path?,
    private val confirmOverwrite: (Path) -> Boolean,
    private val showError: (String) -> Unit,
    private val showStatus: (String) -> Unit,
) {
  private var sessionId: Long? = null

  init {
    eventBus.register<StateUxSessionEvent> { event ->
      dispatchSwingMutation {
        sessionId = event.sessionId.takeIf { event.available || event.unavailableReason != null }
      }
    }
    eventBus.register<EmulationStoppedEvent> {
      dispatchSwingMutation { sessionId = null }
    }
    eventBus.register<ControllerOwnershipChangingEvent> {
      dispatchSwingMutation { sessionId = null }
    }
    eventBus.register<BessOperationCompletedEvent> { event ->
      dispatchSwingMutation {
        showStatus("BESS state ${if (event.load) "loaded from" else "saved as"} ${event.path.fileName}.")
      }
    }
    eventBus.register<BessOperationFailedEvent> { event ->
      dispatchSwingMutation { showError(event.message) }
    }
  }

  fun load() = request(load = true)

  fun save() = request(load = false)

  private fun request(load: Boolean) {
    val expectedSessionId = sessionId ?: return
    if (!isAvailable()) return
    val selected = chooseFile(load) ?: return
    if (!isCurrent(expectedSessionId)) return
    val path = if (load) selected else bessSavePath(selected)
    if (!load && Files.exists(path) && !confirmOverwrite(path)) return
    if (!isCurrent(expectedSessionId)) return
    if (load) {
      eventBus.post(BessLoadRequestEvent(path = path, expectedSessionId = expectedSessionId))
    } else {
      eventBus.post(BessSaveRequestEvent(path = path, expectedSessionId = expectedSessionId))
    }
  }

  private fun isCurrent(expectedSessionId: Long): Boolean =
      sessionId == expectedSessionId && isAvailable()
}

internal fun bessSavePath(path: Path): Path =
    if (path.fileName.toString().endsWith(".bess", ignoreCase = true)) path
    else path.resolveSibling(path.fileName.toString() + ".bess")
