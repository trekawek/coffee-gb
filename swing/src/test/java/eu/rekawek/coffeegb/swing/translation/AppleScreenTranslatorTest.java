package eu.rekawek.coffeegb.swing.translation;

import org.junit.Test;
import java.awt.image.BufferedImage;
import java.nio.file.Path;
import java.time.Duration;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.Assert.*;
import static eu.rekawek.coffeegb.swing.translation.TranslationException.Kind;

public class AppleScreenTranslatorTest {
    @Test public void exchangesNativeResolutionPngAndReceivesPositionedText() throws Exception {
        try (var translator = translator("success")) {
            assertEquals(List.of(new TranslationRegion("Hello", 8, 96, 140, 32)),
                    translator.translate(image()).get(5, TimeUnit.SECONDS));
        }
    }

    @Test public void languageSetupHasASeparateDeadlineAndReportsProgress() throws Exception {
        var progress = new CopyOnWriteArrayList<ScreenTranslator.Progress>();
        try (var translator = new AppleScreenTranslator(() -> process("setup"),
                Duration.ofSeconds(1), Duration.ofSeconds(5))) {
            assertTrue(translator.translate(image(), progress::add).get(6, TimeUnit.SECONDS).isEmpty());
            assertEquals(List.of(ScreenTranslator.Progress.LANGUAGE_SETUP, ScreenTranslator.Progress.TRANSLATING), progress);
        }
    }

    @Test public void cancellationKillsTheHelper() throws Exception {
        var started = new CountDownLatch(1);
        var process = new AtomicReference<Process>();
        try (var translator = new AppleScreenTranslator(() -> {
            Process helper = process("stall");
            process.set(helper);
            started.countDown();
            return helper;
        }, Duration.ofSeconds(5), Duration.ofSeconds(5))) {
            var result = translator.translate(image());
            assertTrue(started.await(3, TimeUnit.SECONDS));
            result.cancel(true);
            assertTrue(process.get().waitFor(3, TimeUnit.SECONDS));
            assertTrue(result.isCancelled());
        }
    }

    @Test public void normalAndSetupTimeoutsKillTheHelper() throws Exception {
        for (String mode : List.of("stall", "setup-stall")) {
            var process = new AtomicReference<Process>();
            try (var translator = new AppleScreenTranslator(() -> {
                Process helper = process(mode);
                process.set(helper);
                return helper;
            }, Duration.ofSeconds(1), Duration.ofMillis(300))) {
                TranslationException failure = failure(translator);
                assertEquals(Kind.TIMEOUT, failure.kind());
                assertTrue(process.get().waitFor(3, TimeUnit.SECONDS));
            }
        }
    }

    @Test public void cancellationDuringStartupKillsTheLateProcess() throws Exception {
        var spawning = new CountDownLatch(1);
        var release = new CountDownLatch(1);
        var spawned = new CountDownLatch(1);
        var process = new AtomicReference<Process>();
        try (var translator = new AppleScreenTranslator(() -> {
            spawning.countDown();
            try {
                if (!release.await(3, TimeUnit.SECONDS)) throw new java.io.IOException("Test gate expired");
            } catch (InterruptedException error) {
                Thread.currentThread().interrupt();
                throw new java.io.IOException(error);
            }
            Process helper = process("success");
            process.set(helper);
            spawned.countDown();
            return helper;
        }, Duration.ofSeconds(5), Duration.ofSeconds(5))) {
            var result = translator.translate(image());
            assertTrue(spawning.await(3, TimeUnit.SECONDS));
            result.cancel(true);
            release.countDown();
            assertTrue(spawned.await(3, TimeUnit.SECONDS));
            assertTrue(process.get().waitFor(3, TimeUnit.SECONDS));
            assertTrue(result.isCancelled());
        } finally {
            release.countDown();
        }
    }

    @Test public void rejectsInvalidBoundsFractionalPositionsOversizedAndMalformedOutput() throws Exception {
        for (String mode : List.of("outside", "fraction", "oversize", "malformed", "repeat-setup")) {
            try (var translator = translator(mode)) {
                TranslationException failure = failure(translator);
                assertEquals(mode, Kind.MALFORMED_RESPONSE, failure.kind());
                assertFalse(failure.getMessage().contains("private"));
            }
        }
    }

    @Test public void errorCodesAreMappedWithoutExposingNativeMessages() throws Exception {
        try (var translator = translator("error")) {
            TranslationException failure = failure(translator);
            assertEquals(Kind.SERVICE, failure.kind());
            assertTrue(failure.getMessage().contains("download"));
            assertFalse(failure.getMessage().contains("private"));
        }
        try (var translator = translator("eof")) {
            assertEquals(Kind.SERVICE, failure(translator).kind());
        }
    }

    @Test public void closeCancelsPendingWorkAndRejectsFurtherScreens() throws Exception {
        var started = new CountDownLatch(1);
        try (var translator = new AppleScreenTranslator(() -> {
            Process helper = process("stall");
            started.countDown();
            return helper;
        }, Duration.ofSeconds(5), Duration.ofSeconds(5))) {
            var request = translator.translate(image());
            assertTrue(started.await(3, TimeUnit.SECONDS));
            translator.close();
            assertTrue(request.isCancelled());
            assertEquals(Kind.CONFIGURATION, failure(translator).kind());
        }
    }

    @Test public void unavailablePlatformsHaveActionableErrors() {
        AppleScreenTranslator.checkPlatform("Mac OS X", "15.0");
        AppleScreenTranslator.checkPlatform("Mac OS X", "26.0.1");
        for (String version : List.of("14.7", "10.15", "invalid")) {
            assertEquals(Kind.CONFIGURATION, assertThrows(TranslationException.class,
                    () -> AppleScreenTranslator.checkPlatform("Mac OS X", version)).kind());
        }
        assertTrue(assertThrows(TranslationException.class,
                () -> AppleScreenTranslator.checkPlatform("Linux", "6.0")).getMessage().contains("macOS 15"));
    }

    private static AppleScreenTranslator translator(String mode) {
        return new AppleScreenTranslator(() -> process(mode), Duration.ofSeconds(4), Duration.ofSeconds(5));
    }

    private static Process process(String mode) throws java.io.IOException {
        String java = Path.of(System.getProperty("java.home"), "bin", "java").toString();
        return new ProcessBuilder(java, "-cp", System.getProperty("java.class.path"),
                FakeAppleTranslationHelper.class.getName(), mode)
                .redirectError(ProcessBuilder.Redirect.DISCARD).start();
    }

    private static TranslationException failure(AppleScreenTranslator translator) throws Exception {
        ExecutionException failure = assertThrows(ExecutionException.class,
                () -> translator.translate(image()).get(6, TimeUnit.SECONDS));
        assertTrue(failure.getCause().toString(), failure.getCause() instanceof TranslationException);
        return (TranslationException) failure.getCause();
    }

    private static BufferedImage image() {
        var image = new BufferedImage(160, 144, BufferedImage.TYPE_INT_RGB);
        image.setRGB(1, 1, 0xffff0000);
        return image;
    }
}
