package eu.rekawek.coffeegb.controller

interface SnapshotSupport {
  fun snapshotAvailable(slot: Int): Boolean
}
