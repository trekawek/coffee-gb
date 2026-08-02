package eu.rekawek.coffeegb.core.cpu.opcode;

import eu.rekawek.coffeegb.core.cpu.Opcodes;
import eu.rekawek.coffeegb.core.cpu.op.Op;
import org.junit.Test;

import java.util.List;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertSame;

public class OpcodeExecutionMetadataTest {

    @Test
    public void executionViewMatchesEveryBaseAndExtendedOpcode() {
        assertExecutionViews(Opcodes.COMMANDS);
        assertExecutionViews(Opcodes.EXT_COMMANDS);
    }

    private static void assertExecutionViews(List<Opcode> opcodes) {
        for (Opcode opcode : opcodes) {
            if (opcode == null) {
                continue;
            }
            List<Op> operations = opcode.getOps();
            assertEquals(operations.size(), opcode.getOpCount());
            for (int i = 0; i < operations.size(); i++) {
                Op operation = operations.get(i);
                assertSame(operation, opcode.getOp(i));
                assertEquals(operation.readsMemory() || operation.writesMemory(),
                        opcode.opAccessesMemory(i));
                assertEquals(operation.writesMemory(), opcode.opWritesMemory(i));
            }
        }
    }
}
