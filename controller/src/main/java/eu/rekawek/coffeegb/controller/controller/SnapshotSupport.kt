package eu.rekawek.coffeegb.controller.controller

interface SnapshotSupport {
  fun saveSnapshot(slot: Int)

  fun loadSnapshot(slot: Int)

  fun snapshotAvailable(slot: Int): Boolean
}
