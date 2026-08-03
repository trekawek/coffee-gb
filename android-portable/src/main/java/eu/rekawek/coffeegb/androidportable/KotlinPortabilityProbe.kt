package eu.rekawek.coffeegb.androidportable

/** Kotlin bytecode companion for [AndroidPortabilityProbe]'s D8/R8 smoke path. */
class KotlinPortabilityProbe(private val moduleName: String) {
  fun description(): String = "$moduleName includes Kotlin metadata"
}
