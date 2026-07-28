package eu.rekawek.coffeegb.swing.packaging;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Comparator;

final class NativePackagingTestSupport {

    private NativePackagingTestSupport() {
    }

    static Path writeTargetSbom(
            Path output,
            Path artifact,
            NativeTarget target,
            NativePackageMetadata.PackageType packageType,
            String version,
            String signing)
            throws Exception {
        Path work = Files.createTempDirectory(
                output.getParent().getParent(), "target-sbom-test-");
        Path source = work.resolve("source.cdx.json");
        String rootRef =
                "pkg:maven/eu.rekawek.coffeegb/swing@" + version + "?type=jar";
        Files.writeString(
                source,
                "{\"bomFormat\":\"CycloneDX\",\"specVersion\":\"1.6\",\"metadata\":"
                        + "{\"component\":{\"type\":\"application\",\"bom-ref\":\""
                        + rootRef + "\",\"name\":\"Coffee GB\",\"version\":\""
                        + version + "\"}},"
                        + "\"components\":[{\"type\":\"library\",\"name\":\"example\","
                        + "\"version\":\"1\",\"purl\":\"pkg:maven/example/example@1?type=jar\"}],"
                        + "\"dependencies\":[{\"ref\":\"" + rootRef
                        + "\",\"dependsOn\":[\"pkg:maven/example/example@1?type=jar\"]}]}",
                StandardCharsets.UTF_8);
        Path javaHome = Files.createDirectories(work.resolve("jdk"));
        Files.writeString(
                javaHome.resolve("release"),
                "IMPLEMENTOR=\"Synthetic JDK\"\n"
                        + "JAVA_RUNTIME_VERSION=\"21.0.9+1\"\n"
                        + "JAVA_VERSION=\"21.0.9\"\n"
                        + "OS_ARCH=\"x86_64\"\n",
                StandardCharsets.UTF_8);
        String modules = NativePackageMetadata.LINKED_RUNTIME_MODULES.stream()
                .sorted(Comparator.naturalOrder())
                .map(module -> module + "@21.0.9")
                .reduce("", (left, right) -> left + right + "\n");
        NativeTargetSbom.write(
                source,
                output,
                target,
                packageType,
                version,
                javaHome,
                modules,
                artifact,
                signing);
        return output;
    }
}
