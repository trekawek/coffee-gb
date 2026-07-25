package eu.rekawek.coffeegb.swing

import eu.rekawek.coffeegb.controller.Controller
import eu.rekawek.coffeegb.controller.events.register
import eu.rekawek.coffeegb.core.events.EventBus
import eu.rekawek.coffeegb.core.memory.cart.battery.BatteryPersistenceFailedEvent

/** Keeps persistence error routing testable without constructing a Swing window. */
internal class PersistenceFailureHandler(
    eventBus: EventBus,
    showError: (title: String, message: String) -> Unit,
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
  }
}
