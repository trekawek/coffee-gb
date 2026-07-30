package eu.rekawek.coffeegb.swing

import eu.rekawek.coffeegb.controller.Controller
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import org.junit.Test

class MobileAdapterStateBoundaryPresentationTest {

  @Test
  fun `save explains observational capture while load and rewind explain disconnection`() {
    val save =
        mobileAdapterStateBoundaryMessage(
            Controller.MobileAdapterStateBoundaryEvent(
                Controller.MobileAdapterStateBoundary.SAVE,
                Controller.MobileAdapterStateBoundaryImpact.SAVED_WITH_NON_RESTORABLE_IO,
            ))
    val load =
        mobileAdapterStateBoundaryMessage(
            Controller.MobileAdapterStateBoundaryEvent(
                Controller.MobileAdapterStateBoundary.LOAD,
                Controller.MobileAdapterStateBoundaryImpact.DISCONNECTED_NOT_RESTORED,
            ))
    val rewind =
        mobileAdapterStateBoundaryMessage(
            Controller.MobileAdapterStateBoundaryEvent(
                Controller.MobileAdapterStateBoundary.REWIND,
                Controller.MobileAdapterStateBoundaryImpact.DISCONNECTED_NOT_RESTORED,
            ))

    assertTrue(save.contains("connection remains active"))
    assertTrue(save.contains("restore the Mobile Adapter disconnected"))
    assertTrue(load.contains("disconnected while loading"))
    assertTrue(rewind.contains("disconnected while rewinding"))
    assertFalse((save + load + rewind).contains("http"))
    assertFalse((save + load + rewind).contains('/'))
  }
}
