package eu.rekawek.coffeegb.core.memory.cart.battery;

import eu.rekawek.coffeegb.core.events.EventBusImpl;
import eu.rekawek.coffeegb.core.persistence.AtomicFileWriter;
import org.junit.Test;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.FileTime;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.Assert.assertArrayEquals;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertThrows;
import static org.junit.Assert.assertTrue;

public class FileBatteryTest {

    @Test
    public void twoPhaseCaptureIsImmutablePersistsOffCaptureThreadAndCommitsLater()
            throws Exception {
        withDirectory(directory -> {
            Path target = directory.resolve("barrier.sav");
            ThreadTrackingWriter persistence = new ThreadTrackingWriter();
            FileBattery battery = new FileBattery(target.toFile(), 4, persistence);
            int[] mapperRam = {1, 2, 3, 4};
            AtomicReference<BatteryFlush> captured = new AtomicReference<>();

            Thread safePoint =
                    new Thread(
                            () ->
                                    captured.set(
                                            battery.prepareFlush(
                                                    () -> {
                                                        battery.saveRam(mapperRam);
                                                        battery.flush();
                                                    })),
                            "test-emulation-safe-point");
            safePoint.start();
            safePoint.join(5_000);
            Arrays.fill(mapperRam, 9);

            ExecutorService worker =
                    Executors.newSingleThreadExecutor(
                            runnable -> new Thread(runnable, "test-persistence-worker"));
            BatteryPersistenceResult result;
            try {
                result = worker.submit(captured.get()::persist).get(5, TimeUnit.SECONDS);
            } finally {
                worker.shutdownNow();
            }

            assertTrue(result instanceof BatteryPersistenceResult.Success);
            assertEquals("test-persistence-worker", persistence.writeThread);
            assertArrayEquals(new byte[] {1, 2, 3, 4}, Files.readAllBytes(target));
            assertTrue("I/O success is not committed from the worker", battery.isDirtyForTesting());

            captured.get().complete(result);
            assertFalse(battery.isDirtyForTesting());
        });
    }

    @Test
    public void typedBarrierFailureRetainsCaptureForRetry() throws Exception {
        withDirectory(directory -> {
            Path target = directory.resolve("retry.sav");
            FileBattery battery = new FileBattery(target.toFile(), 4, new FailOnceWriter());
            BatteryFlush capture =
                    battery.prepareFlush(
                            () -> {
                                battery.saveRam(new int[] {8, 7, 6, 5});
                                battery.flush();
                            });

            BatteryPersistenceResult failed = capture.persist();

            assertTrue(failed instanceof BatteryPersistenceResult.Failure);
            BatteryPersistenceResult.Failure failure =
                    (BatteryPersistenceResult.Failure) failed;
            assertEquals(
                    BatteryPersistenceResult.FailureKind.WRITE_FAILED,
                    failure.kind());
            capture.complete(failed);
            assertTrue(battery.isDirtyForTesting());

            BatteryPersistenceResult retried = capture.persist();
            assertTrue(retried instanceof BatteryPersistenceResult.Success);
            capture.complete(retried);
            assertFalse(battery.isDirtyForTesting());
            assertArrayEquals(new byte[] {8, 7, 6, 5}, Files.readAllBytes(target));
        });
    }

    @Test
    public void importsLegacySaveOnlyWhenCallerProvesArchiveIsUnambiguous()
            throws Exception {
        withDirectory(directory -> {
            Path legacy = directory.resolve("collection.sav");
            Files.write(legacy, new byte[] {4, 3, 2, 1});

            Path migrated = directory.resolve("collection--only-123.sav");
            FileBattery allowed =
                    new FileBattery(
                            migrated.toFile(),
                            legacy.toFile(),
                            true,
                            4,
                            AtomicFileWriter.system());
            int[] loaded = new int[4];
            allowed.loadRam(loaded);

            assertArrayEquals(new int[] {4, 3, 2, 1}, loaded);
            assertArrayEquals(new byte[] {4, 3, 2, 1}, Files.readAllBytes(migrated));
            assertTrue("legacy fallback remains available to older versions", Files.exists(legacy));

            Path ambiguous = directory.resolve("collection--other-456.sav");
            FileBattery denied =
                    new FileBattery(
                            ambiguous.toFile(),
                            legacy.toFile(),
                            false,
                            4,
                            AtomicFileWriter.system());
            int[] unchanged = {9, 9, 9, 9};
            denied.loadRam(unchanged);

            assertArrayEquals(new int[] {9, 9, 9, 9}, unchanged);
            assertFalse(Files.exists(ambiguous));
        });
    }

    @Test
    public void failedLegacyImportRemainsRetryable() throws Exception {
        withDirectory(directory -> {
            Path legacy = directory.resolve("collection.sav");
            Path migrated = directory.resolve("collection--only-123.sav");
            Files.write(legacy, new byte[] {4, 3, 2, 1});
            FileBattery battery =
                    new FileBattery(
                            migrated.toFile(),
                            legacy.toFile(),
                            true,
                            4,
                            new FailOnceWriter());
            int[] loaded = {9, 9, 9, 9};

            battery.loadRam(loaded);
            assertArrayEquals(new int[] {9, 9, 9, 9}, loaded);
            assertFalse(Files.exists(migrated));

            battery.loadRam(loaded);
            assertArrayEquals(new int[] {4, 3, 2, 1}, loaded);
            assertArrayEquals(new byte[] {4, 3, 2, 1}, Files.readAllBytes(migrated));
        });
    }

    @Test
    public void oversizedLegacyImportIsRejectedWithoutCreatingATarget() throws Exception {
        withDirectory(directory -> {
            Path legacy = directory.resolve("collection.sav");
            Path migrated = directory.resolve("collection--only-123.sav");
            Files.write(legacy, new byte[4 + 11 * Integer.BYTES + 1]);
            FileBattery battery =
                    new FileBattery(
                            migrated.toFile(),
                            legacy.toFile(),
                            true,
                            4,
                            AtomicFileWriter.system());
            int[] unchanged = {9, 9, 9, 9};

            battery.loadRam(unchanged);

            assertArrayEquals(new int[] {9, 9, 9, 9}, unchanged);
            assertFalse(Files.exists(migrated));
        });
    }

    @Test
    public void failedRamAndRtcFlushRetainsExactPendingBytesAndRetryClearsDirty()
            throws Exception {
        withDirectory(directory -> {
            Path target = directory.resolve("clock.sav");
            byte[] old = "old-complete-battery".getBytes();
            Files.write(target, old);
            FailOnceWriter persistence = new FailOnceWriter();
            FileBattery battery = new FileBattery(target.toFile(), 8, persistence);
            EventBusImpl eventBus = new EventBusImpl();
            LinkedBlockingQueue<BatteryPersistenceFailedEvent> failures =
                    new LinkedBlockingQueue<>();
            eventBus.register(failures::add, BatteryPersistenceFailedEvent.class);
            battery.init(eventBus);
            int[] ram = {1, 2, 3, 4, 250, 251, 252, 253};
            long[] clock = new long[11];
            for (int i = 0; i < clock.length; i++) {
                clock[i] = 0x10203040L + i;
            }
            byte[] expected = expectedBytes(ram, clock);

            battery.saveRamWithClock(ram, clock);
            Arrays.fill(ram, 0);
            Arrays.fill(clock, 0);
            battery.flush();

            assertArrayEquals(old, Files.readAllBytes(target));
            assertTrue(battery.isDirtyForTesting());
            assertTrue(battery.isClockPresentForTesting());
            BatteryPersistenceFailedEvent event = failures.poll(2, TimeUnit.SECONDS);
            assertNotNull(event);
            assertEquals(BatteryPersistenceFailedEvent.Operation.SAVE, event.operation());
            assertTrue(event.message().contains("Changes remain pending"));

            battery.flush();
            assertArrayEquals(expected, Files.readAllBytes(target));
            assertFalse(battery.isDirtyForTesting());
            assertFalse(battery.isClockPresentForTesting());
            eventBus.close();
        });
    }

    @Test
    public void twelveElementRtcCaptureRetainsLegacyElevenWordFileFormat() throws Exception {
        withDirectory(directory -> {
            Path target = directory.resolve("huc3.sav");
            FileBattery battery = new FileBattery(target.toFile(), 4);
            long[] clock = new long[12];
            for (int i = 0; i < 11; i++) {
                clock[i] = i + 1;
            }

            battery.saveRamWithClock(new int[] {1, 2, 3, 4}, clock);
            battery.flush();

            byte[] persisted = Files.readAllBytes(target);
            assertEquals(4 + 11 * Integer.BYTES, persisted.length);
            ByteBuffer words =
                    ByteBuffer.wrap(persisted, 4, 11 * Integer.BYTES)
                            .order(ByteOrder.LITTLE_ENDIAN);
            for (int i = 0; i < 11; i++) {
                assertEquals(i + 1, words.getInt());
            }
        });
    }

    @Test
    public void reportedPostRenameFailureKeepsRetryStateEvenWhenNewFileIsVisible()
            throws Exception {
        withDirectory(directory -> {
            Path target = directory.resolve("post-rename.sav");
            Files.write(target, new byte[] {9, 9, 9, 9});
            CommitThenFailOnceWriter persistence = new CommitThenFailOnceWriter();
            FileBattery battery = new FileBattery(target.toFile(), 4, persistence);
            int[] ram = {5, 6, 7, 8};

            battery.saveRam(ram);
            battery.flush();

            assertArrayEquals(new byte[] {5, 6, 7, 8}, Files.readAllBytes(target));
            assertTrue(battery.isDirtyForTesting());

            battery.flush();
            assertArrayEquals(new byte[] {5, 6, 7, 8}, Files.readAllBytes(target));
            assertFalse(battery.isDirtyForTesting());
        });
    }

    @Test
    public void newerGenerationWaitingForOlderFlushIsNeverClearedByThatFlush()
            throws Exception {
        withDirectory(directory -> {
            Path target = directory.resolve("generation.sav");
            BlockingWriter persistence = new BlockingWriter();
            FileBattery battery = new FileBattery(target.toFile(), 4, persistence);
            int[] older = {1, 1, 1, 1};
            int[] newer = {2, 2, 2, 2};
            AtomicReference<Throwable> failure = new AtomicReference<>();

            battery.saveRam(older);
            Thread flushing = new Thread(() -> run(battery::flush, failure));
            flushing.start();
            assertTrue(persistence.entered.await(5, TimeUnit.SECONDS));

            Thread updating = new Thread(() -> run(() -> battery.saveRam(newer), failure));
            updating.start();
            Thread.sleep(50);
            persistence.release.countDown();
            flushing.join(5_000);
            updating.join(5_000);
            if (failure.get() != null) {
                throw new AssertionError(failure.get());
            }

            assertArrayEquals(new byte[] {1, 1, 1, 1}, Files.readAllBytes(target));
            assertTrue("the newer generation must remain dirty", battery.isDirtyForTesting());

            battery.flush();
            assertArrayEquals(new byte[] {2, 2, 2, 2}, Files.readAllBytes(target));
            assertFalse(battery.isDirtyForTesting());
        });
    }

    @Test
    public void loadUsesRecoveryRoutePreservesSmallTruncatedSemanticsAndReportsIoFailure()
            throws Exception {
        withDirectory(directory -> {
            Path target = directory.resolve("small.sav");
            Files.write(target, new byte[] {0x11, 0x22, (byte) 0xfe});
            TrackingWriter persistence = new TrackingWriter();
            FileBattery battery = new FileBattery(target.toFile(), 8, persistence);
            int[] ram = new int[8];

            battery.loadRam(ram);

            assertEquals(1, persistence.reads);
            assertArrayEquals(new int[] {0x11, 0x22, 0xfe, 0, 0, 0, 0, 0}, ram);

            EventBusImpl eventBus = new EventBusImpl();
            LinkedBlockingQueue<BatteryPersistenceFailedEvent> failures =
                    new LinkedBlockingQueue<>();
            eventBus.register(failures::add, BatteryPersistenceFailedEvent.class);
            eventBus.register(
                    ignored -> {
                        throw new IllegalStateException("broken UI subscriber");
                    },
                    BatteryPersistenceFailedEvent.class);
            FileBattery failing =
                    new FileBattery(
                            directory.resolve("unreadable.sav").toFile(),
                            4,
                            new ReadFailingWriter());
            int[] unchanged = {7, 7, 7, 7};
            // Construction-time mapper loads happen before Gameboy.init; the signal is retained
            // and delivered when the session event route becomes available.
            failing.loadRam(unchanged);
            failing.init(eventBus);

            assertArrayEquals(new int[] {7, 7, 7, 7}, unchanged);
            BatteryPersistenceFailedEvent event = failures.poll(2, TimeUnit.SECONDS);
            assertNotNull(event);
            assertEquals(BatteryPersistenceFailedEvent.Operation.LOAD, event.operation());
            assertEquals("unreadable.sav", event.fileName());
            assertTrue(event.message().contains("Unable to load battery save"));
            assertEquals("execution continues after a broken subscriber", 4, unchanged.length);
            eventBus.close();
        });
    }

    @Test
    public void managedTargetImportsFirstSafeFallbackWithoutDeletingIt() throws Exception {
        withDirectory(directory -> {
            Path activeRoot = Files.createDirectory(directory.resolve("active"));
            Path previousRoot = Files.createDirectory(directory.resolve("previous"));
            Path identity = Path.of("games", "a".repeat(64), "battery.sav");
            Path target = activeRoot.resolve(identity);
            Path fallback = previousRoot.resolve(identity);
            Files.createDirectories(fallback.getParent());
            Files.write(fallback, new byte[] {4, 3, 2, 1});
            BatteryStorage storage =
                    new BatteryStorage(
                            BatteryStorage.Source.managed(target, activeRoot),
                            List.of(BatteryStorage.Source.managed(fallback, previousRoot)));
            FileBattery battery = new FileBattery(storage, 4);
            int[] loaded = new int[4];

            battery.loadRam(loaded);

            assertArrayEquals(new int[] {4, 3, 2, 1}, loaded);
            assertArrayEquals(new byte[] {4, 3, 2, 1}, Files.readAllBytes(target));
            assertArrayEquals(new byte[] {4, 3, 2, 1}, Files.readAllBytes(fallback));
        });
    }

    @Test
    public void newerPreviousDestinationReplacesStaleActiveTargetWithoutDeletingEitherGeneration()
            throws Exception {
        withDirectory(directory -> {
            Path active = Files.createDirectory(directory.resolve("active"));
            Path previous = Files.createDirectory(directory.resolve("previous"));
            Path target = active.resolve("battery.sav");
            Path fallback = previous.resolve("battery.sav");
            Files.write(target, new byte[] {1, 1, 1, 1});
            Files.write(fallback, new byte[] {8, 7, 6, 5});
            Files.setLastModifiedTime(target, FileTime.fromMillis(1_000));
            Files.setLastModifiedTime(fallback, FileTime.fromMillis(2_000));
            FileBattery battery =
                    new FileBattery(
                            new BatteryStorage(
                                    BatteryStorage.Source.managed(target, active),
                                    List.of(
                                            BatteryStorage.Source.managed(
                                                    fallback, previous))),
                            4);
            int[] loaded = new int[4];

            battery.loadRam(loaded);

            assertArrayEquals(new int[] {8, 7, 6, 5}, loaded);
            assertArrayEquals(new byte[] {8, 7, 6, 5}, Files.readAllBytes(target));
            assertArrayEquals(new byte[] {8, 7, 6, 5}, Files.readAllBytes(fallback));
        });
    }

    @Test
    public void newerActiveDestinationWinsAfterMigrationAndIsNotRolledBackOnRestart()
            throws Exception {
        withDirectory(directory -> {
            Path active = Files.createDirectory(directory.resolve("active"));
            Path previous = Files.createDirectory(directory.resolve("previous"));
            Path target = active.resolve("battery.sav");
            Path fallback = previous.resolve("battery.sav");
            Files.write(target, new byte[] {9, 9, 9, 9});
            Files.write(fallback, new byte[] {2, 2, 2, 2});
            Files.setLastModifiedTime(fallback, FileTime.fromMillis(1_000));
            Files.setLastModifiedTime(target, FileTime.fromMillis(2_000));
            FileBattery battery =
                    new FileBattery(
                            new BatteryStorage(
                                    BatteryStorage.Source.managed(target, active),
                                    List.of(
                                            BatteryStorage.Source.managed(
                                                    fallback, previous))),
                            4);
            int[] loaded = new int[4];

            battery.loadRam(loaded);

            assertArrayEquals(new int[] {9, 9, 9, 9}, loaded);
            assertArrayEquals(new byte[] {9, 9, 9, 9}, Files.readAllBytes(target));
            assertArrayEquals(new byte[] {2, 2, 2, 2}, Files.readAllBytes(fallback));
        });
    }

    @Test
    public void managedTargetRefusesSymlinkTraversalWithoutWritingOutsideRoot() throws Exception {
        withDirectory(directory -> {
            Path activeRoot = Files.createDirectory(directory.resolve("active"));
            Path outside = Files.createDirectory(directory.resolve("outside"));
            Path games = Files.createDirectory(activeRoot.resolve("games"));
            Path linkedIdentity = games.resolve("b".repeat(64));
            try {
                Files.createSymbolicLink(linkedIdentity, outside);
            } catch (IOException | UnsupportedOperationException unsupported) {
                return;
            }
            Path target = linkedIdentity.resolve("battery.sav");
            FileBattery battery =
                    new FileBattery(
                            new BatteryStorage(
                                    BatteryStorage.Source.managed(target, activeRoot),
                                    List.of()),
                            4);
            battery.saveRam(new int[] {1, 2, 3, 4});
            BatteryFlush flush = battery.prepareFlush(() -> {});

            BatteryPersistenceResult result = flush.persist();

            assertTrue(result instanceof BatteryPersistenceResult.Failure);
            assertFalse(Files.exists(outside.resolve("battery.sav")));
            assertTrue(battery.isDirtyForTesting());
        });
    }

    @Test
    public void directTargetRefusesSymbolicLinkParentWithoutWritingThroughIt() throws Exception {
        withDirectory(directory -> {
            Path outside = Files.createDirectory(directory.resolve("outside"));
            Path linkedParent = directory.resolve("linked-parent");
            try {
                Files.createSymbolicLink(linkedParent, outside);
            } catch (IOException | UnsupportedOperationException unsupported) {
                return;
            }
            Path target = linkedParent.resolve("battery.sav");
            FileBattery battery =
                    new FileBattery(
                            BatteryStorage.direct(target),
                            4,
                            AtomicFileWriter.system());
            battery.saveRam(new int[] {1, 2, 3, 4});

            BatteryPersistenceResult result = battery.prepareFlush(() -> {}).persist();

            assertTrue(result instanceof BatteryPersistenceResult.Failure);
            assertFalse(Files.exists(outside.resolve("battery.sav")));
            assertTrue(battery.isDirtyForTesting());
        });
    }

    @Test
    public void directTargetRefusesHigherAncestorLinkWithoutWritingThroughIt() throws Exception {
        withDirectory(directory -> {
            Path outside = Files.createDirectory(directory.resolve("outside"));
            Path realParent = Files.createDirectory(outside.resolve("real-parent"));
            Path linkedAncestor = directory.resolve("linked-ancestor");
            try {
                Files.createSymbolicLink(linkedAncestor, outside);
            } catch (IOException | UnsupportedOperationException unsupported) {
                return;
            }
            Path target = linkedAncestor.resolve(realParent.getFileName()).resolve("battery.sav");
            FileBattery battery =
                    new FileBattery(
                            BatteryStorage.direct(target),
                            4,
                            AtomicFileWriter.system());
            battery.saveRam(new int[] {4, 3, 2, 1});

            BatteryPersistenceResult result = battery.prepareFlush(() -> {}).persist();

            assertTrue(result instanceof BatteryPersistenceResult.Failure);
            assertFalse(Files.exists(realParent.resolve("battery.sav")));
            assertTrue(battery.isDirtyForTesting());
        });
    }

    @Test
    public void batteryImportListIsBoundedAndReconfigurationRetainsCapturedDestination()
            throws Exception {
        withDirectory(directory -> {
            List<BatteryStorage.Source> excessive = new ArrayList<>();
            for (int i = 0; i <= BatteryStorage.MAX_IMPORT_SOURCES; i++) {
                excessive.add(BatteryStorage.Source.direct(directory.resolve("old-" + i + ".sav")));
            }
            assertThrows(
                    IllegalArgumentException.class,
                    () ->
                            new BatteryStorage(
                                    BatteryStorage.Source.direct(directory.resolve("target.sav")),
                                    excessive));

            Path first = directory.resolve("first.sav");
            Path second = directory.resolve("second.sav");
            FileBattery battery =
                    new FileBattery(BatteryStorage.direct(first), 4, AtomicFileWriter.system());
            battery.saveRam(new int[] {9, 8, 7, 6});
            BatteryFlush captured = battery.prepareFlush(() -> {});
            battery.setStorage(BatteryStorage.direct(second));

            BatteryPersistenceResult firstResult = captured.persist();
            captured.complete(firstResult);

            assertArrayEquals(new byte[] {9, 8, 7, 6}, Files.readAllBytes(first));
            assertFalse(Files.exists(second));
            assertTrue(battery.isDirtyForTesting());

            BatteryFlush redirected =
                    battery.prepareFlush(() -> battery.saveRam(new int[] {9, 8, 7, 6}));
            BatteryPersistenceResult redirectedResult = redirected.persist();
            redirected.complete(redirectedResult);
            assertArrayEquals(new byte[] {9, 8, 7, 6}, Files.readAllBytes(second));
            assertFalse(battery.isDirtyForTesting());
        });
    }

    @Test
    public void failedCapturedFlushReportsItsOldTargetAfterStorageIsRetargeted()
            throws Exception {
        withDirectory(directory -> {
            Path oldTarget = directory.resolve("old-target.sav");
            Path newTarget = directory.resolve("new-target.sav");
            BlockingFailingWriter persistence = new BlockingFailingWriter();
            FileBattery battery =
                    new FileBattery(
                            BatteryStorage.direct(oldTarget),
                            4,
                            persistence);
            EventBusImpl eventBus = new EventBusImpl();
            LinkedBlockingQueue<BatteryPersistenceFailedEvent> failures =
                    new LinkedBlockingQueue<>();
            eventBus.register(failures::add, BatteryPersistenceFailedEvent.class);
            battery.init(eventBus);
            battery.saveRam(new int[] {1, 2, 3, 4});
            Thread oldFlush = new Thread(battery::flush, "old-battery-flush");
            try {
                oldFlush.start();
                assertTrue(persistence.entered.await(5, TimeUnit.SECONDS));
                battery.setStorage(BatteryStorage.direct(newTarget));
                persistence.release.countDown();
                oldFlush.join(5_000);

                assertFalse(oldFlush.isAlive());
                BatteryPersistenceFailedEvent failure = failures.poll(2, TimeUnit.SECONDS);
                assertNotNull(failure);
                assertEquals(BatteryPersistenceFailedEvent.Operation.SAVE, failure.operation());
                assertEquals(oldTarget.getFileName().toString(), failure.fileName());
                assertTrue(failure.message().contains(oldTarget.getFileName().toString()));
                assertFalse(failure.message().contains(newTarget.getFileName().toString()));
                assertFalse(Files.exists(oldTarget));
                assertFalse(Files.exists(newTarget));
                assertTrue(battery.isDirtyForTesting());
            } finally {
                persistence.release.countDown();
                oldFlush.join(5_000);
                eventBus.close();
            }
        });
    }

    @Test
    public void fallbackRejectsAncestorSymlinkAndNeverImportsOutsideBytes() throws Exception {
        withDirectory(directory -> {
            Path active = Files.createDirectory(directory.resolve("active"));
            Path outside = Files.createDirectory(directory.resolve("outside"));
            Path linked = directory.resolve("linked");
            try {
                Files.createSymbolicLink(linked, outside);
            } catch (IOException | UnsupportedOperationException unsupported) {
                return;
            }
            Path fallback = linked.resolve("legacy.sav");
            Files.write(outside.resolve("legacy.sav"), new byte[] {6, 6, 6, 6});
            Path target = active.resolve("battery.sav");
            FileBattery battery =
                    new FileBattery(
                            BatteryStorage.direct(target, List.of(fallback)),
                            4,
                            AtomicFileWriter.system());
            int[] unchanged = {9, 9, 9, 9};

            battery.loadRam(unchanged);

            assertArrayEquals(new int[] {9, 9, 9, 9}, unchanged);
            assertFalse(Files.exists(target));
            assertArrayEquals(
                    new byte[] {6, 6, 6, 6},
                    Files.readAllBytes(outside.resolve("legacy.sav")));
        });
    }

    @Test
    public void recoveryReaderCannotSubstituteAnUnapprovedSibling() throws Exception {
        withDirectory(directory -> {
            Path active = Files.createDirectory(directory.resolve("active"));
            Path previous = Files.createDirectory(directory.resolve("previous"));
            Path target = active.resolve("battery.sav");
            Path fallback = previous.resolve("battery.sav");
            Path attacker = previous.resolve("other.sav");
            Files.write(fallback, new byte[] {1, 2, 3, 4});
            Files.write(attacker, new byte[] {8, 8, 8, 8});
            BatteryStorage storage =
                    new BatteryStorage(
                            BatteryStorage.Source.managed(target, active),
                            List.of(BatteryStorage.Source.managed(fallback, previous)));
            FileBattery battery =
                    new FileBattery(
                            storage,
                            4,
                            new SubstitutingRecoveryWriter(fallback, attacker));
            int[] unchanged = {7, 7, 7, 7};

            battery.loadRam(unchanged);

            assertArrayEquals(new int[] {7, 7, 7, 7}, unchanged);
            assertFalse(Files.exists(target));
        });
    }

    @Test
    public void recoveredFallbackIsRevalidatedAndImportedFromItsExactDeclaredPath()
            throws Exception {
        withDirectory(directory -> {
            Path active = Files.createDirectory(directory.resolve("active"));
            Path previous = Files.createDirectory(directory.resolve("previous"));
            Path target = active.resolve("battery.sav");
            Path fallback = previous.resolve("battery.sav");
            BatteryStorage storage =
                    new BatteryStorage(
                            BatteryStorage.Source.managed(target, active),
                            List.of(BatteryStorage.Source.managed(fallback, previous)));
            FileBattery battery =
                    new FileBattery(
                            storage,
                            4,
                            new RecoveringFallbackWriter(
                                    fallback,
                                    new byte[] {5, 4, 3, 2}));
            int[] loaded = new int[4];

            battery.loadRam(loaded);

            assertArrayEquals(new int[] {5, 4, 3, 2}, loaded);
            assertArrayEquals(new byte[] {5, 4, 3, 2}, Files.readAllBytes(target));
            assertArrayEquals(new byte[] {5, 4, 3, 2}, Files.readAllBytes(fallback));
        });
    }

    private static byte[] expectedBytes(int[] ram, long[] clock) {
        ByteBuffer bytes =
                ByteBuffer.allocate(ram.length + clock.length * Integer.BYTES)
                        .order(ByteOrder.LITTLE_ENDIAN);
        for (int value : ram) {
            bytes.put((byte) value);
        }
        for (long value : clock) {
            bytes.putInt((int) value);
        }
        return bytes.array();
    }

    private static void run(Runnable action, AtomicReference<Throwable> failure) {
        try {
            action.run();
        } catch (Throwable t) {
            failure.compareAndSet(null, t);
        }
    }

    private static void withDirectory(DirectoryTest test) throws Exception {
        Path directory = Files.createTempDirectory("coffee-gb-file-battery");
        try {
            test.run(directory);
        } finally {
            try (var files = Files.walk(directory)) {
                files.sorted(java.util.Comparator.reverseOrder()).forEach(path -> {
                    try {
                        Files.deleteIfExists(path);
                    } catch (IOException e) {
                        throw new RuntimeException(e);
                    }
                });
            }
        }
    }

    @FunctionalInterface
    private interface DirectoryTest {
        void run(Path directory) throws Exception;
    }

    private static class FailOnceWriter extends AtomicFileWriter {
        private boolean fail = true;

        @Override
        public void write(Path target, byte[] intendedBytes) throws IOException {
            if (fail) {
                fail = false;
                throw new IOException("injected before write");
            }
            AtomicFileWriter.system().write(target, intendedBytes);
        }
    }

    private static class ThreadTrackingWriter extends AtomicFileWriter {

        private volatile String writeThread;

        @Override
        public void write(Path target, byte[] intendedBytes) throws IOException {
            writeThread = Thread.currentThread().getName();
            AtomicFileWriter.system().write(target, intendedBytes);
        }
    }

    private static class CommitThenFailOnceWriter extends AtomicFileWriter {
        private boolean fail = true;

        @Override
        public void write(Path target, byte[] intendedBytes) throws IOException {
            AtomicFileWriter.system().write(target, intendedBytes);
            if (fail) {
                fail = false;
                throw new IOException("injected after commit");
            }
        }
    }

    private static class BlockingWriter extends AtomicFileWriter {
        private final CountDownLatch entered = new CountDownLatch(1);

        private final CountDownLatch release = new CountDownLatch(1);

        @Override
        public void write(Path target, byte[] intendedBytes) throws IOException {
            if (entered.getCount() > 0) {
                entered.countDown();
                try {
                    if (!release.await(5, TimeUnit.SECONDS)) {
                        throw new IOException("test timed out");
                    }
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    throw new IOException("test interrupted", e);
                }
            }
            AtomicFileWriter.system().write(target, intendedBytes);
        }
    }

    private static class BlockingFailingWriter extends AtomicFileWriter {
        private final CountDownLatch entered = new CountDownLatch(1);

        private final CountDownLatch release = new CountDownLatch(1);

        @Override
        public void write(Path target, byte[] intendedBytes) throws IOException {
            entered.countDown();
            try {
                if (!release.await(5, TimeUnit.SECONDS)) {
                    throw new IOException("test timed out");
                }
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                throw new IOException("test interrupted", e);
            }
            throw new IOException("injected captured-target failure");
        }
    }

    private static class TrackingWriter extends AtomicFileWriter {
        private int reads;

        @Override
        public <T> T read(Path target, PathReader<T> reader) throws IOException {
            reads++;
            return AtomicFileWriter.system().read(target, reader);
        }
    }

    private static class ReadFailingWriter extends AtomicFileWriter {
        @Override
        public <T> T read(Path target, PathReader<T> reader) throws IOException {
            throw new IOException("injected recovery failure");
        }
    }

    private static class SubstitutingRecoveryWriter extends AtomicFileWriter {
        private final Path expected;

        private final Path substituted;

        private SubstitutingRecoveryWriter(Path expected, Path substituted) {
            this.expected = expected;
            this.substituted = substituted;
        }

        @Override
        public <T> T read(Path target, PathReader<T> reader) throws IOException {
            if (target.toAbsolutePath().normalize().equals(expected.toAbsolutePath().normalize())) {
                return reader.read(substituted);
            }
            return AtomicFileWriter.system().read(target, reader);
        }
    }

    private static class RecoveringFallbackWriter extends AtomicFileWriter {
        private final Path recovered;

        private final byte[] bytes;

        private RecoveringFallbackWriter(Path recovered, byte[] bytes) {
            this.recovered = recovered.toAbsolutePath().normalize();
            this.bytes = bytes;
        }

        @Override
        public boolean exists(Path target) throws IOException {
            Path normalized = target.toAbsolutePath().normalize();
            if (normalized.equals(recovered) && !Files.exists(normalized)) {
                Files.write(normalized, bytes);
            }
            return AtomicFileWriter.system().exists(normalized);
        }

        @Override
        public <T> T read(Path target, PathReader<T> reader) throws IOException {
            return AtomicFileWriter.system().read(target, reader);
        }

        @Override
        public void write(Path target, byte[] intendedBytes) throws IOException {
            AtomicFileWriter.system().write(target, intendedBytes);
        }
    }
}
