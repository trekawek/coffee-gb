package eu.rekawek.coffeegb.swing

import eu.rekawek.coffeegb.controller.Controller.TurboFileAction
import javax.swing.SwingUtilities
import kotlin.test.assertEquals
import org.junit.Test

class TurboFileMenuTest {
  @Test fun menuExposesBothMemoriesAndPhysicalSwitches() {
    SwingUtilities.invokeAndWait {
      val events = mutableListOf<TurboFileAction>()
      val menu = turboFileMenu(events::add)
      for (i in 0 until menu.itemCount) menu.getItem(i)?.doClick()
      assertEquals(TurboFileAction.entries.toSet(), events.toSet())
    }
  }
}
