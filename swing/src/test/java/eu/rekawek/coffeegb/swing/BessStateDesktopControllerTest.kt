package eu.rekawek.coffeegb.swing

import eu.rekawek.coffeegb.controller.events.register
import eu.rekawek.coffeegb.controller.state.StateUxSessionEvent
import eu.rekawek.coffeegb.controller.state.bess.BessLoadRequestEvent
import eu.rekawek.coffeegb.controller.state.bess.BessSaveRequestEvent
import eu.rekawek.coffeegb.core.events.EventBusImpl
import java.nio.file.Files
import java.nio.file.Path
import java.util.concurrent.FutureTask
import javax.swing.SwingUtilities
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class BessStateDesktopControllerTest {
  @get:Rule val temporary = TemporaryFolder()

  @Test
  fun `load preserves the chosen filename and save adds the BESS extension`() {
    Fixture().use { fixture ->
      val path = temporary.root.toPath().resolve("chosen.state")
      fixture.choose = { path }
      fixture.start(1)
      onBessEdt { fixture.controller.load() }
      onBessEdt { fixture.controller.save() }

      assertEquals(listOf(BessLoadRequestEvent(path = path, expectedSessionId = 1)), fixture.loads)
      assertEquals(
          listOf(BessSaveRequestEvent(path = path.resolveSibling("chosen.state.bess"), expectedSessionId = 1)),
          fixture.saves,
      )
      assertEquals(path.resolveSibling("chosen.BESS"), bessSavePath(path.resolveSibling("chosen.BESS")))
    }
  }

  @Test
  fun `cancelled chooser and session replacement during a chooser do not post requests`() {
    Fixture().use { fixture ->
      fixture.start(1)
      fixture.choose = { null }
      onBessEdt { fixture.controller.load() }
      fixture.choose = {
        fixture.eventBus.post(StateUxSessionEvent(2, true, temporary.root.toPath()))
        temporary.root.toPath().resolve("old-session.bess")
      }
      onBessEdt { fixture.controller.save() }

      assertTrue(fixture.loads.isEmpty())
      assertTrue(fixture.saves.isEmpty())
    }
  }

  @Test
  fun `save confirms the final path after appending the extension and honors cancellation`() {
    Fixture().use { fixture ->
      val existing = temporary.root.toPath().resolve("existing.bess")
      Files.writeString(existing, "keep")
      fixture.start(1)
      fixture.choose = { existing.resolveSibling("existing") }
      val confirmations = mutableListOf<Path>()
      fixture.overwrite = { path ->
        confirmations.add(path)
        false
      }
      onBessEdt { fixture.controller.save() }

      assertEquals(listOf(existing), confirmations)
      assertTrue(fixture.saves.isEmpty())
      assertEquals("keep", Files.readString(existing))

      fixture.overwrite = { true }
      onBessEdt { fixture.controller.save() }
      assertEquals(listOf(BessSaveRequestEvent(path = existing, expectedSessionId = 1)), fixture.saves)
    }
  }

  @Test
  fun `ownership changes inside overwrite confirmation cancel the request`() {
    Fixture().use { fixture ->
      val existing = temporary.newFile("existing.bess").toPath()
      fixture.start(1)
      fixture.choose = { existing }
      fixture.overwrite = {
        fixture.eventBus.post(ControllerOwnershipChangingEvent())
        true
      }
      onBessEdt { fixture.controller.save() }
      assertTrue(fixture.saves.isEmpty())
    }
  }

  private class Fixture : AutoCloseable {
    val eventBus = EventBusImpl()
    val loads = mutableListOf<BessLoadRequestEvent>()
    val saves = mutableListOf<BessSaveRequestEvent>()
    var choose: (Boolean) -> Path? = { null }
    var overwrite: (Path) -> Boolean = { false }
    val controller =
        onBessEdt {
          BessStateDesktopController(
              eventBus,
              isAvailable = { true },
              chooseFile = { choose(it) },
              confirmOverwrite = { overwrite(it) },
              showError = {},
              showStatus = {},
          )
        }

    init {
      eventBus.register<BessLoadRequestEvent> { loads += it }
      eventBus.register<BessSaveRequestEvent> { saves += it }
    }

    fun start(id: Long) {
      onBessEdt { eventBus.post(StateUxSessionEvent(id, true, Path.of("game"))) }
    }

    override fun close() = eventBus.close()
  }
}

private fun <T> onBessEdt(action: () -> T): T {
  val task = FutureTask(action)
  SwingUtilities.invokeAndWait(task)
  return task.get()
}
