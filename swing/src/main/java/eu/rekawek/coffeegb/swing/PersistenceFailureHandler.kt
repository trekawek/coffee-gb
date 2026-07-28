package eu.rekawek.coffeegb.swing

import eu.rekawek.coffeegb.controller.Controller
import eu.rekawek.coffeegb.controller.events.register
import eu.rekawek.coffeegb.core.events.EventBus
import eu.rekawek.coffeegb.core.memory.cart.battery.BatteryPersistenceFailedEvent
import java.util.concurrent.atomic.AtomicBoolean

/** Keeps persistence error routing testable without constructing a Swing window. */
internal class PersistenceFailureHandler(
    eventBus: EventBus,
    showError: (title: String, message: String) -> Unit,
    requestRetryOrCancel:
        ((title: String, message: String, decide: (retry: Boolean) -> Unit) -> Unit)? = null,
) {
  init {
    eventBus.register<Controller.SnapshotSaveFailedEvent> {
      showError("Unable to save state", it.message)
    }
    eventBus.register<BatteryPersistenceFailedEvent> {
      val action =
          when (it.operation) {
            BatteryPersistenceFailedEvent.Operation.LOAD -> "load"
            BatteryPersistenceFailedEvent.Operation.SAVE -> "save"
      }
      showError("Battery $action error", it.message)
    }
    eventBus.register<Controller.RomReplacementPersistenceFailedEvent> {
      val title =
          when (it.operation) {
            Controller.PersistenceBarrierOperation.ROM_REPLACEMENT ->
                "Unable to switch games"
            Controller.PersistenceBarrierOperation.STOP -> "Unable to stop game"
            Controller.PersistenceBarrierOperation.CLOSE -> "Unable to close game"
      }
      if (it.operation == Controller.PersistenceBarrierOperation.CLOSE) {
        // closeWithState() also throws a typed exception and retains the immutable capture so its
        // caller can retry. The controller timing thread has already stopped, so queue commands
        // are deliberately not used for this synchronous lifecycle path.
        showError(title, it.message)
      } else {
        val decided = AtomicBoolean()
        val decide: (Boolean) -> Unit = { retry ->
          if (decided.compareAndSet(false, true)) {
            eventBus.post(
                if (retry) {
                  Controller.RetryRomReplacementEvent(it.requestId)
                } else {
                  Controller.CancelRomReplacementEvent(it.requestId)
                })
          }
        }
        if (requestRetryOrCancel == null) {
          showError(title, it.message)
          decide(false)
        } else {
          requestRetryOrCancel(title, it.message, decide)
        }
      }
    }
  }
}
