package eu.rekawek.coffeegb.swing

import java.text.ParseException
import java.util.Locale
import javax.swing.JFormattedTextField
import javax.swing.JSpinner
import javax.swing.text.DefaultFormatterFactory
import kotlin.math.absoluteValue

/**
 * A hexadecimal spinner whose model can represent one or more disjoint inclusive ranges.
 *
 * The editor is a formatted Swing control rather than a free-form text input: invalid values are
 * rejected on commit, and the arrow actions skip directly across gaps in the allowed address set.
 */
internal class DebuggerHexSpinner private constructor(
    private val hexModel: BoundedHexSpinnerModel,
    private val digits: Int,
) : JSpinner(hexModel) {
  constructor(
      initialValue: Int,
      allowedRanges: List<IntRange>,
      stepSize: Int = 1,
      digits: Int = 4,
  ) : this(BoundedHexSpinnerModel(initialValue, allowedRanges, stepSize), digits)

  init {
    require(digits > 0) { "Hexadecimal digit count must be positive" }
    editor =
        DefaultEditor(this).apply {
          textField.columns = digits + 1
          textField.horizontalAlignment = JFormattedTextField.RIGHT
          textField.focusLostBehavior = JFormattedTextField.COMMIT_OR_REVERT
          textField.formatterFactory =
              DefaultFormatterFactory(HexFormatter(hexModel, digits))
          textField.value = hexModel.value
        }
  }

  var intValue: Int
    get() = hexModel.intValue
    set(value) {
      hexModel.value = value
    }

  /**
   * Canonical text compatibility for callers which previously populated a hexadecimal text field.
   *
   * Assignments are parsed and validated by the same formatter used by the spinner editor, so this
   * does not reintroduce an unbounded free-form value model.
   */
  var text: String
    get() = HexFormatter(hexModel, digits).valueToString(intValue)
    set(value) {
      intValue = (HexFormatter(hexModel, digits).stringToValue(value) as Number).toInt()
    }

  val allowedRanges: List<IntRange>
    get() = hexModel.allowedRanges

  fun setAllowedRanges(ranges: List<IntRange>, preferredValue: Int = intValue) {
    hexModel.setAllowedRanges(ranges, preferredValue)
  }

  fun isValueAllowed(value: Int): Boolean = hexModel.isAllowed(value)

  private class HexFormatter(
      private val model: BoundedHexSpinnerModel,
      private val digits: Int,
  ) : JFormattedTextField.AbstractFormatter() {
    override fun stringToValue(text: String?): Any {
      val source = text?.trim().orEmpty()
      val digitsOnly =
          when {
            source.startsWith("$") -> source.drop(1)
            source.startsWith("0x", ignoreCase = true) -> source.drop(2)
            else -> source
          }
      if (digitsOnly.isEmpty() || !digitsOnly.all { it.isDigit() || it.lowercaseChar() in 'a'..'f' }) {
        throw ParseException("Enter a hexadecimal value", 0)
      }
      val parsed =
          digitsOnly.toLongOrNull(16)
              ?.takeIf { it <= Int.MAX_VALUE }
              ?.toInt()
              ?: throw ParseException("Hexadecimal value is too large", 0)
      if (!model.isAllowed(parsed)) {
        throw ParseException("Value is outside the available range", 0)
      }
      return parsed
    }

    override fun valueToString(value: Any?): String {
      val number = (value as? Number)?.toInt() ?: model.intValue
      return "$" + String.format(Locale.ROOT, "%0${digits}X", number)
    }
  }
}

/** Spinner model with deterministic navigation through disjoint integer ranges. */
private class BoundedHexSpinnerModel(
    initialValue: Int,
    allowedRanges: List<IntRange>,
    private val stepSize: Int,
) : javax.swing.AbstractSpinnerModel() {
  private var ranges = normalizeRanges(allowedRanges)
  private var currentValue: Int

  init {
    require(stepSize > 0) { "Spinner step size must be positive" }
    require(isAllowed(initialValue)) { "Initial value is outside the available ranges" }
    currentValue = initialValue
  }

  val intValue: Int
    get() = currentValue

  val allowedRanges: List<IntRange>
    get() = ranges.toList()

  override fun getValue(): Any = currentValue

  override fun setValue(value: Any?) {
    val number = value as? Number ?: throw IllegalArgumentException("Spinner value must be numeric")
    val candidate = number.toLong()
    require(candidate in Int.MIN_VALUE.toLong()..Int.MAX_VALUE.toLong()) {
      "Spinner value is outside the integer range"
    }
    val intCandidate = candidate.toInt()
    require(isAllowed(intCandidate)) { "Spinner value is outside the available ranges" }
    if (currentValue != intCandidate) {
      currentValue = intCandidate
      fireStateChanged()
    }
  }

  override fun getNextValue(): Any? {
    val target = currentValue.toLong() + stepSize
    if (target > Int.MAX_VALUE) return null
    return ranges.firstNotNullOfOrNull { range ->
      when {
        target > range.last -> null
        target <= range.first -> range.first
        else -> target.toInt()
      }
    }
  }

  override fun getPreviousValue(): Any? {
    val target = currentValue.toLong() - stepSize
    if (target < Int.MIN_VALUE) return null
    return ranges.asReversed().firstNotNullOfOrNull { range ->
      when {
        target < range.first -> null
        target >= range.last -> range.last
        else -> target.toInt()
      }
    }
  }

  fun isAllowed(value: Int): Boolean = ranges.any { value in it }

  fun setAllowedRanges(newRanges: List<IntRange>, preferredValue: Int) {
    val normalized = normalizeRanges(newRanges)
    val nextValue = nearestAllowed(preferredValue, normalized)
    val changed = normalized != ranges || nextValue != currentValue
    ranges = normalized
    currentValue = nextValue
    if (changed) fireStateChanged()
  }

  companion object {
    private fun normalizeRanges(source: List<IntRange>): List<IntRange> {
      val sorted =
          source
              .filterNot(IntRange::isEmpty)
              .onEach { range -> require(range.first >= 0) { "Hexadecimal ranges cannot be negative" } }
              .sortedBy(IntRange::first)
      require(sorted.isNotEmpty()) { "At least one non-empty range is required" }

      val merged = mutableListOf<IntRange>()
      sorted.forEach { range ->
        val previous = merged.lastOrNull()
        if (previous == null || range.first.toLong() > previous.last.toLong() + 1L) {
          merged += range
        } else {
          merged[merged.lastIndex] = previous.first..maxOf(previous.last, range.last)
        }
      }
      return merged
    }

    private fun nearestAllowed(value: Int, ranges: List<IntRange>): Int {
      if (ranges.any { value in it }) return value
      return ranges
          .flatMap { range -> listOf(range.first, range.last) }
          .minWith(compareBy<Int> { (it.toLong() - value).absoluteValue }.thenBy { it })
    }
  }
}
