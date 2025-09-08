package eu.rekawek.coffeegb.swing.controller

interface SnapshotSupport {
  fun saveSnapshot(slot: Int)

  fun loadSnapshot(slot: Int)

  fun snapshotAvailable(slot: Int): Boolean
}
