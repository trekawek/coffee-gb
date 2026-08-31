package eu.rekawek.coffeegb.core.serial;

import eu.rekawek.coffeegb.core.memento.Memento;

import eu.rekawek.coffeegb.core.hardware.ClockSpec;
import eu.rekawek.coffeegb.core.state.ComponentState;

import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.ArrayDeque;
import java.util.Locale;
import java.util.Objects;

/**
 * A deterministic Trimble Lassen SK II compatible GPS receiver for the CGB link port.
 *
 * <p>GPS Boy connects the receiver's TX line to CGB link-port pin 4 and samples it through
 * undocumented RP bit 4. It drives the receiver's RX line with a software UART on the serial
 * output pin. Both directions use 9600 baud, eight data bits, odd parity and one stop bit.
 * After the receiver is switched to TAIP, GPS Boy polls it with {@code QST}, {@code QAL},
 * {@code QPV} and {@code QTM} messages.
 *
 * <p>This endpoint follows the protocol documented in the original GPS Boy distribution. Its
 * default source reports a fixed valid position so ordinary runs and save states remain
 * deterministic. Hosts may explicitly inject a live, thread-safe {@link GpsDataSource}.
 */
public class GpsReceiverSerialEndpoint implements SerialEndpoint {

    /** @deprecated Use an endpoint instance configured from the owning session clock. */
    @Deprecated
    static final int UART_BIT_TICKS = 437;

    /** @deprecated Use an endpoint instance configured from the owning session clock. */
    @Deprecated
    static final int STARTUP_DELAY_TICKS = 4_194_304 / 4;

    /** @deprecated Use an endpoint instance configured from the owning session clock. */
    @Deprecated
    static final int STARTUP_BEACON_INTERVAL_TICKS = 4_194_304;

    /** @deprecated Use an endpoint instance configured from the owning session clock. */
    @Deprecated
    static final int RESPONSE_TURNAROUND_TICKS = UART_BIT_TICKS * 4;

    private final int uartBitTicks;

    private final long secondStartupBeaconTick;

    private final int responseTurnaroundTicks;

    private static final String VERSION_RESPONSE = ">RVRGPSBOY<";

    private static final String STATUS_RESPONSE = ">RST00015A0200;*00<";

    private static final String ALTITUDE_RESPONSE = ">RAL00000+01520+02512;*00<";

    private static final String POSITION_RESPONSE =
            ">RPV00000+3738500-0059750000000012;*00<";

    private static final String TIME_RESPONSE = ">RTM1345230000601199000104100000;*00<";

    private static final long MAX_LIVE_FIX_AGE_MILLIS = 120_000L;

    private static final double METERS_PER_SECOND_TO_MILES_PER_HOUR = 2.2369362920544;

    /** Null deliberately selects the legacy deterministic response strings above. */
    private final GpsDataSource dataSource;

    private final ArrayDeque<Integer> outputBytes = new ArrayDeque<>();

    private long ticks;

    private long nextStartupBeacon;

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

    public GpsReceiverSerialEndpoint() {
        this(ClockSpec.LEGACY, null);
    }

    public GpsReceiverSerialEndpoint(ClockSpec clockSpec) {
        this(clockSpec, null);
    }

    public GpsReceiverSerialEndpoint(ClockSpec clockSpec, GpsDataSource dataSource) {
        Objects.requireNonNull(clockSpec, "clockSpec");
        this.dataSource = dataSource;
        uartBitTicks = Math.toIntExact(clockSpec.ticksPerRateUnit(9_600, ClockSpec.Rounding.NEAREST));
        if (uartBitTicks <= 0) {
            throw new IllegalArgumentException("GPS UART timing requires at least one tick per bit");
        }
        nextStartupBeacon = clockSpec.ticksForRateUnits(1, 4, ClockSpec.Rounding.FLOOR);
        // Absolute rational deadlines avoid accumulating a rounded one-second interval.
        secondStartupBeaconTick = clockSpec.ticksForRateUnits(5, 4, ClockSpec.Rounding.FLOOR);
        responseTurnaroundTicks = Math.multiplyExact(uartBitTicks, 4);
    }

    @Override
    public void tick() {
        ticks++;
        if (startupBeacons < 2 && ticks >= nextStartupBeacon) {
            // The real receiver emits periodic data after power-on. GPS Boy only checks
            // that two non-empty bursts arrive before it configures TAIP.
            queueAscii("GPS\r");
            startupBeacons++;
            if (startupBeacons == 1) {
                nextStartupBeacon = secondStartupBeaconTick;
            }
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

    /**
     * Keeps PERFORMANCE epochs inside one stable UART level. The event tick which queues a
     * startup beacon or advances the UART frame remains scalar, while the countdown leading to
     * it can be applied arithmetically.
     */
    @Override
    public int performanceQuietSpanLimit(int requested) {
        if (requested <= 0) {
            return 0;
        }
        int span = requested;
        if (startupBeacons < 2) {
            long ticksBeforeStartup = nextStartupBeacon - ticks - 1;
            if (ticksBeforeStartup <= 0) {
                return 0;
            }
            span = (int) Math.min(span, ticksBeforeStartup);
        }
        if (outputDelayTicks > 0) {
            return Math.min(span, outputDelayTicks);
        }
        if (outputTicksRemaining > 0) {
            return Math.min(span, outputTicksRemaining - 1);
        }
        if (outputByte != -1 || !outputBytes.isEmpty()) {
            return 0;
        }
        return span;
    }

    @Override
    public int performanceExternalClockWaitSpanLimit(int requested) {
        return performanceQuietSpanLimit(requested);
    }

    @Override
    public boolean tickPerformanceQuietSpan(int span) {
        if (span <= 0 || performanceQuietSpanLimit(span) < span) {
            return false;
        }
        tickPerformanceQuietSpanTrusted(span);
        return true;
    }

    @Override
    public void tickPerformanceQuietSpanTrusted(int span) {
        if (span <= 0) {
            return;
        }
        ticks = Math.addExact(ticks, span);
        if (outputDelayTicks > 0) {
            outputDelayTicks -= span;
        } else if (outputTicksRemaining > 0) {
            outputTicksRemaining -= span;
        }
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
            case "QST" -> queueResponse(dataSource == null ? STATUS_RESPONSE : liveStatusResponse());
            case "QAL" -> queueResponse(dataSource == null ? ALTITUDE_RESPONSE : liveAltitudeResponse());
            case "QPV" -> queueResponse(dataSource == null ? POSITION_RESPONSE : livePositionResponse());
            case "QTM" -> queueResponse(dataSource == null ? TIME_RESPONSE : liveTimeResponse());
            default -> {
                // Configuration messages (for example FPV00000000) need no reply.
            }
        }
    }

    private String liveStatusResponse() {
        LiveObservation observation = liveObservation();
        // 00 means the receiver is producing fixes; 08 means no usable fix is currently present.
        return observation.valid ? STATUS_RESPONSE : ">RST08015A0200;*00<";
    }

    private String livePositionResponse() {
        LiveObservation observation = liveObservation();
        GpsFix fix = observation.fix;
        int latitude = observation.valid
                ? scaledCoordinate(fix.latitudeDegrees(), 90.0) : 0;
        int longitude = observation.valid
                ? scaledCoordinate(fix.longitudeDegrees(), 180.0) : 0;
        int speed = observation.valid && fix.hasSpeed()
                ? boundedRounded(fix.speedMetersPerSecond()
                        * METERS_PER_SECOND_TO_MILES_PER_HOUR, 0, 999) : 0;
        int heading = observation.valid && fix.hasBearing()
                ? normalizedHeading(fix.bearingDegrees()) : 0;
        return String.format(Locale.ROOT, ">RPV%05d%+08d%+09d%03d%03d%d%d;*00<",
                observation.fixSecondsOfDay(), latitude, longitude, speed, heading,
                observation.positionSource(), observation.ageIndicator);
    }

    private String liveAltitudeResponse() {
        LiveObservation observation = liveObservation();
        GpsFix fix = observation.fix;
        boolean altitudeValid = observation.valid && fix.hasAltitude();
        int altitude = altitudeValid
                ? boundedRounded(fix.altitudeMeters(), -99_999, 99_999) : 0;
        int verticalSpeed = altitudeValid && fix.hasVerticalSpeed()
                ? boundedRounded(fix.verticalSpeedMetersPerSecond()
                        * METERS_PER_SECOND_TO_MILES_PER_HOUR, -999, 999) : 0;
        return String.format(Locale.ROOT, ">RAL%05d%+06d%+04d%d%d;*00<",
                observation.fixSecondsOfDay(), altitude, verticalSpeed,
                altitudeValid ? 1 : 9, altitudeValid ? observation.ageIndicator : 0);
    }

    private String liveTimeResponse() {
        LiveObservation observation = liveObservation();
        LocalDateTime utc = LocalDateTime.ofInstant(
                Instant.ofEpochMilli(observation.nowMillis), ZoneOffset.UTC);
        // GPS currently leads UTC by 18 seconds. The valid flag tells the receiver that the
        // transmitted time fields are UTC, not raw GPS-system time.
        return String.format(Locale.ROOT,
                ">RTM%02d%02d%02d%03d%02d%02d%04d18%d%02d1%05d;*00<",
                utc.getHour(), utc.getMinute(), utc.getSecond(),
                utc.getNano() / 1_000_000, utc.getDayOfMonth(), utc.getMonthValue(),
                utc.getYear(), observation.positionSource(),
                observation.valid ? (observation.fix.hasAltitude() ? 4 : 3) : 0, 0);
    }

    private LiveObservation liveObservation() {
        long nowMillis;
        try {
            nowMillis = dataSource.currentTimeMillis();
        } catch (RuntimeException ignored) {
            nowMillis = System.currentTimeMillis();
        }
        if (nowMillis <= 0) {
            nowMillis = System.currentTimeMillis();
        }
        GpsFix fix;
        try {
            fix = dataSource.currentFix();
        } catch (RuntimeException ignored) {
            fix = null;
        }
        if (fix == null) {
            fix = GpsFix.unavailable(nowMillis);
        }
        long ageMillis = Math.max(0L, nowMillis - fix.timestampMillis());
        boolean valid = fix.hasPosition() && ageMillis <= MAX_LIVE_FIX_AGE_MILLIS;
        int ageIndicator = !valid ? 0 : ageMillis < 10_000L ? 2 : 1;
        return new LiveObservation(fix, nowMillis, valid, ageIndicator);
    }

    private static int scaledCoordinate(double degrees, double maximum) {
        double bounded = Math.max(-maximum, Math.min(maximum, degrees));
        return (int) Math.round(bounded * 100_000.0);
    }

    private static int boundedRounded(double value, int minimum, int maximum) {
        long rounded = Math.round(value);
        return (int) Math.max(minimum, Math.min(maximum, rounded));
    }

    private static int normalizedHeading(double degrees) {
        int rounded = (int) Math.round(degrees) % 360;
        return rounded < 0 ? rounded + 360 : rounded;
    }

    private record LiveObservation(GpsFix fix, long nowMillis, boolean valid,
                                   int ageIndicator) {

        int fixSecondsOfDay() {
            long timestamp = valid ? fix.timestampMillis() : nowMillis;
            LocalDateTime utc = LocalDateTime.ofInstant(
                    Instant.ofEpochMilli(timestamp), ZoneOffset.UTC);
            return utc.toLocalTime().toSecondOfDay();
        }

        int positionSource() {
            if (!valid) {
                return 9;
            }
            return fix.hasAltitude() ? 1 : 0;
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
            outputDelayTicks = responseTurnaroundTicks;
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
            outputTicksRemaining = uartBitTicks;
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
        outputTicksRemaining = uartBitTicks;
    }

    @Override
    public ComponentState<SerialEndpoint> captureState() {
        int[] queued = outputBytes.stream().mapToInt(Integer::intValue).toArray();
        return new GpsReceiverState(ticks, nextStartupBeacon, startupBeacons, queued,
                outputByte, outputBit, outputTicksRemaining, outputDelayTicks, serialInputHigh, sb,
                receiveBit, receiveByte, receiveOnes, receiveParityValid, capturingTaip,
                taipCommand.toString());
    }

    @Override
    public void restoreState(ComponentState<SerialEndpoint> state) {
        if (!(state instanceof GpsReceiverState mem)) {
            throw new IllegalArgumentException("Invalid state type");
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

    private record GpsReceiverState(long ticks, long nextStartupBeacon, int startupBeacons,
                                      int[] outputBytes, int outputByte, int outputBit,
                                      int outputTicksRemaining, int outputDelayTicks,
                                      boolean serialInputHigh, int sb,
                                      int receiveBit, int receiveByte, int receiveOnes,
                                      boolean receiveParityValid, boolean capturingTaip,
                                      String taipCommand) implements ComponentState<SerialEndpoint> {
    }

    /** Importer-only compatibility record for released local snapshots. */
    private record GpsReceiverMemento(long ticks, long nextStartupBeacon, int startupBeacons,
                                      int[] outputBytes, int outputByte, int outputBit,
                                      int outputTicksRemaining, int outputDelayTicks,
                                      boolean serialInputHigh, int sb,
                                      int receiveBit, int receiveByte, int receiveOnes,
                                      boolean receiveParityValid, boolean capturingTaip,
                                      String taipCommand) implements Memento<SerialEndpoint> {
    }
}
