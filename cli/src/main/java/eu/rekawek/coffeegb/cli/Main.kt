package eu.rekawek.coffeegb.cli

import kotlin.system.exitProcess

private object CliVersionMarker

fun main(args: Array<String>) {
  // The executable contract owns stderr. Library logging must not inject nondeterministic lines.
  System.setProperty("org.slf4j.simpleLogger.defaultLogLevel", "off")
  val version = CliVersionMarker::class.java.`package`.implementationVersion ?: "development"
  exitProcess(CliApplication(HeadlessCliEngineFactory.create()).run(args, version = version))
}
