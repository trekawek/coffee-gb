package eu.rekawek.coffeegb.swing.io;

import eu.rekawek.coffeegb.core.events.EventBus;
import eu.rekawek.coffeegb.core.memory.cart.type.AccelerometerEvent;

import java.awt.*;
import java.awt.event.MouseEvent;
import java.awt.event.MouseMotionListener;

public class SwingAccelerometer implements MouseMotionListener {

    private final EventBus eventBus;

    private Dimension dimension;

    public SwingAccelerometer(EventBus eventBus, Dimension initialDimension) {
        this.eventBus = eventBus;
        this.dimension = initialDimension;
        eventBus.register(event -> dimension = event.preferredSize(), SwingDisplay.DisplaySizeUpdatedEvent.class);
    }

    @Override
    public void mouseDragged(MouseEvent e) {
    }

    @Override
    public void mouseMoved(MouseEvent e) {
        double x = (double) e.getPoint().x / dimension.width;
        double y = (double) e.getPoint().y / dimension.height;

        x = Math.max(0, Math.min(1, x)) * 2 - 1;
        y = Math.max(0, Math.min(1, y)) * 2 - 1;

        eventBus.post(new AccelerometerEvent(x, y));
    }
}
