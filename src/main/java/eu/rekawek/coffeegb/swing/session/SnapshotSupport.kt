package eu.rekawek.coffeegb.swing.session

interface SnapshotSupport {
  fun saveSnapshot(slot: Int)

  fun loadSnapshot(slot: Int)

  fun snapshotAvailable(slot: Int): Boolean
}
