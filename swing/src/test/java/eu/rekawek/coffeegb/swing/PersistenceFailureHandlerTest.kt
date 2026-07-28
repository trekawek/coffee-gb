package eu.rekawek.coffeegb.swing

import eu.rekawek.coffeegb.controller.Controller
import eu.rekawek.coffeegb.controller.events.register
import eu.rekawek.coffeegb.core.events.EventBusImpl
import eu.rekawek.coffeegb.core.memory.cart.battery.BatteryPersistenceFailedEvent
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import org.junit.Test

class PersistenceFailureHandlerTest {

  @Test
  fun snapshotAndBatteryFailuresUseTheErrorDialogRoute() {
    val eventBus = EventBusImpl()
    val dialogs = mutableListOf<Pair<String, String>>()
    PersistenceFailureHandler(
        eventBus,
        showError = { title, message -> dialogs += title to message },
    )

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

  @Test
  fun replacementAndStopFailuresRouteRetryAndCancelCommands() {
    val eventBus = EventBusImpl()
    val commands = mutableListOf<Any>()
    val decisions = ArrayDeque<(Boolean) -> Unit>()
    eventBus.register<Controller.RetryRomReplacementEvent> { commands += it }
    eventBus.register<Controller.CancelRomReplacementEvent> { commands += it }
    PersistenceFailureHandler(
        eventBus,
        showError = { _, _ -> error("interactive barriers must use the decision route") },
        requestRetryOrCancel = { _, _, decide -> decisions += decide },
    )

    eventBus.post(
        Controller.RomReplacementPersistenceFailedEvent(
            17,
            "old.sav",
            "replacement failed",
        ))
    eventBus.post(
        Controller.RomReplacementPersistenceFailedEvent(
            18,
            "old.sav",
            "stop failed",
            Controller.PersistenceBarrierOperation.STOP,
        ))

    assertTrue(commands.isEmpty(), "controller event posting must not wait for a modal decision")
    assertEquals(2, decisions.size)
    val replacementDecision = decisions.removeFirst()
    replacementDecision(true)
    // A stale/double callback from UI teardown must not emit a second command.
    replacementDecision(false)
    assertEquals(1, commands.size)
    decisions.removeFirst()(false)

    assertEquals(17, (commands[0] as Controller.RetryRomReplacementEvent).requestId)
    assertEquals(18, (commands[1] as Controller.CancelRomReplacementEvent).requestId)
    assertTrue(decisions.isEmpty())
    eventBus.close()
  }
}
