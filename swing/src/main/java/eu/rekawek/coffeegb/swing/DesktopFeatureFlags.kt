package eu.rekawek.coffeegb.swing

/** Opt-in desktop features that are intentionally not part of the stable default UI yet. */
internal object DesktopFeatureFlags {
  /** Enables the portable Proposal 3 menu with `-Dcoffee-gb.desktop.proposal3-menu=true`. */
  const val PROPOSAL3_MENU_PROPERTY = "coffee-gb.desktop.proposal3-menu"

  fun proposal3MenuEnabled(
      value: String? = System.getProperty(PROPOSAL3_MENU_PROPERTY),
  ): Boolean = value.equals("true", ignoreCase = true)
}
