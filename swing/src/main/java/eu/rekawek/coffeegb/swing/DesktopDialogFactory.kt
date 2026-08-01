package eu.rekawek.coffeegb.swing

import java.awt.Component
import java.awt.Dialog
import java.awt.Dimension
import java.awt.Font
import java.awt.GraphicsEnvironment
import java.awt.KeyboardFocusManager
import java.awt.Rectangle
import java.awt.Toolkit
import java.awt.Window
import java.awt.datatransfer.StringSelection
import java.awt.event.ActionEvent
import java.awt.event.KeyEvent
import java.awt.event.WindowAdapter
import java.awt.event.WindowEvent
import javax.swing.AbstractAction
import javax.swing.BorderFactory
import javax.swing.Box
import javax.swing.BoxLayout
import javax.swing.JButton
import javax.swing.JCheckBox
import javax.swing.JComponent
import javax.swing.JDialog
import javax.swing.JPanel
import javax.swing.JRootPane
import javax.swing.JScrollPane
import javax.swing.JTextArea
import javax.swing.KeyStroke
import javax.swing.SwingConstants
import javax.swing.SwingUtilities
import javax.swing.border.EmptyBorder

/**
 * One application-owned command shown in a common dialog button bar.
 *
 * Labels are normally static application copy. They are nevertheless configured for literal Swing
 * rendering so a future workflow cannot accidentally turn a peer name or path into BasicHTML.
 */
internal data class DesktopDialogAction<R>(
    val label: String,
    val result: R,
    val mnemonic: Int? = null,
    val accessibleDescription: String = label,
    val destructive: Boolean = false,
) {
  init {
    require(label.isNotBlank()) { "A dialog action needs a visible label" }
    require('\n' !in label && '\r' !in label) { "Dialog action labels must fit on one line" }
    require(accessibleDescription.isNotBlank()) {
      "A dialog action needs an accessible description"
    }
    require(mnemonic == null || mnemonic > KeyEvent.VK_UNDEFINED) {
      "A mnemonic must be a defined key code"
    }
  }
}

internal enum class DesktopDialogDefaultButton {
  /** Safe primary action, such as Open or Save changes. */
  PRIMARY,
  /** The non-destructive cancel/close outcome. */
  CANCEL,
  /** No Enter shortcut, for decisions where neither outcome is a safe implicit choice. */
  NONE,
}

/**
 * Common button grammar: at most one filled primary action, optional secondary outcomes, and one
 * mandatory safe cancel outcome used by Escape and the window decoration close button.
 */
internal data class DesktopDialogButtons<R>(
    val primary: DesktopDialogAction<R>? = null,
    val secondary: List<DesktopDialogAction<R>> = emptyList(),
    val cancel: DesktopDialogAction<R>,
    val defaultButton: DesktopDialogDefaultButton = DesktopDialogDefaultButton.CANCEL,
) {
  init {
    require(defaultButton != DesktopDialogDefaultButton.PRIMARY || primary != null) {
      "A primary default requires a primary action"
    }
    require(defaultButton != DesktopDialogDefaultButton.PRIMARY || primary?.destructive != true) {
      "A destructive action cannot be the Enter default"
    }
    require(!cancel.destructive) { "The Escape/cancel action must be non-destructive" }
    val results = orderedActions().map { it.result }
    require(results.distinct().size == results.size) {
      "Every dialog action must produce a distinct result"
    }
  }

  /** Visual order keeps safe alternatives before Cancel and the primary outcome at the edge. */
  fun orderedActions(): List<DesktopDialogAction<R>> =
      buildList {
        addAll(secondary)
        add(cancel)
        primary?.let { add(it) }
      }
}

internal enum class DesktopOwnedDialogModality(val awtType: Dialog.ModalityType) {
  DOCUMENT(Dialog.ModalityType.DOCUMENT_MODAL),
  APPLICATION(Dialog.ModalityType.APPLICATION_MODAL),
}

internal data class DesktopDecisionSpec<R>(
    val title: String,
    val heading: String,
    val message: String,
    val buttons: DesktopDialogButtons<R>,
    val remember: DesktopDecisionRememberOption<R>? = null,
    val modality: DesktopOwnedDialogModality = DesktopOwnedDialogModality.APPLICATION,
) {
  init {
    require(title.isNotBlank()) { "A decision dialog needs a title" }
    require(heading.isNotBlank()) { "A decision dialog needs a heading" }
    require(message.isNotBlank()) { "A decision dialog needs a consequence" }
    require(buttons.primary != null) { "A decision dialog needs an explicit outcome action" }
  }
}

/** Optional persistent choice shown only after an explicit, eligible confirmation outcome. */
internal data class DesktopDecisionRememberOption<R>(
    val results: Set<R>,
    val onSelected: (R) -> Unit,
    val label: String = "Don't ask me again",
    val accessibleDescription: String =
        "Remember this choice and skip this confirmation in the future",
) {
  init {
    require(results.isNotEmpty()) { "A remembered decision needs at least one outcome" }
    require(label.isNotBlank()) { "A remembered decision needs a visible label" }
    require(accessibleDescription.isNotBlank()) {
      "A remembered decision needs an accessible description"
    }
  }
}

internal data class DesktopFormSpec<R>(
    val title: String,
    val heading: String,
    val description: String,
    val contentAccessibleName: String,
    val buttons: DesktopDialogButtons<R>,
    val initiallyValid: Boolean = true,
    val modality: DesktopOwnedDialogModality = DesktopOwnedDialogModality.APPLICATION,
) {
  init {
    require(title.isNotBlank()) { "A form dialog needs a title" }
    require(heading.isNotBlank()) { "A form dialog needs a heading" }
    require(description.isNotBlank()) { "A form dialog needs concise instructions" }
    require(contentAccessibleName.isNotBlank()) { "A form needs an accessible group name" }
    require(buttons.primary != null) { "A form dialog needs a submit action" }
  }
}

internal data class DesktopErrorSpec<R>(
    val title: String,
    val summary: String,
    val recovery: String,
    /** Already sanitized by the owning workflow. This layer only applies display bounds. */
    val sanitizedDetails: String? = null,
    val buttons: DesktopDialogButtons<R>,
    val detailLimits: DesktopDialogDetailLimits = DesktopDialogDetailLimits(),
    val modality: DesktopOwnedDialogModality = DesktopOwnedDialogModality.APPLICATION,
) {
  init {
    require(title.isNotBlank()) { "An error dialog needs a title" }
    require(summary.isNotBlank()) { "An error dialog needs a human-readable summary" }
    require(recovery.isNotBlank()) { "An error dialog needs recovery guidance" }
  }
}

/** A read-only owned surface, such as shortcut reference or application information. */
internal data class DesktopInformationSpec<R>(
    val title: String,
    val heading: String,
    val description: String,
    val contentAccessibleName: String,
    val buttons: DesktopDialogButtons<R>,
    val modality: DesktopOwnedDialogModality = DesktopOwnedDialogModality.APPLICATION,
) {
  init {
    require(title.isNotBlank()) { "An information dialog needs a title" }
    require(heading.isNotBlank()) { "An information dialog needs a heading" }
    require(description.isNotBlank()) { "An information dialog needs a description" }
    require(contentAccessibleName.isNotBlank()) {
      "An information dialog needs an accessible content name"
    }
  }
}

/** A content-only owned surface for information that does not need a duplicated dialog heading. */
internal data class DesktopContentSpec<R>(
    val title: String,
    val accessibleDescription: String,
    val contentAccessibleName: String,
    val buttons: DesktopDialogButtons<R>,
    val modality: DesktopOwnedDialogModality = DesktopOwnedDialogModality.APPLICATION,
) {
  init {
    require(title.isNotBlank()) { "A content dialog needs a title" }
    require(accessibleDescription.isNotBlank()) {
      "A content dialog needs an accessible description"
    }
    require(contentAccessibleName.isNotBlank()) { "A content dialog needs an accessible content name" }
  }
}

internal data class DesktopDialogDetailLimits(
    val maximumCharacters: Int = 32_768,
    /** Includes the visible truncation marker when content is truncated. */
    val maximumLines: Int = 200,
) {
  init {
    require(maximumCharacters >= DETAILS_TRUNCATED_MARKER.length) {
      "The character limit must fit the truncation marker"
    }
    require(maximumLines > 0) { "The line limit must be positive" }
  }
}

internal data class BoundedDesktopDialogDetails(
    val text: String,
    val truncated: Boolean,
    val originalCharacterCount: Int,
)

/** Bounds diagnostic text without interpreting, logging, or attempting to sanitize its content. */
internal fun boundDesktopDialogDetails(
    details: String,
    limits: DesktopDialogDetailLimits = DesktopDialogDetailLimits(),
): BoundedDesktopDialogDetails {
  var unmarkedEnd = minOf(details.length, limits.maximumCharacters)
  var lines = 1
  for (index in 0 until unmarkedEnd) {
    if (details[index] == '\n') {
      if (lines == limits.maximumLines) {
        unmarkedEnd = index
        break
      }
      lines++
    }
  }
  val truncated = unmarkedEnd < details.length
  if (!truncated) {
    return BoundedDesktopDialogDetails(details, false, details.length)
  }

  if (limits.maximumLines == 1) {
    return BoundedDesktopDialogDetails(DETAILS_TRUNCATED_MARKER, true, details.length)
  }

  val markerSeparator = "\n"
  val maximumPrefixCharacters =
      (limits.maximumCharacters - markerSeparator.length - DETAILS_TRUNCATED_MARKER.length)
          .coerceAtLeast(0)
  var prefixEnd = minOf(unmarkedEnd, maximumPrefixCharacters)
  var prefixLines = 1
  val maximumPrefixLines = limits.maximumLines - 1
  for (index in 0 until prefixEnd) {
    if (details[index] == '\n') {
      if (prefixLines == maximumPrefixLines) {
        prefixEnd = index
        break
      }
      prefixLines++
    }
  }
  if (prefixEnd > 0 && Character.isHighSurrogate(details[prefixEnd - 1])) {
    prefixEnd--
  }
  val prefix = details.substring(0, prefixEnd).trimEnd('\r', '\n')
  val bounded =
      if (prefix.isEmpty()) DETAILS_TRUNCATED_MARKER
      else prefix + markerSeparator + DETAILS_TRUNCATED_MARKER
  return BoundedDesktopDialogDetails(bounded, true, details.length)
}

internal fun interface DesktopClipboardWriter {
  fun copy(text: String)
}

private object SystemDesktopClipboardWriter : DesktopClipboardWriter {
  override fun copy(text: String) {
    Toolkit.getDefaultToolkit().systemClipboard.setContents(StringSelection(text), null)
  }
}

/** Base for independently testable common surfaces and factory-created owned dialogs. */
internal abstract class DesktopDialogSurface<R>(
    buttons: DesktopDialogButtons<R>,
    initialTokens: DesktopThemeTokens,
    onResult: (R) -> Unit,
) : JPanel(java.awt.BorderLayout(0, initialTokens.spacing.section)), DesktopThemeRefreshHook {
  private var completed = false
  private val resultConsumer = onResult

  internal val buttonBar = DesktopDialogButtonBar(buttons, initialTokens, ::complete)

  init {
    check(SwingUtilities.isEventDispatchThread()) { "Dialog surfaces must be created on the EDT" }
    add(buttonBar, java.awt.BorderLayout.SOUTH)
  }

  fun bindRootPane(rootPane: JRootPane) {
    check(SwingUtilities.isEventDispatchThread()) { "Dialog root panes must be bound on the EDT" }
    configureDesktopDialogRootPane(rootPane, buttonBar.defaultButton, ::cancel)
  }

  fun cancel() {
    check(SwingUtilities.isEventDispatchThread()) { "Dialog cancellation must run on the EDT" }
    complete(buttonBar.buttons.cancel.result)
  }

  fun buttonFor(result: R): JButton? = buttonBar.buttonFor(result)

  protected fun setPrimaryEnabled(enabled: Boolean) {
    buttonBar.primaryButton?.isEnabled = enabled
  }

  override fun desktopThemeChanged(tokens: DesktopThemeTokens) {
    check(SwingUtilities.isEventDispatchThread()) { "Dialog themes must update on the EDT" }
    background = tokens.surface
    border =
        EmptyBorder(
            tokens.spacing.dialogEdge,
            tokens.spacing.dialogEdge,
            tokens.spacing.dialogEdge,
            tokens.spacing.dialogEdge,
        )
    buttonBar.applyTheme(tokens)
    applySurfaceTheme(tokens)
  }

  protected abstract fun applySurfaceTheme(tokens: DesktopThemeTokens)

  protected open fun beforeComplete(result: R) = Unit

  private fun complete(result: R) {
    if (completed) return
    completed = true
    beforeComplete(result)
    resultConsumer(result)
  }
}

internal class DesktopDecisionPanel<R>(
    internal val spec: DesktopDecisionSpec<R>,
    tokens: DesktopThemeTokens,
    onResult: (R) -> Unit,
) : DesktopDialogSurface<R>(spec.buttons, tokens, onResult) {
  internal val headingText =
      literalDialogText(spec.heading, "Decision heading", columns = BODY_COLUMNS, bold = true)
  internal val messageText =
      literalDialogText(spec.message, "Decision consequence", columns = BODY_COLUMNS)
  internal val dontAskAgain =
      spec.remember?.let { remember ->
        JCheckBox(remember.label).apply {
          isOpaque = false
          getAccessibleContext().accessibleName = remember.label
          getAccessibleContext().accessibleDescription = remember.accessibleDescription
        }
      }

  private val body =
      JPanel().apply {
        layout = BoxLayout(this, BoxLayout.Y_AXIS)
        add(headingText)
        add(Box.createVerticalStrut(tokens.spacing.related))
        add(messageText)
        dontAskAgain?.let {
          add(Box.createVerticalStrut(tokens.spacing.related))
          add(it)
        }
      }

  init {
    getAccessibleContext().accessibleName = spec.heading
    getAccessibleContext().accessibleDescription = spec.message
    add(body, java.awt.BorderLayout.CENTER)
    desktopThemeChanged(tokens)
  }

  override fun applySurfaceTheme(tokens: DesktopThemeTokens) {
    body.background = tokens.surface
    styleLiteralText(headingText, tokens.surface, tokens.primaryText)
    styleLiteralText(messageText, tokens.surface, tokens.secondaryText)
    dontAskAgain?.apply {
      background = tokens.surface
      foreground = tokens.primaryText
    }
  }

  override fun beforeComplete(result: R) {
    spec.remember
        ?.takeIf { dontAskAgain?.isSelected == true && result in it.results }
        ?.onSelected
        ?.invoke(result)
  }
}

internal class DesktopFormPanel<R>(
    internal val spec: DesktopFormSpec<R>,
    internal val formContent: JComponent,
    tokens: DesktopThemeTokens,
    onResult: (R) -> Unit,
) : DesktopDialogSurface<R>(spec.buttons, tokens, onResult) {
  internal val headingText =
      literalDialogText(spec.heading, "Form heading", columns = BODY_COLUMNS, bold = true)
  internal val descriptionText =
      literalDialogText(spec.description, "Form instructions", columns = BODY_COLUMNS)
  internal val validationText =
      literalDialogText("", "Form validation error", columns = BODY_COLUMNS).apply {
        isVisible = false
      }

  private val body =
      JPanel().apply {
        layout = BoxLayout(this, BoxLayout.Y_AXIS)
        add(headingText)
        add(Box.createVerticalStrut(tokens.spacing.related))
        add(descriptionText)
        add(Box.createVerticalStrut(tokens.spacing.section))
        add(formContent)
        add(Box.createVerticalStrut(tokens.spacing.related))
        add(validationText)
      }

  init {
    if (formContent.accessibleContext.accessibleName.isNullOrBlank()) {
      formContent.accessibleContext.accessibleName = spec.contentAccessibleName
    }
    getAccessibleContext().accessibleName = spec.heading
    getAccessibleContext().accessibleDescription = spec.description
    add(body, java.awt.BorderLayout.CENTER)
    setSubmissionState(spec.initiallyValid)
    desktopThemeChanged(tokens)
  }

  /** The message is synchronous form validation copy and is always rendered literally. */
  fun setSubmissionState(enabled: Boolean, validationMessage: String? = null) {
    check(SwingUtilities.isEventDispatchThread()) { "Form validation must update on the EDT" }
    setPrimaryEnabled(enabled)
    validationText.text = validationMessage.orEmpty()
    validationText.isVisible = !validationMessage.isNullOrBlank()
    validationText.accessibleContext.accessibleDescription = validationMessage.orEmpty()
    revalidate()
    repaint()
  }

  override fun applySurfaceTheme(tokens: DesktopThemeTokens) {
    body.background = tokens.surface
    formContent.background = tokens.surface
    styleLiteralText(headingText, tokens.surface, tokens.primaryText)
    styleLiteralText(descriptionText, tokens.surface, tokens.secondaryText)
    styleLiteralText(validationText, tokens.surface, tokens.danger)
  }
}

internal class DesktopErrorPanel<R>(
    internal val spec: DesktopErrorSpec<R>,
    tokens: DesktopThemeTokens,
    private val clipboardWriter: DesktopClipboardWriter = SystemDesktopClipboardWriter,
    onResult: (R) -> Unit,
) : DesktopDialogSurface<R>(spec.buttons, tokens, onResult) {
  internal val boundedDetails =
      spec.sanitizedDetails
          ?.takeIf { it.isNotBlank() }
          ?.let { boundDesktopDialogDetails(it, spec.detailLimits) }
  internal val summaryText =
      literalDialogText(spec.summary, "Error summary", columns = BODY_COLUMNS, bold = true)
  internal val recoveryText =
      literalDialogText(spec.recovery, "Error recovery guidance", columns = BODY_COLUMNS)
  internal val detailsText =
      JTextArea(boundedDetails?.text.orEmpty(), DETAILS_ROWS, DETAILS_COLUMNS).apply {
        isEditable = false
        lineWrap = false
        caretPosition = 0
        putClientProperty(DISABLE_HTML_PROPERTY, true)
        getAccessibleContext().accessibleName = "Sanitized technical details"
        getAccessibleContext().accessibleDescription =
            if (boundedDetails?.truncated == true) {
              "Bounded sanitized technical details; additional content was truncated"
            } else {
              "Sanitized technical details"
            }
      }
  internal val detailsScroll =
      JScrollPane(detailsText).apply {
        isVisible = false
        verticalScrollBarPolicy = JScrollPane.VERTICAL_SCROLLBAR_AS_NEEDED
        horizontalScrollBarPolicy = JScrollPane.HORIZONTAL_SCROLLBAR_AS_NEEDED
        getAccessibleContext().accessibleName = "Sanitized technical details"
      }
  internal val detailsToggle =
      literalButton("Show details", "Show sanitized technical error details").apply {
        mnemonic = KeyEvent.VK_D
        addActionListener {
          detailsScroll.isVisible = !detailsScroll.isVisible
          text = if (detailsScroll.isVisible) "Hide details" else "Show details"
          getAccessibleContext().accessibleName = text
          getAccessibleContext().accessibleDescription =
              if (detailsScroll.isVisible) {
                "Hide sanitized technical error details"
              } else {
                "Show sanitized technical error details"
              }
          revalidateAndConstrainDesktopDialog(this@DesktopErrorPanel)
        }
      }
  internal val copyDetailsButton =
      literalButton("Copy details", "Copy the bounded sanitized technical details").apply {
        mnemonic = KeyEvent.VK_C
        addActionListener {
          runCatching { clipboardWriter.copy(detailsText.text) }
              .onSuccess { showCopyStatus("Details copied.", false) }
              .onFailure { showCopyStatus("Could not copy details.", true) }
        }
      }
  internal val copyStatus =
      literalDialogText("", "Copy details status", columns = BODY_COLUMNS).apply {
        isVisible = false
        getAccessibleContext().accessibleDescription = "No copy operation has been requested"
      }

  private val detailsControls =
      JPanel(java.awt.FlowLayout(java.awt.FlowLayout.LEADING, tokens.spacing.related, 0)).apply {
        add(detailsToggle)
        add(copyDetailsButton)
      }
  private val body =
      JPanel().apply {
        layout = BoxLayout(this, BoxLayout.Y_AXIS)
        add(summaryText)
        add(Box.createVerticalStrut(tokens.spacing.related))
        add(recoveryText)
        if (boundedDetails != null) {
          add(Box.createVerticalStrut(tokens.spacing.section))
          add(detailsControls)
          add(Box.createVerticalStrut(tokens.spacing.related))
          add(detailsScroll)
          add(Box.createVerticalStrut(tokens.spacing.compact))
          add(copyStatus)
        }
      }
  private var copyFailed = false

  init {
    getAccessibleContext().accessibleName = spec.summary
    getAccessibleContext().accessibleDescription = spec.recovery
    add(body, java.awt.BorderLayout.CENTER)
    desktopThemeChanged(tokens)
  }

  override fun applySurfaceTheme(tokens: DesktopThemeTokens) {
    body.background = tokens.surface
    detailsControls.background = tokens.surface
    styleLiteralText(summaryText, tokens.surface, tokens.danger)
    styleLiteralText(recoveryText, tokens.surface, tokens.primaryText)
    styleLiteralText(
        copyStatus,
        tokens.surface,
        if (copyFailed) tokens.danger else tokens.secondaryText,
    )
    detailsText.background = tokens.elevatedSurface
    detailsText.foreground = tokens.primaryText
    detailsText.caretColor = tokens.focus
    detailsScroll.border = BorderFactory.createLineBorder(tokens.border)
  }

  private fun showCopyStatus(message: String, failure: Boolean) {
    copyFailed = failure
    copyStatus.text = message
    copyStatus.foreground = if (failure) currentErrorColor() else currentTextColor()
    copyStatus.isVisible = true
    copyStatus.accessibleContext.accessibleDescription = message
    revalidate()
    repaint()
  }

  private fun currentErrorColor() = summaryText.foreground

  private fun currentTextColor() = recoveryText.foreground

  companion object {
    internal const val DETAILS_ROWS = 10
    internal const val DETAILS_COLUMNS = 72
  }
}

internal class DesktopInformationPanel<R>(
    internal val spec: DesktopInformationSpec<R>,
    internal val informationContent: JComponent,
    tokens: DesktopThemeTokens,
    onResult: (R) -> Unit,
) : DesktopDialogSurface<R>(spec.buttons, tokens, onResult) {
  internal val headingText =
      literalDialogText(spec.heading, "Information heading", columns = BODY_COLUMNS, bold = true)
  internal val descriptionText =
      literalDialogText(spec.description, "Information description", columns = BODY_COLUMNS)

  // Keep the introduction on the same full-width reading column as the content. BoxLayout sizes
  // text areas from their preferred columns on Aqua, which can leave an information header adrift
  // on the right after the dialog is widened.
  private val header =
      JPanel(java.awt.BorderLayout(0, tokens.spacing.related)).apply {
        add(headingText, java.awt.BorderLayout.NORTH)
        add(descriptionText, java.awt.BorderLayout.CENTER)
      }
  private val body =
      JPanel(java.awt.BorderLayout(0, tokens.spacing.section)).apply {
        add(header, java.awt.BorderLayout.NORTH)
        add(informationContent, java.awt.BorderLayout.CENTER)
      }

  init {
    if (informationContent.accessibleContext.accessibleName.isNullOrBlank()) {
      informationContent.accessibleContext.accessibleName = spec.contentAccessibleName
    }
    getAccessibleContext().accessibleName = spec.heading
    getAccessibleContext().accessibleDescription = spec.description
    add(body, java.awt.BorderLayout.CENTER)
    desktopThemeChanged(tokens)
  }

  override fun applySurfaceTheme(tokens: DesktopThemeTokens) {
    body.background = tokens.surface
    header.background = tokens.surface
    informationContent.background = tokens.surface
    styleLiteralText(headingText, tokens.surface, tokens.primaryText)
    styleLiteralText(descriptionText, tokens.surface, tokens.secondaryText)
    (informationContent as? DesktopThemeRefreshHook)?.desktopThemeChanged(tokens)
  }
}

/** Common owned-dialog chrome around self-contained content, without an introductory section. */
internal class DesktopContentPanel<R>(
    internal val spec: DesktopContentSpec<R>,
    internal val content: JComponent,
    tokens: DesktopThemeTokens,
    onResult: (R) -> Unit,
) : DesktopDialogSurface<R>(spec.buttons, tokens, onResult) {
  init {
    if (content.accessibleContext.accessibleName.isNullOrBlank()) {
      content.accessibleContext.accessibleName = spec.contentAccessibleName
    }
    getAccessibleContext().accessibleName = spec.title
    getAccessibleContext().accessibleDescription = spec.accessibleDescription
    add(content, java.awt.BorderLayout.CENTER)
    desktopThemeChanged(tokens)
  }

  override fun applySurfaceTheme(tokens: DesktopThemeTokens) {
    content.background = tokens.surface
    (content as? DesktopThemeRefreshHook)?.desktopThemeChanged(tokens)
  }
}

/**
 * Creates independently testable panels and, when requested, wraps them in owned modal dialogs.
 * Call [showDecision], [showForm], [showError], and [showInformation] on the EDT; Swing's modal
 * event loop keeps the UI responsive while returning one typed outcome.
 */
internal class DesktopDialogFactory(
    private val tokenProvider: () -> DesktopThemeTokens = {
      DesktopThemeTokens.capture(DesktopAppearance.SYSTEM)
    },
    private val clipboardWriter: DesktopClipboardWriter = SystemDesktopClipboardWriter,
) {
  fun <R> createDecisionPanel(
      spec: DesktopDecisionSpec<R>,
      onResult: (R) -> Unit,
  ): DesktopDecisionPanel<R> = DesktopDecisionPanel(spec, tokenProvider(), onResult)

  fun <R> createFormPanel(
      spec: DesktopFormSpec<R>,
      content: JComponent,
      onResult: (R) -> Unit,
  ): DesktopFormPanel<R> = DesktopFormPanel(spec, content, tokenProvider(), onResult)

  fun <R> createErrorPanel(
      spec: DesktopErrorSpec<R>,
      onResult: (R) -> Unit,
  ): DesktopErrorPanel<R> =
      DesktopErrorPanel(spec, tokenProvider(), clipboardWriter, onResult)

  fun <R> createInformationPanel(
      spec: DesktopInformationSpec<R>,
      content: JComponent,
      onResult: (R) -> Unit,
  ): DesktopInformationPanel<R> =
      DesktopInformationPanel(spec, content, tokenProvider(), onResult)

  fun <R> createContentPanel(
      spec: DesktopContentSpec<R>,
      content: JComponent,
      onResult: (R) -> Unit,
  ): DesktopContentPanel<R> = DesktopContentPanel(spec, content, tokenProvider(), onResult)

  fun <R> showDecision(owner: Window, spec: DesktopDecisionSpec<R>): R =
      showOwned(
          owner = owner,
          title = spec.title,
          accessibleDescription = spec.message,
          modality = spec.modality,
          resizable = false,
          cancelResult = spec.buttons.cancel.result,
      ) { complete -> createDecisionPanel(spec, complete) }

  fun <R> showForm(owner: Window, spec: DesktopFormSpec<R>, content: JComponent): R =
      showOwned(
          owner = owner,
          title = spec.title,
          accessibleDescription = spec.description,
          modality = spec.modality,
          resizable = true,
          cancelResult = spec.buttons.cancel.result,
      ) { complete -> createFormPanel(spec, content, complete) }

  fun <R> showError(owner: Window, spec: DesktopErrorSpec<R>): R =
      showOwned(
          owner = owner,
          title = spec.title,
          accessibleDescription = spec.recovery,
          modality = spec.modality,
          resizable = true,
          cancelResult = spec.buttons.cancel.result,
      ) { complete -> createErrorPanel(spec, complete) }

  fun <R> showInformation(
      owner: Window,
      spec: DesktopInformationSpec<R>,
      content: JComponent,
  ): R =
      showOwned(
          owner = owner,
          title = spec.title,
          accessibleDescription = spec.description,
          modality = spec.modality,
          resizable = true,
          cancelResult = spec.buttons.cancel.result,
      ) { complete -> createInformationPanel(spec, content, complete) }

  fun <R> showContent(
      owner: Window,
      spec: DesktopContentSpec<R>,
      content: JComponent,
  ): R =
      showOwned(
          owner = owner,
          title = spec.title,
          accessibleDescription = spec.accessibleDescription,
          modality = spec.modality,
          resizable = true,
          cancelResult = spec.buttons.cancel.result,
      ) { complete -> createContentPanel(spec, content, complete) }

  private fun <R> showOwned(
      owner: Window,
      title: String,
      accessibleDescription: String,
      modality: DesktopOwnedDialogModality,
      resizable: Boolean,
      cancelResult: R,
      panelFactory: ((R) -> Unit) -> DesktopDialogSurface<R>,
  ): R {
    check(SwingUtilities.isEventDispatchThread()) { "Owned dialogs must be shown on the EDT" }
    check(!GraphicsEnvironment.isHeadless()) { "Owned dialogs are unavailable in headless mode" }

    val previousFocus = KeyboardFocusManager.getCurrentKeyboardFocusManager().focusOwner
    val outcome = DesktopDialogOutcome<R>()
    val dialog = JDialog(owner, title, modality.awtType)
    lateinit var panel: DesktopDialogSurface<R>
    try {
      panel =
          panelFactory { result ->
            if (outcome.complete(result)) {
              dialog.dispose()
            }
          }
    } catch (failure: RuntimeException) {
      dialog.dispose()
      throw failure
    }

    dialog.defaultCloseOperation = JDialog.DO_NOTHING_ON_CLOSE
    dialog.isResizable = resizable
    dialog.contentPane = panel
    dialog.accessibleContext.accessibleName = title
    dialog.accessibleContext.accessibleDescription = accessibleDescription
    panel.bindRootPane(dialog.rootPane)
    dialog.addWindowListener(
        object : WindowAdapter() {
          override fun windowClosing(event: WindowEvent) {
            panel.cancel()
          }

          override fun windowOpened(event: WindowEvent) {
            (dialog.rootPane.defaultButton ?: panel.buttonBar.cancelButton).requestFocusInWindow()
          }
        })
    dialog.pack()
    dialog.setLocationRelativeTo(owner)
    constrainDesktopDialog(dialog)
    dialog.isVisible = true

    if (previousFocus?.isShowing == true) {
      previousFocus.requestFocusInWindow()
    }
    return outcome.valueOr(cancelResult)
  }
}

private class DesktopDialogOutcome<R> {
  private var completed = false
  private var value: R? = null

  fun complete(next: R): Boolean {
    if (completed) return false
    completed = true
    value = next
    return true
  }

  @Suppress("UNCHECKED_CAST")
  fun valueOr(fallback: R): R = if (completed) value as R else fallback
}

internal class DesktopDialogButtonBar<R>(
    internal val buttons: DesktopDialogButtons<R>,
    tokens: DesktopThemeTokens,
    onResult: (R) -> Unit,
) : JPanel(java.awt.FlowLayout(java.awt.FlowLayout.TRAILING, tokens.spacing.related, 0)) {
  private val entries =
      buttons.orderedActions().map { action ->
        action to
            literalButton(action.label, action.accessibleDescription).apply {
              action.mnemonic?.let { mnemonic = it }
              addActionListener { onResult(action.result) }
            }
      }

  internal val primaryButton = buttons.primary?.let(::buttonForAction)
  internal val cancelButton = buttonForAction(buttons.cancel)
  internal val defaultButton: JButton? =
      when (buttons.defaultButton) {
        DesktopDialogDefaultButton.PRIMARY -> primaryButton
        DesktopDialogDefaultButton.CANCEL -> cancelButton
        DesktopDialogDefaultButton.NONE -> null
      }

  init {
    entries.forEach { (_, button) -> add(button) }
    getAccessibleContext().accessibleName = "Dialog actions"
    getAccessibleContext().accessibleDescription =
        "Available outcomes; Escape activates ${buttons.cancel.label}"
    applyTheme(tokens)
  }

  fun buttonFor(result: R): JButton? =
      entries.firstOrNull { (action, _) -> action.result == result }?.second

  fun applyTheme(tokens: DesktopThemeTokens) {
    background = tokens.surface
    entries.forEach { (action, button) ->
      when {
        action === buttons.primary && action.destructive -> {
          button.background = tokens.danger
          button.foreground = contrastingText(tokens.danger)
        }
        action === buttons.primary -> {
          button.background = tokens.accent
          button.foreground = tokens.onAccent
        }
        action.destructive -> {
          button.background = tokens.elevatedSurface
          button.foreground = tokens.danger
        }
        else -> {
          button.background = tokens.elevatedSurface
          button.foreground = tokens.primaryText
        }
      }
      button.isOpaque = true
      button.horizontalAlignment = SwingConstants.CENTER
    }
  }

  private fun buttonForAction(action: DesktopDialogAction<R>): JButton =
      checkNotNull(entries.firstOrNull { (candidate, _) -> candidate === action }?.second)
}

internal fun configureDesktopDialogRootPane(
    rootPane: JRootPane,
    defaultButton: JButton?,
    cancel: () -> Unit,
) {
  rootPane.defaultButton = defaultButton
  val actionKey = "coffee-gb.desktop-dialog.cancel"
  rootPane
      .getInputMap(JComponent.WHEN_IN_FOCUSED_WINDOW)
      .put(KeyStroke.getKeyStroke(KeyEvent.VK_ESCAPE, 0), actionKey)
  rootPane.actionMap.put(
      actionKey,
      object : AbstractAction() {
        override fun actionPerformed(event: ActionEvent) {
          cancel()
        }
      },
  )
}

/** Pure, overflow-safe fitting policy used after packing and after expanding error details. */
internal fun fitDesktopDialogBounds(
    preferred: Rectangle,
    usable: Rectangle,
    minimumSize: Dimension = Dimension(360, 160),
): Rectangle {
  require(usable.width > 0 && usable.height > 0) { "Usable bounds must have positive dimensions" }
  require(minimumSize.width > 0 && minimumSize.height > 0) {
    "Dialog minimum dimensions must be positive"
  }
  val minimumWidth = minOf(minimumSize.width, usable.width)
  val minimumHeight = minOf(minimumSize.height, usable.height)
  val width = preferred.width.coerceIn(minimumWidth, usable.width)
  val height = preferred.height.coerceIn(minimumHeight, usable.height)
  val maximumX = usable.x.toLong() + usable.width - width
  val maximumY = usable.y.toLong() + usable.height - height
  val x = preferred.x.toLong().coerceIn(usable.x.toLong(), maximumX).toInt()
  val y = preferred.y.toLong().coerceIn(usable.y.toLong(), maximumY).toInt()
  return Rectangle(x, y, width, height)
}

private fun constrainDesktopDialog(dialog: JDialog) {
  val configuration = dialog.graphicsConfiguration ?: dialog.owner?.graphicsConfiguration ?: return
  val screen = configuration.bounds
  val insets = Toolkit.getDefaultToolkit().getScreenInsets(configuration)
  val usable =
      Rectangle(
          screen.x + insets.left,
          screen.y + insets.top,
          (screen.width - insets.left - insets.right).coerceAtLeast(1),
          (screen.height - insets.top - insets.bottom).coerceAtLeast(1),
      )
  dialog.bounds = fitDesktopDialogBounds(dialog.bounds, usable)
}

private fun revalidateAndConstrainDesktopDialog(component: Component) {
  (component as? JComponent)?.revalidate()
  component.repaint()
  val dialog = SwingUtilities.getWindowAncestor(component) as? JDialog ?: return
  dialog.pack()
  constrainDesktopDialog(dialog)
}

private fun literalDialogText(
    value: String,
    accessibleName: String,
    columns: Int,
    bold: Boolean = false,
): JTextArea =
    JTextArea(value).apply {
      isEditable = false
      isOpaque = false
      lineWrap = true
      wrapStyleWord = true
      this.columns = columns
      alignmentX = Component.LEFT_ALIGNMENT
      putClientProperty(DISABLE_HTML_PROPERTY, true)
      if (bold) font = font.deriveFont(Font.BOLD)
      getAccessibleContext().accessibleName = accessibleName
      getAccessibleContext().accessibleDescription = value
    }

private fun literalButton(
    value: String,
    accessibleDescription: String,
): JButton =
    JButton(value).apply {
      putClientProperty(DISABLE_HTML_PROPERTY, true)
      getAccessibleContext().accessibleName = value
      getAccessibleContext().accessibleDescription = accessibleDescription
    }

private fun verticalBody(
    first: JComponent,
    gap: Int,
    second: JComponent,
): JPanel =
    JPanel().apply {
      layout = BoxLayout(this, BoxLayout.Y_AXIS)
      add(first)
      add(Box.createVerticalStrut(gap))
      add(second)
    }

private fun styleLiteralText(text: JTextArea, background: java.awt.Color, foreground: java.awt.Color) {
  text.background = background
  text.foreground = foreground
  text.caretColor = foreground
}

private fun contrastingText(background: java.awt.Color): java.awt.Color =
    if (desktopContrastRatio(java.awt.Color.BLACK, background) >=
        desktopContrastRatio(java.awt.Color.WHITE, background)) {
      java.awt.Color.BLACK
    } else {
      java.awt.Color.WHITE
    }

private const val BODY_COLUMNS = 48
private const val DISABLE_HTML_PROPERTY = "html.disable"
private const val DETAILS_TRUNCATED_MARKER = "… Details truncated."
