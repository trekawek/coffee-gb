package eu.rekawek.coffeegb.swing.io;

import eu.rekawek.coffeegb.controller.Button;
import eu.rekawek.coffeegb.controller.ButtonPressEvent;
import eu.rekawek.coffeegb.controller.ButtonReleaseEvent;
import eu.rekawek.coffeegb.events.EventBus;

import java.awt.event.KeyEvent;
import java.awt.event.KeyListener;
import java.util.Map;

public class SwingController implements KeyListener {

  private final Map<Integer, Button> mapping;

  private final EventBus eventBus;

  public SwingController(Map<Integer, Button> mapping, EventBus eventBus) {
    this.mapping = mapping;
    this.eventBus = eventBus;
  }

  @Override
  public void keyTyped(KeyEvent e) {}

  @Override
  public void keyPressed(KeyEvent e) {
    Button b = getButton(e);
    if (b != null) {
      eventBus.post(new ButtonPressEvent(b));
    }
  }

  @Override
  public void keyReleased(KeyEvent e) {
    Button b = getButton(e);
    if (b != null) {
      eventBus.post(new ButtonReleaseEvent(b));
    }
  }

  private Button getButton(KeyEvent e) {
    return mapping.get(e.getKeyCode());
  }
}
