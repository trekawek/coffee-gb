package eu.rekawek.coffeegb.controller

interface SnapshotSupport {
  fun saveSnapshot(slot: Int)

  fun loadSnapshot(slot: Int)

  fun snapshotAvailable(slot: Int): Boolean
}
