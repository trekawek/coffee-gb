package eu.rekawek.coffeegb.swing

import java.text.ParseException
import java.util.concurrent.FutureTask
import javax.swing.JSpinner
import javax.swing.SwingUtilities
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import org.junit.Test

class DebuggerHexSpinnerTest {

  @Test
  fun `formatted editor commits hexadecimal values and rejects unavailable addresses`() {
    val spinner =
        onEdt {
          DebuggerHexSpinner(
              0xc000,
              listOf(0xc000..0xfdff, 0xff80..0xfffe),
              digits = 4,
          )
        }

    onEdt {
      val editor = (spinner.editor as JSpinner.DefaultEditor).textField
      assertEquals("\$C000", editor.text)

      editor.text = "\$FF80"
      spinner.commitEdit()
      assertEquals(0xff80, spinner.intValue)

      editor.text = "\$FE00"
      assertFailsWith<ParseException> { spinner.commitEdit() }
      assertEquals(0xff80, spinner.intValue)
      assertFailsWith<IllegalArgumentException> { spinner.intValue = 0xfe00 }
    }
  }

  @Test
  fun `arrow navigation crosses disjoint ranges without entering the gap`() {
    val spinner =
        onEdt {
          DebuggerHexSpinner(
              0xfdff,
              listOf(0xc000..0xfdff, 0xff80..0xfffe),
              digits = 4,
          )
        }

    onEdt {
      assertEquals(0xff80, spinner.model.nextValue)
      spinner.intValue = 0xff80
      assertEquals(0xfdff, spinner.model.previousValue)
      assertFalse(spinner.isValueAllowed(0xfe00))
      assertTrue(spinner.isValueAllowed(0xff80))

      spinner.setAllowedRanges(listOf(0x00..0xff), preferredValue = 0xaa)
      assertEquals(0xaa, spinner.intValue)
      assertEquals("\$00AA", (spinner.editor as JSpinner.DefaultEditor).textField.text)
    }
  }

  @Test
  fun `constructor requires an allowed initial value and usable ranges`() {
    onEdt {
      assertFailsWith<IllegalArgumentException> {
        DebuggerHexSpinner(0x100, listOf(0x00..0xff))
      }
      assertFailsWith<IllegalArgumentException> {
        DebuggerHexSpinner(0, emptyList())
      }
    }
  }

  private fun <T> onEdt(action: () -> T): T {
    if (SwingUtilities.isEventDispatchThread()) return action()
    val task = FutureTask(action)
    SwingUtilities.invokeAndWait(task)
    return task.get()
  }
}
