package eu.rekawek.coffeegb.swing

import eu.rekawek.coffeegb.controller.network.NetplayDiagnosticsExporter
import eu.rekawek.coffeegb.controller.network.NetplayRollbackMetricsSnapshot
import eu.rekawek.coffeegb.controller.network.NetplaySnapshotListener
import eu.rekawek.coffeegb.controller.network.NetplaySnapshotSource
import eu.rekawek.coffeegb.controller.network.v9.V9TransportMetricsSnapshot
import java.awt.BorderLayout
import java.awt.FlowLayout
import java.awt.Toolkit
import java.awt.datatransfer.StringSelection
import java.io.Closeable
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicLong
import javax.swing.JButton
import javax.swing.JCheckBox
import javax.swing.JLabel
import javax.swing.JPanel
import javax.swing.JScrollPane
import javax.swing.JTextArea
import javax.swing.SwingUtilities

fun interface V9DiagnosticsClipboard {
  fun copy(value: String)
}

/**
 * Real opt-in in-session diagnostics surface. Its model is immutable, publication is EDT-only,
 * and clipboard text is built exclusively by the bounded whitelist exporter.
 */
class V9SwingDiagnosticsPanel private constructor(
    transportSource: NetplaySnapshotSource<V9TransportMetricsSnapshot>,
    rollbackSource: NetplaySnapshotSource<NetplayRollbackMetricsSnapshot>?,
    private val clipboard: V9DiagnosticsClipboard,
    @Suppress("UNUSED_PARAMETER") edtGuard: Unit,
) : JPanel(BorderLayout()), Closeable {
  constructor(
      transportSource: NetplaySnapshotSource<V9TransportMetricsSnapshot>,
      rollbackSource: NetplaySnapshotSource<NetplayRollbackMetricsSnapshot>? = null,
      clipboard: V9DiagnosticsClipboard = SYSTEM_CLIPBOARD,
  ) : this(transportSource, rollbackSource, clipboard, requireEdt())

  private val closed = AtomicBoolean(false)
  private val generation = AtomicLong(1)
  @Volatile private var transport = transportSource.snapshot()
  @Volatile private var rollback = rollbackSource?.snapshot()
  internal val includeAddress = JCheckBox("Include numeric peer address")
  internal val copyButton = JButton("Copy sanitized diagnostics")
  internal val text = JTextArea(18, 52)
  internal val status = JLabel("Diagnostics are local and sanitized")
  private val subscriptions = mutableListOf<Closeable>()

  init {
    text.isEditable = false
    text.lineWrap = false
    val actions = JPanel(FlowLayout(FlowLayout.LEADING))
    actions.add(includeAddress)
    actions.add(copyButton)
    actions.add(status)
    add(JScrollPane(text), BorderLayout.CENTER)
    add(actions, BorderLayout.SOUTH)

    val current = generation.get()
    subscriptions +=
        transportSource.addListener(
            NetplaySnapshotListener { value ->
              publish(current) {
                transport = value
                render()
              }
            },
        )
    if (rollbackSource != null) {
      subscriptions +=
          rollbackSource.addListener(
              NetplaySnapshotListener { value ->
                publish(current) {
                  rollback = value
                  render()
                }
              },
          )
    }
    includeAddress.addActionListener { render() }
    copyButton.addActionListener {
      check(SwingUtilities.isEventDispatchThread())
      val value =
          NetplayDiagnosticsExporter.export(transport, rollback, includeAddress.isSelected)
      try {
        clipboard.copy(value)
        status.text = "Sanitized diagnostics copied"
      } catch (_: RuntimeException) {
        status.text = "Clipboard unavailable"
      }
    }
    render()
  }

  private fun render() {
    check(SwingUtilities.isEventDispatchThread())
    text.text = NetplayDiagnosticsExporter.export(transport, rollback, includeAddress.isSelected)
  }

  private fun publish(expected: Long, update: () -> Unit) {
    SwingUtilities.invokeLater {
      if (!closed.get() && generation.get() == expected) update()
    }
  }

  override fun close() {
    if (!closed.compareAndSet(false, true)) return
    generation.incrementAndGet()
    subscriptions.toList().forEach(Closeable::close)
    subscriptions.clear()
  }

  companion object {
    private fun requireEdt() {
      check(SwingUtilities.isEventDispatchThread()) {
        "v9 diagnostics panel must be constructed on the EDT"
      }
    }

    private val SYSTEM_CLIPBOARD = V9DiagnosticsClipboard { value ->
      check(SwingUtilities.isEventDispatchThread())
      Toolkit.getDefaultToolkit().systemClipboard.setContents(StringSelection(value), null)
    }
  }
}
