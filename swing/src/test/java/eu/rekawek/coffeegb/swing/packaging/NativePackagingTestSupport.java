package eu.rekawek.coffeegb.swing.packaging;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

final class NativePackagingTestSupport {

    private NativePackagingTestSupport() {
    }

    static Path writeMavenSbom(Path output, String version) throws Exception {
        String rootRef =
                "pkg:maven/eu.rekawek.coffeegb/swing@" + version + "?type=jar";
        Files.writeString(
                output,
                "{\"bomFormat\":\"CycloneDX\",\"specVersion\":\"1.6\",\"metadata\":"
                        + "{\"component\":{\"type\":\"application\",\"bom-ref\":\""
                        + rootRef + "\",\"group\":\"eu.rekawek.coffeegb\","
                        + "\"name\":\"swing\",\"version\":\"" + version + "\","
                        + "\"purl\":\"" + rootRef + "\"}},"
                        + "\"components\":[{\"type\":\"library\",\"name\":\"example\","
                        + "\"version\":\"1\",\"purl\":\"pkg:maven/example/example@1?type=jar\"}],"
                        + "\"dependencies\":[{\"ref\":\"" + rootRef
                        + "\",\"dependsOn\":[\"pkg:maven/example/example@1?type=jar\"]}]}",
                StandardCharsets.UTF_8);
        return output;
    }

    static Path writeNativeSbom(Path output, NativeTarget target, String version)
            throws Exception {
        NativeComponentInventory.writeNativeSbom(target, version, output);
        return output;
    }
}
