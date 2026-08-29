package eu.rekawek.coffeegb.controller

import eu.rekawek.coffeegb.controller.events.owningFunnel
import eu.rekawek.coffeegb.controller.events.register
import eu.rekawek.coffeegb.controller.events.withPresentationSuppressed
import eu.rekawek.coffeegb.core.events.EventBusImpl
import eu.rekawek.coffeegb.core.gpu.Display
import eu.rekawek.coffeegb.core.joypad.Button
import eu.rekawek.coffeegb.core.joypad.Joypad
import eu.rekawek.coffeegb.core.rumble.RumbleEvent
import eu.rekawek.coffeegb.core.sgb.SgbDisplay
import eu.rekawek.coffeegb.core.sound.Sound
import kotlin.test.assertEquals
import org.junit.Test

class StagedPresentationSuppressionTest {

  @Test
  fun activatedStagedSessionPropagatesPresentationSuppressionToItsOwningFunnel() {
    val inbound = EventBusImpl(null, null, false)
    val outbound = EventBusImpl(null, null, false)
    val staged =
        StagedEventBus(
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
            ),
        )
    val received = mutableListOf<String>()
    outbound.register<Display.DmgFrameReadyEvent> { received += "display" }
    outbound.register<Sound.SoundSampleEvent> { received += "sound" }
    outbound.register<RumbleEvent> { received += "rumble" }
    outbound.register<Joypad.JoypadPressEvent> { received += "joypad" }

    try {
      staged.activate()
      staged.withPresentationSuppressed {
        staged.post(Display.DmgFrameReadyEvent(intArrayOf(1)))
        staged.post(Sound.SoundSampleEvent(intArrayOf(1)))
        staged.post(RumbleEvent(true))
        staged.post(Joypad.JoypadPressEvent(Button.A, 1))
      }
      staged.post(Display.DmgFrameReadyEvent(intArrayOf(2)))

      assertEquals(listOf("joypad", "display"), received)
    } finally {
      staged.close()
    }
  }
}
