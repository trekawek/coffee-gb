package eu.rekawek.coffeegb.swing

import eu.rekawek.coffeegb.swing.SwingGui.Companion.run
import eu.rekawek.coffeegb.core.hardware.HardwareProfile
import eu.rekawek.coffeegb.core.hardware.HardwareProfileRegistry
import java.io.File
import java.io.PrintStream

fun main(args: Array<String>) {
  val parsedArgs = ParsedArgs.Companion.parse(args)
  if (parsedArgs.shortParams.contains("h") || parsedArgs.params.contains("help")) {
    printUsage(System.out)
    return
  }
  if (parsedArgs.args.size > 1) {
    printUsage(System.err)
    return
  }

  val debug = parsedArgs.params.contains("debug")

  val profileOverride =
      try {
        parsedArgs.hardwareProfileOverride()
      } catch (failure: IllegalArgumentException) {
        System.err.println(failure.message)
        printUsage(System.err)
        return
      }

  var initialRom: File? = null
  if (parsedArgs.args.size == 1) {
    initialRom = File(parsedArgs.args[0]!!)
  }
  run(debug, initialRom, profileOverride)
}

private fun printUsage(stream: PrintStream) {
  stream.println("Usage:")
  stream.println("java -jar coffee-gb.jar [OPTIONS] [ROM_FILE]")
  stream.println()
  stream.println("Available options:")
  stream.println("  -d  --force-dmg                Emulate classic GB (DMG) for universal ROMs")
  stream.println("  -c  --force-cgb                Emulate color GB (CGB) for all ROMs")
  stream.println("      --profile=<id>             Select profile: ${HardwareProfileRegistry.supportedIds().joinToString(", ")}")
  stream.println("  -b  --use-bootstrap            Start with the GB bootstrap")
  stream.println("  -db --disable-battery-saves    Disable battery saves")
  stream.println("  -h  --help                     Displays this info")
  stream.println("      --debug                    Enable debug console")
}

class ParsedArgs(
    val params: MutableSet<String?>,
    val shortParams: MutableSet<String?>,
    val args: MutableList<String?>
) {
  fun hardwareProfileOverride(): HardwareProfile? {
    val long = params.filterNotNull()
    val profileOptions = long.filter { it == "profile" || it.startsWith("profile=") }
    if (profileOptions.size > 1) {
      throw IllegalArgumentException("Specify --profile exactly once")
    }
    val forceDmg = long.contains("force-dmg") || shortParams.contains("d")
    val forceCgb = long.contains("force-cgb") || shortParams.contains("c")
    if (forceDmg && forceCgb) {
      throw IllegalArgumentException("--force-dmg and --force-cgb cannot be used together")
    }
    if (profileOptions.isNotEmpty() && (forceDmg || forceCgb)) {
      throw IllegalArgumentException("--profile conflicts with --force-dmg/--force-cgb")
    }
    if (profileOptions.isNotEmpty()) {
      val option = profileOptions.single()
      if (option == "profile") {
        throw IllegalArgumentException("--profile requires =<id>")
      }
      val id = option.substringAfter("profile=")
      if (id.isBlank() || id.contains('=')) {
        throw IllegalArgumentException("--profile requires one non-empty stable ID")
      }
      return HardwareProfileRegistry.resolve(id)
    }
    return when {
      forceDmg -> HardwareProfileRegistry.DMG
      forceCgb -> HardwareProfileRegistry.CGB
      else -> null
    }
  }

  companion object {
    fun parse(args: Array<String>): ParsedArgs {
      val params: MutableSet<String?> = LinkedHashSet<String?>()
      val shortParams: MutableSet<String?> = LinkedHashSet<String?>()
      val restArgs: MutableList<String?> = ArrayList<String?>()
      for (a in args) {
        if (a.startsWith("--")) {
          params.add(a.substring(2))
        } else if (a.startsWith("-")) {
          shortParams.add(a.substring(1))
        } else {
          restArgs.add(a)
        }
      }
      return ParsedArgs(params, shortParams, restArgs)
    }
  }
}
