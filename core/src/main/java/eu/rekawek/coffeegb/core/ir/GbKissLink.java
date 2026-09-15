package eu.rekawek.coffeegb.core.ir;

import java.io.ByteArrayOutputStream;
import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import java.util.BitSet;
import java.util.function.Consumer;

/**
 * Host GBKiss Link modem. It exchanges real cartridge IR pulses, never edits the game's RAM.
 * A transfer is a host peripheral operation, not portable machine state. Detach it before
 * restoring a machine; netplay uses Peer2PeerInfraredEndpoint instead.
 */
public final class GbKissLink implements InfraredEndpoint {
    public enum Status { WAITING, TRANSFERRING, COMPLETE, FAILED, CANCELLED }
    public record Progress(Status status, int transferred, int total, String message) { }

    private final Consumer<Progress> progress;
    private final Consumer<GbfFile> received;
    private final GbKissWire wire = new GbKissWire(this::fail);
    private Status status = Status.WAITING;
    private int transferred;
    private int total;
    private GbfFile outgoing;
    private int slot;
    private byte[] searchInput;
    private byte[] incoming;
    private BitSet incomingWritten;
    private byte[] titleIcon;
    private int metadataSize;
    private int cursor;
    private boolean sessionStarted;
    private boolean transferEnded;
    private int menuStatus;
    private int waitingTicks;

    private GbKissLink(Consumer<Progress> progress, Consumer<GbfFile> received) {
        this.progress = progress;
        this.received = received;
    }

    public static GbKissLink send(GbfFile file, Consumer<Progress> progress) {
        GbKissLink link = new GbKissLink(progress, ignored -> { });
        link.outgoing = file;
        link.total = file.payloadSize();
        link.report(Status.WAITING, "Select Receive in the game's GBKiss menu.");
        link.wire.delay(70_224);
        link.sendStage(0);
        return link;
    }

    public static GbKissLink receive(Consumer<Progress> progress, Consumer<GbfFile> received) {
        GbKissLink link = new GbKissLink(progress, received);
        link.report(Status.WAITING, "Select a file and Send in the game's GBKiss menu.");
        link.listen();
        return link;
    }

    public boolean isActive() { return status == Status.WAITING || status == Status.TRANSFERRING; }
    public Status status() { return status; }
    @Override public void tick() {
        if (status == Status.WAITING && ++waitingTicks > 60 * 4_194_304) {
            wire.clear();
            report(Status.FAILED, "No GBKiss partner responded within one minute.");
        }
        if (isActive()) wire.tick();
    }
    @Override public void setLightOn(boolean lightOn) { wire.input(lightOn); }
    @Override public boolean isLightOn() { return isActive() && wire.output(); }
    @Override public int performanceQuietSpanLimit(int requested) { return isActive() ? 0 : Math.max(0, requested); }

    @Override public void disconnect() {
        if (isActive()) report(Status.CANCELLED, "GBKiss transfer cancelled.");
        wire.clear();
    }

    @Override public void onMachineStateRestored() { disconnect(); }

    private void fail(String message) {
        if (!isActive()) return;
        wire.clear();
        if (status == Status.WAITING) {
            wire.delay(70_224);
            wire.then(() -> { if (outgoing != null) sendStage(0); else listen(); });
            return;
        }
        report(Status.FAILED, message);
    }

    private void report(Status next, String message) {
        status = next;
        progress.accept(new Progress(next, transferred, total, message));
    }

    private void sendStage(int stage) {
        if (!isActive()) return;
        switch (stage) {
            case 0 -> command(8, 0xce00, 0xce00, 16, 0, null, 17, reply -> {
                byte[] id = "GB KISS MENU ".getBytes(StandardCharsets.US_ASCII);
                if (!Arrays.equals(id, Arrays.copyOfRange(reply, 2, 15))) {
                    fail("The game is not listening in its GBKiss file menu.");
                    return;
                }
                slot = reply[15] & 0xff;
                report(Status.TRANSFERRING, "Sending GBF file…");
                sendStage(1);
            });
            case 1 -> flag(1, () -> sendStage(2));
            case 2 -> command(0, 0xce00, 0xce00, 1, 0, null, 0, reply -> {
                // Closing the discovery session lets the game redraw its transfer screen.
                wire.delay(2_097_152);
                wire.then(() -> sendStage(3));
            });
            case 3 -> command(2, 0xc700, 0xc50c, outgoing.titleIconSize(), 0,
                    outgoing.titleIcon(), 266, reply -> {
                if ((reply[0] & 0x10) == 0) {
                    fail("The game already has this file. Choose an empty slot or remove the existing file first.");
                    return;
                }
                searchInput = Arrays.copyOfRange(reply, 9, 265);
                sendStage(4);
            });
            case 4 -> flag(5, () -> sendStage(5));
            case 5 -> command(3, 0xc700, outgoing.payloadSize(), slot, outgoing.flags(), searchInput, 266,
                    reply -> {
                if ((reply[0] & 0x10) != 0) {
                    fail("The game could not allocate space for this GBF file.");
                    return;
                }
                command(8, 0xce00, 0xdffc, 2, reply[7] & 0xff, null, 3,
                        ignored -> sendStage(outgoing.historySize() == 0 ? 8 : 6));
            });
            case 6 -> command(4, 0xc500, 0xffd2, 46, 0, null, 9, reply -> sendStage(7));
            case 7 -> command(10, 0xc500, 0xc600, outgoing.historySize(), 0,
                    outgoing.history(), 9, reply -> sendStage(8));
            case 8 -> {
                if (transferred == total) {
                    sendStage(9);
                    return;
                }
                int length = Math.min(256, total - transferred);
                byte[] block = Arrays.copyOfRange(outgoing.payload(), transferred, transferred + length);
                command(10, 0xc500, 0xc600, length & 0xff, transferred + length < total ? 1 : 0,
                        block, 9, reply -> {
                    transferred += length;
                    report(Status.TRANSFERRING, "Sending GBF file…");
                    sendStage(8);
                });
            }
            case 9 -> command(4, 0xc50b, (3 - outgoing.metadataSize()) & 0xffff, 0, 0,
                    null, 9, reply -> sendStage(10));
            case 10 -> command(10, 0xc500, 0xc50a, 1, 0, new byte[]{(byte) outgoing.cartridgeCode()},
                    9, reply -> sendStage(11));
            case 11 -> flag(2, () -> sendStage(12));
            case 12 -> command(0, 0xce00, 0xce00, 1, 0, null, 0, reply -> {
                wire.clear();
                report(Status.COMPLETE, "GBF file sent.");
            });
            default -> throw new IllegalArgumentException("Unknown send stage");
        }
    }

    private void flag(int value, Runnable done) {
        command(11, 0xce00, 0xce00, 1, value == 5 ? slot : 0, new byte[]{(byte) value}, 1,
                reply -> {
                    if ((reply[0] & 0xff) != ((-value) & 0xff)) fail("GBKiss write was not acknowledged.");
                    else done.run();
                });
    }

    private void command(int op, int local, int remote, int length, int parameter,
                         byte[] data, int replyLength, Consumer<byte[]> done) {
        byte[] packet = join(new byte[]{0x48, 0x75}, registers(0x30, op, local, remote, length, parameter),
                data == null ? new byte[0] : checked(data));
        wire.send(packet, () -> {
            if (replyLength == 0) done.accept(new byte[0]);
            else readReply(replyLength, op == 11, done);
        });
    }

    private void readReply(int length, boolean continuation, Consumer<byte[]> done) {
        ByteArrayOutputStream buffer = new ByteArrayOutputStream();
        java.util.function.IntPredicate accept = value -> {
            buffer.write(value);
            return buffer.size() == length;
        };
        Runnable complete = () -> {
            byte[] reply = buffer.toByteArray();
            if (length == 1 || (length == 266
                    ? valid(reply, 0, 9) && valid(reply, 9, 266) : valid(reply, 0, length))) {
                done.accept(reply);
            } else fail("GBKiss reply checksum failed.");
        };
        if (continuation) wire.receiveContinuation(accept, complete);
        else wire.receive(accept, complete);
    }

    private void listen() {
        if (!isActive()) return;
        ByteArrayOutputStream buffer = new ByteArrayOutputStream();
        int[] length = {11};
        wire.receive(value -> {
            buffer.write(value);
            if (buffer.size() == 11) {
                byte[] header = buffer.toByteArray();
                if (header[0] != 0x48 || header[1] != 0x75 || !valid(header, 2, 11)) {
                    fail("Invalid GBKiss command or checksum.");
                    return true;
                }
                int op = header[3] & 0xff;
                int count = header[8] & 0xff;
                if (op == 3 || op == 6) count = 256;
                else if (op != 2 && op != 10 && op != 11) count = -1;
                else if (count == 0) count = 256;
                if (count >= 0) length[0] += count + 1;
            }
            return buffer.size() == length[0];
        }, () -> {
            if (!isActive()) return;
            byte[] packet = buffer.toByteArray();
            if (packet.length > 11 && !valid(packet, 11, packet.length)) {
                fail("GBKiss file data checksum failed.");
                return;
            }
            try {
                receiveCommand(packet);
            } catch (IllegalArgumentException e) {
                fail(e.getMessage());
            }
        });
    }

    private void receiveCommand(byte[] packet) {
        int op = packet[3] & 0xff;
        int local = GbfFile.word(packet, 4);
        int remote = GbfFile.word(packet, 6);
        int length = packet[8] & 0xff;
        int parameter = packet[9] & 0xff;
        byte[] data = packet.length == 11 ? new byte[0] : Arrays.copyOfRange(packet, 11, packet.length - 1);
        switch (op) {
            case 8 -> {
                // HL identifies our source; DE is the game's destination and may be on its stack.
                if (local == 0xce00 && (length == 16 || length == 2)) {
                    byte[] identity = join(new byte[]{(byte) menuStatus, 2},
                            "GB KISS MENU ".getBytes(StandardCharsets.US_ASCII), new byte[]{0});
                    reply(checked(Arrays.copyOf(identity, length)));
                } else fail("Unsupported GBKiss remote read.");
            }
            case 11 -> {
                if (remote != 0xce00 || data.length != 1) {
                    fail("Unsupported GBKiss remote write.");
                    return;
                }
                menuStatus = data[0] & 0xff;
                transferEnded = menuStatus == 2;
                wire.sendContinuation(new byte[]{(byte) -data[0]}, this::listen);
            }
            case 0 -> {
                if (!sessionStarted) {
                    sessionStarted = true;
                    report(Status.TRANSFERRING, "Receiving GBF file…");
                    listen();
                } else if (incoming != null && transferEnded
                        && incomingWritten.nextClearBit(0) >= incoming.length) {
                    GbfFile file = new GbfFile(incoming);
                    wire.clear();
                    received.accept(file);
                    report(Status.COMPLETE, "GBF file received.");
                } else fail("GBKiss session ended before the whole file arrived.");
            }
            case 2 -> {
                if (data.length < 2 || data.length > 245) throw new IllegalArgumentException("Invalid GBF title/icon length.");
                titleIcon = data;
                byte[] echo = new byte[256];
                echo[10] = (byte) data.length;
                System.arraycopy(data, 0, echo, 11, data.length);
                reply(join(registers(0x90, 0, 0xc500, 0xc400 | data.length, 0, 0), checked(echo)));
            }
            case 3 -> {
                if (titleIcon == null) throw new IllegalArgumentException("GBKiss file has no title/icon.");
                if (incoming != null) throw new IllegalArgumentException("A GBKiss transfer can receive only one file.");
                metadataSize = 5 + titleIcon.length + ((parameter & 1) == 0 ? 0 : GbfFile.HISTORY_SIZE);
                total = remote;
                if (total + metadataSize > GbfFile.MAX_SIZE) throw new IllegalArgumentException("GBKiss file is too large.");
                incoming = new byte[total + metadataSize];
                cursor = metadataSize;
                putWord(incoming, 0, incoming.length);
                incoming[2] = (byte) parameter;
                incoming[3] = (byte) 0xff;
                incoming[4] = (byte) titleIcon.length;
                System.arraycopy(titleIcon, 0, incoming, 5, titleIcon.length);
                new GbfFile(incoming); // Validate declared metadata before acknowledging allocation.
                incomingWritten = new BitSet(incoming.length);
                incomingWritten.set(0, 3);
                incomingWritten.set(4, 5 + titleIcon.length);
                byte[] echo = new byte[256];
                putWord(echo, 3, metadataSize);
                putWord(echo, 5, metadataSize);
                putWord(echo, 7, incoming.length);
                echo[9] = incoming[2];
                echo[10] = (byte) 0xff;
                echo[11] = incoming[4];
                System.arraycopy(titleIcon, 0, echo, 12, Math.min(titleIcon.length, 244));
                reply(join(registers(0x80, 0, 0xc500, 0xc50c, 0, 0), checked(echo)));
            }
            case 4 -> {
                requireIncoming();
                cursor = metadataSize + (short) remote;
                reply(registers(0x40, 1, 0xc500, remote, metadataSize & 0xff, metadataSize >> 8));
            }
            case 10 -> {
                requireIncoming();
                if (cursor < 0 || cursor + data.length > incoming.length) {
                    throw new IllegalArgumentException("GBKiss write exceeds the declared file size.");
                }
                System.arraycopy(data, 0, incoming, cursor, data.length);
                incomingWritten.set(cursor, cursor + data.length);
                transferred = incomingWritten.get(metadataSize, incoming.length).cardinality();
                cursor += data.length;
                report(Status.TRANSFERRING, "Receiving GBF file…");
                reply(registers(0x80, 0, local, 0xc400 + data.length, length, parameter));
            }
            default -> fail("Unsupported GBKiss file command.");
        }
    }

    private void requireIncoming() {
        if (incoming == null) throw new IllegalArgumentException("GBKiss file data arrived before its metadata.");
    }

    private void reply(byte[] bytes) { wire.delay(512); wire.send(bytes, this::listen); }

    static byte[] registers(int status, int op, int local, int remote, int length, int parameter) {
        return checked(new byte[]{(byte) status, (byte) op, (byte) local, (byte) (local >> 8),
                (byte) remote, (byte) (remote >> 8), (byte) length, (byte) parameter});
    }

    static byte[] checked(byte[] bytes) {
        byte[] result = Arrays.copyOf(bytes, bytes.length + 1);
        for (byte value : bytes) result[bytes.length] -= value;
        return result;
    }

    private static boolean valid(byte[] bytes, int start, int end) {
        int sum = 0;
        for (int i = start; i < end; i++) sum += bytes[i] & 0xff;
        return (sum & 0xff) == 0;
    }

    static byte[] join(byte[]... blocks) {
        ByteArrayOutputStream buffer = new ByteArrayOutputStream();
        for (byte[] block : blocks) buffer.writeBytes(block);
        return buffer.toByteArray();
    }

    private static void putWord(byte[] bytes, int offset, int value) {
        bytes[offset] = (byte) value;
        bytes[offset + 1] = (byte) (value >> 8);
    }
}
