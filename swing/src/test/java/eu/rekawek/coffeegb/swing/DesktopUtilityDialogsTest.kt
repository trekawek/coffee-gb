package eu.rekawek.coffeegb.swing

import java.awt.Color
import java.awt.Component
import java.awt.event.ActionEvent
import java.awt.event.KeyEvent
import java.nio.file.Path
import javax.swing.JButton
import javax.swing.JComponent
import javax.swing.JLabel
import javax.swing.JPanel
import javax.swing.JRootPane
import javax.swing.KeyStroke
import javax.swing.SwingUtilities
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertSame
import kotlin.test.assertTrue
import org.junit.Test

class DesktopUtilityDialogsTest {
  @Test
  fun `Barcode Boy requires thirteen ASCII decimal digits and the selected device`() =
      onEdt {
        var selectRequests = 0
        val form =
            BarcodeBoyForm(barcodeBoySelected = false, onSelectBarcodeBoy = {
              selectRequests++
              true
            })
        val shell = factory().createFormPanel(form.spec(), form) {}
        val submit = assertNotNull(shell.buttonFor(DesktopUtilityFormResult.APPLY))

        assertFalse(submit.isEnabled)
        assertFalse(shell.validationText.isVisible)
        assertTrue(form.selectDeviceButton.isVisible)

        form.barcodeField.text = "1234567890123"
        assertFalse(submit.isEnabled)
        assertEquals("Select Barcode Boy before scanning.", shell.validationText.text)

        form.selectDeviceButton.doClick()
        assertEquals(1, selectRequests)
        assertTrue(submit.isEnabled)
        assertFalse(form.selectDeviceButton.isVisible)
        assertEquals("1234567890123", form.barcode)
      }

  @Test
  fun `Barcode Boy validation rejects non ASCII digits and reports live length`() =
      onEdt {
        val form = BarcodeBoyForm(barcodeBoySelected = true, onSelectBarcodeBoy = { true })
        val shell = factory().createFormPanel(form.spec(), form) {}

        form.barcodeField.text = "1234"
        assertEquals("Enter exactly 13 digits (4 of 13).", shell.validationText.text)

        form.barcodeField.text = "123456789012x"
        assertEquals("Use decimal digits only.", shell.validationText.text)
        assertFalse(assertNotNull(shell.buttonFor(DesktopUtilityFormResult.APPLY)).isEnabled)

        assertFalse(validateBarcodeBoyCode("１２３４５６７８９０１２３").valid)
        assertFalse(validateBarcodeBoyCode("12345678901234").valid)
        assertTrue(validateBarcodeBoyCode("1234567890123").valid)

        form.barcodeField.text = "12345678901234"
        assertEquals("123456789012x", form.barcodeField.text)
      }

  @Test
  fun `Full Changer searches supplied literal choices and reset restores its default`() =
      onEdt {
        val literalChoice = "<html><b>02 Current</b></html>"
        val choices = listOf("01 Alkaline Powered", literalChoice, "03 Ultra Runner")
        val form = FullChangerForm(choices, literalChoice, resetChoice = choices.first())
        val shell = factory().createFormPanel(form.spec(), form) {}

        assertEquals(literalChoice, form.selectedChoice)
        assertEquals(literalChoice, form.currentChoiceText.text)
        assertTrue(assertNotNull(shell.buttonFor(DesktopUtilityFormResult.APPLY)).isEnabled)

        val renderer =
            form.choiceList.cellRenderer.getListCellRendererComponent(
                form.choiceList,
                literalChoice,
                1,
                false,
                false,
            ) as JLabel
        assertEquals(literalChoice, renderer.text)
        assertEquals(true, renderer.getClientProperty("html.disable"))

        form.searchField.text = "ultra"
        assertEquals(listOf("03 Ultra Runner"), modelValues(form.choiceModel))
        assertEquals("03 Ultra Runner", form.selectedChoice)

        form.searchField.text = "missing"
        assertNull(form.selectedChoice)
        assertFalse(assertNotNull(shell.buttonFor(DesktopUtilityFormResult.APPLY)).isEnabled)
        assertEquals("No characters match this search.", form.resultCount.text)

        form.resetButton.doClick()
        assertEquals("", form.searchField.text)
        assertEquals(choices.first(), form.selectedChoice)
        assertTrue(assertNotNull(shell.buttonFor(DesktopUtilityFormResult.APPLY)).isEnabled)
      }

  @Test
  fun `Action Replay attachment exposes Browse Remove Apply and literal current path`() =
      onEdt {
        val initial = Path.of("/games/<html>old.gb</html>")
        val replacement = Path.of("/games/& new.gbc")
        val form = ActionReplaySlotForm(initial) { replacement }
        val results = mutableListOf<DesktopUtilityFormResult>()
        val shell = factory().createFormPanel(form.spec(), form, results::add)

        assertEquals(initial.toString(), form.fileText.text)
        assertEquals(true, form.fileText.getClientProperty("html.disable"))
        assertTrue(form.removeButton.isEnabled)
        assertEquals(
            listOf("Browse…", "Remove"),
            listOf(form.browseButton.text, form.removeButton.text),
        )

        form.browseButton.doClick()
        assertEquals(replacement, form.attachment)
        assertEquals(replacement.toString(), form.fileText.text)
        assertEquals("Cartridge selected. Apply to save this attachment.", form.attachmentStatus.text)

        form.removeButton.doClick()
        assertNull(form.attachment)
        assertEquals("No cartridge attached", form.fileText.text)
        assertFalse(form.removeButton.isEnabled)

        shell.buttonFor(DesktopUtilityFormResult.APPLY)?.doClick()
        assertEquals(listOf(DesktopUtilityFormResult.APPLY), results)
      }

  @Test
  fun `Cheats shell has only database and validated manual-add pages`() =
      onEdt {
        val databasePage = JPanel()
        val added = mutableListOf<String>()
        var closed = false
        val panel =
            DesktopCheatsDialogPanel(
                databasePage = databasePage,
                initialPage = DesktopCheatsPage.MANUAL_ENTRY,
                onManualCode = added::add,
                onClose = { closed = true },
                initialTokens = tokens(),
            )
        val rootPane = JRootPane()
        panel.bindRootPane(rootPane)

        assertEquals(listOf("Database", "Manual Entry"), (0 until panel.tabs.tabCount).map(panel.tabs::getTitleAt))
        assertEquals("Cheat database", databasePage.accessibleContext.accessibleName)
        assertFalse(panel.addCodeButton.isEnabled)
        assertNull(rootPane.defaultButton)

        panel.manualCodeField.text = "not-a-code"
        assertFalse(panel.addCodeButton.isEnabled)
        assertEquals("Enter a valid Game Genie or GameShark code.", panel.manualValidation.text)

        panel.manualCodeField.text = " 123-456 "
        assertTrue(panel.addCodeButton.isEnabled)
        assertSame(panel.addCodeButton, rootPane.defaultButton)
        panel.addCodeButton.doClick()

        assertEquals(listOf("123-456"), added)
        assertEquals("Code added.", panel.manualStatus.text)
        assertTrue(panel.manualStatus.isVisible)
        assertFalse(descendants(panel).filterIsInstance<JButton>().any { it.text.contains("Remove") })
        assertFalse(descendants(panel).filterIsInstance<JButton>().any { it.text.contains("Disable") })

        panel.closeButton.doClick()
        assertTrue(closed)
      }

  @Test
  fun `Cheats Escape closes safely and database page has no implicit Enter action`() =
      onEdt {
        var closes = 0
        val panel =
            DesktopCheatsDialogPanel(
                databasePage = JPanel(),
                initialPage = DesktopCheatsPage.DATABASE,
                onManualCode = {},
                onClose = { closes++ },
                initialTokens = tokens(),
            )
        val rootPane = JRootPane()
        panel.bindRootPane(rootPane)

        assertNull(rootPane.defaultButton)
        assertFalse(panel.accessibleContext.accessibleDescription.isNullOrBlank())
        assertFalse(panel.tabs.accessibleContext.accessibleDescription.isNullOrBlank())
        val escape = KeyStroke.getKeyStroke(KeyEvent.VK_ESCAPE, 0)
        val actionKey =
            rootPane.getInputMap(JComponent.WHEN_IN_FOCUSED_WINDOW).get(escape)
        assertNotNull(actionKey)
        rootPane.actionMap.get(actionKey).actionPerformed(ActionEvent(rootPane, 0, "escape"))

        assertEquals(1, closes)
      }

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

  private fun modelValues(model: javax.swing.DefaultListModel<String>): List<String> =
      (0 until model.size).map(model::getElementAt)

  private fun descendants(root: java.awt.Container): Sequence<Component> =
      root.components.asSequence().flatMap { child ->
        sequenceOf(child) +
            if (child is java.awt.Container) descendants(child) else emptySequence()
      }

  private fun <T> onEdt(action: () -> T): T {
    var result: Result<T>? = null
    SwingUtilities.invokeAndWait { result = runCatching(action) }
    return checkNotNull(result).getOrThrow()
  }
}
