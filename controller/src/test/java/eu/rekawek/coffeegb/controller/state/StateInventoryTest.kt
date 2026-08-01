package eu.rekawek.coffeegb.controller.state

import eu.rekawek.coffeegb.controller.StateTypeRegistry
import eu.rekawek.coffeegb.core.serial.mobile.MobileAdapterEngine
import java.nio.file.Files
import java.nio.file.Paths
import kotlin.io.path.readLines
import kotlin.io.path.readText
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import org.junit.Test

class StateInventoryTest {

  @Test
  fun committedFieldInventoryExactlyMatchesAuditedProductionRegistry() {
    val documented =
        Paths.get("../docs/state-memento-schema.md")
            .readLines()
            .filter { it.startsWith("- `") }
    val runtime =
        StateTypeRegistry.recordClasses.map { type ->
          val fields = type.recordComponents.joinToString(", ") { it.name }
          "- `${type.name}`: $fields"
        }

    assertEquals(99, runtime.size)
    assertEquals(runtime, documented)
    assertEquals(11, StateTypeRegistry.enumClasses.size)
  }

  @Test
  fun mobileAdapterPortableIdsArePinnedToAppendOnlyValues() {
    assertEquals(
        95,
        StateTypeRegistry.recordClassNames.indexOf(
            "eu.rekawek.coffeegb.core.serial.mobile.MobileAdapterEngine\$MobileAdapterEngineState") +
            1,
    )
    assertEquals(
        96,
        StateTypeRegistry.recordClassNames.indexOf(
            "eu.rekawek.coffeegb.core.serial.mobile.MobileAdapterSerialEndpoint\$MobileAdapterSerialEndpointState") +
            1,
    )
    assertEquals(
        97,
        StateTypeRegistry.recordClassNames.indexOf(
            "eu.rekawek.coffeegb.core.serial.mobile.MobileAdapterEngine\$MobileAdapterEngineNetworkState") +
            1,
    )
    assertEquals(
        98,
        StateTypeRegistry.recordClassNames.indexOf(
            "eu.rekawek.coffeegb.core.serial.mobile.MobileAdapterSerialEndpoint\$MobileAdapterSerialEndpointNetworkState") +
            1,
    )
    assertEquals(
        99,
        StateTypeRegistry.recordClassNames.indexOf(
            "eu.rekawek.coffeegb.core.serial.mobile.MobileAdapterSerialEndpoint\$MobileAdapterSerialEndpointWireState") +
            1,
    )
    assertEquals(
        8,
        StatePayloadSectionCodec.serialPeripheralId(SerialPeripheralState.MOBILE_ADAPTER_GB),
    )
    assertEquals(
        SerialPeripheralState.MOBILE_ADAPTER_GB,
        StatePayloadSectionCodec.serialPeripheral(8),
    )
    assertEquals(18, MobileAdapterEngine.Outcome.CONFIG_WRITE.id())
    assertEquals(19, MobileAdapterEngine.Outcome.BACKEND_PENDING.id())
    assertEquals(20, MobileAdapterEngine.Outcome.BACKEND_RESPONSE.id())
    assertEquals(21, MobileAdapterEngine.Outcome.BACKEND_ERROR.id())
    assertEquals(22, MobileAdapterEngine.Outcome.BACKEND_REMOTE_CLOSED.id())
    assertEquals(23, MobileAdapterEngine.Outcome.EXTERNAL_IO_DISCONNECTED.id())
    assertEquals(3, MobileAdapterEngine.Phase.TELEPHONE.id())
    assertEquals(4, MobileAdapterEngine.Phase.INTERNET.id())
    assertEquals(24, MobileAdapterEngine.Outcome.TELEPHONE_DIALLED.id())
    assertEquals(25, MobileAdapterEngine.Outcome.TELEPHONE_HUNG_UP.id())
    assertEquals(26, MobileAdapterEngine.Outcome.TELEPHONE_STATUS.id())
    assertEquals(27, MobileAdapterEngine.Outcome.ISP_LOGGED_IN.id())
    assertEquals(28, MobileAdapterEngine.Outcome.ISP_LOGGED_OUT.id())
    assertEquals(29, MobileAdapterEngine.Outcome.SERVICE_ERROR.id())
    assertEquals(9, MobileAdapterEngine.ErrorCode.BACKEND_BUSY.id())
    assertEquals(10, MobileAdapterEngine.ErrorCode.BACKEND_UNAVAILABLE.id())
    assertEquals(11, MobileAdapterEngine.ErrorCode.BACKEND_RESPONSE_INVALID.id())
    assertEquals(12, MobileAdapterEngine.ErrorCode.EXTERNAL_IO_DISCONNECTED.id())
  }

  @Test
  fun everyProductionOriginatorAndCaptureSiteIsInTheOwnershipAudit() {
    val repository = Paths.get("..").toAbsolutePath().normalize()
    val productionRoots =
        listOf(repository.resolve("core/src/main/java"), repository.resolve("controller/src/main/java"))
    val discovered =
        productionRoots
            .flatMap { root ->
              Files.walk(root).use { paths ->
                paths
                    .filter { Files.isRegularFile(it) }
                    .filter {
                      val source = it.readText()
                      "captureState(" in source ||
                          "captureStateWithoutTimeSource(" in source ||
                          "StatefulComponent<" in source ||
                          "captureDetachedState(" in source ||
                          "DetachedStateAdapter.capture(" in source ||
                          "MachineSnapshot.capture(" in source
                    }
                    .map { path ->
                      repository.relativize(path).joinToString("/") { it.toString() }
                    }
                    .toList()
              }
            }
            .sorted()
    val documented =
        repository
            .resolve("docs/state-originator-sites.md")
            .readLines()
            .mapNotNull { line ->
              ORIGINATOR_AUDIT_LINE.matchEntire(line)?.groupValues?.get(1)
            }
            .sorted()

    assertEquals(107, discovered.size)
    assertTrue(discovered.all { '\\' !in it })
    assertEquals(discovered, documented)
  }

  @Test
  fun everyAdmittedRecordHasAnExplicitSemanticPolicyAndRationale() {
    assertEquals(StateTypeRegistry.recordClassNames.toSet(), StateSemantics.policyAudit.keys)
    assertEquals(99, StateSemantics.policyAudit.size)
    assertTrue(StateSemantics.policyAudit.values.all { it.isNotBlank() })
  }

  private companion object {
    val ORIGINATOR_AUDIT_LINE = Regex("- (?:OWNER|COMPOSITE|WORKFLOW|CONTRACT) `([^`]+)`")
  }
}
