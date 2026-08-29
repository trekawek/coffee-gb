package eu.rekawek.coffeegb.controller.events

import eu.rekawek.coffeegb.core.events.EventBusImpl
import eu.rekawek.coffeegb.core.gpu.Display
import eu.rekawek.coffeegb.core.joypad.Button
import eu.rekawek.coffeegb.core.joypad.Joypad
import eu.rekawek.coffeegb.core.rumble.RumbleEvent
import eu.rekawek.coffeegb.core.sgb.SgbDisplay
import eu.rekawek.coffeegb.core.sound.Sound
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import org.junit.Test

class EventBusExtTest {

  @Test
  fun selectivelySuppressesForwardingAndReopensAfterFailure() {
    val inbound = EventBusImpl(null, null, false)
    val outbound = EventBusImpl(null, null, false)
    val funnel =
        owningFunnel(
            inbound,
            outbound,
            setOf(
                Display.DmgFrameReadyEvent::class,
                Display.GbcFrameReadyEvent::class,
                SgbDisplay.SgbFrameReadyEvent::class,
                Sound.SoundSampleEvent::class,
                RumbleEvent::class,
                Joypad.JoypadPressEvent::class,
            ),
        )
    val received = mutableListOf<String>()
    outbound.register<Display.DmgFrameReadyEvent> { received += "display" }
    outbound.register<Sound.SoundSampleEvent> { received += "sound" }
    outbound.register<RumbleEvent> { received += "rumble" }
    outbound.register<Joypad.JoypadPressEvent> { received += "joypad" }

    try {
      inbound.post(Display.DmgFrameReadyEvent(intArrayOf(1)))
      assertFailsWith<ExpectedFailure> {
        funnel.withPresentationSuppressed {
          inbound.post(Display.DmgFrameReadyEvent(intArrayOf(2)))
          inbound.post(Sound.SoundSampleEvent(intArrayOf(2)))
          inbound.post(RumbleEvent(true))
          inbound.post(Joypad.JoypadPressEvent(Button.A, 2))
          throw ExpectedFailure()
        }
      }
      inbound.post(Display.DmgFrameReadyEvent(intArrayOf(3)))
      inbound.post(Sound.SoundSampleEvent(intArrayOf(3)))
      inbound.post(RumbleEvent(false))

      assertEquals(
          listOf("display", "joypad", "display", "sound", "rumble"),
          received,
      )
    } finally {
      funnel.close()
    }
  }

  private class ExpectedFailure : RuntimeException()
}
