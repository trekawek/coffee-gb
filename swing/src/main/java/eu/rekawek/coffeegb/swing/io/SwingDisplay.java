package eu.rekawek.coffeegb.swing.io;

import eu.rekawek.coffeegb.controller.Controller;
import eu.rekawek.coffeegb.controller.properties.DisplayProperties;
import eu.rekawek.coffeegb.controller.state.StateImage;
import eu.rekawek.coffeegb.controller.state.StateOperationCompletedEvent;
import eu.rekawek.coffeegb.controller.state.StateOperationFailedEvent;
import eu.rekawek.coffeegb.core.events.Event;
import eu.rekawek.coffeegb.core.events.EventBus;
import eu.rekawek.coffeegb.core.hardware.HardwareProfile;
import eu.rekawek.coffeegb.core.hardware.HardwareProfileRegistry;
import eu.rekawek.coffeegb.core.gpu.Display;
import eu.rekawek.coffeegb.core.rumble.RumbleEvent;
import eu.rekawek.coffeegb.core.sgb.SgbDisplay;

import javax.swing.*;
import java.awt.*;
import java.util.Arrays;
import java.util.Objects;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicReference;

import static eu.rekawek.coffeegb.core.gpu.Display.DISPLAY_HEIGHT;
import static eu.rekawek.coffeegb.core.gpu.Display.DISPLAY_WIDTH;
import static eu.rekawek.coffeegb.core.sgb.SuperGameboy.SGB_DISPLAY_HEIGHT;
import static eu.rekawek.coffeegb.core.sgb.SuperGameboy.SGB_DISPLAY_WIDTH;

public class SwingDisplay extends JPanel implements Runnable {

    private static final int NOTIFICATION_DURATION_MS = 1500;

    private final EventBus eventBus;

    private final int[] waitingFrame;

    private final AtomicReference<DisplayFrameSnapshot> displayedFrame;

    private final AtomicLong preferredSizeRevision = new AtomicLong();

    private final PresentationFrameRateMeter presentationFrameRate =
            new PresentationFrameRateMeter();

    private PendingFrame pendingFrame;

    private volatile int displayWidth = DISPLAY_WIDTH;

    private volatile int displayHeight = DISPLAY_HEIGHT;

    private volatile boolean doStop;

    private volatile boolean isStopped;

    private volatile DisplayScaleMode scaleMode;

    private boolean grayscale;

    private boolean blending;

    private boolean colorCorrection;

    private volatile int rotation;

    private int[] previousFrame;

    private HardwareProfile hardwareProfile = HardwareProfileRegistry.DMG;

    // while a rumble motor runs, jiggle the picture by a pixel each frame as a
    // dependency-free stand-in for a vibrating console
    private volatile boolean rumbling;

    private int rumblePhase;

    private volatile String notificationText;

    private volatile long notificationExpiresAt;

    private volatile String persistentNotificationText;

    public SwingDisplay(DisplayProperties properties, EventBus eventBus, String callerId) {
        super();
        requireEventDispatchThread("SwingDisplay construction");
        this.eventBus = eventBus;
        setOpaque(true);
        setBackground(new Color(properties.getLetterboxColor()));
        waitingFrame = new int[SGB_DISPLAY_WIDTH * SGB_DISPLAY_HEIGHT];
        displayedFrame = new AtomicReference<>(DisplayFrameSnapshot.copyOf(
                DISPLAY_WIDTH,
                DISPLAY_HEIGHT,
                new int[DISPLAY_WIDTH * DISPLAY_HEIGHT]));
        this.grayscale = properties.getGrayscale();
        this.rotation = normalizeRotation(properties.getRotation());
        this.scaleMode = initialScaleMode(properties);
        setBlending(properties.getBlending());
        setColorCorrection(properties.getColorCorrection());

        eventBus.register(this::onDmgFrame, Display.DmgFrameReadyEvent.class, callerId);
        eventBus.register(this::onGbcFrame, Display.GbcFrameReadyEvent.class, callerId);
        eventBus.register(this::onSgbFrame, SgbDisplay.SgbFrameReadyEvent.class, callerId);
        eventBus.register(this::onHardwareProfile, Controller.HardwareProfileEvent.class, callerId);
        eventBus.register(e -> setScale(e.scale), SetScaleEvent.class);
        eventBus.register(
                e -> setScaleMode(e.mode, e.forcePreferredSizeUpdate),
                SetScaleModeEvent.class);
        eventBus.register(e -> setGrayscale(e.grayscale), SetGrayscaleEvent.class);
        eventBus.register(e -> setBlending(e.blending), SetBlendingEvent.class);
        eventBus.register(e -> setColorCorrection(e.colorCorrection), SetColorCorrectionEvent.class);
        eventBus.register(e -> setRotation(e.rotation), SetRotationEvent.class);
        eventBus.register(e -> setLetterboxColor(e.color), SetLetterboxColorEvent.class);
        eventBus.register(e -> this.rumbling = e.on(), RumbleEvent.class, callerId);
        eventBus.register(e -> showNotification("State saved (slot " + e.getSlot() + ")"),
                Controller.SnapshotSavedEvent.class, callerId);
        eventBus.register(e -> showNotification("State loaded (slot " + e.getSlot() + ")"),
                Controller.SnapshotRestoredEvent.class, callerId);
        eventBus.register(this::onStateOperationCompleted, StateOperationCompletedEvent.class);
        eventBus.register(e -> showNotification(e.getError().getSummary()),
                StateOperationFailedEvent.class);
        eventBus.register(e -> showPersistentNotification("Loading…"),
                Controller.RomLoadingEvent.class);
        eventBus.register(e -> clearPersistentNotification(),
                Controller.LoadRomFailedEvent.class);
        eventBus.register(e -> clearPersistentNotification(),
                Controller.RomLoadingCancelledEvent.class);
        // LinkedController publishes the local owner lifecycle from its "session" bus while
        // forwarding frame/audio output through the caller-filtered "main" bus. Loading is a
        // host lifecycle notification, so its successful terminal event must remain unfiltered.
        eventBus.register(e -> clearPersistentNotification(),
                Controller.EmulationStartedEvent.class);
        // Session teardown silently quiesces core outputs after its bus stops. Reset host-only
        // visual rumble through the owner lifecycle while subscribers are still active.
        eventBus.register(e -> this.rumbling = false, Controller.RomLoadingEvent.class);
        eventBus.register(e -> this.rumbling = false, Controller.EmulationStoppedEvent.class);
        eventBus.register(e -> resetPresentationFrameRate(), Controller.PauseEmulationEvent.class);
        eventBus.register(e -> resetPresentationFrameRate(), Controller.ResumeEmulationEvent.class);
        eventBus.register(e -> resetPresentationFrameRate(), Controller.RomLoadingEvent.class);
        eventBus.register(e -> resetPresentationFrameRate(), Controller.EmulationStartedEvent.class);
        eventBus.register(e -> resetPresentationFrameRate(), Controller.EmulationStoppedEvent.class);
        requestPreferredSizeUpdate();
    }

    /** Resets host-only output state before a controller can quiesce or close its event bus. */
    public void releaseForLifecycleChange() {
        rumbling = false;
        resetPresentationFrameRate();
    }

    private void resetPresentationFrameRate() {
        presentationFrameRate.reset();
        eventBus.post(new PresentationFrameRateResetEvent());
    }

    private synchronized void onHardwareProfile(Controller.HardwareProfileEvent e) {
        this.hardwareProfile = e.getProfile();
    }

    private synchronized void onGbcFrame(Display.GbcFrameReadyEvent e) {
        requireFrameLength(e.pixels().length, DISPLAY_WIDTH, DISPLAY_HEIGHT);
        e.toRgb(waitingFrame, colorCorrection);
        setFrameSize(DISPLAY_WIDTH, DISPLAY_HEIGHT);
        frameQueued(DISPLAY_WIDTH, DISPLAY_HEIGHT, false);
    }

    private synchronized void onDmgFrame(Display.DmgFrameReadyEvent e) {
        if (hardwareProfile.capabilities().superGameboyBorder()) {
            return;
        }
        requireFrameLength(e.pixels().length, DISPLAY_WIDTH, DISPLAY_HEIGHT);
        e.toRgb(waitingFrame, grayscale);
        setFrameSize(DISPLAY_WIDTH, DISPLAY_HEIGHT);
        frameQueued(DISPLAY_WIDTH, DISPLAY_HEIGHT, e.lcdBlank());
    }

    private synchronized void onSgbFrame(SgbDisplay.SgbFrameReadyEvent e) {
        int width = e.includeBorder() ? SGB_DISPLAY_WIDTH : DISPLAY_WIDTH;
        int height = e.includeBorder() ? SGB_DISPLAY_HEIGHT : DISPLAY_HEIGHT;
        requireFrameLength(e.buffer().length, width, height);
        e.toRgb(waitingFrame, grayscale);
        setFrameSize(width, height);
        frameQueued(width, height, false);
    }

    private static void requireFrameLength(int actualLength, int width, int height) {
        int expectedLength = Math.multiplyExact(width, height);
        if (actualLength != expectedLength) {
            throw new IllegalArgumentException(
                    "Frame pixel count must be exactly " + expectedLength);
        }
    }

    /**
     * Keep the newest panel state while the display thread has not published the pending
     * frame yet. In particular, LCD-off can replace a just-finished partial scanout before
     * Swing paints it instead of leaving that stale transition visible for a host frame.
     */
    private void frameQueued(int width, int height, boolean resetBlend) {
        if (!Thread.holdsLock(this)) {
            throw new IllegalStateException(
                    "Frame queue must be updated while holding the display lock");
        }
        int size = Math.multiplyExact(width, height);
        pendingFrame = new PendingFrame(
                width, height, Arrays.copyOf(waitingFrame, size), resetBlend);
        notifyAll();
    }

    private void setFrameSize(int width, int height) {
        if (!Thread.holdsLock(this)) {
            throw new IllegalStateException(
                    "Frame size must be updated while holding the display lock");
        }
        if (width != displayWidth || height != displayHeight) {
            displayWidth = width;
            displayHeight = height;
            requestPreferredSizeUpdate();
        }
    }

    private void setScale(int scale) {
        setScaleMode(legacyScaleMode(scale), false);
    }

    private void setScaleMode(DisplayScaleMode mode, boolean forcePreferredSizeUpdate) {
        DisplayScaleMode requiredMode = Objects.requireNonNull(mode, "mode");
        if (requiredMode == scaleMode && !forcePreferredSizeUpdate) {
            return;
        }
        scaleMode = requiredMode;
        requestPreferredSizeUpdate();
    }

    private void setRotation(int degrees) {
        int normalized = normalizeRotation(degrees);
        if (normalized == rotation) {
            return;
        }
        this.rotation = normalized;
        requestPreferredSizeUpdate();
    }

    private static int normalizeRotation(int degrees) {
        int r = ((degrees % 360) + 360) % 360;
        // snap to the four quarter turns the display supports
        return (r / 90) * 90;
    }

    private static DisplayScaleMode legacyScaleMode(int scale) {
        if (scale <= 1) {
            return DisplayScaleMode.EXPLICIT_1X;
        } else if (scale == 2) {
            return DisplayScaleMode.EXPLICIT_2X;
        } else if (scale == 3) {
            return DisplayScaleMode.EXPLICIT_3X;
        } else {
            return DisplayScaleMode.EXPLICIT_4X;
        }
    }

    private static DisplayScaleMode initialScaleMode(DisplayProperties properties) {
        return switch (properties.getScalingMode()) {
            case INTEGER_FIT -> DisplayScaleMode.INTEGER_FIT;
            case ASPECT_FIT -> DisplayScaleMode.ASPECT_FIT;
            case EXPLICIT -> DisplayScaleMode.explicit(properties.getExplicitScale());
        };
    }

    private void requestPreferredSizeUpdate() {
        int width;
        int height;
        int localRotation;
        DisplayScaleMode localMode;
        long revision;
        synchronized (this) {
            width = displayWidth;
            height = displayHeight;
            localRotation = rotation;
            localMode = scaleMode;
            revision = preferredSizeRevision.incrementAndGet();
        }
        Dimension preferredSize =
                DisplayViewport.preferredSize(width, height, localRotation, localMode);
        runOnEventDispatchThread(() -> {
            requireEventDispatchThread("Display size update");
            if (preferredSizeRevision.get() != revision) {
                return;
            }
            Dimension appliedSize = new Dimension(preferredSize);
            setPreferredSize(appliedSize);
            revalidate();
            eventBus.post(new DisplaySizeUpdatedEvent(appliedSize));
            repaint();
        });
    }

    /**
     * Updates the letterbox/pillarbox color. Calls from emulation or settings threads are
     * marshalled to the EDT.
     */
    public void setLetterboxColor(Color color) {
        Color copiedColor = Objects.requireNonNull(color, "color");
        runOnEventDispatchThread(() -> {
            requireEventDispatchThread("Letterbox color update");
            setBackground(copiedColor);
            repaint();
        });
    }

    public DisplayScaleMode getScaleMode() {
        return scaleMode;
    }

    /** Returns an immutable copy of the last fully published presentation frame. */
    public StateImage captureStateImage() {
        DisplayFrameSnapshot frame = displayedFrame.get();
        return new StateImage(frame.width(), frame.height(), frame.copyRgb());
    }

    @Override
    protected void paintComponent(Graphics g) {
        requireEventDispatchThread("Display painting");
        super.paintComponent(g);

        DisplayFrameSnapshot frame = displayedFrame.get();
        DisplayViewport viewport = DisplayViewport.calculate(
                getWidth(),
                getHeight(),
                frame.width(),
                frame.height(),
                rotation,
                scaleMode);
        Graphics2D frameGraphics = (Graphics2D) g.create();
        frameGraphics.setRenderingHint(
                RenderingHints.KEY_INTERPOLATION,
                RenderingHints.VALUE_INTERPOLATION_NEAREST_NEIGHBOR);
        frameGraphics.setRenderingHint(
                RenderingHints.KEY_RENDERING,
                RenderingHints.VALUE_RENDER_SPEED);
        if (rumbling) {
            rumblePhase++;
            frameGraphics.translate(
                    (rumblePhase & 2) == 0 ? 1 : -1,
                    (rumblePhase & 1) == 0 ? 1 : -1);
        }
        frameGraphics.transform(viewport.sourceToComponentTransform());
        frame.paint(frameGraphics);
        frameGraphics.dispose();

        Graphics2D notificationGraphics = (Graphics2D) g.create();
        paintNotification(notificationGraphics, viewport);
        notificationGraphics.dispose();
    }

    private void showNotification(String text) {
        notificationText = text;
        notificationExpiresAt = System.nanoTime()
                + TimeUnit.MILLISECONDS.toNanos(NOTIFICATION_DURATION_MS);
        repaintNotification(NOTIFICATION_DURATION_MS);
    }

    private void onStateOperationCompleted(StateOperationCompletedEvent event) {
        switch (event.getOperation()) {
            case SAVE, LOAD, RESUME, SCREENSHOT -> showNotification(event.getMessage());
            default -> {
                // Browser/folder operations have their own visible desktop affordance.
            }
        }
    }

    private void showPersistentNotification(String text) {
        persistentNotificationText = text;
        repaintNotification(0);
    }

    private void clearPersistentNotification() {
        if (persistentNotificationText == null) {
            return;
        }
        persistentNotificationText = null;
        repaintNotification(0);
    }

    private void repaintNotification(int repaintAfterMs) {
        runOnEventDispatchThread(() -> {
            repaint();
            if (repaintAfterMs > 0) {
                Timer timer = new Timer(repaintAfterMs, e -> repaint());
                timer.setRepeats(false);
                timer.start();
            }
        });
    }

    private void paintNotification(Graphics2D g, DisplayViewport viewport) {
        String persistentText = persistentNotificationText;
        String text = persistentText != null ? persistentText : notificationText;
        if (text == null || (persistentText == null && System.nanoTime() >= notificationExpiresAt)) {
            return;
        }

        Rectangle bounds = viewport.paintBounds();
        int localScale = Math.max(1, (int) Math.floor(viewport.scale()));
        int fontSize = Math.max(12, 7 * localScale);
        g.setFont(new Font(Font.SANS_SERIF, Font.BOLD, fontSize));
        FontMetrics metrics = g.getFontMetrics();
        int paddingX = Math.max(6, 4 * localScale);
        int paddingY = Math.max(4, 2 * localScale);
        int boxWidth = metrics.stringWidth(text) + 2 * paddingX;
        int boxHeight = metrics.getHeight() + 2 * paddingY;
        int x = bounds.x + (bounds.width - boxWidth) / 2;
        int y = bounds.y + bounds.height - boxHeight - Math.max(4, 4 * localScale);
        int arc = Math.max(6, 4 * localScale);

        g.setColor(new Color(0, 0, 0, 190));
        g.fillRoundRect(x, y, boxWidth, boxHeight, arc, arc);
        g.setColor(Color.WHITE);
        g.drawString(text, x + paddingX, y + paddingY + metrics.getAscent());
    }

    @Override
    public void run() {
        doStop = false;
        isStopped = false;

        while (!doStop) {
            PendingFrame frame;
            synchronized (this) {
                while (!doStop && pendingFrame == null) {
                    try {
                        wait(10);
                    } catch (InterruptedException e) {
                        Thread.currentThread().interrupt();
                        doStop = true;
                    }
                }
                if (doStop) {
                    break;
                }
                frame = pendingFrame;
                pendingFrame = null;
                if (blending) {
                    if (frame.resetBlend()) {
                        // LCD-off is a new panel state, not another rendered frame to
                        // average. Retaining a partial scanout here creates ghost sprites.
                        previousFrame = null;
                    }
                    blendWithPreviousFrame(frame.rgb());
                }
            }

            displayedFrame.set(DisplayFrameSnapshot.copyOf(
                    frame.width(), frame.height(), frame.rgb()));
            double framesPerSecond = presentationFrameRate.framePublished();
            if (!Double.isNaN(framesPerSecond)) {
                eventBus.post(new PresentationFrameRateEvent(framesPerSecond));
            }
            requestRepaint();
        }
        isStopped = true;
        synchronized (this) {
            notifyAll();
        }
    }

    public void stop() {
        doStop = true;
        synchronized (this) {
            notifyAll();
            while (!isStopped) {
                try {
                    wait(10);
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    return;
                }
            }
        }
    }

    private synchronized void setColorCorrection(boolean colorCorrection) {
        this.colorCorrection = colorCorrection;
    }

    private synchronized void setGrayscale(boolean grayscale) {
        this.grayscale = grayscale;
    }

    private synchronized void setBlending(boolean blending) {
        this.blending = blending;
        previousFrame = null;
    }

    /**
     * Approximates the ghosting of the original LCD by averaging with the previous frame;
     * games flickering sprites at 30 Hz (like Chikyuu Kaihou Gun ZAS) rely on it.
     */
    private void blendWithPreviousFrame(int[] frame) {
        int size = frame.length;
        if (previousFrame == null || previousFrame.length != size) {
            previousFrame = frame.clone();
            return;
        }
        for (int i = 0; i < size; i++) {
            int a = frame[i];
            int b = previousFrame[i];
            previousFrame[i] = a;
            frame[i] = (((a ^ b) & 0xfefefe) >> 1) + (a & b);
        }
    }

    DisplayFrameSnapshot displayedFrame() {
        return displayedFrame.get();
    }

    private void requestRepaint() {
        runOnEventDispatchThread(this::repaint);
    }

    private static void runOnEventDispatchThread(Runnable runnable) {
        if (SwingUtilities.isEventDispatchThread()) {
            runnable.run();
        } else {
            SwingUtilities.invokeLater(runnable);
        }
    }

    private static void requireEventDispatchThread(String operation) {
        if (!SwingUtilities.isEventDispatchThread()) {
            throw new IllegalStateException(operation + " must run on the Event Dispatch Thread");
        }
    }

    public record SetScaleEvent(int scale) implements Event {
    }

    public record SetScaleModeEvent(
            DisplayScaleMode mode,
            boolean forcePreferredSizeUpdate) implements Event {
        public SetScaleModeEvent(DisplayScaleMode mode) {
            this(mode, false);
        }

        public SetScaleModeEvent {
            Objects.requireNonNull(mode, "mode");
        }
    }

    public record SetGrayscaleEvent(boolean grayscale) implements Event {
    }

    public record SetColorCorrectionEvent(boolean colorCorrection) implements Event {
    }

    public record SetRotationEvent(int rotation) implements Event {
    }

    public record SetLetterboxColorEvent(Color color) implements Event {
        public SetLetterboxColorEvent {
            Objects.requireNonNull(color, "color");
        }
    }

    public record SetBlendingEvent(boolean blending) implements Event {
    }

    public record DisplaySizeUpdatedEvent(Dimension preferredSize) implements Event {
        public DisplaySizeUpdatedEvent {
            preferredSize = new Dimension(Objects.requireNonNull(preferredSize, "preferredSize"));
        }

        @Override
        public Dimension preferredSize() {
            return new Dimension(preferredSize);
        }
    }

    /** Low-frequency count of frames that survived SwingDisplay coalescing and were published. */
    public record PresentationFrameRateEvent(double framesPerSecond) implements Event {
        public PresentationFrameRateEvent {
            if (!Double.isFinite(framesPerSecond) || framesPerSecond < 0) {
                throw new IllegalArgumentException("Frame rate must be finite and non-negative");
            }
        }
    }

    /** Clears a stale presentation-rate sample after a pause or session ownership transition. */
    public record PresentationFrameRateResetEvent() implements Event {
    }

    private record PendingFrame(int width, int height, int[] rgb, boolean resetBlend) {
    }
}
