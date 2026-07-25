package eu.rekawek.coffeegb.swing

import eu.rekawek.coffeegb.controller.Controller
import eu.rekawek.coffeegb.core.events.EventBusImpl
import eu.rekawek.coffeegb.core.memory.cart.battery.BatteryPersistenceFailedEvent
import kotlin.test.assertEquals
import org.junit.Test

class PersistenceFailureHandlerTest {

  @Test
  fun snapshotAndBatteryFailuresUseTheErrorDialogRoute() {
    val eventBus = EventBusImpl()
    val dialogs = mutableListOf<Pair<String, String>>()
    PersistenceFailureHandler(eventBus) { title, message -> dialogs += title to message }

    eventBus.post(Controller.SnapshotSaveFailedEvent(3, "snapshot detail"))
    eventBus.post(
        BatteryPersistenceFailedEvent(
            BatteryPersistenceFailedEvent.Operation.SAVE,
            "game.sav",
            "battery detail",
        ))
    eventBus.post(
        BatteryPersistenceFailedEvent(
            BatteryPersistenceFailedEvent.Operation.LOAD,
            "game.sav",
            "battery load detail",
        ))

    assertEquals(
        listOf(
            "Unable to save state" to "snapshot detail",
            "Battery save error" to "battery detail",
            "Battery load error" to "battery load detail",
        ),
        dialogs,
    )
    eventBus.close()
  }
}
