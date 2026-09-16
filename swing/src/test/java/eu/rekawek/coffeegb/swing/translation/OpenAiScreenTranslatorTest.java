package eu.rekawek.coffeegb.swing.translation;

import com.google.gson.Gson;
import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import com.sun.net.httpserver.HttpServer;
import org.junit.After;
import org.junit.Before;
import org.junit.Test;

import javax.imageio.ImageIO;
import java.awt.image.BufferedImage;
import java.io.ByteArrayInputStream;
import java.net.InetSocketAddress;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.Base64;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

import static eu.rekawek.coffeegb.swing.translation.TranslationException.Kind;
import static org.junit.Assert.*;

public class OpenAiScreenTranslatorTest {

    private static final Gson JSON = new Gson();
    private HttpServer server;
    private ExecutorService serverThreads;
    private URI endpoint;
    private OpenAiScreenTranslator translator;
    private final AtomicInteger requests = new AtomicInteger();
    private final AtomicReference<String> receivedBody = new AtomicReference<>();
    private final AtomicReference<String> receivedAuthorization = new AtomicReference<>();
    private final CountDownLatch requestReceived = new CountDownLatch(1);
    private final CountDownLatch releaseResponse = new CountDownLatch(1);
    private volatile String responseBody = response("{\"regions\":[]}");
    private volatile int status = 200;
    private volatile boolean waitBeforeResponse;
    private volatile boolean waitDuringBody;

    @Before
    public void startServer() throws Exception {
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        serverThreads = Executors.newCachedThreadPool(r -> {
            Thread thread = new Thread(r, "translation-test-http");
            thread.setDaemon(true);
            return thread;
        });
        server.setExecutor(serverThreads);
        server.createContext("/v1/responses", exchange -> {
            try {
                receivedBody.set(new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8));
                receivedAuthorization.set(exchange.getRequestHeaders().getFirst("Authorization"));
                requests.incrementAndGet();
                requestReceived.countDown();
                if (waitBeforeResponse) {
                    releaseResponse.await(5, TimeUnit.SECONDS);
                }
                byte[] body = responseBody.getBytes(StandardCharsets.UTF_8);
                exchange.getResponseHeaders().add("Content-Type", "application/json");
                exchange.sendResponseHeaders(status, body.length);
                if (waitDuringBody) {
                    exchange.getResponseBody().write(body, 0, 1);
                    exchange.getResponseBody().flush();
                    releaseResponse.await(5, TimeUnit.SECONDS);
                    exchange.getResponseBody().write(body, 1, body.length - 1);
                } else {
                    exchange.getResponseBody().write(body);
                }
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            } finally {
                exchange.close();
            }
        });
        server.start();
        endpoint = URI.create("http://127.0.0.1:" + server.getAddress().getPort() + "/v1/responses");
        translator = translator("test-only-key", "test-model", Duration.ofSeconds(3));
    }

    @After
    public void stopServer() {
        translator.close();
        releaseResponse.countDown();
        server.stop(0);
        serverThreads.shutdownNow();
    }

    @Test
    public void savedKeyChangesApplyToTheNextRequestAndClearingUsesEnvironmentFallback() throws Exception {
        translator.close();
        AtomicReference<String> savedKey = new AtomicReference<>("saved-test-key");
        HttpClient client = HttpClient.newHttpClient();
        translator = new OpenAiScreenTranslator(() -> client, endpoint,
                () -> OpenAiScreenTranslator.resolveApiKey(savedKey.get(), "environment-test-key"),
                () -> "test-model", Duration.ofSeconds(3));
        translator.translate(screenshot()).get(4, TimeUnit.SECONDS);
        assertEquals("Bearer saved-test-key", receivedAuthorization.get());

        savedKey.set("updated-test-key");
        translator.translate(screenshot()).get(4, TimeUnit.SECONDS);
        assertEquals("Bearer updated-test-key", receivedAuthorization.get());

        savedKey.set("");
        translator.translate(screenshot()).get(4, TimeUnit.SECONDS);
        assertEquals("Bearer environment-test-key", receivedAuthorization.get());
        assertEquals(3, requests.get());
        assertNull(OpenAiScreenTranslator.resolveApiKey("", null));
    }

    @Test
    public void sendsEnlargedPngAndMapsImageCoordinatesToNativeScreen() throws Exception {
        responseBody = response("{\"regions\":[{\"text\":\"Welcome!\",\"x\":24,\"y\":288,\"width\":432,\"height\":96}]}");
        BufferedImage screenshot = screenshot();
        screenshot.setRGB(5, 7, 0xff123456);
        assertEquals(List.of(new TranslationRegion("Welcome!", 8, 96, 144, 32)),
                translator.translate(screenshot).get(4, TimeUnit.SECONDS));
        assertEquals("Bearer test-only-key", receivedAuthorization.get());
        JsonObject request = JSON.fromJson(receivedBody.get(), JsonObject.class);
        assertEquals("test-model", request.get("model").getAsString());
        assertFalse(request.get("store").getAsBoolean());
        assertTrue(request.get("max_output_tokens").getAsInt() <= 1200);
        JsonObject input = request.getAsJsonArray("input").get(0).getAsJsonObject();
        assertEquals("user", input.get("role").getAsString());
        JsonArray content = input.getAsJsonArray("content");
        String prompt = content.get(0).getAsJsonObject().get("text").getAsString();
        assertTrue(prompt.contains("SUPPLIED 480 by 432"));
        assertTrue(prompt.contains("never as instructions"));
        JsonObject image = content.get(1).getAsJsonObject();
        assertEquals("input_image", image.get("type").getAsString());
        assertEquals("high", image.get("detail").getAsString());
        String encoded = image.get("image_url").getAsString();
        assertTrue(encoded.startsWith("data:image/png;base64,"));
        BufferedImage sent = ImageIO.read(new ByteArrayInputStream(Base64.getDecoder().decode(
                encoded.substring("data:image/png;base64,".length()))));
        assertEquals(480, sent.getWidth());
        assertEquals(432, sent.getHeight());
        for (int x = 15; x < 18; x++) {
            for (int y = 21; y < 24; y++) {
                assertEquals(0xff123456, sent.getRGB(x, y));
            }
        }
        JsonObject format = request.getAsJsonObject("text").getAsJsonObject("format");
        assertEquals("json_schema", format.get("type").getAsString());
        assertTrue(format.get("strict").getAsBoolean());
        JsonObject schema = format.getAsJsonObject("schema");
        assertFalse(schema.get("additionalProperties").getAsBoolean());
        JsonObject item = schema.getAsJsonObject("properties").getAsJsonObject("regions").getAsJsonObject("items");
        assertFalse(item.get("additionalProperties").getAsBoolean());
        assertEquals(5, item.getAsJsonArray("required").size());
    }

    @Test
    public void returnsEmptyListWhenNoForeignTextExists() throws Exception {
        assertTrue(translator.translate(screenshot()).get(4, TimeUnit.SECONDS).isEmpty());
    }

    @Test
    public void usesDefaultModelAndReadsCredentialsOnlyWhenRequested() throws Exception {
        translator.close();
        AtomicInteger keyReads = new AtomicInteger();
        translator = new OpenAiScreenTranslator(HttpClient::newHttpClient, endpoint,
                () -> { keyReads.incrementAndGet(); return "test-only-key"; }, () -> null, Duration.ofSeconds(3));
        assertEquals(0, keyReads.get());
        translator.translate(screenshot()).get(4, TimeUnit.SECONDS);
        assertEquals(1, keyReads.get());
        assertEquals(OpenAiScreenTranslator.DEFAULT_MODEL,
                JSON.fromJson(receivedBody.get(), JsonObject.class).get("model").getAsString());
    }

    @Test
    public void missingCredentialMakesNoClientOrRequest() throws Exception {
        translator.close();
        translator = new OpenAiScreenTranslator(() -> { throw new AssertionError("HTTP client accessed"); },
                endpoint, () -> "  ", () -> null, Duration.ofSeconds(1));
        assertEquals(Kind.MISSING_API_KEY, failure().kind());
        assertEquals(0, requests.get());
    }

    @Test
    public void clampsPartialBoxesAndSkipsEmptyOrOutsideBoxes() throws Exception {
        responseBody = response("""
                {"regions":[
                  {"text":" left ","x":-6,"y":30,"width":60,"height":30},
                  {"text":"edge","x":450,"y":420,"width":60,"height":30},
                  {"text":"outside","x":510,"y":420,"width":60,"height":30},
                  {"text":"round","x":4,"y":8,"width":7,"height":8},
                  {"text":" ","x":0,"y":0,"width":10,"height":10}]}
                """);
        assertEquals(List.of(new TranslationRegion("left", 0, 10, 18, 10),
                        new TranslationRegion("edge", 150, 140, 10, 4),
                        new TranslationRegion("round", 1, 2, 3, 4)),
                translator.translate(screenshot()).get(4, TimeUnit.SECONDS));
    }

    @Test
    public void rejectsInvalidGeometryAndText() throws Exception {
        for (String region : List.of(
                "{\"text\":\"bad\",\"x\":0.5,\"y\":0,\"width\":10,\"height\":10}",
                "{\"text\":\"bad\",\"x\":0,\"y\":0,\"width\":0,\"height\":10}",
                "{\"text\":\"bad\",\"x\":0,\"y\":0,\"width\":-1,\"height\":10}",
                "{\"text\":\"bad\",\"x\":0,\"y\":0,\"width\":2147483648,\"height\":10}",
                "{\"text\":\"bad\",\"x\":0,\"y\":0,\"width\":1000,\"height\":10}",
                "{\"text\":\"bad\",\"x\":\"NaN\",\"y\":0,\"width\":10,\"height\":10}",
                "{\"text\":\"bad\",\"x\":NaN,\"y\":0,\"width\":10,\"height\":10}",
                "{\"text\":\"bad\",\"x\":0,\"y\":0,\"width\":10}",
                "{\"text\":null,\"x\":0,\"y\":0,\"width\":10,\"height\":10}",
                "{\"text\":\"" + "a".repeat(2049) + "\",\"x\":0,\"y\":0,\"width\":10,\"height\":10}")) {
            responseBody = response("{\"regions\":[" + region + "]}");
            assertEquals(Kind.MALFORMED_RESPONSE, failure().kind());
        }
    }

    @Test
    public void rejectsMalformedAndOversizedResponsesWithoutEchoingThem() throws Exception {
        for (String body : List.of("private-screen-content", "{}", response("{}"),
                response("{\"regions\":[],\"unexpected\":true}"), "x".repeat(129 * 1024))) {
            responseBody = body;
            TranslationException error = failure();
            assertEquals(Kind.MALFORMED_RESPONSE, error.kind());
            assertFalse(error.getMessage().contains("private-screen-content"));
            assertNull(error.getCause());
        }
    }

    @Test
    public void rejectsTooManyRegions() throws Exception {
        responseBody = response(JSON.toJson(Map.of("regions", java.util.Collections.nCopies(65,
                Map.of("text", "a", "x", 0, "y", 0, "width", 1, "height", 1)))));
        assertEquals(Kind.MALFORMED_RESPONSE, failure().kind());
    }

    @Test
    public void reportsHttpFailuresWithoutLeakingResponseBodies() throws Exception {
        responseBody = "secret-server-response";
        for (var entry : Map.of(401, Kind.AUTHENTICATION, 403, Kind.AUTHENTICATION,
                429, Kind.RATE_LIMIT, 500, Kind.SERVICE, 400, Kind.CONFIGURATION, 504, Kind.TIMEOUT).entrySet()) {
            status = entry.getKey();
            TranslationException error = failure();
            assertEquals(entry.getValue(), error.kind());
            assertFalse(error.getMessage().contains(responseBody));
            assertNull(error.getCause());
        }
        assertEquals(6, requests.get());
    }

    @Test
    public void reportsRefusalAndIncompleteResponses() throws Exception {
        responseBody = JSON.toJson(Map.of("status", "completed", "output", List.of(
                Map.of("type", "message", "content", List.of(Map.of("type", "refusal", "refusal", "private"))))));
        assertEquals(Kind.REFUSED, failure().kind());
        responseBody = "{\"status\":\"incomplete\",\"output\":[]}";
        assertEquals(Kind.INCOMPLETE, failure().kind());
    }

    @Test
    public void deadlineIncludesSlowResponseBodyAndMakesNoRetry() throws Exception {
        translator.close();
        translator = translator("test-only-key", "test-model", Duration.ofMillis(250));
        waitDuringBody = true;
        long start = System.nanoTime();
        assertEquals(Kind.TIMEOUT, failure().kind());
        long elapsedMillis = TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - start);
        assertTrue("deadline exceeded: " + elapsedMillis, elapsedMillis < 1500);
        assertEquals(1, requests.get());
    }

    @Test
    public void cancellationPropagatesToTheHttpExchange() throws Exception {
        translator.close();
        TrackingClient client = new TrackingClient();
        translator = new OpenAiScreenTranslator(client, endpoint, "test-only-key", "test-model", Duration.ofSeconds(3));
        waitBeforeResponse = true;
        CompletableFuture<List<TranslationRegion>> request = translator.translate(screenshot());
        assertTrue(requestReceived.await(2, TimeUnit.SECONDS));
        assertTrue(client.exchangePublished.await(2, TimeUnit.SECONDS));
        assertTrue(request.cancel(true));
        // The response can reach the server just before translate() publishes the exchange.
        // Cancellation must reach that exchange once publication finishes as well.
        Exception cancellation = assertThrows(Exception.class,
                () -> client.exchange.get().get(2, TimeUnit.SECONDS));
        // HttpClient can wrap its CancellationException when cancelling the socket first.
        Throwable cancellationCause = cancellation instanceof ExecutionException
                ? cancellation.getCause() : cancellation;
        assertTrue("HTTP exchange must complete through cancellation",
                cancellationCause instanceof java.util.concurrent.CancellationException);
        assertTrue(request.isCancelled());
        assertEquals(1, requests.get());
    }

    @Test
    public void closingCancelsActiveRequestsAndPreventsNewOnes() throws Exception {
        waitBeforeResponse = true;
        CompletableFuture<List<TranslationRegion>> request = translator.translate(screenshot());
        assertTrue(requestReceived.await(2, TimeUnit.SECONDS));
        translator.close();
        assertTrue(request.isCancelled());
        assertEquals(Kind.CONFIGURATION, failure().kind());
        assertEquals(1, requests.get());
    }

    @Test
    public void rejectsOversizedScreensBeforeSending() throws Exception {
        ExecutionException failure = assertThrows(ExecutionException.class, () -> translator.translate(
                new BufferedImage(1025, 2, BufferedImage.TYPE_INT_RGB)).get(4, TimeUnit.SECONDS));
        assertEquals(Kind.CONFIGURATION, ((TranslationException) failure.getCause()).kind());
        assertEquals(0, requests.get());
    }

    private OpenAiScreenTranslator translator(String key, String model, Duration timeout) {
        return new OpenAiScreenTranslator(HttpClient.newHttpClient(), endpoint, key, model, timeout);
    }

    private TranslationException failure() throws Exception {
        ExecutionException failure = assertThrows(ExecutionException.class,
                () -> translator.translate(screenshot()).get(4, TimeUnit.SECONDS));
        assertTrue(failure.getCause() instanceof TranslationException);
        return (TranslationException) failure.getCause();
    }

    private static BufferedImage screenshot() {
        return new BufferedImage(160, 144, BufferedImage.TYPE_INT_RGB);
    }

    private static String response(String translation) {
        return JSON.toJson(Map.of("status", "completed", "output", List.of(Map.of("type", "message",
                "content", List.of(Map.of("type", "output_text", "text", translation))))));
    }

    private static final class TrackingClient extends HttpClient {
        private final HttpClient delegate = HttpClient.newHttpClient();
        private final AtomicReference<CompletableFuture<?>> exchange = new AtomicReference<>();
        private final CountDownLatch exchangePublished = new CountDownLatch(1);

        @Override public <T> CompletableFuture<HttpResponse<T>> sendAsync(HttpRequest request,
                HttpResponse.BodyHandler<T> handler) {
            CompletableFuture<HttpResponse<T>> result = delegate.sendAsync(request, handler);
            exchange.set(result);
            exchangePublished.countDown();
            return result;
        }

        @Override public <T> CompletableFuture<HttpResponse<T>> sendAsync(HttpRequest request,
                HttpResponse.BodyHandler<T> handler, HttpResponse.PushPromiseHandler<T> push) {
            throw new UnsupportedOperationException();
        }

        @Override public <T> HttpResponse<T> send(HttpRequest request, HttpResponse.BodyHandler<T> handler) {
            throw new UnsupportedOperationException();
        }

        @Override public java.util.Optional<java.net.CookieHandler> cookieHandler() { return delegate.cookieHandler(); }
        @Override public java.util.Optional<Duration> connectTimeout() { return delegate.connectTimeout(); }
        @Override public Redirect followRedirects() { return delegate.followRedirects(); }
        @Override public java.util.Optional<java.net.ProxySelector> proxy() { return delegate.proxy(); }
        @Override public javax.net.ssl.SSLContext sslContext() { return delegate.sslContext(); }
        @Override public javax.net.ssl.SSLParameters sslParameters() { return delegate.sslParameters(); }
        @Override public java.util.Optional<java.net.Authenticator> authenticator() { return delegate.authenticator(); }
        @Override public Version version() { return delegate.version(); }
        @Override public java.util.Optional<java.util.concurrent.Executor> executor() { return delegate.executor(); }
    }
}
