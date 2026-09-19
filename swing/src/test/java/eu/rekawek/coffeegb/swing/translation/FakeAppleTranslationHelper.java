package eu.rekawek.coffeegb.swing.translation;

import com.google.gson.JsonParser;
import javax.imageio.ImageIO;
import java.io.BufferedReader;
import java.io.ByteArrayInputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.Base64;

/** A real child process exercising the pipe protocol without Apple frameworks or network access. */
public final class FakeAppleTranslationHelper {
    public static void main(String[] args) throws Exception {
        var request = JsonParser.parseString(new BufferedReader(
                new InputStreamReader(System.in, StandardCharsets.UTF_8)).readLine()).getAsJsonObject();
        var image = ImageIO.read(new ByteArrayInputStream(Base64.getDecoder()
                .decode(request.get("image").getAsString())));
        if (request.get("version").getAsInt() != 1 || image.getWidth() != 160 ||
                image.getHeight() != 144 || request.get("width").getAsInt() != 160 ||
                request.get("height").getAsInt() != 144 || image.getRGB(1, 1) != 0xffff0000) {
            System.exit(2);
        }
        switch (args[0]) {
            case "stall" -> Thread.sleep(30_000);
            case "setup" -> {
                emit("{\"event\":\"setup\"}");
                Thread.sleep(1300);
                emit("{\"event\":\"translating\"}");
                emit("{\"event\":\"result\",\"regions\":[]}");
            }
            case "setup-stall" -> {
                emit("{\"event\":\"setup\"}");
                Thread.sleep(30_000);
            }
            case "repeat-setup" -> {
                emit("{\"event\":\"setup\"}");
                emit("{\"event\":\"setup\"}");
            }
            case "oversize" -> emit("x".repeat(129 * 1024));
            case "malformed" -> emit("private screenshot text is not valid JSON");
            case "error" -> emit("{\"event\":\"error\",\"code\":\"download_failed\",\"message\":\"private screenshot text\"}");
            case "outside" -> emit("{\"event\":\"result\",\"regions\":[{\"text\":\"Hello\",\"x\":150,\"y\":10,\"width\":20,\"height\":10}]}");
            case "fraction" -> emit("{\"event\":\"result\",\"regions\":[{\"text\":\"Hello\",\"x\":1.5,\"y\":10,\"width\":20,\"height\":10}]}");
            case "eof" -> { }
            default -> emit("{\"event\":\"result\",\"regions\":[{\"text\":\"Hello\",\"x\":8,\"y\":96,\"width\":140,\"height\":32}]}");
        }
    }

    private static void emit(String event) {
        System.out.println(event);
        System.out.flush();
    }
}
