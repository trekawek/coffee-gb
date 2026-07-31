package eu.rekawek.coffeegb.swing

import java.awt.Color
import java.awt.Dimension
import java.awt.Rectangle
import java.awt.event.ActionEvent
import java.awt.event.KeyEvent
import javax.swing.JButton
import javax.swing.JComponent
import javax.swing.JPanel
import javax.swing.JRootPane
import javax.swing.KeyStroke
import javax.swing.SwingUtilities
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertFailsWith
import kotlin.test.assertNotNull
import kotlin.test.assertSame
import kotlin.test.assertTrue
import org.junit.Test

class DesktopDialogFactoryTest {
  @Test
  fun `decision buttons return one typed result and follow the shared grammar`() =
      onEdt {
        val results = mutableListOf<TestResult>()
        val panel = factory().createDecisionPanel(decisionSpec(), results::add)

        assertEquals(
            listOf("Keep playing", "Cancel", "Save and open"),
            panel.buttonBar.components.filterIsInstance<JButton>().map(JButton::getText),
        )
        assertSame(panel.buttonFor(TestResult.SAVE_AND_OPEN), panel.buttonBar.primaryButton)
        assertEquals(tokens().accent, panel.buttonBar.primaryButton?.background)
        assertEquals("Dialog actions", panel.buttonBar.accessibleContext.accessibleName)

        panel.buttonFor(TestResult.SAVE_AND_OPEN)?.doClick()
        panel.buttonFor(TestResult.CANCEL)?.doClick()

        assertEquals(listOf(TestResult.SAVE_AND_OPEN), results)
      }

  @Test
  fun `Escape and decoration cancellation select the safe result while Enter uses the safe default`() =
      onEdt {
        val results = mutableListOf<TestResult>()
        val panel = factory().createDecisionPanel(decisionSpec(), results::add)
        val rootPane = JRootPane()
        panel.bindRootPane(rootPane)

        assertSame(panel.buttonFor(TestResult.SAVE_AND_OPEN), rootPane.defaultButton)
        val escape = KeyStroke.getKeyStroke(KeyEvent.VK_ESCAPE, 0)
        val actionKey =
            rootPane.getInputMap(JComponent.WHEN_IN_FOCUSED_WINDOW).get(escape)
        assertNotNull(actionKey)
        rootPane.actionMap.get(actionKey).actionPerformed(ActionEvent(rootPane, 0, "escape"))
        panel.cancel()

        assertEquals(listOf(TestResult.CANCEL), results)
      }

  @Test
  fun `destructive outcomes cannot become the Enter default`() {
    val destructive =
        DesktopDialogAction(
            "Delete state",
            TestResult.SAVE_AND_OPEN,
            destructive = true,
        )

    assertFailsWith<IllegalArgumentException> {
      DesktopDialogButtons(
          primary = destructive,
          cancel = DesktopDialogAction("Cancel", TestResult.CANCEL),
          defaultButton = DesktopDialogDefaultButton.PRIMARY,
      )
    }
  }

  @Test
  fun `form shell exposes its group and literal inline validation without submitting invalid input`() =
      onEdt {
        val content = JPanel()
        val results = mutableListOf<TestResult>()
        val spec =
            DesktopFormSpec(
                title = "Attach file",
                heading = "Choose an attachment",
                description = "Select one supported cartridge file.",
                contentAccessibleName = "Attachment fields",
                buttons =
                    DesktopDialogButtons(
                        primary =
                            DesktopDialogAction("Apply", TestResult.SAVE_AND_OPEN),
                        cancel = DesktopDialogAction("Cancel", TestResult.CANCEL),
                        defaultButton = DesktopDialogDefaultButton.PRIMARY,
                    ),
                initiallyValid = false,
            )
        val panel = factory().createFormPanel(spec, content, results::add)

        assertEquals("Attachment fields", content.accessibleContext.accessibleName)
        assertFalse(assertNotNull(panel.buttonBar.primaryButton).isEnabled)
        assertFalse(panel.validationText.isVisible)

        val untrustedValidation = "<html><b>peer.invalid</b> & bad path</html>"
        panel.setSubmissionState(false, untrustedValidation)
        assertEquals(untrustedValidation, panel.validationText.text)
        assertEquals(true, panel.validationText.getClientProperty("html.disable"))
        assertTrue(panel.validationText.isVisible)
        panel.buttonBar.primaryButton?.doClick()
        assertTrue(results.isEmpty())

        panel.setSubmissionState(true)
        assertFalse(panel.validationText.isVisible)
        assertTrue(panel.buttonBar.primaryButton?.isEnabled == true)
        panel.buttonBar.primaryButton?.doClick()
        assertEquals(listOf(TestResult.SAVE_AND_OPEN), results)
      }

  @Test
  fun `error prose and details stay literal selectable bounded and copyable`() =
      onEdt {
        val summary = "<html><b>peer.example failed</b></html>"
        val recovery = "Choose another file named <html>save.gb</html>."
        val details = "<html>java.io.IOException: & unexpected peer</html>"
        val copied = mutableListOf<String>()
        val spec =
            DesktopErrorSpec(
                title = "Unable to connect",
                summary = summary,
                recovery = recovery,
                sanitizedDetails = details,
                buttons =
                    DesktopDialogButtons(
                        primary = DesktopDialogAction("Retry", TestResult.SAVE_AND_OPEN),
                        cancel = DesktopDialogAction("Close", TestResult.CANCEL),
                    ),
            )
        val panel =
            DesktopDialogFactory(
                    tokenProvider = ::tokens,
                    clipboardWriter = DesktopClipboardWriter(copied::add),
                )
                .createErrorPanel(spec) {}

        assertEquals(summary, panel.summaryText.text)
        assertEquals(recovery, panel.recoveryText.text)
        assertEquals(details, panel.detailsText.text)
        assertEquals(true, panel.summaryText.getClientProperty("html.disable"))
        assertEquals(true, panel.detailsText.getClientProperty("html.disable"))
        assertFalse(panel.detailsText.isEditable)
        assertEquals(DesktopErrorPanel.DETAILS_ROWS, panel.detailsText.rows)
        assertEquals(DesktopErrorPanel.DETAILS_COLUMNS, panel.detailsText.columns)
        assertFalse(panel.detailsScroll.isVisible)

        panel.detailsToggle.doClick()
        assertTrue(panel.detailsScroll.isVisible)
        assertEquals("Hide details", panel.detailsToggle.text)
        panel.copyDetailsButton.doClick()

        assertEquals(listOf(details), copied)
        assertEquals("Details copied.", panel.copyStatus.text)
        assertTrue(panel.copyStatus.isVisible)
        assertFalse(panel.copyStatus.accessibleContext.accessibleDescription.isNullOrBlank())
      }

  @Test
  fun `details bound includes its notice and does not split a surrogate pair`() {
    val input = "first line with a long value 😃\nsecond line\nthird line"
    val bounded =
        boundDesktopDialogDetails(
            input,
            DesktopDialogDetailLimits(maximumCharacters = 36, maximumLines = 2),
        )

    assertTrue(bounded.truncated)
    assertEquals(input.length, bounded.originalCharacterCount)
    assertTrue(bounded.text.length <= 36)
    assertTrue(bounded.text.lineSequence().count() <= 2)
    assertTrue(bounded.text.endsWith("… Details truncated."))
    assertFalse(bounded.text.dropLast("… Details truncated.".length).lastOrNull()?.isHighSurrogate() == true)
  }

  @Test
  fun `short diagnostics remain byte for byte unchanged`() {
    val literal = "path=C:\\Games\\<html>literal.gb</html>\r\ncode=&42"

    val bounded = boundDesktopDialogDetails(literal)

    assertFalse(bounded.truncated)
    assertEquals(literal, bounded.text)
  }

  @Test
  fun `dialog bounds are fully constrained on negative and tiny work areas`() {
    assertEquals(
        Rectangle(-1_920, -40, 1_920, 1_040),
        fitDesktopDialogBounds(
            Rectangle(5_000, -3_000, 2_500, 1_500),
            Rectangle(-1_920, -40, 1_920, 1_040),
        ),
    )
    assertEquals(
        Rectangle(Int.MAX_VALUE - 99, -200, 100, 80),
        fitDesktopDialogBounds(
            Rectangle(Int.MAX_VALUE, Int.MIN_VALUE, 10, 10),
            Rectangle(Int.MAX_VALUE - 99, -200, 100, 80),
            Dimension(360, 160),
        ),
    )
  }

  @Test
  fun `theme refresh reapplies semantic surfaces status and primary action colors`() =
      onEdt {
        val initial = tokens()
        val panel =
            DesktopDialogFactory(tokenProvider = { initial }).createErrorPanel(
                DesktopErrorSpec(
                    title = "Failure",
                    summary = "The operation failed.",
                    recovery = "Try again.",
                    buttons =
                        DesktopDialogButtons(
                            primary = DesktopDialogAction("Retry", TestResult.SAVE_AND_OPEN),
                            cancel = DesktopDialogAction("Close", TestResult.CANCEL),
                        ),
                )) {}
        val refreshed =
            initial.copy(
                surface = Color(0x202020),
                elevatedSurface = Color(0x303030),
                primaryText = Color.WHITE,
                secondaryText = Color.LIGHT_GRAY,
                accent = Color(0xFFAA88),
                onAccent = Color.BLACK,
                danger = Color(0xFFB4AB),
            )

        panel.desktopThemeChanged(refreshed)

        assertEquals(refreshed.surface, panel.background)
        assertEquals(refreshed.danger, panel.summaryText.foreground)
        assertEquals(refreshed.accent, panel.buttonBar.primaryButton?.background)
        assertEquals(refreshed.onAccent, panel.buttonBar.primaryButton?.foreground)
      }

  private fun decisionSpec(): DesktopDecisionSpec<TestResult> =
      DesktopDecisionSpec(
          title = "Open another game",
          heading = "Save before opening another game?",
          message = "Unsaved progress may be lost.",
          buttons =
              DesktopDialogButtons(
                  primary =
                      DesktopDialogAction(
                          "Save and open",
                          TestResult.SAVE_AND_OPEN,
                          mnemonic = KeyEvent.VK_S,
                      ),
                  secondary =
                      listOf(DesktopDialogAction("Keep playing", TestResult.KEEP_PLAYING)),
                  cancel = DesktopDialogAction("Cancel", TestResult.CANCEL),
                  defaultButton = DesktopDialogDefaultButton.PRIMARY,
              ),
      )

  private fun factory(): DesktopDialogFactory = DesktopDialogFactory(tokenProvider = ::tokens)

  private fun tokens(): DesktopThemeTokens =
      DesktopThemeTokens(
          surface = Color(0xF7F4F1),
          elevatedSurface = Color.WHITE,
          primaryText = Color(0x201A18),
          secondaryText = Color(0x5C4E49),
          border = Color(0x746B67),
          focus = Color(0x005FCC),
          accent = Color(0xA6422B),
          onAccent = Color.WHITE,
          success = Color(0x256F3A),
          warning = Color(0x805500),
          danger = Color(0xB3261E),
      )

  private fun <T> onEdt(action: () -> T): T {
    var result: Result<T>? = null
    SwingUtilities.invokeAndWait { result = runCatching(action) }
    return checkNotNull(result).getOrThrow()
  }

  private enum class TestResult {
    SAVE_AND_OPEN,
    KEEP_PLAYING,
    CANCEL,
  }
}
