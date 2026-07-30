package eu.rekawek.coffeegb.core.serial.mobile;

import eu.rekawek.coffeegb.core.hardware.ClockSpec;
import org.junit.Test;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.regex.Pattern;
import java.util.stream.Collectors;

import static org.junit.Assert.assertArrayEquals;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;

public class MobileAdapterContractTest {

    @Test
    public void sourceInventoryIsPinnedLicensedScopedAndCleanRoom() throws Exception {
        List<Map<String, String>> sources = rows("/mobile-adapter/sources.tsv");
        assertEquals(Set.of("DAN_PACKET", "DAN_COMMANDS", "DAN_TIMEOUT",
                "LIBMOBILE_CORROBORATION", "ROADMAP_311"),
                sources.stream().map(r -> r.get("key")).collect(Collectors.toSet()));
        for (Map<String, String> source : sources) {
            for (String field : List.of("upstream", "revision", "url", "license", "accessed",
                    "claim", "disagreement", "redistribution")) {
                assertFalse(source.get("key") + ":" + field, source.get(field).isBlank());
            }
            assertEquals("2026-07-26", source.get("accessed"));
        }
        assertTrue(sources.stream().anyMatch(r -> r.get("revision").equals(
                "490595c3b8506d3f155aa6be9d7a5cd7d0fa9a5b") &&
                r.get("license").equals("GPL-3.0")));
        assertTrue(sources.stream().anyMatch(r -> r.get("revision").equals(
                "0704f56902f23b7ebf05c82c222e0e145e3140b6") &&
                r.get("redistribution").contains("no-code")));

        String contract = read(repositoryRoot().resolve("docs/mobile-adapter-contract.md"));
        String adr = read(repositoryRoot().resolve("docs/adr/0002-mobile-adapter-clean-room.md"));
        for (String required : List.of("99 66", "96 66", "254 bytes", "3,000 ms",
                "no third-party implementation source", "never a rollback")) {
            assertTrue("Missing documented clean-room decision: " + required,
                    contract.contains(required) || adr.contains(required));
        }
    }

    @Test
    public void commandInventoryIsConservativeAndHasNoSpeculativeServiceSupport() throws Exception {
        List<Map<String, String>> commands = rows("/mobile-adapter/commands.tsv");
        assertEquals(commands.size(), commands.stream().map(r -> r.get("id")).distinct().count());
        Map<String, String> statuses = commands.stream().collect(Collectors.toMap(
                r -> r.get("id"), r -> r.get("phase_351_status")));
        assertEquals("supported", statuses.get("0x10"));
        assertEquals("supported", statuses.get("0x11"));
        assertEquals("supported", statuses.get("0x16"));
        assertEquals("supported", statuses.get("0x19"));
        assertEquals("specified-later-opt-in", statuses.get("0x1a"));
        for (String networkCommand : List.of("0x12", "0x14", "0x15", "0x21", "0x23",
                "0x25", "0x28", "0x3f")) {
            assertEquals(networkCommand, "unsupported", statuses.get(networkCommand));
        }

        Map<String, String> phase352Statuses = commands.stream().collect(Collectors.toMap(
                r -> r.get("id"), r -> r.get("phase_352_status")));
        for (String minimumCommand : List.of("0x10", "0x11", "0x16", "0x19")) {
            assertEquals(minimumCommand, "supported", phase352Statuses.get(minimumCommand));
        }
        assertEquals("supported-pure", phase352Statuses.get("0x1a"));
        for (String directBackendCommand : List.of(
                "0x15", "0x23", "0x24", "0x25", "0x26", "0x28")) {
            assertEquals(directBackendCommand, "custom-backend-direct-channel",
                    phase352Statuses.get(directBackendCommand));
        }
        for (String excludedServiceCommand : List.of("0x12", "0x21", "0x22")) {
            assertEquals(excludedServiceCommand, "unsupported",
                    phase352Statuses.get(excludedServiceCommand));
        }
        for (Map<String, String> row : commands) {
            assertFalse(row.get("evidence").isBlank());
            assertFalse(row.get("uncertainty").isBlank());
        }
    }

    @Test
    public void deterministicTranscriptsHaveExactFramingChecksumsTimingAndHashes() throws Exception {
        List<Map<String, String>> transcripts = rows("/mobile-adapter/transcripts.tsv");
        assertEquals(Set.of("begin_session", "end_session", "reset", "invalid_checksum", "timeout",
                "timeout_exact_boundary", "config_read", "max_payload_unsupported", "config_read_boundary",
                "payload_boundary_plus_one"),
                transcripts.stream().map(r -> r.get("id")).collect(Collectors.toSet()));

        for (Map<String, String> row : transcripts) {
            byte[] request = hex(row.get("request_hex"));
            byte[] response = optionalHex(row.get("response_hex"));
            byte[] ack = optionalHex(row.get("ack_hex"));
            assertEquals(row.get("sha256"), sha256(join(request, response, ack)));
            assertTrue(row.get("provenance").startsWith("coffee-gb-synthetic"));
            assertTrue(Integer.parseInt(row.get("buffer_limit")) <= 262);
            assertEquals(2, Integer.parseInt(row.get("pending_slot_limit")));

            List<FragmentStep> fragments = fragmentSteps(row.get("fragments"));
            assertEquals(request.length,
                    fragments.stream().mapToInt(FragmentStep::count).sum());
            long previous = -1;
            long productionMillis = 0;
            int requestOffset = 0;
            ReferenceMobileEngine engine = new ReferenceMobileEngine(row.get("initial_state"));
            MobileAdapterEngine production = productionEngine(row.get("initial_state"));
            for (int i = 0; i < fragments.size(); i++) {
                FragmentStep fragment = fragments.get(i);
                assertTrue(row.get("id"), fragment.millis >= previous);
                previous = fragment.millis;
                int next = Math.addExact(requestOffset, fragment.count);
                assertTrue(row.get("id"), next <= request.length);
                EngineResult current;
                MobileAdapterEngine.EngineResult productionCurrent =
                        production.advanceTicks(fragment.millis - productionMillis);
                productionMillis = fragment.millis;
                if (fragment.count == 0) {
                    current = engine.advanceTo(fragment.millis);
                } else {
                    current = engine.feed(Arrays.copyOfRange(request, requestOffset, next),
                            fragment.millis);
                    for (int offset = requestOffset; offset < next; offset++) {
                        productionCurrent = production.acceptByte(request[offset] & 0xff);
                    }
                    requestOffset = next;
                }
                if (requestOffset < request.length &&
                        !row.get("expected_result").equals("LENGTH_LIMIT")) {
                    assertEquals(row.get("id") + ":fragment=" + i,
                            "NEED_MORE", current.outcome);
                    assertEquals(0, current.response.length);
                    assertEquals(0, current.ack.length);
                    assertEquals(0, current.commits);
                    assertEquals(row.get("id") + ":production-fragment=" + i,
                            MobileAdapterEngine.Outcome.NEED_MORE, productionCurrent.outcome());
                    assertEquals(0, productionCurrent.responsePacket().length);
                    assertEquals(0, productionCurrent.acknowledgement().length);
                }
                assertTrue(current.retainedBytes <= 262);
                assertTrue(current.pendingSlots <= 2);
                assertTrue(productionCurrent.retainedBytes() <= 262);
                assertTrue(productionCurrent.pendingPacketSlots() <= 2);
            }
            assertEquals(request.length, requestOffset);
            EngineResult engineResult = engine.snapshot();
            MobileAdapterEngine.EngineResult productionResult = production.snapshot();
            assertEquals(row.get("id"), row.get("expected_state"), engineResult.state);
            assertEquals(row.get("id"), row.get("expected_result"), engineResult.outcome);
            assertArrayEquals(row.get("id"), response, engineResult.response);
            assertArrayEquals(row.get("id"), ack, engineResult.ack);
            assertEquals(row.get("id"), row.get("expected_state"),
                    productionResult.phase().name());
            assertEquals(row.get("id"), row.get("expected_result"),
                    productionResult.outcome().name());
            assertArrayEquals(row.get("id"), response, productionResult.responsePacket());
            assertArrayEquals(row.get("id"), ack, productionResult.acknowledgement());

            FragmentResult fragmentSummary = fragments(row.get("fragments"));
            if (row.get("expected_result").equals("IDLE_TIMEOUT_RESET")) {
                assertTrue(Math.subtractExact(fragmentSummary.lastMillis,
                        fragmentSummary.lastDataMillis) > 3_000);
                assertEquals(0, fragmentSummary.lastCount);
                assertEquals(0, engineResult.retainedBytes);
                assertEquals(0, engineResult.pendingSlots);
            }
            if (row.get("expected_result").equals("IDLE_BOUNDARY_WAIT")) {
                assertEquals(3_000,
                        Math.subtractExact(fragmentSummary.lastMillis,
                                fragmentSummary.lastDataMillis));
                assertEquals(0, fragmentSummary.lastCount);
                assertTrue(engineResult.retainedBytes > 0);
            }

            Packet requestPacket = parsePacket(request);
            if (row.get("expected_result").equals("LENGTH_LIMIT")) {
                assertEquals(255, requestPacket.declaredLength);
                assertFalse(requestPacket.complete);
                continue;
            }
            if (row.get("expected_result").equals("IDLE_TIMEOUT_RESET") ||
                    row.get("expected_result").equals("IDLE_BOUNDARY_WAIT")) {
                assertFalse(requestPacket.complete);
                continue;
            }
            assertTrue(requestPacket.complete);
            assertEquals(row.get("command"), String.format("0x%02x", requestPacket.command));
            if (row.get("expected_result").equals("CHECKSUM_ERROR")) {
                assertFalse(requestPacket.checksumValid);
                assertArrayEquals(new byte[]{(byte) 0x88, (byte) 0xf1}, ack);
                continue;
            }
            assertTrue(requestPacket.checksumValid);
            assertTrue(requestPacket.declaredLength <= 254);
            if (response.length > 0) {
                Packet responsePacket = parsePacket(response);
                assertTrue(responsePacket.complete);
                assertTrue(responsePacket.checksumValid);
                assertEquals(requestPacket.command | 0x80, responsePacket.command);
            }
        }
    }

    @Test
    public void incrementalEngineCleansPartialBuffersSlotsAndResponsesAtExactLifecycleBoundaries()
            throws Exception {
        Map<String, Map<String, String>> transcripts = rows("/mobile-adapter/transcripts.tsv")
                .stream().collect(Collectors.toMap(r -> r.get("id"), r -> r));
        byte[] begin = hex(transcripts.get("begin_session").get("request_hex"));
        ReferenceMobileEngine engine = new ReferenceMobileEngine("SLEEP");
        EngineResult badMagic = new ReferenceMobileEngine("SLEEP")
                .feed(new byte[]{(byte) 0x99, 0x65}, 0);
        assertEquals("MAGIC_ERROR", badMagic.outcome);
        assertEquals(0, badMagic.retainedBytes);
        assertEquals(0, badMagic.commits);
        EngineResult reserved = new ReferenceMobileEngine("SLEEP")
                .feed(new byte[]{(byte) 0x99, 0x66, 0x10, 0x01}, 0);
        assertEquals("RESERVED_ERROR", reserved.outcome);
        assertEquals(0, reserved.retainedBytes);
        assertEquals(0, reserved.commits);
        for (int cut : List.of(1, 2, 3, 4, 5, 6, begin.length - 2, begin.length - 1)) {
            ReferenceMobileEngine partial = new ReferenceMobileEngine("SLEEP");
            EngineResult pending = partial.feed(Arrays.copyOf(begin, cut), 0);
            assertEquals("cut=" + cut, "NEED_MORE", pending.outcome);
            assertEquals(0, pending.response.length);
            assertEquals(0, pending.ack.length);
            assertEquals(0, pending.commits);
            assertEquals(cut, pending.retainedBytes);
        }

        assertTrue(engine.reservePendingSlot());
        assertTrue(engine.reservePendingSlot());
        assertFalse(engine.reservePendingSlot());
        assertEquals("NEED_MORE", engine.feed(Arrays.copyOf(begin, 6), 0).outcome);
        EngineResult exact = engine.advanceTo(3_000);
        assertEquals("IDLE_BOUNDARY_WAIT", exact.outcome);
        assertEquals(2, exact.pendingSlots);
        assertEquals(6, exact.retainedBytes);
        EngineResult expired = engine.advanceTo(3_001);
        assertEquals("IDLE_TIMEOUT_RESET", expired.outcome);
        assertEquals("SLEEP", expired.state);
        assertEquals(0, expired.pendingSlots);
        assertEquals(0, expired.retainedBytes);
        assertEquals(0, expired.response.length);

        EngineResult started = engine.feed(begin, 3_002);
        assertEquals("SESSION_STARTED", started.outcome);
        assertEquals("SESSION", started.state);
        assertArrayEquals(hex(transcripts.get("begin_session").get("response_hex")),
                started.response);

        ReferenceMobileEngine sequential = new ReferenceMobileEngine("SLEEP");
        assertEquals("SESSION_STARTED", sequential.feed(begin, 0).outcome);
        EngineResult afterSuccessPartial = sequential.feed(Arrays.copyOf(begin, 3), 1);
        assertEquals("NEED_MORE", afterSuccessPartial.outcome);
        assertEquals(3, afterSuccessPartial.retainedBytes);
        assertEquals(0, afterSuccessPartial.response.length);
        assertEquals(0, afterSuccessPartial.ack.length);
        assertEquals(0, afterSuccessPartial.commits);

        ReferenceMobileEngine bytewise = new ReferenceMobileEngine("SLEEP");
        for (int i = 0; i < begin.length; i++) {
            EngineResult part = bytewise.feed(new byte[]{begin[i]}, i);
            assertEquals("byte=" + i, i == begin.length - 1 ? "SESSION_STARTED" : "NEED_MORE",
                    part.outcome);
        }
        ReferenceMobileEngine irregular = new ReferenceMobileEngine("SLEEP");
        int irregularOffset = 0;
        for (int size : List.of(1, 3, 2, 5, begin.length - 11)) {
            int endOffset = Math.addExact(irregularOffset, size);
            EngineResult part = irregular.feed(
                    Arrays.copyOfRange(begin, irregularOffset, endOffset), endOffset);
            irregularOffset = endOffset;
            assertEquals(irregularOffset == begin.length ? "SESSION_STARTED" : "NEED_MORE",
                    part.outcome);
        }
        assertEquals(begin.length, irregularOffset);

        byte[] badChecksum = hex(transcripts.get("invalid_checksum").get("request_hex"));
        ReferenceMobileEngine afterError = new ReferenceMobileEngine("SESSION");
        assertEquals("CHECKSUM_ERROR", afterError.feed(badChecksum, 0).outcome);
        EngineResult afterErrorPartial = afterError.feed(Arrays.copyOf(begin, 5), 1);
        assertEquals("NEED_MORE", afterErrorPartial.outcome);
        assertEquals(5, afterErrorPartial.retainedBytes);
        assertEquals(0, afterErrorPartial.response.length);
        assertEquals(0, afterErrorPartial.ack.length);
        assertEquals(0, afterErrorPartial.commits);

        ReferenceMobileEngine monotonic = new ReferenceMobileEngine("SESSION");
        assertEquals("NEED_MORE", monotonic.feed(Arrays.copyOf(begin, 4), 100).outcome);
        EngineResult beforeRegression = monotonic.snapshot();
        EngineResult regression = monotonic.feed(new byte[]{begin[4]}, 99);
        assertEquals("TIME_REGRESSION", regression.outcome);
        assertEquals(beforeRegression.state, regression.state);
        assertEquals(beforeRegression.retainedBytes, regression.retainedBytes);
        assertEquals(beforeRegression.pendingSlots, regression.pendingSlots);
        assertArrayEquals(beforeRegression.response, regression.response);
        assertArrayEquals(beforeRegression.ack, regression.ack);

        byte[] end = hex(transcripts.get("end_session").get("request_hex"));
        ReferenceMobileEngine ending = new ReferenceMobileEngine("SESSION");
        assertTrue(ending.reservePendingSlot());
        EngineResult ended = ending.feed(end, 0);
        assertEquals("SESSION_ENDED", ended.outcome);
        assertEquals("SLEEP", ended.state);
        assertEquals(0, ended.pendingSlots);
        assertEquals(0, ended.retainedBytes);
        assertArrayEquals(hex(transcripts.get("end_session").get("response_hex")),
                ended.response);
        assertArrayEquals(hex(transcripts.get("end_session").get("ack_hex")), ended.ack);

        ReferenceMobileEngine cancelled = new ReferenceMobileEngine("SESSION");
        assertTrue(cancelled.reservePendingSlot());
        assertEquals("NEED_MORE", cancelled.feed(Arrays.copyOf(begin, 6), 0).outcome);
        EngineResult cancellation = cancelled.cancelOrReplace();
        assertEquals("CANCELLED", cancellation.outcome);
        assertEquals("SLEEP", cancellation.state);
        assertEquals(0, cancellation.pendingSlots);
        assertEquals(0, cancellation.retainedBytes);
        assertEquals(0, cancellation.response.length);
        assertEquals(0, cancellation.ack.length);

        assertTrue(engine.reservePendingSlot());
        byte[] reset = hex(transcripts.get("reset").get("request_hex"));
        EngineResult resetResult = engine.feed(reset, 3_003);
        assertEquals("SESSION_RESET", resetResult.outcome);
        assertEquals(0, resetResult.pendingSlots);
        assertEquals(0, resetResult.retainedBytes);

        byte[] plusOne = hex(transcripts.get("payload_boundary_plus_one").get("request_hex"));
        EngineResult limited = engine.feed(plusOne, 3_004);
        assertEquals("LENGTH_LIMIT", limited.outcome);
        assertEquals(0, limited.retainedBytes);
        assertEquals(0, limited.response.length);
        assertEquals(0, limited.commits);
    }

    @Test
    public void resourceManifestHashesEveryCommittedMobileContractArtifact() throws Exception {
        Path root = repositoryRoot().resolve("core/src/test/resources/mobile-adapter");
        Map<String, Map<String, String>> manifest = rows("/mobile-adapter/manifest.tsv").stream()
                .collect(Collectors.toMap(r -> r.get("path"), r -> r));
        Set<String> actual;
        try (var paths = Files.list(root)) {
            actual = paths.filter(Files::isRegularFile)
                    .map(path -> path.getFileName().toString())
                    .filter(name -> !name.equals("manifest.tsv"))
                    .collect(Collectors.toSet());
        }
        assertEquals(actual, manifest.keySet());
        for (Map.Entry<String, Map<String, String>> entry : manifest.entrySet()) {
            Map<String, String> row = entry.getValue();
            assertEquals(entry.getKey(), row.get("sha256"),
                    sha256(Files.readAllBytes(root.resolve(entry.getKey()))));
            assertTrue(Set.of("evidence-registry", "conformance-vector", "documentation")
                    .contains(row.get("kind")));
            String expectedProvenance = Set.of("README.md", "commands.tsv")
                    .contains(entry.getKey()) ?
                    "coffee-gb-phase-346+352" : "coffee-gb-phase-346";
            assertEquals(expectedProvenance, row.get("provenance"));
            assertFalse(row.get("generator").isBlank());
        }
    }

    @Test
    public void beginResetConfigurationAndBoundariesMatchTheFrozenSubset() throws Exception {
        Map<String, Map<String, String>> rows = rows("/mobile-adapter/transcripts.tsv").stream()
                .collect(Collectors.toMap(r -> r.get("id"), r -> r));
        Packet begin = parsePacket(hex(rows.get("begin_session").get("request_hex")));
        assertArrayEquals("NINTENDO".getBytes(StandardCharsets.US_ASCII), begin.data);
        assertArrayEquals(begin.data,
                parsePacket(hex(rows.get("begin_session").get("response_hex"))).data);

        Packet reset = parsePacket(hex(rows.get("reset").get("request_hex")));
        assertEquals(0x16, reset.command);
        assertEquals(0, reset.data.length);

        Packet end = parsePacket(hex(rows.get("end_session").get("request_hex")));
        assertEquals(0x11, end.command);
        assertEquals(0, end.data.length);

        Packet config = parsePacket(hex(rows.get("config_read").get("request_hex")));
        assertArrayEquals(new byte[]{0, 4}, config.data);
        byte[] configReply = parsePacket(hex(rows.get("config_read").get("response_hex"))).data;
        assertArrayEquals(new byte[]{0, 0x4d, 0x41, (byte) 0x81, 0}, configReply);

        Packet configBoundary = parsePacket(hex(rows.get("config_read_boundary").get("request_hex")));
        assertEquals(128, configBoundary.data[0] & 0xff);
        assertEquals(128, configBoundary.data[1] & 0xff);
        assertEquals(256, Math.addExact(configBoundary.data[0] & 0xff,
                configBoundary.data[1] & 0xff));

        Packet max = parsePacket(hex(rows.get("max_payload_unsupported").get("request_hex")));
        assertEquals(254, max.data.length);
        Packet plusOne = parsePacket(hex(rows.get("payload_boundary_plus_one").get("request_hex")));
        assertEquals(255, plusOne.declaredLength);
        assertFalse(plusOne.complete);

        ReferenceSlots slots = new ReferenceSlots(2);
        assertTrue(slots.reserve());
        assertTrue(slots.reserve());
        assertFalse(slots.reserve());
        slots.complete();
        assertTrue(slots.reserve());
    }

    @Test
    public void productionMobileAdapterStaysPureAndHostIndependent() throws Exception {
        Path root = repositoryRoot();
        Path productionRoot = root.resolve("core/src/main");
        Path mobilePackage = productionRoot.resolve(
                "java/eu/rekawek/coffeegb/core/serial/mobile");
        List<Path> production;
        try (var paths = Files.walk(productionRoot)) {
            production = paths.filter(Files::isRegularFile)
                    .filter(path -> path.toString().endsWith(".java") ||
                            path.toString().endsWith(".kt"))
                    .filter(path -> path.startsWith(mobilePackage) ||
                            path.getFileName().toString().contains("MobileAdapter"))
                    .sorted()
                    .collect(Collectors.toList());
        }
        assertFalse("Expected Mobile Adapter production sources", production.isEmpty());

        Set<String> allowedInternalImports = Set.of(
                "eu.rekawek.coffeegb.core.hardware.ClockSpec",
                "eu.rekawek.coffeegb.core.serial.SerialEndpoint",
                "eu.rekawek.coffeegb.core.state.ComponentState",
                "eu.rekawek.coffeegb.core.state.StatefulComponent");
        Pattern internalImport = Pattern.compile(
                "(?m)^\\s*import\\s+(eu\\.rekawek\\.coffeegb\\.[A-Za-z0-9_.$]+)\\s*;");
        Set<String> observedInternalImports = new java.util.HashSet<>();
        List<String> violations = new ArrayList<>();
        for (Path source : production) {
            String code = sourceCodeOnly(read(source));
            var imports = internalImport.matcher(code);
            while (imports.find()) {
                String imported = imports.group(1);
                observedInternalImports.add(imported);
                if (!allowedInternalImports.contains(imported)) {
                    violations.add(root.relativize(source) +
                            ": unapproved internal dependency " + imported);
                }
            }
            String declarationsRemoved = code.replaceAll(
                    "(?m)^\\s*(?:package|import)\\s+[^;]+;\\s*", "");
            if (Pattern.compile("\\beu\\.rekawek\\.coffeegb\\.")
                    .matcher(declarationsRemoved).find()) {
                violations.add(root.relativize(source) +
                        ": fully-qualified internal dependency bypasses the import whitelist");
            }
        }
        assertEquals(allowedInternalImports, observedInternalImports);

        // Scan the complete, explicitly pinned dependency closure as well as the Mobile package.
        // A future Mobile class therefore cannot hide host work behind a generic core helper.
        List<Path> purityClosure = new ArrayList<>(production);
        for (String imported : allowedInternalImports) {
            purityClosure.add(productionRoot.resolve("java/").resolve(
                    imported.replace('.', '/') + ".java"));
        }
        purityClosure.add(productionRoot.resolve(
                "java/eu/rekawek/coffeegb/core/state/MachineStateCapture.java"));
        assertTrue(purityClosure.stream().allMatch(Files::isRegularFile));

        Map<String, Pattern> forbiddenDependencies = new LinkedHashMap<>();
        forbiddenDependencies.put("networking or DNS", Pattern.compile(
                "\\b(?:java|javax)\\.net(?:\\.|\\b)|" +
                        "\\bjavax\\.naming(?:\\.|\\b)|" +
                        "\\b(?:okhttp3|io\\.netty|org\\.apache\\.http)(?:\\.|\\b)|" +
                        "\\b(?:Socket|ServerSocket|DatagramSocket|DatagramPacket|" +
                        "InetAddress|URLConnection|HttpClient)\\b"));
        forbiddenDependencies.put("filesystem access", Pattern.compile(
                "\\b(?:java\\.nio\\.file|java\\.nio\\.channels|kotlin\\.io\\.path)" +
                        "(?:\\.|\\b)|" +
                        "\\bjava\\.io\\.(?:File|FileDescriptor|FileInputStream|" +
                        "FileOutputStream|FileReader|FileWriter|RandomAccessFile)\\b|" +
                        "\\b(?:File|Files|Path|Paths|FileSystem|FileSystems|" +
                        "FileInputStream|FileOutputStream|FileReader|FileWriter|" +
                        "RandomAccessFile)\\b"));
        forbiddenDependencies.put("threads, blocking synchronization, futures, or executors", Pattern.compile(
                "\\bjava\\.util\\.concurrent\\.(?!atomic\\.AtomicReference\\b)|" +
                        "\\bkotlin\\.concurrent(?:\\.|\\b)|" +
                        "\\b(?:Thread|ThreadLocal|Runnable|Callable|Future|CompletionStage|" +
                        "CompletableFuture|Executor|ExecutorService|ScheduledExecutorService|" +
                        "ForkJoinPool|Timer|TimerTask|Lock|Condition|Semaphore|CountDownLatch|" +
                        "BlockingQueue)\\b|\\b(?:synchronized|volatile)\\b"));
        forbiddenDependencies.put("AWT or Swing", Pattern.compile(
                "\\b(?:java\\.awt|javax\\.swing)(?:\\.|\\b)"));
        forbiddenDependencies.put("controller layer", Pattern.compile(
                "\\beu\\.rekawek\\.coffeegb\\.controller(?:\\.|\\b)"));
        forbiddenDependencies.put("host wall clock", Pattern.compile(
                "\\bSystem\\s*(?:\\.|::)\\s*(?:currentTimeMillis|nanoTime)\\b|" +
                        "\\b(?:Instant|LocalDate|LocalDateTime|OffsetDateTime|ZonedDateTime)" +
                        "\\s*(?:\\.|::)\\s*now\\b|" +
                        "\\bClock\\s*\\.\\s*system(?:UTC|DefaultZone)?\\b|" +
                        "\\bCalendar\\s*\\.\\s*getInstance\\b|" +
                        "\\bnew\\s+Date\\s*\\(|" +
                        "\\bjava\\.time\\.Clock\\b|\\bjava\\.util\\.(?:Date|Calendar)\\b|" +
                        "\\b(?:TimeSource|SystemClock|measureTimeMillis|measureNanoTime)\\b"));

        for (Path source : purityClosure) {
            String code = sourceCodeOnly(read(source));
            for (Map.Entry<String, Pattern> dependency : forbiddenDependencies.entrySet()) {
                if (dependency.getValue().matcher(code).find()) {
                    violations.add(root.relativize(source) + ": " + dependency.getKey());
                }
            }
        }
        assertTrue("Forbidden Mobile Adapter production dependencies:\n" +
                String.join("\n", violations), violations.isEmpty());

        String resources = Files.readString(root.resolve(
                "core/src/test/resources/mobile-adapter/transcripts.tsv"), StandardCharsets.UTF_8);
        for (String forbidden : List.of("gameboy.datacenter.ne.jp", "ObjectInputStream",
                "java.net.Socket", "java.util." + "Hex" + "Format")) {
            assertFalse(forbidden, resources.contains(forbidden));
        }
        String adr = read(root.resolve("docs/adr/0002-mobile-adapter-clean-room.md"))
                .replaceAll("\\s+", " ");
        for (String forbiddenCore : List.of("sockets", "DNS", "files", "threads", "AWT/Swing",
                "host wall clock", "blocking")) {
            assertTrue(forbiddenCore, adr.contains(forbiddenCore));
        }
    }

    private static String sourceCodeOnly(String source) {
        char[] result = source.toCharArray();
        int offset = 0;
        while (offset < result.length) {
            if (offset + 1 < result.length && result[offset] == '/' && result[offset + 1] == '/') {
                blank(result, offset++);
                blank(result, offset++);
                while (offset < result.length && result[offset] != '\n' && result[offset] != '\r') {
                    blank(result, offset++);
                }
            } else if (offset + 1 < result.length && result[offset] == '/' &&
                    result[offset + 1] == '*') {
                blank(result, offset++);
                blank(result, offset++);
                while (offset < result.length) {
                    if (offset + 1 < result.length && result[offset] == '*' &&
                            result[offset + 1] == '/') {
                        blank(result, offset++);
                        blank(result, offset++);
                        break;
                    }
                    blank(result, offset++);
                }
            } else if (offset + 2 < result.length && result[offset] == '"' &&
                    result[offset + 1] == '"' && result[offset + 2] == '"') {
                blank(result, offset++);
                blank(result, offset++);
                blank(result, offset++);
                while (offset < result.length) {
                    if (offset + 2 < result.length && result[offset] == '"' &&
                            result[offset + 1] == '"' && result[offset + 2] == '"') {
                        blank(result, offset++);
                        blank(result, offset++);
                        blank(result, offset++);
                        break;
                    }
                    blank(result, offset++);
                }
            } else if (result[offset] == '"' || result[offset] == '\'') {
                char delimiter = result[offset];
                blank(result, offset++);
                boolean escaped = false;
                while (offset < result.length) {
                    char value = result[offset];
                    blank(result, offset++);
                    if (escaped) {
                        escaped = false;
                    } else if (value == '\\') {
                        escaped = true;
                    } else if (value == delimiter) {
                        break;
                    }
                }
            } else {
                offset++;
            }
        }
        return new String(result);
    }

    private static void blank(char[] value, int offset) {
        if (value[offset] != '\n' && value[offset] != '\r') value[offset] = ' ';
    }

    private static Packet parsePacket(byte[] bytes) {
        if (bytes.length < 6) {
            int declared = bytes.length >= 6 ? unsigned16(bytes, 4) : -1;
            return new Packet(-1, declared, new byte[0], false, false);
        }
        assertEquals(0x99, bytes[0] & 0xff);
        assertEquals(0x66, bytes[1] & 0xff);
        int command = bytes[2] & 0xff;
        assertEquals(0, bytes[3] & 0xff);
        int length = unsigned16(bytes, 4);
        if (bytes.length != 8 + length) {
            return new Packet(command, length, new byte[0], false, false);
        }
        byte[] data = Arrays.copyOfRange(bytes, 6, 6 + length);
        int expected = 0;
        for (int i = 2; i < 6 + length; i++) expected = (expected + (bytes[i] & 0xff)) & 0xffff;
        int actual = unsigned16(bytes, 6 + length);
        return new Packet(command, length, data, true, expected == actual);
    }

    private static FragmentResult fragments(String value) {
        int bytes = 0;
        long previous = -1;
        boolean monotonic = true;
        int lastCount = -1;
        long lastMillis = -1;
        long lastDataMillis = -1;
        for (String entry : value.split(";")) {
            String[] parts = entry.split(":", -1);
            assertEquals(2, parts.length);
            long millis = Long.parseLong(parts[0]);
            int count = Integer.parseInt(parts[1]);
            monotonic &= millis >= previous;
            previous = millis;
            bytes = Math.addExact(bytes, count);
            if (count > 0) lastDataMillis = millis;
            lastCount = count;
            lastMillis = millis;
        }
        return new FragmentResult(bytes, monotonic, lastMillis, lastDataMillis, lastCount);
    }

    private static List<FragmentStep> fragmentSteps(String value) {
        List<FragmentStep> result = new ArrayList<>();
        for (String entry : value.split(";")) {
            String[] parts = entry.split(":", -1);
            assertEquals(2, parts.length);
            result.add(new FragmentStep(Long.parseLong(parts[0]), Integer.parseInt(parts[1])));
        }
        return result;
    }

    private static List<Map<String, String>> rows(String resource) throws IOException {
        InputStream stream = MobileAdapterContractTest.class.getResourceAsStream(resource);
        assertNotNull("Missing " + resource, stream);
        List<String> lines;
        try (BufferedReader reader = new BufferedReader(new InputStreamReader(stream, StandardCharsets.UTF_8))) {
            lines = reader.lines().filter(line -> !line.isBlank() && !line.startsWith("#"))
                    .collect(Collectors.toList());
        }
        String[] header = lines.get(0).split("\\t", -1);
        List<Map<String, String>> result = new ArrayList<>();
        for (int i = 1; i < lines.size(); i++) {
            String[] values = lines.get(i).split("\\t", -1);
            assertEquals(resource + ":" + (i + 1), header.length, values.length);
            Map<String, String> row = new LinkedHashMap<>();
            for (int column = 0; column < header.length; column++) row.put(header[column], values[column]);
            result.add(row);
        }
        return result;
    }

    private static byte[] optionalHex(String value) {
        return value.equals("-") ? new byte[0] : hex(value);
    }

    private static byte[] hex(String value) {
        if ((value.length() & 1) != 0) throw new IllegalArgumentException("Odd hex length");
        byte[] result = new byte[value.length() / 2];
        for (int i = 0; i < result.length; i++) {
            int high = Character.digit(value.charAt(i * 2), 16);
            int low = Character.digit(value.charAt(i * 2 + 1), 16);
            if (high < 0 || low < 0) throw new IllegalArgumentException("Invalid hex");
            result[i] = (byte) ((high << 4) | low);
        }
        return result;
    }

    private static byte[] join(byte[]... arrays) {
        int length = 0;
        for (byte[] array : arrays) length = Math.addExact(length, array.length);
        byte[] result = new byte[length];
        int offset = 0;
        for (byte[] array : arrays) {
            System.arraycopy(array, 0, result, offset, array.length);
            offset += array.length;
        }
        return result;
    }

    private static MobileAdapterEngine productionEngine(String initialState) {
        byte[] configuration = new byte[MobileAdapterEngine.CONFIGURATION_BYTES];
        configuration[0] = 0x4d;
        configuration[1] = 0x41;
        configuration[2] = (byte) 0x81;
        for (int i = 0; i < 128; i++) configuration[128 + i] = (byte) i;
        MobileAdapterEngine engine = new MobileAdapterEngine(
                new ClockSpec(1_000, 60, 1), 0x08, configuration);
        if (initialState.equals("SESSION")) {
            for (byte value : hex("9966100000084e494e54454e444f0277")) {
                engine.acceptByte(value & 0xff);
            }
        } else if (!initialState.equals("SLEEP")) {
            throw new IllegalArgumentException("Unknown Mobile state " + initialState);
        }
        return engine;
    }

    private static String sha256(byte[] bytes) throws NoSuchAlgorithmException {
        byte[] digest = MessageDigest.getInstance("SHA-256").digest(bytes);
        char[] result = new char[digest.length * 2];
        char[] alphabet = "0123456789abcdef".toCharArray();
        for (int i = 0; i < digest.length; i++) {
            int value = digest[i] & 0xff;
            result[i * 2] = alphabet[value >>> 4];
            result[i * 2 + 1] = alphabet[value & 0x0f];
        }
        return new String(result);
    }

    private static int unsigned16(byte[] bytes, int offset) {
        return ((bytes[offset] & 0xff) << 8) | (bytes[offset + 1] & 0xff);
    }

    private static String read(Path path) throws IOException {
        return Files.readString(path, StandardCharsets.UTF_8);
    }

    private static Path repositoryRoot() {
        String mavenRoot = System.getProperty("maven.multiModuleProjectDirectory");
        if (mavenRoot != null) {
            Path root = Paths.get(mavenRoot).toAbsolutePath().normalize();
            if (Files.isRegularFile(root.resolve("core/pom.xml"))) return root;
        }
        Path candidate = Paths.get("").toAbsolutePath().normalize();
        while (candidate.getParent() != null) {
            if (Files.isRegularFile(candidate.resolve("core/pom.xml"))) return candidate;
            candidate = candidate.getParent();
        }
        throw new AssertionError("Cannot locate repository root");
    }

    private record Packet(int command, int declaredLength, byte[] data, boolean complete,
                          boolean checksumValid) {}

    private record FragmentResult(int bytes, boolean monotonic, long lastMillis,
                                  long lastDataMillis, int lastCount) {}

    private record FragmentStep(long millis, int count) {}

    private record EngineResult(String state, String outcome, byte[] response, byte[] ack,
                                int retainedBytes, int pendingSlots, int commits) {}

    /**
     * Test-only incremental serial receiver. It owns one fixed maximum packet buffer and changes
     * session/configuration state only after a complete checksum-valid request has passed its
     * command-specific checks.
     */
    private static final class ReferenceMobileEngine {
        private final byte[] packet = new byte[262];
        private final byte[] configuration = new byte[256];
        private final ReferenceSlots pendingSlots = new ReferenceSlots(2);
        private int count;
        private int expectedPacketBytes = -1;
        private long lastByteMillis = -1;
        private long lastObservedMillis = -1;
        private String state;
        private String outcome = "NEED_MORE";
        private byte[] response = new byte[0];
        private byte[] ack = new byte[0];
        private int commits;

        private ReferenceMobileEngine(String initialState) {
            if (!Set.of("SLEEP", "SESSION").contains(initialState)) {
                throw new IllegalArgumentException("Unknown Mobile state " + initialState);
            }
            state = initialState;
            configuration[0] = 0x4d;
            configuration[1] = 0x41;
            configuration[2] = (byte) 0x81;
            for (int i = 0; i < 128; i++) configuration[128 + i] = (byte) i;
        }

        private EngineResult feed(byte[] bytes, long emulatedMillis) {
            EngineResult time = advanceTo(emulatedMillis);
            if (time.outcome.equals("TIME_REGRESSION")) return time;
            outcome = "NEED_MORE";
            response = new byte[0];
            ack = new byte[0];
            commits = 0;
            for (byte value : bytes) {
                lastByteMillis = emulatedMillis;
                if (count >= packet.length) {
                    reject("BUFFER_LIMIT");
                    break;
                }
                packet[count++] = value;
                if (count == 2 &&
                        ((packet[0] & 0xff) != 0x99 || (packet[1] & 0xff) != 0x66)) {
                    reject("MAGIC_ERROR");
                    break;
                }
                if (count == 4 && packet[3] != 0) {
                    reject("RESERVED_ERROR");
                    break;
                }
                if (count == 6) {
                    int declared = unsigned16(packet, 4);
                    if (declared > 254) {
                        reject("LENGTH_LIMIT");
                        break;
                    }
                    expectedPacketBytes = Math.addExact(8, declared);
                }
                if (expectedPacketBytes > 0 && count == expectedPacketBytes) {
                    commitPacket();
                }
            }
            if (count > 0 && outcome.equals("SUCCESS")) outcome = "NEED_MORE";
            return snapshot();
        }

        private EngineResult advanceTo(long emulatedMillis) {
            if (emulatedMillis < 0) throw new IllegalArgumentException("Negative emulated time");
            if (lastObservedMillis >= 0 && emulatedMillis < lastObservedMillis) {
                return snapshot("TIME_REGRESSION");
            }
            lastObservedMillis = emulatedMillis;
            if (lastByteMillis >= 0 &&
                    Math.subtractExact(emulatedMillis, lastByteMillis) > 3_000) {
                state = "SLEEP";
                outcome = "IDLE_TIMEOUT_RESET";
                response = new byte[0];
                ack = new byte[0];
                commits = 0;
                clearParser();
                pendingSlots.clear();
            } else if (lastByteMillis >= 0 && count > 0 &&
                    Math.subtractExact(emulatedMillis, lastByteMillis) == 3_000) {
                outcome = "IDLE_BOUNDARY_WAIT";
            }
            return snapshot();
        }

        private boolean reservePendingSlot() {
            return pendingSlots.reserve();
        }

        private EngineResult snapshot() {
            return snapshot(outcome);
        }

        private EngineResult snapshot(String visibleOutcome) {
            return new EngineResult(state, visibleOutcome, response.clone(), ack.clone(), count,
                    pendingSlots.used(), commits);
        }

        private EngineResult cancelOrReplace() {
            state = "SLEEP";
            outcome = "CANCELLED";
            response = new byte[0];
            ack = new byte[0];
            commits = 0;
            clearParser();
            pendingSlots.clear();
            return snapshot();
        }

        private void commitPacket() {
            int command = packet[2] & 0xff;
            int length = unsigned16(packet, 4);
            int expectedChecksum = 0;
            for (int i = 2; i < 6 + length; i++) {
                expectedChecksum = (expectedChecksum + (packet[i] & 0xff)) & 0xffff;
            }
            int actualChecksum = unsigned16(packet, 6 + length);
            byte[] data = Arrays.copyOfRange(packet, 6, 6 + length);
            clearParser();
            if (actualChecksum != expectedChecksum) {
                outcome = "CHECKSUM_ERROR";
                ack = new byte[]{(byte) 0x88, (byte) 0xf1};
                return;
            }
            switch (command) {
                case 0x10:
                    if (!Arrays.equals(data, "NINTENDO".getBytes(StandardCharsets.US_ASCII))) {
                        unsupported();
                        return;
                    }
                    state = "SESSION";
                    outcome = "SESSION_STARTED";
                    response = packet(0x90, data);
                    ack = new byte[]{(byte) 0x88, (byte) 0x90};
                    commits++;
                    return;
                case 0x11:
                    if (data.length != 0) {
                        unsupported();
                        return;
                    }
                    state = "SLEEP";
                    pendingSlots.clear();
                    outcome = "SESSION_ENDED";
                    response = packet(0x91, new byte[0]);
                    ack = new byte[]{(byte) 0x88, (byte) 0x91};
                    commits++;
                    return;
                case 0x16:
                    if (data.length != 0) {
                        unsupported();
                        return;
                    }
                    state = "SESSION";
                    pendingSlots.clear();
                    outcome = "SESSION_RESET";
                    response = packet(0x96, new byte[0]);
                    ack = new byte[]{(byte) 0x88, (byte) 0x96};
                    commits++;
                    return;
                case 0x19:
                    if (data.length != 2) {
                        unsupported();
                        return;
                    }
                    int offset = data[0] & 0xff;
                    int requested = data[1] & 0xff;
                    int end;
                    try {
                        end = Math.addExact(offset, requested);
                    } catch (ArithmeticException e) {
                        unsupported();
                        return;
                    }
                    if (requested > 128 || end > configuration.length) {
                        unsupported();
                        return;
                    }
                    byte[] result = new byte[requested + 1];
                    result[0] = (byte) offset;
                    System.arraycopy(configuration, offset, result, 1, requested);
                    outcome = requested == 128 ? "CONFIG_READ_BOUNDARY" : "CONFIG_READ";
                    response = packet(0x99, result);
                    ack = new byte[]{(byte) 0x88, (byte) 0x99};
                    commits++;
                    return;
                default:
                    unsupported();
            }
        }

        private void unsupported() {
            outcome = "UNSUPPORTED_COMMAND";
            ack = new byte[]{(byte) 0x88, (byte) 0xf0};
        }

        private void reject(String reason) {
            outcome = reason;
            response = new byte[0];
            ack = new byte[0];
            commits = 0;
            clearParser();
        }

        private void clearParser() {
            Arrays.fill(packet, 0, count, (byte) 0);
            count = 0;
            expectedPacketBytes = -1;
        }

        private static byte[] packet(int command, byte[] data) {
            byte[] bytes = new byte[8 + data.length];
            bytes[0] = (byte) 0x99;
            bytes[1] = 0x66;
            bytes[2] = (byte) command;
            bytes[3] = 0;
            bytes[4] = (byte) (data.length >>> 8);
            bytes[5] = (byte) data.length;
            System.arraycopy(data, 0, bytes, 6, data.length);
            int checksum = 0;
            for (int i = 2; i < 6 + data.length; i++) {
                checksum = (checksum + (bytes[i] & 0xff)) & 0xffff;
            }
            bytes[6 + data.length] = (byte) (checksum >>> 8);
            bytes[7 + data.length] = (byte) checksum;
            return bytes;
        }
    }

    private static final class ReferenceSlots {
        private final int capacity;
        private int used;

        private ReferenceSlots(int capacity) {
            this.capacity = capacity;
        }

        private boolean reserve() {
            if (used >= capacity) return false;
            used++;
            return true;
        }

        private void complete() {
            if (used <= 0) throw new IllegalStateException("No pending slot");
            used--;
        }

        private void clear() {
            used = 0;
        }

        private int used() {
            return used;
        }
    }
}
