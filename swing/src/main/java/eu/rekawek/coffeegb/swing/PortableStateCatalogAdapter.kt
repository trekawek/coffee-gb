package eu.rekawek.coffeegb.swing

import eu.rekawek.coffeegb.controller.state.StateBrowserCatalog
import eu.rekawek.coffeegb.controller.state.StateRef
import eu.rekawek.coffeegb.ui.menu.MenuPreview

/** Converts the asynchronous managed/compatibility catalog into path-free menu rows. */
internal fun portableMenuStateSlots(
    catalog: StateBrowserCatalog,
    compatibilitySlots: Set<Int>,
): List<PortableMenuStateSlot> =
    catalog.entries
        .asSequence()
        .filter { it.ref is StateRef.Slot }
        .filter { (it.ref as StateRef.Slot).index in StateRef.MIN_SLOT..StateRef.MAX_SLOT }
        .map { entry ->
          val ref = entry.ref as StateRef.Slot
          val managedLoadable = entry.canLoad
          // A present managed entry is authoritative even when it is corrupt or incompatible;
          // only an empty managed slot may fall through to a preserved `.snN` sidecar.
          val compatibilityLoadable =
              entry.catalogEntry == null && ref.index in compatibilitySlots
          val image = entry.thumbnail
          val preview =
              if (image == null) {
                MenuPreview.empty()
              } else {
                val rgb = image.copyRgb()
                val argb = IntArray(rgb.size) { index -> 0xff000000.toInt() or rgb[index] }
                MenuPreview.ready(image.width, image.height, argb)
              }
          PortableMenuStateSlot(
              ref.index,
              managedLoadable || compatibilityLoadable,
              preview,
              entry.catalogEntry?.metadata?.savedAt?.takeIf { managedLoadable },
          )
        }
        .toList()
