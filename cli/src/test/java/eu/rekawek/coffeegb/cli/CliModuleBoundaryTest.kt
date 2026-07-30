package eu.rekawek.coffeegb.cli

import java.nio.charset.StandardCharsets
import java.nio.file.Files
import java.nio.file.Path
import kotlin.io.path.extension
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import org.junit.Test

class CliModuleBoundaryTest {
  private val repoRoot = Path.of(System.getProperty("coffee-gb.repo-root")).toAbsolutePath()

  @Test
  fun cliDeclaresOnlyTheControllerModuleAndAnExecutableFatJar() {
    val pom = Files.readString(repoRoot.resolve("cli/pom.xml"))
    val dependencyArtifacts =
        Regex("<dependencies>(.*?)</dependencies>", RegexOption.DOT_MATCHES_ALL)
            .find(pom)!!
            .groupValues[1]
            .let { Regex("<artifactId>([^<]+)</artifactId>").findAll(it).map { match -> match.groupValues[1] }.toList() }
    assertEquals(listOf("controller"), dependencyArtifacts)
    assertTrue("maven-assembly-plugin" in pom)
    assertTrue("eu.rekawek.coffeegb.cli.MainKt" in pom)
  }

  @Test
  fun productionCliSourcesAndClassesDoNotReferenceDesktopApis() {
    val sourceRoot = repoRoot.resolve("cli/src/main")
    Files.walk(sourceRoot).use { paths ->
      paths
          .filter(Files::isRegularFile)
          .filter { it.extension in setOf("kt", "java") }
          .forEach { path ->
            val source = Files.readString(path)
            assertFalse("java.awt" in source, "$path imports AWT")
            assertFalse("javax.swing" in source, "$path imports Swing")
            assertFalse("coffeegb.swing" in source, "$path imports the desktop module")
          }
    }

    val classes = repoRoot.resolve("cli/target/classes/eu/rekawek/coffeegb/cli")
    Files.walk(classes).use { paths ->
      paths.filter { Files.isRegularFile(it) && it.extension == "class" }.forEach { path ->
        val constants = Files.readAllBytes(path).toString(StandardCharsets.ISO_8859_1)
        assertFalse("java/awt" in constants, "$path references AWT")
        assertFalse("javax/swing" in constants, "$path references Swing")
      }
    }
  }
}
