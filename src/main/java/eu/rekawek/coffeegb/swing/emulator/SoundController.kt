package eu.rekawek.coffeegb.swing.emulator

import eu.rekawek.coffeegb.swing.io.AudioSystemSoundOutput

class SoundController(private val sound: AudioSystemSoundOutput) {
    var enabled: Boolean
        get() = sound.isEnabled
        set(value) {
            sound.isEnabled = value
        }
}