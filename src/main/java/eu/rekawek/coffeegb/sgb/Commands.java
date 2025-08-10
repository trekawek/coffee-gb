package eu.rekawek.coffeegb.sgb;

import eu.rekawek.coffeegb.events.Event;

public class Commands {

    private Commands() {
    }

    public static AbstractCommand toCommand(int[] packet) {
        int code = packet[0] / 8;
        return switch (code) {
            case 0x11 -> new MltReqCmd(packet);
            default -> null;
        };
    }

    public static class AbstractCommand implements Event {
        protected final int[] packet;

        protected AbstractCommand(int[] packet) {
            this.packet = packet;
        }

        public int getCode() {
            return packet[0] / 8;
        }

        public int getLength() {
            return packet[0] % 8;
        }
    }

    public static class Pal01Cmd extends AbstractCommand {
        protected Pal01Cmd(int[] packet) {
            super(packet);
        }


    }

    public static class MltReqCmd extends AbstractCommand {
        protected MltReqCmd(int[] packet) {
            super(packet);
        }

        public int getMultiplayerControl() {
            return packet[1];
        }
    }

}
