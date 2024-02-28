package eu.rekawek.coffeegb.swing.gui.properties

import eu.rekawek.coffeegb.controller.ButtonListener
import java.awt.event.KeyEvent
import java.util.*

object ControllerProperties {
    fun getControllerMapping(properties: Properties): Map<Int, ButtonListener.Button> {
        val buttonToKey = EnumMap<ButtonListener.Button, Int>(ButtonListener.Button::class.java)

        buttonToKey[ButtonListener.Button.LEFT] = KeyEvent.VK_LEFT
        buttonToKey[ButtonListener.Button.RIGHT] = KeyEvent.VK_RIGHT
        buttonToKey[ButtonListener.Button.UP] = KeyEvent.VK_UP
        buttonToKey[ButtonListener.Button.DOWN] = KeyEvent.VK_DOWN
        buttonToKey[ButtonListener.Button.A] = KeyEvent.VK_Z
        buttonToKey[ButtonListener.Button.B] = KeyEvent.VK_X
        buttonToKey[ButtonListener.Button.START] = KeyEvent.VK_ENTER
        buttonToKey[ButtonListener.Button.SELECT] = KeyEvent.VK_BACK_SPACE

        for (k in properties.stringPropertyNames()) {
            val v = properties.getProperty(k)
            if (k.startsWith("btn_") && v.startsWith("VK_")) {
                val button = ButtonListener.Button.valueOf(k.substring(4).uppercase(Locale.getDefault()))
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