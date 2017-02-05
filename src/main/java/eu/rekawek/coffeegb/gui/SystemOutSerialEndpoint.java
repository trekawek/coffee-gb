package eu.rekawek.coffeegb.gui;

import eu.rekawek.coffeegb.serial.SerialEndpoint;

public class SystemOutSerialEndpoint implements SerialEndpoint {

    @Override
    public int transfer(int b) {
        System.out.print((char) b);
        return 0;
    }

}
