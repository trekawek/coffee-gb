package eu.rekawek.coffeegb.core.experimental.ppu_pipeline;

/**
 * A detached, deliberately small DMG pixel-path hypothesis.
 *
 * <p>There is one control path and one forward data path. A tile launch advances fetch-X
 * immediately, its independently latched low/high bytes become a FIFO row four dots later,
 * and a FIFO pop becomes an LCD pixel after another three dots. Object fetches stop FIFO pops,
 * but do not stop the already launched scanout stages. Nothing in the graph can move a pixel
 * backwards or modify one after it has left the FIFO.
 *
 * <p>This is not a second renderer. It intentionally omits the separately bounded cones
 * listed by {@link #incompleteBehaviorMask()}; its immutable raw output composes with the
 * {@link DmgLcdOutputSignalCone} palette/LCDC consumer. Its purpose is to make the latency
 * hypothesis executable before it is allowed anywhere near production.
 *
 * <p><strong>Evidence label: fitted hypothesis, self-test, and bounded production
 * differential.</strong> The fixed byte/scanout latencies, object-wait threshold, and token
 * invalidation operations are candidate rules, not recovered gates. Existing Mealybug integration
 * results still exercise the production renderer. This graph cannot support deleting the dual
 * renderer until it shadows complete hardware-reference traces, including every behavior named by
 * {@link #incompleteBehaviorMask()}.
 */
final class ForwardDmgPixelPipeline {

    static final int CONTROL_TO_FIFO_DOTS = 4;

    static final int RAW_TO_LCD_DOTS = 3;

    /** A disabled-window comparator token remains useful while its raw pixels are in scanout. */
    static final int WINDOW_TRIGGER_RETENTION_DOTS = RAW_TO_LCD_DOTS;

    static final int OUTSIDE_MIDLINE_FINE_SCX_REPHASE = 1;

    static final int OUTSIDE_DISABLED_WINDOW_INSERTION = 1 << 1;

    static final int OUTSIDE_PALETTE_AND_LCDC_OUTPUT_MUX = 1 << 2;

    static final int OUTSIDE_OVERLAPPING_OBJECT_PRIORITY = 1 << 3;

    static final int OUTSIDE_CGB = 1 << 4;

    static final int OUTSIDE_MODE3_END_AND_STAT = 1 << 5;

    /**
     * Falsifier: a trigger accepted after its matching raw token reached the LCD needs a
     * different retained signal; this graph will not recreate an already committed pixel.
     */
    static final int OUTSIDE_WINDOW_TRIGGER_AFTER_SCANOUT_COMMIT = 1 << 6;

    /** An active window source cannot currently retire on a later LCDC.5 falling edge. */
    static final int OUTSIDE_ACTIVE_WINDOW_SOURCE_DEACTIVATION = 1 << 7;

    private static final int TILE_LOW_SAMPLE_OFFSET = 0;

    private static final int TILE_HIGH_SAMPLE_OFFSET = 3;

    private static final int FIFO_CAPACITY = 32;

    private static final int LCD_CAPTURE_CAPACITY = 256;

    private static final int VRAM_START = 0x8000;

    private static final int VRAM_SIZE = 0x2000;

    private final int[] vram = new int[VRAM_SIZE];

    private final int[] vramReadCount = new int[VRAM_SIZE];

    private final int[] fifoBackground = new int[FIFO_CAPACITY];

    private final int[] fifoObject = new int[FIFO_CAPACITY];

    private int fifoHead;

    private int fifoSize;

    /** Immutable raw pixels in flight between FIFO pop and the LCD interface. */
    private final int[] scanoutRaw = new int[RAW_TO_LCD_DOTS];

    private final boolean[] scanoutValid = new boolean[RAW_TO_LCD_DOTS];

    private final boolean[] scanoutOutputEnable = new boolean[RAW_TO_LCD_DOTS];

    private final int[] scanoutLogicalX = new int[RAW_TO_LCD_DOTS];

    private final int[] lcdRaw = new int[LCD_CAPTURE_CAPACITY];

    private int dot;

    /** Tile-control coordinate: advances by eight when a tile flight is launched. */
    private int fetchX;

    /** Raw-data coordinate: advances when a pixel leaves the FIFO. */
    private int fifoPopX;

    /** Panel coordinate: advances when a scanout token reaches the LCD. */
    private int lcdX;

    private int lastFifoPopDot = -1;

    private int lastLcdDot = -1;

    private int lastPoppedRaw = -1;

    private boolean flushDrive;

    private boolean flushLastDot;

    private boolean stallLastDot;

    private int windowCompareX;

    private int fineScx;

    private int fineScxRemaining;

    private boolean windowYMaster;

    private boolean windowEnabled;

    private boolean windowActive;

    private int windowX;

    private int windowTileId;

    private int windowTileLine;

    private boolean windowTriggerValid;

    private int windowTriggerX;

    private int windowTriggerWx;

    private int windowTriggerAge;

    private int windowTriggerDot = -1;

    private int windowActivationDot = -1;

    private boolean windowTriggerLastDot;

    private boolean windowActivationLastDot;

    /* Tile flight. The tile identity is control; each byte has its own address/data latch. In
       particular, no speculative high-byte read exists to be refreshed later. A first window
       tile is merely this ordinary flight launched on the same dot as a FIFO flush. */
    private boolean tileValid;

    private int tileLaunchDot;

    private int tileId;

    private int tileLine;

    private boolean unsignedTileData = true;

    private boolean tileLowAddressValid;

    private int tileLowAddress;

    private boolean tileLowDataValid;

    private int tileLowData;

    private boolean tileHighAddressValid;

    private int tileHighAddress;

    private boolean tileHighDataValid;

    private int tileHighData;

    private int tileLowSampleDot = -1;

    private int tileHighSampleDot = -1;

    /* The background fetch phase is enough to test the object wait cone. Values 0..4 wait for
       HIGH_T2; values 5..7 may start the fixed six-dot object sequence immediately. */
    private int backgroundFetchPhase;

    private ObjectState objectState = ObjectState.IDLE;

    private int objectStep;

    private int objectTileId;

    private int objectLine;

    private boolean objectXFlip;

    private boolean tallObjects;

    private boolean objectsEnabled = true;

    private boolean objectAbortLastDot;

    private boolean objectLowAddressValid;

    private int objectLowAddress;

    private boolean objectLowDataValid;

    private int objectLowData;

    private boolean objectHighAddressValid;

    private int objectHighAddress;

    private boolean objectHighDataValid;

    private int objectHighData;

    private int objectLowSampleDot = -1;

    private int objectHighSampleDot = -1;

    private enum ObjectState {
        IDLE,
        WAIT_FOR_BACKGROUND_HIGH,
        FETCHING,
        HIGH_DATA_PENDING
    }

    static int incompleteBehaviorMask() {
        return OUTSIDE_MIDLINE_FINE_SCX_REPHASE
                | OUTSIDE_DISABLED_WINDOW_INSERTION
                | OUTSIDE_PALETTE_AND_LCDC_OUTPUT_MUX
                | OUTSIDE_OVERLAPPING_OBJECT_PRIORITY
                | OUTSIDE_CGB
                | OUTSIDE_MODE3_END_AND_STAT
                | OUTSIDE_WINDOW_TRIGGER_AFTER_SCANOUT_COMMIT
                | OUTSIDE_ACTIVE_WINDOW_SOURCE_DEACTIVATION;
    }

    void writeVram(int address, int value) {
        vram[index(address)] = value & 0xff;
    }

    int vramReadCount(int address) {
        return vramReadCount[index(address)];
    }

    void setUnsignedTileData(boolean unsignedTileData) {
        this.unsignedTileData = unsignedTileData;
    }

    void setTallObjects(boolean tallObjects) {
        this.tallObjects = tallObjects;
    }

    void setObjectsEnabled(boolean objectsEnabled) {
        this.objectsEnabled = objectsEnabled;
    }

    /** Arms the DMG WX comparator. The persistent WY equality is represented by one latch. */
    void configureWindow(int windowX, int tileId, int line) {
        this.windowX = windowX & 0xff;
        this.windowTileId = tileId & 0xff;
        this.windowTileLine = line & 7;
        this.windowYMaster = true;
        this.windowActive = false;
        this.windowTriggerValid = false;
    }

    /** Comparator enable only; retiring an active window source is a separately named boundary. */
    void setWindowEnabled(boolean windowEnabled) {
        this.windowEnabled = windowEnabled;
    }

    void seedWindowCompareX(int windowCompareX) {
        if (fifoPopX != 0 || windowTriggerValid || windowActive) {
            throw new IllegalStateException("window comparator has already advanced");
        }
        this.windowCompareX = windowCompareX;
    }

    /**
     * Selects how many initial raw pops are valid for fetch timing but disabled at scanout.
     * A write after raw popping began would need a separately derived phase cone and is an
     * explicit falsifier of this bounded graph.
     */
    void setFineScx(int fineScx) {
        if (fineScx < 0 || fineScx > 7) {
            throw new IllegalArgumentException("fine SCX");
        }
        if (fifoPopX != 0 && fineScx != this.fineScx) {
            throw new UnsupportedOperationException("mid-line fine-SCX rephase is outside the graph");
        }
        this.fineScx = fineScx;
        this.fineScxRemaining = fineScx;
    }

    /** Launches an ordinary tile flight on the current control dot. */
    void launchTile(int tileId, int line) {
        if (tileValid) {
            throw new IllegalStateException("tile flight already valid");
        }
        tileValid = true;
        tileLaunchDot = dot;
        this.tileId = tileId & 0xff;
        tileLine = line & 7;
        tileLowAddressValid = false;
        tileLowDataValid = false;
        tileHighAddressValid = false;
        tileHighDataValid = false;
        fetchX += 8;
    }

    void requestFifoFlush() {
        flushDrive = true;
    }

    void seedRawFifo(int pixels, int rawBackground) {
        if (tileValid || objectState != ObjectState.IDLE) {
            throw new IllegalStateException("cannot seed a running graph");
        }
        for (int i = 0; i < pixels; i++) {
            enqueueRaw(rawBackground & 3, 0);
        }
    }

    void seedBackgroundFetchPhase(int phase) {
        if (phase < 0 || phase > 7 || objectState != ObjectState.IDLE) {
            throw new IllegalArgumentException("background fetch phase");
        }
        backgroundFetchPhase = phase;
    }

    void requestObject(int tileId, int line, boolean xFlip) {
        if (objectState != ObjectState.IDLE) {
            throw new IllegalStateException("object fetch already active");
        }
        if (!objectsEnabled) {
            return;
        }
        objectTileId = tileId & 0xff;
        objectLine = line & 0xf;
        objectXFlip = xFlip;
        objectStep = 0;
        objectLowAddressValid = false;
        objectLowDataValid = false;
        objectHighAddressValid = false;
        objectHighDataValid = false;
        objectState = backgroundFetchPhase < 5
                ? ObjectState.WAIT_FOR_BACKGROUND_HIGH : ObjectState.FETCHING;
    }

    /** Advances every forward stage exactly once. */
    void tick() {
        objectAbortLastDot = false;
        windowTriggerLastDot = false;
        windowActivationLastDot = false;
        flushLastDot = flushDrive;
        if (flushDrive) {
            flushFifo();
            flushDrive = false;
        }

        boolean windowActivated = resolveWindowTrigger();
        advanceScanout();
        if (windowTriggerValid && !windowActivated) {
            windowTriggerAge++;
            if (windowTriggerAge >= WINDOW_TRIGGER_RETENTION_DOTS) {
                // The matching raw token has now crossed the irreversible LCD boundary.
                windowTriggerValid = false;
            }
        }
        advanceTileFlight();

        if (objectState != ObjectState.IDLE && !objectsEnabled) {
            // LCDC.1 is an enable on the future object stages. Invalidating the stage releases
            // the FIFO pop gate on this same dot; there is no second machine to catch up.
            objectState = ObjectState.IDLE;
            objectAbortLastDot = true;
        }
        stallLastDot = objectState == ObjectState.WAIT_FOR_BACKGROUND_HIGH
                || objectState == ObjectState.FETCHING;
        boolean objectHighCommitted = advanceObjectFetch();

        if (!stallLastDot && fifoSize != 0) {
            captureWindowTrigger();
            boolean outputEnable = fineScxRemaining == 0;
            popRawPixel(outputEnable, windowCompareX);
            if (fineScxRemaining > 0) {
                fineScxRemaining--;
            }
            windowCompareX++;
            // The high-byte commit shares the first resumed dot with the pop. The background
            // fetch control does not also consume a second edge on that dot.
            if (!objectHighCommitted) {
                backgroundFetchPhase = (backgroundFetchPhase + 1) & 7;
            }
        }
        dot++;
    }

    private void advanceScanout() {
        int outputStage = RAW_TO_LCD_DOTS - 1;
        if (scanoutValid[outputStage] && scanoutOutputEnable[outputStage]) {
            if (lcdX >= lcdRaw.length) {
                throw new IllegalStateException("LCD capture overflow");
            }
            lcdRaw[lcdX++] = scanoutRaw[outputStage];
            lastLcdDot = dot;
        }
        for (int stage = outputStage; stage > 0; stage--) {
            scanoutRaw[stage] = scanoutRaw[stage - 1];
            scanoutValid[stage] = scanoutValid[stage - 1];
            scanoutOutputEnable[stage] = scanoutOutputEnable[stage - 1];
            scanoutLogicalX[stage] = scanoutLogicalX[stage - 1];
        }
        scanoutValid[0] = false;
    }

    private boolean resolveWindowTrigger() {
        if (!windowTriggerValid || !windowEnabled || !windowYMaster
                || windowX != windowTriggerWx) {
            return false;
        }
        flushScanoutFrom(windowTriggerX);
        flushFifo();
        // Any background tile still in flight is downstream of the source-select edge.
        tileValid = false;
        launchTile(windowTileId, windowTileLine);
        fineScxRemaining = 0;
        windowActive = true;
        windowTriggerValid = false;
        windowActivationDot = dot;
        windowActivationLastDot = true;
        return true;
    }

    private void captureWindowTrigger() {
        if (windowActive || windowTriggerValid || !windowYMaster) {
            return;
        }
        if (windowX == ((windowCompareX + 7) & 0xff)) {
            windowTriggerValid = true;
            windowTriggerX = windowCompareX;
            windowTriggerWx = windowX;
            windowTriggerAge = 0;
            windowTriggerDot = dot;
            windowTriggerLastDot = true;
        }
    }

    private void flushScanoutFrom(int logicalX) {
        for (int stage = 0; stage < RAW_TO_LCD_DOTS; stage++) {
            if (scanoutValid[stage] && scanoutLogicalX[stage] >= logicalX) {
                scanoutValid[stage] = false;
            }
        }
    }

    private void flushFifo() {
        fifoHead = 0;
        fifoSize = 0;
    }

    private void advanceTileFlight() {
        if (!tileValid) {
            return;
        }
        int age = dot - tileLaunchDot;
        if (age == TILE_LOW_SAMPLE_OFFSET) {
            tileLowAddress = tileAddress(tileId, tileLine, 0, unsignedTileData);
            tileLowAddressValid = true;
            tileLowData = readVram(tileLowAddress);
            tileLowDataValid = true;
            tileLowSampleDot = dot;
        }
        if (age == TILE_HIGH_SAMPLE_OFFSET) {
            tileHighAddress = tileAddress(tileId, tileLine, 1, unsignedTileData);
            tileHighAddressValid = true;
            tileHighData = readVram(tileHighAddress);
            tileHighDataValid = true;
            tileHighSampleDot = dot;
        }
        if (age == CONTROL_TO_FIFO_DOTS) {
            if (!tileLowDataValid || !tileHighDataValid) {
                throw new IllegalStateException("tile reached FIFO before its data");
            }
            enqueueTile(tileLowData, tileHighData);
            tileValid = false;
        }
    }

    /**
     * @return whether the high object byte was sampled and made available to the FIFO this dot
     */
    private boolean advanceObjectFetch() {
        if (objectState == ObjectState.WAIT_FOR_BACKGROUND_HIGH) {
            backgroundFetchPhase++;
            if (backgroundFetchPhase == 5) {
                objectState = ObjectState.FETCHING;
                objectStep = 0;
            }
            return false;
        }
        if (objectState == ObjectState.FETCHING) {
            // These are the two object-fetch clocks on which the background fetcher's control
            // cone is still allowed to settle. PUSH remains held instead of wrapping.
            if (objectStep < 2 && backgroundFetchPhase < 6) {
                backgroundFetchPhase++;
            }
            if (objectStep == 4) {
                objectLowAddress = objectAddress(objectTileId, objectLine, 0);
                objectLowAddressValid = true;
            } else if (objectStep == 5) {
                if (!objectLowAddressValid) {
                    throw new IllegalStateException("object low address was not latched");
                }
                objectLowData = readVram(objectLowAddress);
                objectLowDataValid = true;
                objectLowSampleDot = dot;
                objectState = ObjectState.HIGH_DATA_PENDING;
                return false;
            }
            objectStep++;
            return false;
        }
        if (objectState == ObjectState.HIGH_DATA_PENDING) {
            // LCDC.2 is intentionally sampled again here: this is a new address/data
            // transaction, not a second read of a previously completed high-byte transaction.
            objectHighAddress = objectAddress(objectTileId, objectLine, 1);
            objectHighAddressValid = true;
            objectHighData = readVram(objectHighAddress);
            objectHighDataValid = true;
            objectHighSampleDot = dot;
            overlayObjectLine(objectLowData, objectHighData);
            objectState = ObjectState.IDLE;
            return true;
        }
        return false;
    }

    private void enqueueTile(int low, int high) {
        for (int bit = 7; bit >= 0; bit--) {
            enqueueRaw(((low >>> bit) & 1) | (((high >>> bit) & 1) << 1), 0);
        }
    }

    private void enqueueRaw(int background, int object) {
        if (fifoSize == FIFO_CAPACITY) {
            throw new IllegalStateException("FIFO overflow");
        }
        int tail = (fifoHead + fifoSize) % FIFO_CAPACITY;
        fifoBackground[tail] = background;
        fifoObject[tail] = object;
        fifoSize++;
    }

    private void overlayObjectLine(int low, int high) {
        int limit = Math.min(8, fifoSize);
        for (int offset = 0; offset < limit; offset++) {
            int bit = objectXFlip ? offset : 7 - offset;
            int pixel = ((low >>> bit) & 1) | (((high >>> bit) & 1) << 1);
            int index = (fifoHead + offset) % FIFO_CAPACITY;
            // This detached DMG cone represents only the first-object-wins transparency rule.
            if (fifoObject[index] == 0 && pixel != 0) {
                fifoObject[index] = pixel;
            }
        }
    }

    private void popRawPixel(boolean outputEnable, int logicalX) {
        int raw = fifoBackground[fifoHead] | (fifoObject[fifoHead] << 2);
        fifoHead = (fifoHead + 1) % FIFO_CAPACITY;
        fifoSize--;
        scanoutRaw[0] = raw;
        scanoutValid[0] = true;
        scanoutOutputEnable[0] = outputEnable;
        scanoutLogicalX[0] = logicalX;
        fifoPopX++;
        lastFifoPopDot = dot;
        lastPoppedRaw = raw;
    }

    private int objectAddress(int tileId, int line, int byteNumber) {
        int mask = tallObjects ? 0xf : 0x7;
        int effectiveLine = line & mask;
        int effectiveTile = tallObjects ? tileId & 0xfe : tileId;
        return VRAM_START + effectiveTile * 0x10 + effectiveLine * 2 + byteNumber;
    }

    private static int tileAddress(int tileId, int line, int byteNumber, boolean unsigned) {
        int tileBase = unsigned
                ? VRAM_START + tileId * 0x10
                : 0x9000 + (byte) tileId * 0x10;
        return tileBase + line * 2 + byteNumber;
    }

    private int readVram(int address) {
        int index = index(address);
        vramReadCount[index]++;
        return vram[index];
    }

    private static int index(int address) {
        if (address < VRAM_START || address >= VRAM_START + VRAM_SIZE) {
            throw new IllegalArgumentException(String.format("not VRAM: %04x", address));
        }
        return address - VRAM_START;
    }

    int dot() {
        return dot;
    }

    int fetchX() {
        return fetchX;
    }

    int fifoPopX() {
        return fifoPopX;
    }

    int lcdX() {
        return lcdX;
    }

    int fifoSize() {
        return fifoSize;
    }

    boolean tileFlightValid() {
        return tileValid;
    }

    boolean fifoValid() {
        return fifoSize != 0;
    }

    boolean scanoutValid() {
        for (boolean valid : scanoutValid) {
            if (valid) {
                return true;
            }
        }
        return false;
    }

    boolean flushLastDot() {
        return flushLastDot;
    }

    boolean popStalled() {
        return objectState == ObjectState.WAIT_FOR_BACKGROUND_HIGH
                || objectState == ObjectState.FETCHING;
    }

    boolean stallLastDot() {
        return stallLastDot;
    }

    boolean objectAbortLastDot() {
        return objectAbortLastDot;
    }

    boolean objectHighPending() {
        return objectState == ObjectState.HIGH_DATA_PENDING;
    }

    int backgroundFetchPhase() {
        return backgroundFetchPhase;
    }

    int windowCompareX() {
        return windowCompareX;
    }

    boolean windowTriggerValid() {
        return windowTriggerValid;
    }

    boolean windowTriggerLastDot() {
        return windowTriggerLastDot;
    }

    boolean windowActivationLastDot() {
        return windowActivationLastDot;
    }

    boolean windowActive() {
        return windowActive;
    }

    int windowTriggerDot() {
        return windowTriggerDot;
    }

    int windowActivationDot() {
        return windowActivationDot;
    }

    int lastFifoPopDot() {
        return lastFifoPopDot;
    }

    int lastLcdDot() {
        return lastLcdDot;
    }

    int lastPoppedRaw() {
        return lastPoppedRaw;
    }

    int lcdRaw(int x) {
        if (x < 0 || x >= lcdX) {
            throw new IllegalArgumentException("LCD X not emitted: " + x);
        }
        return lcdRaw[x];
    }

    int tileLowAddress() {
        require(tileLowAddressValid, "tile low address");
        return tileLowAddress;
    }

    int tileHighAddress() {
        require(tileHighAddressValid, "tile high address");
        return tileHighAddress;
    }

    int tileLowSampleDot() {
        return tileLowSampleDot;
    }

    int tileHighSampleDot() {
        return tileHighSampleDot;
    }

    int objectLowAddress() {
        require(objectLowAddressValid, "object low address");
        return objectLowAddress;
    }

    int objectHighAddress() {
        require(objectHighAddressValid, "object high address");
        return objectHighAddress;
    }

    int objectLowSampleDot() {
        return objectLowSampleDot;
    }

    int objectHighSampleDot() {
        return objectHighSampleDot;
    }

    private static void require(boolean condition, String latch) {
        if (!condition) {
            throw new IllegalStateException(latch + " is not valid");
        }
    }
}
