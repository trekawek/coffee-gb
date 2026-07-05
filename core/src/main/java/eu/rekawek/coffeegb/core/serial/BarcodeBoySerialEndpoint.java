package eu.rekawek.coffeegb.core.serial;

import eu.rekawek.coffeegb.core.memento.Memento;

/**
 * Emulates the Namco Barcode Boy (バーコードボーイ) link-port barcode scanner used by
 * Battle Space and Barcode Taisen Bardigun (issue #70). Protocol reverse-engineered by
 * Shonumi (https://shonumi.github.io/articles/art7.html):
 *
 * <ol>
 *   <li><b>Handshake</b> - the game, as the clock master, sends {@code 0x10 0x07 0x10 0x07}
 *       one byte at a time; the scanner replies {@code 0xFF 0xFF 0x10 0x07} (only the last
 *       two bytes are checked). A disconnected/off scanner replies {@code 0x00} and the
 *       game shows an error.</li>
 *   <li><b>Wait</b> - the game switches to an external clock (it becomes the slave) and
 *       waits for a card to be swiped.</li>
 *   <li><b>Scan</b> - on a swipe the scanner drives the clock and sends 30 bytes: STX
 *       ({@code 0x02}), the 13 ASCII digits of the JAN-13 barcode, ETX ({@code 0x03}), the
 *       whole 15-byte frame twice for redundancy. Afterwards the handshake is required
 *       again.</li>
 * </ol>
 *
 * <p>Call {@link #scan(String)} with a 13-digit barcode to simulate a swipe; the frame is
 * streamed the next time the game is listening on the external clock.
 */
public class BarcodeBoySerialEndpoint implements SerialEndpoint {

    private static final int[] HANDSHAKE_REPLY = {0xFF, 0xFF, 0x10, 0x07};

    private static final int STX = 0x02;

    private static final int ETX = 0x03;

    // external-clock bit period: the scanner drives serial at the standard link rate
    // (~8 kHz = one bit per 512 T-cycles); the game's serial interrupt keeps up easily
    private static final int BIT_PERIOD = 512;

    private enum State {
        HANDSHAKE,
        READY,
        SENDING
    }

    private State state = State.HANDSHAKE;

    // handshake (game is master, internal clock)
    private int handshakeByte;

    private int sendBitIndex;

    // scan data (scanner is master, external clock)
    private int[] data;

    private int dataByte;

    private int recvBitIndex;

    private int clockDivider;

    private boolean transferArmed;

    private volatile int[] pending;

    /**
     * Queues a barcode to be sent the next time the game listens on the external clock.
     *
     * @param jan13 the 13 digit JAN-13/EAN-13 number of the card
     */
    public void scan(String jan13) {
        String digits = jan13 == null ? "" : jan13.trim();
        if (digits.length() != 13 || !digits.chars().allMatch(Character::isDigit)) {
            throw new IllegalArgumentException("Barcode must be 13 digits: " + jan13);
        }
        int[] frame = new int[30];
        for (int rep = 0; rep < 2; rep++) {
            int base = rep * 15;
            frame[base] = STX;
            for (int i = 0; i < 13; i++) {
                frame[base + 1 + i] = digits.charAt(i);
            }
            frame[base + 14] = ETX;
        }
        this.pending = frame;
    }

    public boolean isScanPending() {
        return pending != null || state == State.SENDING;
    }

    @Override
    public void setSb(int sb) {
    }

    @Override
    public void startSending() {
        sendBitIndex = 0;
    }

    @Override
    public int sendBit() {
        // internal clock: the game is master and drives the handshake
        int replyByte = HANDSHAKE_REPLY[handshakeByte];
        int bit = (replyByte >> (7 - sendBitIndex)) & 1;
        if (++sendBitIndex == 8) {
            sendBitIndex = 0;
            if (++handshakeByte == HANDSHAKE_REPLY.length) {
                handshakeByte = 0;
                state = State.READY;
            }
        }
        return bit;
    }

    @Override
    public void setExternalTransfer(boolean inProgress) {
        this.transferArmed = inProgress;
    }

    @Override
    public int recvBit() {
        // external clock: the scanner is master and streams the barcode
        if (state == State.READY && pending != null) {
            data = pending;
            pending = null;
            dataByte = 0;
            recvBitIndex = 0;
            clockDivider = 0;
            state = State.SENDING;
        }
        if (state != State.SENDING) {
            return -1;
        }
        // clock a bit only while the game has a transfer armed, so each byte lines up
        // with the game's reception (it clears SC bit 7 after 8 bits and re-arms in its
        // serial interrupt); this keeps the byte framing exact
        if (!transferArmed) {
            clockDivider = 0;
            return -1;
        }
        if (++clockDivider < BIT_PERIOD) {
            return -1;
        }
        clockDivider = 0;
        int bit = (data[dataByte] >> (7 - recvBitIndex)) & 1;
        if (++recvBitIndex == 8) {
            recvBitIndex = 0;
            if (++dataByte == data.length) {
                // the scanner requires the handshake again after a scan
                data = null;
                dataByte = 0;
                state = State.HANDSHAKE;
                handshakeByte = 0;
            }
        }
        return bit;
    }

    @Override
    public Memento<SerialEndpoint> saveToMemento() {
        return new BarcodeBoyMemento(state, handshakeByte, sendBitIndex,
                data == null ? null : data.clone(), dataByte, recvBitIndex, clockDivider);
    }

    @Override
    public void restoreFromMemento(Memento<SerialEndpoint> memento) {
        if (!(memento instanceof BarcodeBoyMemento mem)) {
            throw new IllegalArgumentException("Invalid memento type");
        }
        this.state = mem.state;
        this.handshakeByte = mem.handshakeByte;
        this.sendBitIndex = mem.sendBitIndex;
        this.data = mem.data == null ? null : mem.data.clone();
        this.dataByte = mem.dataByte;
        this.recvBitIndex = mem.recvBitIndex;
        this.clockDivider = mem.clockDivider;
    }

    private record BarcodeBoyMemento(State state, int handshakeByte, int sendBitIndex, int[] data,
                                     int dataByte, int recvBitIndex, int clockDivider)
            implements Memento<SerialEndpoint> {
    }
}
