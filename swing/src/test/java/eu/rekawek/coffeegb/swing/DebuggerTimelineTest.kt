package eu.rekawek.coffeegb.swing

import eu.rekawek.coffeegb.core.debug.DebugMemoryAccess
import eu.rekawek.coffeegb.core.debug.trace.CpuInstructionTrace
import eu.rekawek.coffeegb.core.debug.trace.MemoryAccessTrace
import eu.rekawek.coffeegb.core.debug.trace.TraceCategory
import eu.rekawek.coffeegb.core.debug.trace.TraceEntry
import eu.rekawek.coffeegb.core.debug.trace.TraceReadResult
import eu.rekawek.coffeegb.core.debug.trace.TraceSource
import kotlin.test.assertContains
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import org.junit.Test

class DebuggerTimelineTest {
  @Test
  fun `rows remain sequence ordered session correlated and bounded to two thousand`() {
    val model = DebuggerTimelineTableModel()
    val firstEntries =
        (0L until 2_000L).map { sequence ->
          TraceEntry(
              sequence,
              sequence * 4,
              TraceSource.CPU,
              CpuInstructionTrace((sequence % 0x8000).toInt(), 0x00, -1),
          )
        }
    model.append(
        DebuggerTimelineIdentity(3, 10),
        TraceReadResult(firstEntries, 1_999, 0, 0, 0, 2_000),
    )
    val update =
        model.append(
            DebuggerTimelineIdentity(3, 11),
            TraceReadResult(
                listOf(
                    TraceEntry(
                        2_000,
                        8_000,
                        TraceSource.CPU,
                        CpuInstructionTrace(0x100, 0, -1),
                    ),
                    TraceEntry(
                        2_001,
                        8_004,
                        TraceSource.CPU,
                        CpuInstructionTrace(0x101, 0, -1),
                    ),
                ),
                2_001,
                0,
                0,
                0,
                2_002,
            ),
        )

    assertEquals(2_000, model.rowCount)
    assertEquals(2L, model.rowAt(0).entry.sequence())
    assertEquals(2_001L, model.rowAt(model.rowCount - 1).entry.sequence())
    assertEquals("S3/#11", model.rowAt(model.rowCount - 1).identity.label)
    assertContains(update.warning!!, "desktop view evicted 2")
  }

  @Test
  fun `duplicate rows are ignored without violating order and all loss is visible`() {
    val model = DebuggerTimelineTableModel(10)
    model.append(
        DebuggerTimelineIdentity(4, 1),
        TraceReadResult(
            listOf(
                TraceEntry(5, 50, TraceSource.CPU, CpuInstructionTrace(0x100, 0, -1)),
                TraceEntry(6, 60, TraceSource.CPU, CpuInstructionTrace(0x101, 0, -1)),
            ),
            6,
            0,
            0,
            5,
            7,
        ),
    )
    val update =
        model.append(
            DebuggerTimelineIdentity(4, 2),
            TraceReadResult(
                listOf(
                    TraceEntry(4, 40, TraceSource.CPU, CpuInstructionTrace(0x0ff, 0, -1)),
                    TraceEntry(
                        7,
                        70,
                        TraceSource.MEMORY_BUS,
                        MemoryAccessTrace(DebugMemoryAccess.WRITE, 0xc000, 0xab),
                    ),
                ),
                7,
                3,
                9,
                4,
                8,
            ),
        )

    assertEquals(
        listOf(5L, 6L, 7L),
        (0 until model.rowCount).map { model.rowAt(it).entry.sequence() },
    )
    assertEquals(1, update.discardedRows)
    val warning = requireNotNull(update.warning)
    assertContains(warning, "missed 3")
    assertContains(warning, "dropped 9")
    assertContains(warning, "out-of-order")
    assertContains(model.copyText(intArrayOf(2)), "WRITE")
    assertContains(model.copyText(intArrayOf(2)), "\$C000")

    model.append(
        DebuggerTimelineIdentity(5, 1),
        TraceReadResult(
            listOf(TraceEntry(0, 1, TraceSource.CPU, CpuInstructionTrace(0x100, 0, -1))),
            0,
            0,
            0,
            0,
            1,
        ),
    )
    assertEquals(1, model.rowCount)
    assertEquals(5L, model.rowAt(0).identity.sessionGeneration)
  }
}

class DebuggerPreferencesStoreTest {
  @Test
  fun `default trace categories are low volume and never start capture`() {
    val defaults = DebuggerUiPreferences()

    assertFalse(TraceCategory.CPU in defaults.timelineCategories)
    assertFalse(TraceCategory.MEMORY in defaults.timelineCategories)
    assertEquals(
        setOf(TraceCategory.INTERRUPT, TraceCategory.PPU, TraceCategory.INPUT),
        defaults.timelineCategories,
    )
  }

  @Test
  fun `persistence round trip writes only the harmless allow listed keys`() {
    val node = MapPreferenceNode()
    val store = DebuggerPreferencesStore(node)
    val expected =
        DebuggerUiPreferences(
            bounds = DebuggerWindowBounds(30, 40, 1200, 800),
            cpuScalarDivider = 300,
            cpuCodeDivider = 650,
            cpuVerticalDivider = 420,
            selectedPane = 5,
            fontScalePercent = 140,
            timelineCategories = setOf(TraceCategory.CPU, TraceCategory.TIMER),
            timelineCapacity = 768,
        )

    store.save(expected)

    assertEquals(DebuggerPreferencesStore.SAFE_KEYS, node.values.keys)
    assertFalse(
        node.values.keys.any { key ->
          key.contains("memory") ||
              key.contains("address") ||
              key.contains("rom") ||
              key.contains("cursor")
        })
    assertEquals(expected, DebuggerPreferencesStore(node).load())
  }

  @Test
  fun `invalid persisted values are clamped or discarded`() {
    val node =
        MapPreferenceNode(
            mutableMapOf(
                DebuggerPreferencesStore.KEY_X to "999999999",
                DebuggerPreferencesStore.KEY_Y to "0",
                DebuggerPreferencesStore.KEY_WIDTH to "20",
                DebuggerPreferencesStore.KEY_HEIGHT to "20",
                DebuggerPreferencesStore.KEY_SELECTED_PANE to "99",
                DebuggerPreferencesStore.KEY_FONT_SCALE to "500",
                DebuggerPreferencesStore.KEY_TIMELINE_CAPACITY to "1",
                DebuggerPreferencesStore.KEY_TIMELINE_CATEGORIES to "CPU,UNKNOWN,TIMER",
            ))
    val value = DebuggerPreferencesStore(node).load()

    assertEquals(null, value.bounds)
    assertEquals(DebuggerUiPreferences.MAX_SELECTED_PANE, value.selectedPane)
    assertEquals(DebuggerUiPreferences.MAX_FONT_SCALE_PERCENT, value.fontScalePercent)
    assertEquals(DebuggerUiPreferences.MIN_TIMELINE_CAPACITY, value.timelineCapacity)
    assertEquals(setOf(TraceCategory.CPU, TraceCategory.TIMER), value.timelineCategories)
    assertTrue(value.timelineCapacity <= DebuggerTimelineTableModel.MAX_RETAINED_ROWS)
  }

  private class MapPreferenceNode(
      val values: MutableMap<String, String> = mutableMapOf(),
  ) : DebuggerPreferenceNode {
    override fun get(key: String): String? = values[key]

    override fun put(key: String, value: String) {
      values[key] = value
    }
  }
}
