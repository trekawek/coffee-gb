package eu.rekawek.coffeegb.core;

import eu.rekawek.coffeegb.core.debug.DebugHooks;
import eu.rekawek.coffeegb.core.debug.DebugInterruptType;

/** Minimal owner-thread hook used by component fast-path guard tests. */
public final class TestDebugHooks implements DebugHooks {

    @Override
    public void onInstructionFetch(int programCounter) {
    }

    @Override
    public void onOpcodeFetched(int programCounter, boolean cbPrefixed, int opcode) {
    }

    @Override
    public void onInstructionRetired(
            boolean instructionKnown, int programCounter, int opcode, int prefixedOpcode) {
    }

    @Override
    public void onInterruptRequested(DebugInterruptType interrupt) {
    }

    @Override
    public void onInterruptAccepted(DebugInterruptType interrupt) {
    }
}
