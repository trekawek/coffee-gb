package eu.rekawek.coffeegb.controller.properties

import eu.rekawek.coffeegb.core.joypad.Button
import java.awt.event.KeyEvent
import java.util.*

object ControllerProperties {
  fun getControllerMapping(properties: Properties): Map<Int, Button> {
    val buttonToKey = EnumMap<Button, Int>(Button::class.java)

    buttonToKey[Button.LEFT] = KeyEvent.VK_LEFT
    buttonToKey[Button.RIGHT] = KeyEvent.VK_RIGHT
    buttonToKey[Button.UP] = KeyEvent.VK_UP
    buttonToKey[Button.DOWN] = KeyEvent.VK_DOWN
    buttonToKey[Button.A] = KeyEvent.VK_Z
    buttonToKey[Button.B] = KeyEvent.VK_X
    buttonToKey[Button.START] = KeyEvent.VK_ENTER
    buttonToKey[Button.SELECT] = KeyEvent.VK_BACK_SPACE

    for (k in properties.stringPropertyNames()) {
      val v = properties.getProperty(k)
      if (k.startsWith("btn_") && v.startsWith("VK_")) {
        val button = Button.valueOf(k.substring(4).uppercase(Locale.getDefault()))
        val field = KeyEvent::class.java.getField(properties.getProperty(k))
        if (field.type != Int::class.javaPrimitiveType) {
          continue
        }
        val value = field.getInt(null)
        buttonToKey[button] = value
      }
    }

    return buttonToKey.entries.associate { (k, v) -> v to k }
  }
}
