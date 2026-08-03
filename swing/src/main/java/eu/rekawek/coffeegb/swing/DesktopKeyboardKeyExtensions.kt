package eu.rekawek.coffeegb.swing

import eu.rekawek.coffeegb.controller.properties.ApplicationSettings

/** Desktop-only convenience view used by Swing widgets and their tests. */
internal val ApplicationSettings.KeyboardKey.code: Int
  get() = DesktopKeyboardKeyAdapter.keyCode(this)
