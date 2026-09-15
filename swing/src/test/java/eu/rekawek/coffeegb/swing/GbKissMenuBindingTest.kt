package eu.rekawek.coffeegb.swing

import eu.rekawek.coffeegb.controller.GbKissCancelRequest
import eu.rekawek.coffeegb.controller.GbKissProgressEvent
import eu.rekawek.coffeegb.controller.GbKissTransferRequest
import eu.rekawek.coffeegb.controller.events.register
import eu.rekawek.coffeegb.core.events.EventBusImpl
import eu.rekawek.coffeegb.core.ir.GbKissLink
import javax.swing.JPanel
import javax.swing.SwingUtilities
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import org.junit.Test

class GbKissMenuBindingTest {
  @Test
  fun receiveAndCancelAreCorrelatedAndStaleStatusIsIgnored() {
    EventBusImpl(null, null, false).use { bus ->
      val requests = mutableListOf<GbKissTransferRequest>()
      val cancellations = mutableListOf<GbKissCancelRequest>()
      bus.register<GbKissTransferRequest>(requests::add)
      bus.register<GbKissCancelRequest>(cancellations::add)
      lateinit var binding: GbKissMenuBinding
      SwingUtilities.invokeAndWait {
        binding = GbKissMenuBinding(JPanel(), bus) {}
        assertEquals("GBKiss Link", binding.menu.text)
        assertEquals("Send GBF file", binding.menu.getItem(0).text)
        assertEquals("Receive GBF file", binding.menu.getItem(1).text)
        binding.menu.getItem(1).doClick()
        assertFalse(binding.menu.getItem(0).isEnabled)
        assertTrue(binding.menu.getItem(2).isEnabled)
        binding.menu.getItem(2).doClick()
      }
      assertEquals(requests.single().requestId, cancellations.single().requestId)
      val done = GbKissLink.Progress(GbKissLink.Status.CANCELLED, 0, 0, "Cancelled")
      bus.post(GbKissProgressEvent(requests.single().requestId + 1, done))
      SwingUtilities.invokeAndWait { assertFalse(binding.menu.getItem(0).isEnabled) }
      bus.post(GbKissProgressEvent(requests.single().requestId, done))
      SwingUtilities.invokeAndWait {
        assertTrue(binding.menu.getItem(0).isEnabled)
        assertFalse(binding.menu.getItem(2).isEnabled)
      }
    }
  }
}
