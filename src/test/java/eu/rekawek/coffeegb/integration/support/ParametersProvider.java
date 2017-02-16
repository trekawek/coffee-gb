package eu.rekawek.coffeegb.integration.support;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.Collection;
import java.util.stream.Collectors;

public final class ParametersProvider {

    private ParametersProvider() {
    }

    public static Collection<Object[]> getParameters(String dirName) throws IOException {
        Path dir = Paths.get("src/test/resources/roms", dirName);
        return Files.walk(dir).filter(Files::isRegularFile).map(p -> new Object[] {dir.relativize(p).toString(), p}).collect(Collectors.toList());
    }

}
