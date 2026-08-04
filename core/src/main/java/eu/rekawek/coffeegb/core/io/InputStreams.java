package eu.rekawek.coffeegb.core.io;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.util.Objects;

/** Stream helpers that remain available on the minimum Android API level. */
public final class InputStreams {

    private static final int BUFFER_SIZE = 8 * 1024;

    private InputStreams() {
    }

    /** Reads the remaining bytes without depending on Java 9's {@link InputStream#readAllBytes()}. */
    public static byte[] readAllBytes(InputStream input) throws IOException {
        Objects.requireNonNull(input, "input");
        ByteArrayOutputStream output = new ByteArrayOutputStream();
        byte[] buffer = new byte[BUFFER_SIZE];
        int count;
        while ((count = input.read(buffer)) != -1) {
            output.write(buffer, 0, count);
        }
        return output.toByteArray();
    }
}
