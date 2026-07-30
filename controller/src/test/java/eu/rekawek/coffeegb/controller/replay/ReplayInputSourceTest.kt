package eu.rekawek.coffeegb.controller.replay

import eu.rekawek.coffeegb.core.joypad.Button
import eu.rekawek.coffeegb.core.joypad.JoypadButtonMask
import eu.rekawek.coffeegb.core.joypad.PlayerInputSnapshot
import org.junit.Assert.assertEquals
import org.junit.Assert.assertSame
import org.junit.Test

class ReplayInputSourceTest {

  @Test
  fun initializesAndResetsAllFourPhysicalPlayerMasks() {
    val initial =
        PlayerInputSnapshot.of(
            listOf(
                setOf(Button.A),
                setOf(Button.LEFT, Button.START),
                emptySet(),
                setOf(Button.B),
            ),
        )
    val source = ReplayInputSource(initial)

    assertSame(initial, source.sample())
    assertEquals(JoypadButtonMask.A, source.mask(0))
    assertEquals(JoypadButtonMask.LEFT or JoypadButtonMask.START, source.mask(1))
    assertEquals(0, source.mask(2))
    assertEquals(JoypadButtonMask.B, source.mask(3))

    val unchanged = source.sample()
    source.apply(1, JoypadButtonMask.LEFT or JoypadButtonMask.START)
    assertSame(unchanged, source.sample())

    val reset =
        PlayerInputSnapshot.of(
            listOf(
                emptySet(),
                setOf(Button.UP),
                setOf(Button.SELECT),
                emptySet(),
            ),
        )
    source.reset(reset)

    assertSame(reset, source.sample())
    assertEquals(0, source.mask(0))
    assertEquals(JoypadButtonMask.UP, source.mask(1))
    assertEquals(JoypadButtonMask.SELECT, source.mask(2))
    assertEquals(0, source.mask(3))
  }

  @Test
  fun applyingOnePlayerRetainsEveryOtherInitializedMask() {
    val source =
        ReplayInputSource(
            PlayerInputSnapshot.of(
                listOf(
                    setOf(Button.A),
                    setOf(Button.B),
                    setOf(Button.LEFT),
                    setOf(Button.RIGHT),
                ),
            ),
        )

    source.apply(2, JoypadButtonMask.UP or JoypadButtonMask.DOWN)

    assertEquals(setOf(Button.A), source.sample().buttons(0))
    assertEquals(setOf(Button.B), source.sample().buttons(1))
    assertEquals(setOf(Button.UP, Button.DOWN), source.sample().buttons(2))
    assertEquals(setOf(Button.RIGHT), source.sample().buttons(3))
  }
}
