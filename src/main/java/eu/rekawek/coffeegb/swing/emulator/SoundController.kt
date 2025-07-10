package eu.rekawek.coffeegb.swing.emulator

import eu.rekawek.coffeegb.swing.io.AudioSystemSound

class SoundController(private val sound: AudioSystemSound) {
  var enabled: Boolean
    get() = sound.isEnabled
    set(value) {
      sound.isEnabled = value
    }
}
