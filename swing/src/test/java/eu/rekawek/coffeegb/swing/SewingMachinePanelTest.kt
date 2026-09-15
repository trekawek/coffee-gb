package eu.rekawek.coffeegb.swing

import eu.rekawek.coffeegb.controller.Controller.SewingAction
import eu.rekawek.coffeegb.controller.Controller.SewingSnapshot
import org.junit.Test
import javax.swing.SwingUtilities
import kotlin.test.*

class SewingMachinePanelTest {
  @Test fun authoritativeSnapshotUpdatesControlsWithoutGeneratingCommands() {
    SwingUtilities.invokeAndWait {
      val panel = SewingMachinePanel()
      val commands = mutableListOf<Pair<SewingAction, Int>>()
      panel.command = { action, value -> commands += action to value }
      panel.render(SewingSnapshot(2, true, true, false, false, 0x123456, 90, 3, false,
          IntArray(512 * 512) { 0xffabcdef.toInt() }))
      assertTrue(commands.isEmpty()); assertTrue(panel.arm.isEnabled); assertTrue(panel.hoop.isEnabled)
      assertEquals(0xffabcdef.toInt(), panel.copyImage().getRGB(0, 0))
      panel.pedal.doClick(); assertEquals(SewingAction.PEDAL to 1, commands.single())
      panel.model.selectedIndex = 0; assertEquals(SewingAction.MODEL to 0, commands.last())
    }
  }
}
