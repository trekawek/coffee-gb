package eu.rekawek.coffeegb.controller;

import eu.rekawek.coffeegb.AddressSpace;
import eu.rekawek.coffeegb.cpu.InterruptManager;
import eu.rekawek.coffeegb.memory.cart.Cartridge;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.HashSet;
import java.util.Set;

public class Joypad implements AddressSpace {

    private static final Logger LOG = LoggerFactory.getLogger(Joypad.class);

    private final InterruptManager interruptManager;

    private Set<ButtonListener.Button> buttons = new HashSet<>();

    private int p1;

    public Joypad(InterruptManager interruptManager, Controller controller) {
        this.interruptManager = interruptManager;
        controller.setButtonListener(new ButtonListener() {
            @Override
            public void onButtonPress(Button button) {
                interruptManager.requestInterrupt(InterruptManager.InterruptType.P10_13);
                buttons.add(button);
                LOG.info("buttons = {}", buttons);
            }

            @Override
            public void onButtonRelease(Button button) {
                buttons.remove(button);
                LOG.info("buttons = {}", buttons);
            }
        });
    }

    @Override
    public boolean accepts(int address) {
        return address == 0xff00;
    }

    @Override
    public void setByte(int address, int value) {
        p1 = value & 0b00110000;
        LOG.info("set p1 {}", Integer.toBinaryString(p1));
    }

    @Override
    public int getByte(int address) {
        int result = p1 | 0b00001111;
        for (ButtonListener.Button b : buttons) {
            if ((b.getLine() | p1) != 0) {
                result &= ~b.getMask();
            }
        }
        LOG.info("get p1 {}", Integer.toBinaryString(result));
        return result;
    }
}
