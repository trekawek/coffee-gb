package eu.rekawek.coffeegb.core.ir;

import java.util.ArrayDeque;
import java.util.function.Consumer;
import java.util.function.IntConsumer;
import java.util.function.IntPredicate;

/**
 * GBKiss pulse and byte framing, clocked in master dots (4.194 MHz).
 * Handshake bytes have a stop pulse; block bytes synchronize both diodes before each byte.
 * Timings follow the measured software windows documented in Dan Docs, GB KISS LINK.
 */
final class GbKissWire {
    private static final int TIMEOUT = 4_194_304;
    private final ArrayDeque<Action> actions = new ArrayDeque<>();
    private final Consumer<String> failure;
    private boolean input;
    private boolean output;
    private long clock;
    private long lastRise;
    private long riseNumber;
    private int age;
    private long generation;

    GbKissWire(Consumer<String> failure) { this.failure = failure; }

    void input(boolean light) {
        if (light && !input) {
            lastRise = clock;
            riseNumber++;
        }
        input = light;
    }

    boolean output() { return output; }

    void clear() {
        generation++;
        actions.clear();
        output = false;
        age = 0;
    }

    void tick() {
        clock++;
        Action action = actions.peek();
        if (action == null) return;
        if (action.tick()) {
            if (actions.peek() == action) actions.removeFirst();
            age = 0;
        } else if (++age > TIMEOUT) {
            clear();
            failure.accept("No response from the game. Select Send or Receive in its GBKiss menu and try again.");
        }
    }

    void delay(int ticks) {
        actions.add(new Action() {
            int remaining = ticks;
            public boolean tick() { return --remaining <= 0; }
        });
    }

    void then(Runnable callback) {
        actions.add(() -> { callback.run(); return true; });
    }

    private void light(boolean on) { then(() -> output = on); }
    private void waitLight(boolean on) { actions.add(() -> input == on); }

    private void pulse(int period) {
        light(true);
        delay(200);
        light(false);
        delay(period - 200);
    }

    private void writeByte(int value, boolean handshake) {
        if (handshake) {
            // The modem sends a leading dummy; cartridge implementations may omit it.
            pulse(560);
        } else {
            light(true);
            waitLight(true);
            delay(400);
            light(false);
            waitLight(false);
            delay(160);
        }
        for (int bit = 7; bit >= 0; bit--) pulse((value & (1 << bit)) == 0 ? 460 : 720);
        if (handshake) pulse(1220);
        light(true);
        delay(200);
        light(false);
        // A block's trailing zero must fall before the next byte raises the sync diode.
        delay(handshake ? 100 : 260);
    }

    private void readByte(boolean handshake, IntConsumer consumer) {
        actions.add(new Action() {
            long seen = -1;
            long previous;
            int intervals;
            int value;
            boolean finished;

            public boolean tick() {
                if (finished) {
                    if (input) return false;
                    output = false;
                    consumer.accept(value);
                    return true;
                }
                if (seen == -1) {
                    seen = riseNumber;
                    if (!handshake) output = true;
                    // A peer can raise its sync LED before this task becomes current.
                    if (input) start();
                } else if (seen != riseNumber) {
                    seen = riseNumber;
                    if (intervals == 0) {
                        start();
                    } else {
                        long elapsed = lastRise - previous;
                        previous = lastRise;
                        if (handshake && elapsed >= 1000 && elapsed <= 1500 && intervals >= 9) {
                            finished = true;
                        } else if (handshake || intervals > 1 && intervals <= 9) {
                            if (elapsed < 300 || elapsed > 950) {
                                clearAfterFailure("Invalid GBKiss infrared pulse");
                                return false;
                            }
                            // Keep the last eight periods before the stop, ignoring any dummy.
                            value = ((value << 1) | (elapsed > 590 ? 1 : 0)) & 0xff;
                        }
                        intervals++;
                        if (!handshake && intervals == 10) finished = true;
                    }
                }
                if (!handshake && intervals > 0 && !input) output = false;
                return false;
            }

            private void start() {
                previous = lastRise;
                intervals = 1;
                if (!handshake) output = true;
            }
        });
    }

    // The tick dispatcher tolerates callbacks replacing the current action queue.
    private void clearAfterFailure(String message) {
        clear();
        failure.accept(message);
    }

    private void expect(int value) {
        readByte(true, actual -> {
            if (actual != value) {
                clearAfterFailure("GBKiss handshake failed");
            }
        });
    }

    void send(byte[] bytes, Runnable done) {
        writeByte(0xaa, true);
        expect(0x55);
        writeByte(0xc3, true);
        expect(0x3c);
        then(() -> sendByte(bytes, 0, done));
    }

    void sendContinuation(byte[] bytes, Runnable done) {
        then(() -> sendByte(bytes, 0, done));
    }

    private void sendByte(byte[] bytes, int index, Runnable done) {
        if (index == bytes.length) {
            then(done);
        } else {
            writeByte(bytes[index] & 0xff, false);
            then(() -> sendByte(bytes, index + 1, done));
        }
    }

    void receive(IntPredicate accept, Runnable done) {
        expect(0xaa);
        writeByte(0x55, true);
        expect(0xc3);
        writeByte(0x3c, true);
        then(() -> receiveByte(accept, done));
    }

    /** RAM-write acknowledgements continue the same synchronized byte stream. */
    void receiveContinuation(IntPredicate accept, Runnable done) {
        then(() -> receiveByte(accept, done));
    }

    private void receiveByte(IntPredicate accept, Runnable done) {
        readByte(false, value -> {
            long before = generation;
            boolean complete = accept.test(value);
            // A rejected packet can clear this stream and schedule a fresh listening attempt.
            if (generation != before) return;
            if (complete) then(done);
            else then(() -> receiveByte(accept, done));
        });
    }

    private interface Action { boolean tick(); }
}
