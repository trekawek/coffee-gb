package eu.rekawek.coffeegb.swing.translation;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.Strictness;

import javax.imageio.ImageIO;
import javax.imageio.stream.MemoryCacheImageOutputStream;
import java.awt.image.BufferedImage;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Base64;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.ScheduledThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Consumer;

import static eu.rekawek.coffeegb.swing.translation.TranslationException.Kind;

/** Exchanges a screenshot with the bundled Apple helper over private pipes, never the network. */
public final class AppleScreenTranslator implements ScreenTranslator {
    public static final String HELPER_PROPERTY = "coffee-gb.translation.apple.helper";
    public static final String HELPER_RELATIVE_PATH =
            "apple-translation/CoffeeGBTranslation.app/Contents/MacOS/CoffeeGBTranslation";
    private static final Gson JSON = new GsonBuilder().setStrictness(Strictness.STRICT).create();
    private static final int MAX_RESPONSE_BYTES = 128 * 1024;
    private static final Duration TRANSLATION_TIMEOUT = Duration.ofMillis(8500);
    private static final Duration SETUP_TIMEOUT = Duration.ofMinutes(15);

    @FunctionalInterface
    interface ProcessStarter {
        Process start() throws IOException;
    }

    private final ProcessStarter starter;
    private final Duration timeout;
    private final Duration setupTimeout;
    private final AtomicBoolean closed = new AtomicBoolean();
    private final Set<CompletableFuture<?>> requests = ConcurrentHashMap.newKeySet();
    private final ExecutorService worker = Executors.newSingleThreadExecutor(task -> {
        Thread thread = new Thread(task, "apple-screen-translation");
        thread.setDaemon(true);
        return thread;
    });
    private final ScheduledThreadPoolExecutor deadlines = new ScheduledThreadPoolExecutor(1, task -> {
        Thread thread = new Thread(task, "apple-translation-deadline");
        thread.setDaemon(true);
        return thread;
    });

    public AppleScreenTranslator() {
        this(AppleScreenTranslator::startHelper, TRANSLATION_TIMEOUT, SETUP_TIMEOUT);
    }

    AppleScreenTranslator(ProcessStarter starter, Duration timeout, Duration setupTimeout) {
        if (timeout.isZero() || timeout.isNegative() || setupTimeout.isZero() || setupTimeout.isNegative()) {
            throw new IllegalArgumentException("Translation deadlines must be positive");
        }
        this.starter = starter;
        this.timeout = timeout;
        this.setupTimeout = setupTimeout;
        deadlines.setRemoveOnCancelPolicy(true);
    }

    @Override
    public CompletableFuture<List<TranslationRegion>> translate(BufferedImage screenshot) {
        return translate(screenshot, ignored -> {});
    }

    @Override
    public CompletableFuture<List<TranslationRegion>> translate(
            BufferedImage screenshot, Consumer<Progress> progress) {
        if (closed.get()) return CompletableFuture.failedFuture(configuration("Screen translation is closed."));
        if (screenshot == null || screenshot.getWidth() > 512 || screenshot.getHeight() > 512) {
            return CompletableFuture.failedFuture(configuration("This screenshot cannot be translated."));
        }
        Request request = new Request(progress);
        requests.add(request.result);
        request.result.whenComplete((value, error) -> {
            requests.remove(request.result);
            request.stop();
        });
        if (closed.get()) {
            request.result.cancel(true);
            return request.result;
        }
        try {
            request.deadline(timeout, false);
            worker.execute(() -> exchange(request, screenshot));
        } catch (RuntimeException error) {
            request.result.completeExceptionally(configuration("Screen translation is closed."));
        }
        return request.result;
    }

    private void exchange(Request request, BufferedImage screenshot) {
        try {
            if (request.result.isDone()) return;
            byte[] input = encode(screenshot);
            if (request.result.isDone()) return;
            Process process = starter.start();
            request.process.set(process);
            if (request.result.isDone()) {
                request.stop();
                return;
            }
            try (var stdin = process.getOutputStream()) {
                stdin.write(input);
            }
            try (var stdout = process.getInputStream()) {
                int remaining = MAX_RESPONSE_BYTES;
                boolean setupSeen = false;
                boolean translatingSeen = false;
                while (!request.result.isDone()) {
                    byte[] line = readLine(stdout, remaining);
                    remaining -= line.length + 1;
                    JsonObject event = JSON.fromJson(new String(line, StandardCharsets.UTF_8), JsonObject.class);
                    String type = string(event, "event");
                    switch (type) {
                        case "setup" -> {
                            if (setupSeen || translatingSeen) throw malformed();
                            setupSeen = true;
                            request.deadline(setupTimeout, true);
                            request.progress.accept(Progress.LANGUAGE_SETUP);
                        }
                        case "translating" -> {
                            if (!setupSeen || translatingSeen) throw malformed();
                            translatingSeen = true;
                            request.deadline(timeout, false);
                            request.progress.accept(Progress.TRANSLATING);
                        }
                        case "result" -> request.result.complete(regions(event, screenshot));
                        case "error" -> throw helperError(string(event, "code"));
                        default -> throw malformed();
                    }
                }
            }
        } catch (TranslationException error) {
            request.result.completeExceptionally(error);
        } catch (IOException error) {
            request.result.completeExceptionally(new TranslationException(Kind.SERVICE,
                    "Apple translation stopped unexpectedly. Dismiss and try again."));
        } catch (RuntimeException error) {
            request.result.completeExceptionally(malformed());
        }
    }

    private static Process startHelper() throws IOException {
        checkPlatform(System.getProperty("os.name", ""), System.getProperty("os.version", ""));
        Path executable;
        try {
            String override = System.getProperty(HELPER_PROPERTY, "");
            if (!override.isBlank()) {
                executable = Path.of(override);
                if (!executable.isAbsolute()) throw configuration("The Apple translation helper path must be absolute.");
            } else {
                Path location = Path.of(AppleScreenTranslator.class.getProtectionDomain()
                        .getCodeSource().getLocation().toURI());
                executable = location.getParent().resolve(HELPER_RELATIVE_PATH);
            }
        } catch (TranslationException error) {
            throw error;
        } catch (Exception error) {
            throw configuration("Install the Coffee GB macOS app to use Apple translation.");
        }
        if (!Files.isRegularFile(executable) || !Files.isExecutable(executable)) {
            throw configuration("Apple translation is missing from this installation. Install the Coffee GB macOS app.");
        }
        return new ProcessBuilder(executable.toString()).redirectError(ProcessBuilder.Redirect.DISCARD).start();
    }

    static void checkPlatform(String name, String version) {
        if (!name.toLowerCase(Locale.ROOT).startsWith("mac")) {
            throw configuration("Apple translation requires macOS 15 or later. Choose OpenAI in Preferences > Translation on this computer.");
        }
        int major;
        try {
            major = Integer.parseInt(version.split("\\.", 2)[0]);
        } catch (NumberFormatException error) {
            major = 0;
        }
        if (major < 15) throw configuration("Apple translation requires macOS 15 or later.");
    }

    private static byte[] encode(BufferedImage image) throws IOException {
        ByteArrayOutputStream bytes = new ByteArrayOutputStream();
        try (var output = new MemoryCacheImageOutputStream(bytes)) {
            if (!ImageIO.write(image, "png", output)) throw new IOException("PNG encoder unavailable");
        }
        return (JSON.toJson(Map.of("version", 1,
                "image", Base64.getEncoder().encodeToString(bytes.toByteArray()),
                "width", image.getWidth(), "height", image.getHeight())) + "\n")
                .getBytes(StandardCharsets.UTF_8);
    }

    private static byte[] readLine(InputStream stream, int limit) throws IOException {
        ByteArrayOutputStream bytes = new ByteArrayOutputStream();
        for (int count = 0; count < limit; count++) {
            int next = stream.read();
            if (next < 0) throw new IOException("Apple helper closed its output");
            if (next == '\n') return bytes.toByteArray();
            bytes.write(next);
        }
        throw malformed();
    }

    private static List<TranslationRegion> regions(JsonObject event, BufferedImage image) {
        JsonElement value = event.get("regions");
        if (value == null || !value.isJsonArray() || value.getAsJsonArray().size() > 64) throw malformed();
        List<TranslationRegion> regions = new ArrayList<>();
        for (JsonElement element : value.getAsJsonArray()) {
            JsonObject region = element.getAsJsonObject();
            String text = string(region, "text");
            int x = integer(region, "x"), y = integer(region, "y");
            int width = integer(region, "width"), height = integer(region, "height");
            if (text.isBlank() || text.length() > 2048 ||
                    text.codePoints().anyMatch(c -> Character.isISOControl(c) && c != '\n' && c != '\t') ||
                    x < 0 || y < 0 || width <= 0 || height <= 0 ||
                    x > image.getWidth() || y > image.getHeight() ||
                    width > image.getWidth() - x || height > image.getHeight() - y) throw malformed();
            regions.add(new TranslationRegion(text, x, y, width, height));
        }
        return List.copyOf(regions);
    }

    private static String string(JsonObject object, String key) {
        JsonElement value = object.get(key);
        if (value == null || !value.isJsonPrimitive() || !value.getAsJsonPrimitive().isString()) throw malformed();
        return value.getAsString();
    }

    private static int integer(JsonObject object, String key) {
        JsonElement value = object.get(key);
        if (value == null || !value.isJsonPrimitive() || !value.getAsJsonPrimitive().isNumber()) throw malformed();
        return value.getAsBigDecimal().intValueExact();
    }

    private static TranslationException helperError(String code) {
        return switch (code) {
            case "unsupported_language" -> configuration("Apple translation does not support this screen's language.");
            case "language_detection" -> configuration("Apple could not identify this screen's language. Try a screen with more text.");
            case "cancelled" -> configuration("Apple language setup was cancelled. Dismiss and try again when ready.");
            case "download_failed" -> new TranslationException(Kind.SERVICE,
                    "Apple could not download the translation languages. Check your connection and try again.");
            case "invalid_request" -> malformed();
            default -> new TranslationException(Kind.SERVICE,
                    "Apple could not translate this screen. Dismiss and try again.");
        };
    }

    private static TranslationException configuration(String message) {
        return new TranslationException(Kind.CONFIGURATION, message);
    }

    private static TranslationException malformed() {
        return new TranslationException(Kind.MALFORMED_RESPONSE,
                "Apple translation returned an invalid result. Dismiss and try again.");
    }

    @Override
    public void close() {
        if (!closed.compareAndSet(false, true)) return;
        requests.forEach(request -> request.cancel(true));
        worker.shutdownNow();
        deadlines.shutdownNow();
    }

    private final class Request {
        final CompletableFuture<List<TranslationRegion>> result = new CompletableFuture<>();
        final AtomicReference<Process> process = new AtomicReference<>();
        final Consumer<Progress> progress;
        private ScheduledFuture<?> timer;
        private long timerGeneration;

        Request(Consumer<Progress> progress) {
            this.progress = progress;
        }

        synchronized void deadline(Duration duration, boolean setup) {
            if (result.isDone()) return;
            if (timer != null) timer.cancel(false);
            long generation = ++timerGeneration;
            timer = deadlines.schedule(() -> expire(generation, setup), duration.toMillis(), TimeUnit.MILLISECONDS);
        }

        private synchronized void expire(long generation, boolean setup) {
            if (generation != timerGeneration) return;
            result.completeExceptionally(new TranslationException(Kind.TIMEOUT, setup
                    ? "Apple language setup timed out. Dismiss and try again."
                    : "Apple translation timed out. Dismiss and try again."));
        }

        synchronized void stop() {
            timerGeneration++;
            if (timer != null) timer.cancel(false);
            Process running = process.get();
            if (running != null && running.isAlive()) running.destroyForcibly();
        }
    }
}
