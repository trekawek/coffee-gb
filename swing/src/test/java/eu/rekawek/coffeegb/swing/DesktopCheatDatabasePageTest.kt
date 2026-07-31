package eu.rekawek.coffeegb.swing

import eu.rekawek.coffeegb.core.genie.CheatDatabase
import javax.swing.JLabel
import javax.swing.SwingUtilities
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import org.junit.Test

class DesktopCheatDatabasePageTest {
  @Test
  fun `search selection and add stay on one literal database page`() =
      onEdt {
        val gameName = "<html>Literal game</html>"
        val supported = CheatDatabase.Cheat("<html>Infinite lives</html>", "supported")
        val unsupported = CheatDatabase.Cheat("Unsupported", "unsupported")
        val searches = mutableListOf<Pair<String, Int>>()
        val added = mutableListOf<List<String>>()
        val page =
            DesktopCheatDatabasePage(
                suggestedTitle = "Literal",
                findGames = { title, limit ->
                  searches += title to limit
                  listOf(CheatDatabase.CheatList(gameName, listOf(supported, unsupported)))
                },
                onAddCodes = added::add,
                isSupportedCode = { it == "supported" },
            )

        assertEquals(listOf("Literal" to 25), searches)
        assertEquals(gameName, page.gameList.selectedValue.name())
        assertEquals(1, page.cheatList.model.size)
        assertTrue(page.addSelectedButton.isEnabled)

        val gameLabel =
            page.gameList.cellRenderer.getListCellRendererComponent(
                page.gameList,
                page.gameList.selectedValue,
                0,
                false,
                false,
            ) as JLabel
        assertEquals(gameName, gameLabel.text)
        assertEquals(true, gameLabel.getClientProperty("html.disable"))

        assertEquals("1 cheat selected.", page.cheatStatus.text)
        page.addSelectedButton.doClick()

        assertEquals(listOf(listOf("supported")), added)
        assertEquals("Cheat added.", page.cheatStatus.text)
      }

  @Test
  fun `empty and failed searches are inline and clear stale cheat actions`() =
      onEdt {
        val page =
            DesktopCheatDatabasePage(
                suggestedTitle = "known",
                findGames = { title, _ ->
                  when (title) {
                    "known" ->
                        listOf(
                            CheatDatabase.CheatList(
                                "Known",
                                listOf(CheatDatabase.Cheat("Lives", "ok")),
                            ),
                        )
                    "failure" -> error("private database detail")
                    else -> emptyList()
                  }
                },
                onAddCodes = {},
                isSupportedCode = { true },
            )
        assertTrue(page.addSelectedButton.isEnabled)

        page.gameTitleField.text = "missing"
        assertEquals(0, page.gameModel.size)
        assertEquals(0, page.cheatList.model.size)
        assertFalse(page.addSelectedButton.isEnabled)
        assertEquals("No matching games. Try a shorter title.", page.gameStatus.text)

        page.gameTitleField.text = "failure"
        assertEquals("Could not load the cheat database.", page.gameStatus.text)
        assertFalse(page.gameStatus.text.contains("private"))
      }

  private fun <T> onEdt(action: () -> T): T {
    var result: Result<T>? = null
    SwingUtilities.invokeAndWait { result = runCatching(action) }
    return checkNotNull(result).getOrThrow()
  }
}
