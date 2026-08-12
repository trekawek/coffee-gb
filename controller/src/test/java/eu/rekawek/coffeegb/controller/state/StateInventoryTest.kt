package eu.rekawek.coffeegb.controller.state

import eu.rekawek.coffeegb.controller.StateTypeRegistry
import eu.rekawek.coffeegb.core.serial.mobile.MobileAdapterEngine
import kotlin.test.assertEquals
import org.junit.Test

class StateInventoryTest {

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
        100,
        StateTypeRegistry.recordClassNames.indexOf(
            "eu.rekawek.coffeegb.core.memory.cart.type.Hitek\$HitekState") + 1,
    )
    assertEquals(
        101,
        StateTypeRegistry.recordClassNames.indexOf(
            "eu.rekawek.coffeegb.core.memory.cart.type.Gowin\$GowinState") + 1,
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

}
