package eu.rekawek.coffeegb.swing

import java.awt.Color
import java.awt.event.InputEvent
import java.awt.event.KeyEvent
import java.net.URI
import javax.swing.KeyStroke
import javax.swing.SwingUtilities
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import org.junit.Test

class DesktopHelpDialogsTest {
  @Test
  fun `shortcut guide groups contexts and reports withdrawn gameplay conflicts`() {
    val actions = actionRegistry()
    actions.applyShortcuts(
        DesktopShortcutRegistry(
            gameplayKeyCodes = listOf(KeyEvent.VK_F11),
            platformMenuMask = InputEvent.CTRL_DOWN_MASK,
        ))

    val groups = desktopShortcutGuide(actions, InputEvent.CTRL_DOWN_MASK)

    assertEquals(listOf("Main window", "Gameplay", "Debugger window"), groups.map { it.title })
    val main = groups.first().rows.associateBy { it.action }
    assertEquals("Ctrl+O", main.getValue("Open ROM").shortcut)
    assertEquals("Ctrl+0–9", main.getValue("Select state slot").shortcut)
    assertEquals("F11 (inactive)", main.getValue("Full Screen").shortcut)
    assertEquals(
        "Inactive because this key is assigned to gameplay",
        main.getValue("Full Screen").note,
    )
    assertTrue(groups[1].rows.any { it.action == "Rewind" && it.shortcut == "Backspace" })
    assertTrue(groups[2].rows.any { it.action == "Step instruction / frame" })
  }

  @Test
  fun `shortcut names use stable readable modifier order`() {
    assertEquals(
        "Ctrl+Shift+F7",
        desktopKeyStrokeText(
            KeyStroke.getKeyStroke(
                KeyEvent.VK_F7,
                InputEvent.CTRL_DOWN_MASK or InputEvent.SHIFT_DOWN_MASK,
            )),
    )
  }

  @Test
  fun `about content is minimal accessible copyable and opens its source link`() =
      onEdt {
        val copied = mutableListOf<String>()
        val opened = mutableListOf<URI>()
        val about =
            DesktopAboutPanel(
                "1.2.3",
                DesktopClipboardWriter(copied::add),
                DesktopUriOpener(opened::add),
            )
        val factory = DesktopDialogFactory(tokenProvider = ::tokens)
        val panel =
            factory.createContentPanel(
                DesktopContentSpec(
                    title = "About Coffee GB",
                    accessibleDescription = "Application information.",
                    contentAccessibleName = "Application information",
                    buttons =
                        DesktopDialogButtons(
                            cancel = DesktopDialogAction("Close", Unit),
                        ),
                ),
                about,
            ) {}

        assertEquals("About Coffee GB", panel.accessibleContext.accessibleName)
        assertEquals("About Coffee GB", about.accessibleContext.accessibleName)
        assertEquals("https://github.com/trekawek/coffee-gb", about.sourceLink.text)
        assertEquals(true, about.sourceLink.getClientProperty("html.disable"))

        about.copyButton.doClick()
        about.sourceLink.doClick()

        assertEquals(listOf(about.versionInformation), copied)
        assertEquals(listOf(URI("https://github.com/trekawek/coffee-gb")), opened)
        assertEquals("Version info copied.", about.copyStatus.text)
        assertEquals(tokens().surface, about.background)
        assertEquals(tokens().elevatedSurface, panel.buttonBar.cancelButton.background)
      }

  @Test
  fun `about details pack as a left-aligned reading column`() =
      onEdt {
        val about = DesktopAboutPanel("1.2.3")
        about.size = about.preferredSize
        layoutTree(about)

        val labels = descendants(about).filterIsInstance<javax.swing.JLabel>().associateBy { it.text }
        val left = labels.getValue("Coffee GB").x
        assertEquals(left, labels.getValue("Version: 1.2.3").x)
        assertEquals(left, labels.getValue("License: MIT").x)
        assertEquals(left, labels.getValue("Source:").x)
        assertEquals(left, about.sourceLink.x)
        assertEquals(left, about.copyButton.x)
      }

  @Test
  fun `about content fits above its dialog actions at packed size`() =
      onEdt {
        val about = DesktopAboutPanel("1.2.3")
        val panel =
            DesktopDialogFactory(tokenProvider = ::tokens)
                .createContentPanel(
                    DesktopContentSpec(
                        title = "About Coffee GB",
                        accessibleDescription = "Application information.",
                        contentAccessibleName = "Application information",
                        buttons = DesktopDialogButtons(cancel = DesktopDialogAction("Close", Unit)),
                    ),
                    about,
                ) {}

        panel.size = panel.preferredSize
        layoutTree(panel)

        assertTrue(about.copyButton.y + about.copyButton.height <= panel.buttonBar.y)
      }

  private fun actionRegistry(): DesktopActionRegistry =
      DesktopActionRegistry(
          DesktopCommandHandlers(
              openRom = {},
              closeGame = {},
              preferences = {},
              quit = {},
              setPaused = {},
              reset = {},
              saveState = {},
              loadState = {},
              manageStates = {},
              openSaveFolder = {},
              netplay = {},
              setMuted = {},
              setFullscreen = {},
              screenshot = {},
              setCommandBarVisible = {},
              selectStateSlot = {},
          ))

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

  private fun descendants(root: java.awt.Container): Sequence<java.awt.Component> =
      root.components.asSequence().flatMap { child ->
        sequenceOf(child) +
            if (child is java.awt.Container) descendants(child) else emptySequence()
      }

  private fun layoutTree(container: java.awt.Container) {
    container.doLayout()
    container.components.filterIsInstance<java.awt.Container>().forEach(::layoutTree)
  }

  private fun <T> onEdt(action: () -> T): T {
    var result: Result<T>? = null
    SwingUtilities.invokeAndWait { result = runCatching(action) }
    return checkNotNull(result).getOrThrow()
  }
}
