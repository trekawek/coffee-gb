package eu.rekawek.coffeegb.swing

import eu.rekawek.coffeegb.controller.Controller
import eu.rekawek.coffeegb.controller.events.register
import eu.rekawek.coffeegb.core.events.EventBusImpl
import javax.swing.SwingUtilities
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import org.junit.Test

class BardigunMenuTest {
  @Test
  fun bardigunHasItsOwnPortSelectionAndScanPrerequisite() {
    EventBusImpl(null, null, false).use { bus ->
      val selected = mutableListOf<Controller.SerialPeripheralSelection>()
      bus.register<Controller.SetSerialPeripheralEvent> { selected += it.selection }
      SwingUtilities.invokeAndWait {
        val binding = SerialPeripheralMenuBinding(bus)
        binding.items.getValue(Controller.SerialPeripheralSelection.BARDIGUN).doClick()
        assertEquals(listOf(Controller.SerialPeripheralSelection.BARDIGUN), selected)
        assertEquals("Barcode Boy", binding.items.getValue(Controller.SerialPeripheralSelection.BARCODE_BOY).text)
        val form = BarcodeBoyForm(false, { true }, "4902370501445", "Bardigun Reader")
        assertFalse(form.submissionValidation.valid)
        assertEquals("Select Bardigun Reader", form.selectDeviceButton.text)
        form.selectDeviceButton.doClick()
        assertTrue(form.submissionValidation.valid)
      }
    }
  }
}
