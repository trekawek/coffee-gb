package eu.rekawek.coffeegb.core.serial;

import eu.rekawek.coffeegb.core.memento.Memento;

/**
 * Emulates the Game Boy Printer (MGB-007), the thermal printer used by Game Boy Camera,
 * Pokemon Trading Card Game, the Pokedex print feature and many others (issue #77). The
 * protocol implementation is ported from SameBoy's {@code Core/printer.c}.
 *
 * <p>The game is always the link-clock master. Each packet it clocks out has the shape
 * {@code 88 33 | cmd | compression | len_lo len_hi | data... | chk_lo chk_hi | 00 00}, and
 * for every byte the printer clocks a reply byte back. All reply bytes are 0x00 except the
 * two trailing dummy bytes, where the printer answers 0x81 (device alive) and then its status
 * byte. The reply is pipelined by one byte on the wire, exactly as on hardware: the value
 * latched while byte <i>k</i> is received is shifted out during byte <i>k+1</i>.
 *
 * <p>Commands: {@code 0x01} init, {@code 0x02} print (4 data bytes: sheets, margins, palette,
 * exposure), {@code 0x04} data (a band of 2bpp tile rows, optionally run-length encoded),
 * {@code 0x0F} status inquiry. A completed print is handed to {@link PrintCallback} as a
 * 160-pixel-wide ARGB band.
 */
public class GameboyPrinterSerialEndpoint implements SerialEndpoint {

    /** Receives a finished band each time the game issues a print command. */
    public interface PrintCallback {
        /**
         * @param argb 160*height ARGB pixels (top row first)
         * @param width always 160
         * @param height band height in pixels (a multiple of 16, or 0)
         * @param topMargin paper feed before the band, in 1/16 of a tile row
         * @param bottomMargin paper feed after the band; a non-zero value ends the sheet
         * @param exposure print density/exposure the game requested (0-127)
         */
        void print(int[] argb, int width, int height, int topMargin, int bottomMargin, int exposure);
    }

    private static final int MAGIC1 = 0x88;
    private static final int MAGIC2 = 0x33;

    private static final int CMD_INIT = 0x01;
    private static final int CMD_PRINT = 0x02;
    private static final int CMD_DATA = 0x04;

    private static final int MAX_COMMAND_LENGTH = 0x280;
    private static final int WIDTH = 160;
    private static final int IMAGE_HEIGHT = 200;

    // packet state machine, in wire order
    private static final int ST_MAGIC1 = 0;
    private static final int ST_MAGIC2 = 1;
    private static final int ST_ID = 2;
    private static final int ST_COMPRESSION = 3;
    private static final int ST_LENGTH_LOW = 4;
    private static final int ST_LENGTH_HIGH = 5;
    private static final int ST_DATA = 6;
    private static final int ST_CHECKSUM_LOW = 7;
    private static final int ST_CHECKSUM_HIGH = 8;
    private static final int ST_ACTIVE = 9;
    private static final int ST_STATUS = 10;

    // the four Game Boy shades the printer renders (white .. black), as ARGB
    private static final int[] SHADES = {0xFFFFFFFF, 0xFFAAAAAA, 0xFF555555, 0xFF000000};

    private final transient PrintCallback callback;

    private int state = ST_MAGIC1;
    private int commandId;
    private boolean compression;
    private int lengthLeft;
    private final int[] commandData = new int[MAX_COMMAND_LENGTH];
    private int commandLength;
    private int checksum;
    private int status;
    private int byteToSend;

    private final int[] image = new int[WIDTH * IMAGE_HEIGHT];
    private int imageOffset;

    private int compressionRunLength;
    private boolean compressionRunIsCompressed;

    // number of status polls the printer reports "printing" before it reports "done"
    private int printCountdown;

    // wire framing: the reply latched for the byte in progress, and the byte being clocked in
    private int currentReply;
    private int sendBits;
    private int sb;

    public GameboyPrinterSerialEndpoint(PrintCallback callback) {
        this.callback = callback;
    }

    @Override
    public void setSb(int sb) {
        this.sb = sb & 0xff;
    }

    @Override
    public void startSending() {
        // the reply for this byte is whatever was prepared while the previous byte arrived
        currentReply = byteToSend;
        sendBits = 0;
        byteReceived(sb);
    }

    @Override
    public int sendBit() {
        int bit = (currentReply >> (7 - sendBits)) & 1;
        if (++sendBits == 8) {
            sendBits = 0;
        }
        return bit;
    }

    @Override
    public int recvBit() {
        return -1;
    }

    private void byteReceived(int b) {
        byteToSend = 0;
        switch (state) {
            case ST_MAGIC1:
                if (b != MAGIC1) {
                    return;
                }
                status &= ~1;
                commandLength = 0;
                checksum = 0;
                break;
            case ST_MAGIC2:
                if (b != MAGIC2) {
                    if (b != MAGIC1) {
                        state = ST_MAGIC1;
                    }
                    return;
                }
                break;
            case ST_ID:
                commandId = b & 0x0f;
                break;
            case ST_COMPRESSION:
                compression = (b & 1) != 0;
                compressionRunLength = 0;
                break;
            case ST_LENGTH_LOW:
                lengthLeft = b;
                break;
            case ST_LENGTH_HIGH:
                lengthLeft |= (b & 3) << 8;
                break;
            case ST_DATA:
                appendData(b);
                lengthLeft--;
                break;
            case ST_CHECKSUM_LOW:
                checksum ^= b;
                break;
            case ST_CHECKSUM_HIGH:
                checksum ^= b << 8;
                if (checksum != 0) {
                    status |= 1; // checksum error
                    state = ST_MAGIC1;
                    return;
                }
                byteToSend = 0x81; // device alive
                break;
            case ST_ACTIVE:
                if ((commandId & 0x0f) == CMD_INIT) {
                    byteToSend = 0; // games expect the init command's status reply to be 0
                } else {
                    if (status == 6) { // printing
                        if (printCountdown > 0) {
                            printCountdown--;
                        }
                        if (printCountdown == 0) {
                            status = 4; // done
                        }
                    }
                    byteToSend = status;
                }
                break;
            case ST_STATUS:
                state = ST_MAGIC1;
                handleCommand();
                return;
            default:
                break;
        }

        if (state >= ST_ID && state < ST_CHECKSUM_LOW) {
            checksum = (checksum + b) & 0xffff;
        }
        if (state != ST_DATA) {
            state++;
        }
        if (state == ST_DATA && lengthLeft == 0) {
            state++;
        }
    }

    private void appendData(int b) {
        if (commandLength == MAX_COMMAND_LENGTH) {
            return;
        }
        if (!compression) {
            commandData[commandLength++] = b;
            return;
        }
        if (compressionRunLength == 0) {
            compressionRunIsCompressed = (b & 0x80) != 0;
            compressionRunLength = (b & 0x7f) + 1 + (compressionRunIsCompressed ? 1 : 0);
        } else if (compressionRunIsCompressed) {
            while (compressionRunLength > 0) {
                commandData[commandLength++] = b;
                compressionRunLength--;
                if (commandLength == MAX_COMMAND_LENGTH) {
                    compressionRunLength = 0;
                }
            }
        } else {
            commandData[commandLength++] = b;
            compressionRunLength--;
        }
    }

    private void handleCommand() {
        switch (commandId) {
            case CMD_INIT:
                status = 0;
                imageOffset = 0;
                break;
            case CMD_PRINT:
                if (commandLength == 4) {
                    doPrint();
                }
                break;
            case CMD_DATA:
                if (commandLength == MAX_COMMAND_LENGTH) {
                    appendBand();
                }
                break;
            default:
                break;
        }
    }

    private void appendBand() {
        imageOffset %= image.length;
        status = 8; // received a full data band
        int b = 0;
        // two tile rows of 20 tiles, each tile 8x8 2bpp
        for (int row = 0; row < 2; row++) {
            for (int tileX = 0; tileX < WIDTH / 8; tileX++) {
                for (int y = 0; y < 8; y++) {
                    int low = commandData[b++];
                    int high = commandData[b++];
                    for (int x = 0; x < 8; x++) {
                        int shade = ((low >> 7) & 1) | (((high >> 7) & 1) << 1);
                        low = (low << 1) & 0xff;
                        high = (high << 1) & 0xff;
                        image[imageOffset + tileX * 8 + x + y * WIDTH] = shade;
                    }
                }
            }
            imageOffset += 8 * WIDTH;
        }
    }

    private void doPrint() {
        status = 6; // printing
        int height = imageOffset / WIDTH;
        int palette = commandData[2];
        if (height > 0 && callback != null) {
            int[] argb = new int[imageOffset];
            for (int i = 0; i < imageOffset; i++) {
                argb[i] = SHADES[(palette >> (image[i] * 2)) & 3];
            }
            int topMargin = (commandData[1] >> 4) & 0x0f;
            int bottomMargin = commandData[1] & 0x0f;
            int exposure = commandData[3] & 0x7f;
            callback.print(argb, WIDTH, height, topMargin, bottomMargin, exposure);
        }
        // report "printing" for roughly one status poll per printed tile row before "done"
        printCountdown = Math.max(1, height / 8);
        imageOffset = 0;
    }

    @Override
    public Memento<SerialEndpoint> saveToMemento() {
        return new PrinterMemento(state, commandId, compression, lengthLeft, commandData.clone(),
                commandLength, checksum, status, byteToSend, image.clone(), imageOffset,
                compressionRunLength, compressionRunIsCompressed, printCountdown, currentReply,
                sendBits, sb);
    }

    @Override
    public void restoreFromMemento(Memento<SerialEndpoint> memento) {
        if (!(memento instanceof PrinterMemento mem)) {
            throw new IllegalArgumentException("Invalid memento type");
        }
        state = mem.state;
        commandId = mem.commandId;
        compression = mem.compression;
        lengthLeft = mem.lengthLeft;
        System.arraycopy(mem.commandData, 0, commandData, 0, commandData.length);
        commandLength = mem.commandLength;
        checksum = mem.checksum;
        status = mem.status;
        byteToSend = mem.byteToSend;
        System.arraycopy(mem.image, 0, image, 0, image.length);
        imageOffset = mem.imageOffset;
        compressionRunLength = mem.compressionRunLength;
        compressionRunIsCompressed = mem.compressionRunIsCompressed;
        printCountdown = mem.printCountdown;
        currentReply = mem.currentReply;
        sendBits = mem.sendBits;
        sb = mem.sb;
    }

    private record PrinterMemento(int state, int commandId, boolean compression, int lengthLeft,
                                  int[] commandData, int commandLength, int checksum, int status,
                                  int byteToSend, int[] image, int imageOffset,
                                  int compressionRunLength, boolean compressionRunIsCompressed,
                                  int printCountdown, int currentReply, int sendBits, int sb)
            implements Memento<SerialEndpoint> {
    }
}
