package eu.rekawek.coffeegb.swing

import eu.rekawek.coffeegb.controller.Controller.TurboFileAction
import javax.swing.JMenu
import javax.swing.JMenuItem

internal fun turboFileMenu(run: (TurboFileAction) -> Unit): JMenu = JMenu("Turbo File GB").apply {
  fun command(label: String, action: TurboFileAction) {
    add(JMenuItem(label).apply { addActionListener { run(action) } })
  }
  command("Import internal memory…", TurboFileAction.IMPORT_INTERNAL)
  command("Export internal memory…", TurboFileAction.EXPORT_INTERNAL)
  addSeparator()
  command("Insert memory card", TurboFileAction.INSERT_CARD)
  command("Import memory card…", TurboFileAction.IMPORT_CARD)
  command("Export memory card…", TurboFileAction.EXPORT_CARD)
  command("Eject memory card", TurboFileAction.EJECT_CARD)
  addSeparator()
  command("Enable write protection", TurboFileAction.PROTECT)
  command("Disable write protection", TurboFileAction.UNPROTECT)
}
