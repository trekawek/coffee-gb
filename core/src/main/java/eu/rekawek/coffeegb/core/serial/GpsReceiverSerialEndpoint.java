package eu.rekawek.coffeegb.core.serial;

import eu.rekawek.coffeegb.core.Gameboy;
import eu.rekawek.coffeegb.core.memento.Memento;

import java.nio.charset.StandardCharsets;
import java.util.ArrayDeque;

/**
 * A deterministic Trimble Lassen SK II compatible GPS receiver for the CGB link port.
 *
 * <p>GPS Boy connects the receiver's TX line to CGB link-port pin 4 and samples it through
 * undocumented RP bit 4. It drives the receiver's RX line with a software UART on the serial
 * output pin. Both directions use 9600 baud, eight data bits, odd parity and one stop bit.
 * After the receiver is switched to TAIP, GPS Boy polls it with {@code QST}, {@code QAL},
 * {@code QPV} and {@code QTM} messages.
 *
 * <p>This endpoint follows the protocol documented in the original GPS Boy distribution. It
 * reports a fixed valid position in Seville, Spain so runs and save states remain deterministic.
 */
public class GpsReceiverSerialEndpoint implements SerialEndpoint {

    static final int UART_BIT_TICKS = 437;

    static final int STARTUP_DELAY_TICKS = Gameboy.TICKS_PER_SEC / 4;

    static final int STARTUP_BEACON_INTERVAL_TICKS = Gameboy.TICKS_PER_SEC;

    static final int RESPONSE_TURNAROUND_TICKS = UART_BIT_TICKS * 4;

    private static final String VERSION_RESPONSE = ">RVRGPSBOY<";

    private static final String STATUS_RESPONSE = ">RST00015A0200;*00<";

    private static final String ALTITUDE_RESPONSE = ">RAL00000+01520+02512;*00<";

    private static final String POSITION_RESPONSE =
            ">RPV00000+3738500-0059750000000012;*00<";

    private static final String TIME_RESPONSE = ">RTM1345230000601199000104100000;*00<";

    private final ArrayDeque<Integer> outputBytes = new ArrayDeque<>();

    private long ticks;

    private long nextStartupBeacon = STARTUP_DELAY_TICKS;

    private int startupBeacons;

    private int outputByte = -1;

    /** 0=start, 1-8=data, 9=parity, 10=stop. */
    private int outputBit = -1;

    private int outputTicksRemaining;

    private int outputDelayTicks;

    private boolean serialInputHigh = true;

    private int sb = 0xff;

    /** -1=waiting for start, 0-7=data, 8=parity, 9=stop. */
    private int receiveBit = -1;

    private int receiveByte;

    private int receiveOnes;

    private boolean receiveParityValid;

    private boolean capturingTaip;

    private final StringBuilder taipCommand = new StringBuilder();

    @Override
    public void tick() {
        ticks++;
        if (startupBeacons < 2 && ticks >= nextStartupBeacon) {
            // The real receiver emits periodic data after power-on. GPS Boy only checks
            // that two non-empty bursts arrive before it configures TAIP.
            queueAscii("GPS\r");
            startupBeacons++;
            nextStartupBeacon += STARTUP_BEACON_INTERVAL_TICKS;
        }

        if (outputDelayTicks > 0) {
            outputDelayTicks--;
            return;
        }
        if (outputTicksRemaining > 0 && --outputTicksRemaining > 0) {
            return;
        }
        advanceOutputBit();
    }

    @Override
    public boolean isSerialInputHigh() {
        return serialInputHigh;
    }

    @Override
    public void setSb(int sb) {
        this.sb = sb & 0xff;
    }

    @Override
    public void startSending() {
        receiveUartBit((sb >>> 7) & 1);
    }

    @Override
    public int sendBit() {
        return serialInputHigh ? 1 : 0;
    }

    @Override
    public int recvBit() {
        return -1;
    }

    private void receiveUartBit(int bit) {
        if (receiveBit == -1) {
            if (bit == 0) {
                receiveBit = 0;
                receiveByte = 0;
                receiveOnes = 0;
            }
            return;
        }
        if (receiveBit < 8) {
            if (bit != 0) {
                receiveByte |= 1 << receiveBit;
                receiveOnes++;
            }
            receiveBit++;
            return;
        }
        if (receiveBit == 8) {
            receiveParityValid = ((receiveOnes + bit) & 1) == 1;
            receiveBit++;
            return;
        }

        if (bit == 1 && receiveParityValid) {
            byteReceived(receiveByte);
        }
        receiveBit = -1;
    }

    private void byteReceived(int value) {
        char c = (char) (value & 0xff);
        if (c == '>') {
            capturingTaip = true;
            taipCommand.setLength(0);
        } else if (capturingTaip && c == '<') {
            capturingTaip = false;
            handleTaipCommand(taipCommand.toString());
        } else if (capturingTaip && taipCommand.length() < 64) {
            taipCommand.append(c);
        }
    }

    private void handleTaipCommand(String command) {
        switch (command) {
            case "QVR" -> queueResponse(VERSION_RESPONSE);
            case "QST" -> queueResponse(STATUS_RESPONSE);
            case "QAL" -> queueResponse(ALTITUDE_RESPONSE);
            case "QPV" -> queueResponse(POSITION_RESPONSE);
            case "QTM" -> queueResponse(TIME_RESPONSE);
            default -> {
                // Configuration messages (for example FPV00000000) need no reply.
            }
        }
    }

    private void queueAscii(String value) {
        for (byte b : value.getBytes(StandardCharsets.US_ASCII)) {
            outputBytes.addLast(b & 0xff);
        }
    }

    private void queueResponse(String value) {
        queueAscii(value);
        if (outputByte == -1) {
            // GPS Boy finishes one more bit-time of its send routine before it starts polling
            // the input. A real receiver also needs time to turn the half-duplex exchange around.
            outputDelayTicks = RESPONSE_TURNAROUND_TICKS;
        }
    }

    private void advanceOutputBit() {
        if (outputByte == -1) {
            if (outputBytes.isEmpty()) {
                serialInputHigh = true;
                return;
            }
            outputByte = outputBytes.removeFirst();
            outputBit = 0;
            serialInputHigh = false;
            outputTicksRemaining = UART_BIT_TICKS;
            return;
        }

        outputBit++;
        if (outputBit <= 8) {
            serialInputHigh = ((outputByte >>> (outputBit - 1)) & 1) != 0;
        } else if (outputBit == 9) {
            // Choose the parity bit so data + parity contains an odd number of ones.
            serialInputHigh = (Integer.bitCount(outputByte) & 1) == 0;
        } else if (outputBit == 10) {
            serialInputHigh = true;
        } else {
            outputByte = -1;
            outputBit = -1;
            advanceOutputBit();
            return;
        }
        outputTicksRemaining = UART_BIT_TICKS;
    }

    @Override
    public Memento<SerialEndpoint> saveToMemento() {
        int[] queued = outputBytes.stream().mapToInt(Integer::intValue).toArray();
        return new GpsReceiverMemento(ticks, nextStartupBeacon, startupBeacons, queued,
                outputByte, outputBit, outputTicksRemaining, outputDelayTicks, serialInputHigh, sb,
                receiveBit, receiveByte, receiveOnes, receiveParityValid, capturingTaip,
                taipCommand.toString());
    }

    @Override
    public void restoreFromMemento(Memento<SerialEndpoint> memento) {
        if (!(memento instanceof GpsReceiverMemento mem)) {
            throw new IllegalArgumentException("Invalid memento type");
        }
        ticks = mem.ticks;
        nextStartupBeacon = mem.nextStartupBeacon;
        startupBeacons = mem.startupBeacons;
        outputBytes.clear();
        for (int b : mem.outputBytes) {
            outputBytes.addLast(b);
        }
        outputByte = mem.outputByte;
        outputBit = mem.outputBit;
        outputTicksRemaining = mem.outputTicksRemaining;
        outputDelayTicks = mem.outputDelayTicks;
        serialInputHigh = mem.serialInputHigh;
        sb = mem.sb;
        receiveBit = mem.receiveBit;
        receiveByte = mem.receiveByte;
        receiveOnes = mem.receiveOnes;
        receiveParityValid = mem.receiveParityValid;
        capturingTaip = mem.capturingTaip;
        taipCommand.setLength(0);
        taipCommand.append(mem.taipCommand);
    }

    private record GpsReceiverMemento(long ticks, long nextStartupBeacon, int startupBeacons,
                                      int[] outputBytes, int outputByte, int outputBit,
                                      int outputTicksRemaining, int outputDelayTicks,
                                      boolean serialInputHigh, int sb,
                                      int receiveBit, int receiveByte, int receiveOnes,
                                      boolean receiveParityValid, boolean capturingTaip,
                                      String taipCommand) implements Memento<SerialEndpoint> {
    }
}
