package eu.rekawek.coffeegb.controller.state

import eu.rekawek.coffeegb.controller.StateLimits
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import org.junit.Test

class DetachedValueBoundsTest {

  @Test
  fun primitiveArrayElementAndByteBoundariesUseCheckedArithmetic() {
    assertEquals(
        StateLimits.LEGACY_MAX_ARRAY_LENGTH,
        DetachedValueBounds.checkedArrayBytesForApply(
            StateLimits.LEGACY_MAX_ARRAY_LENGTH, Byte.SIZE_BYTES.toLong()),
    )
    assertFailsWith<StateApplyException> {
      DetachedValueBounds.checkedArrayBytesForApply(
          StateLimits.LEGACY_MAX_ARRAY_LENGTH + 1, Byte.SIZE_BYTES.toLong())
    }

    val maximumIntElements = StateLimits.LEGACY_MAX_ARRAY_BYTES / Int.SIZE_BYTES
    assertEquals(
        StateLimits.LEGACY_MAX_ARRAY_BYTES,
        DetachedValueBounds.checkedArrayBytesForApply(
            maximumIntElements, Int.SIZE_BYTES.toLong()),
    )
    assertFailsWith<StateApplyException> {
      DetachedValueBounds.checkedArrayBytesForApply(
          maximumIntElements + 1, Int.SIZE_BYTES.toLong())
    }
    assertFailsWith<StateApplyException> {
      DetachedValueBounds.checkedArrayBytesForApply(2, Long.MAX_VALUE)
    }
  }

  @Test
  fun stringCharacterAndEncodingBoundariesAreEnforcedBeforeDecodeAllocation() {
    DetachedValueBounds.checkStringMetricsForApply(
        StateLimits.LEGACY_MAX_STRING_CHARS.toLong(),
        StateLimits.LEGACY_MAX_STRING_BYTES,
    )
    assertFailsWith<StateApplyException> {
      DetachedValueBounds.checkStringMetricsForApply(
          StateLimits.LEGACY_MAX_STRING_CHARS + 1L,
          StateLimits.LEGACY_MAX_STRING_BYTES,
      )
    }
    assertFailsWith<StateApplyException> {
      DetachedValueBounds.checkStringMetricsForApply(
          StateLimits.LEGACY_MAX_STRING_CHARS.toLong(),
          StateLimits.LEGACY_MAX_STRING_BYTES + 1,
      )
    }

    val boundary = "x".repeat(StateLimits.LEGACY_MAX_STRING_CHARS)
    assertEquals(boundary, StateGraph.restore(StringState(boundary)))
    assertFailsWith<IllegalArgumentException> {
      StringState("x".repeat(StateLimits.LEGACY_MAX_STRING_CHARS + 1))
    }
  }
}
