import java.io.File
import javax.xml.XMLConstants
import javax.xml.parsers.DocumentBuilderFactory

pluginManagement {
  repositories {
    google()
    mavenCentral()
    gradlePluginPortal()
  }
}

fun coffeeGbVersion(pom: File): String {
  val factory = DocumentBuilderFactory.newInstance()
  factory.setFeature(XMLConstants.FEATURE_SECURE_PROCESSING, true)
  factory.setFeature("http://apache.org/xml/features/disallow-doctype-decl", true)
  val project = factory.newDocumentBuilder().parse(pom).documentElement
  val versions = project.childNodes
  for (index in 0 until versions.length) {
    val child = versions.item(index)
    if (child.nodeName == "version") {
      return child.textContent.trim()
    }
  }
  error("Unable to read the direct project version from ${pom.absolutePath}")
}

val checkoutRoot = rootDir.parentFile.canonicalFile
val expectedMavenRepository = checkoutRoot.resolve("build/android-m2").canonicalFile
val suppliedMavenRepository = providers.gradleProperty("coffeeGbMavenRepository").orNull
    ?: error(
        "Set -PcoffeeGbMavenRepository=${expectedMavenRepository.path} after installing " +
            "core and controller from this checkout."
    )
val coffeeGbMavenRepository = file(suppliedMavenRepository).canonicalFile

check(coffeeGbMavenRepository == expectedMavenRepository) {
  "coffeeGbMavenRepository must be ${expectedMavenRepository.path}; Maven-local and arbitrary " +
      "repositories are intentionally rejected."
}
check(coffeeGbMavenRepository.isDirectory) {
  "Same-checkout Maven repository ${coffeeGbMavenRepository.path} does not exist. Run the " +
      "documented Maven install command first."
}

gradle.extra["coffeeGbVersion"] = coffeeGbVersion(checkoutRoot.resolve("pom.xml"))

dependencyResolutionManagement {
  repositoriesMode.set(RepositoriesMode.FAIL_ON_PROJECT_REPOS)
  repositories {
    maven {
      name = "sameCheckoutCoffeeGb"
      url = uri(coffeeGbMavenRepository)
      content {
        includeGroup("eu.rekawek.coffeegb")
      }
    }
    google()
    mavenCentral()
  }
}

rootProject.name = "coffee-gb-android"
include(":app")
