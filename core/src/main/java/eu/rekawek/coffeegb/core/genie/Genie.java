package eu.rekawek.coffeegb.core.genie;

import eu.rekawek.coffeegb.core.memento.Memento;

import eu.rekawek.coffeegb.core.AddressSpace;
import eu.rekawek.coffeegb.core.events.EventBus;
import eu.rekawek.coffeegb.core.state.ComponentState;
import eu.rekawek.coffeegb.core.state.StatefulComponent;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

public class Genie implements AddressSpace, StatefulComponent<Genie> {

    private final AddressSpace delegate;

    private final Map<Integer, List<CheatPatch>> patches = new HashMap<>();

    private final boolean gbc;

    private int ramBank;

    public Genie(AddressSpace delegate, boolean gbc) {
        this.delegate = delegate;
        this.gbc = gbc;
    }

    public void init(EventBus eventBus) {
        eventBus.register(e -> e.patches().forEach(this::addPatch), AddPatches.class);
    }

    private void addPatch(CheatPatch patch) {
        patches.computeIfAbsent(patch.getAddress(), k -> new ArrayList<>()).add(patch);
    }

    @Override
    public boolean accepts(int address) {
        return delegate.accepts(address);
    }

    @Override
    public void setByte(int address, int value) {
        updateRamBank(address, value);
        delegate.setByte(address, value);
    }

    @Override
    public void setByteFromCpu(int address, int value) {
        updateRamBank(address, value);
        delegate.setByteFromCpu(address, value);
    }

    private void updateRamBank(int address, int value) {
        if (address >= 0x4000 && address <= 0x5fff) {
            ramBank = value & 0xf;
        }
    }

    @Override
    public int getByte(int address) {
        var value = delegate.getByte(address);
        if (patches.isEmpty()) {
            return value;
        }
        return applyPatches(address, value);
    }

    private int applyPatches(int address, int value) {
        List<CheatPatch> addressPatches = patches.get(address);
        if (addressPatches == null) {
            return value;
        }
        for (CheatPatch patch : addressPatches) {
            if (patch.accepts(delegate, ramBank, gbc)) {
                return patch.getValue();
            }
        }
        return value;
    }

    @Override
    public ComponentState<Genie> captureState() {
        var map = new HashMap<Integer, List<PatchState>>();
        patches.forEach((k, v) -> map.put(k, v.stream().map(Genie::capturePatch).toList()));
        return new GenieState(map);
    }

    @Override
    public void restoreState(ComponentState<Genie> state) {
        if (!(state instanceof GenieState mem)) {
            throw new IllegalArgumentException("Invalid state type");
        }
        patches.clear();
        mem.patches.forEach((k, v) ->
                patches.put(k, new ArrayList<>(v.stream().map(Genie::restorePatch).toList())));
    }

    private static PatchState capturePatch(CheatPatch patch) {
        if (patch instanceof GameGenieCheat genie) {
            return new GameGeniePatchState(genie.newData(), genie.address(), genie.oldData());
        }
        if (patch instanceof GameSharkCheat shark) {
            return new GameSharkPatchState(
                    shark.mode(), shark.bank(), shark.address(), shark.data());
        }
        throw new IllegalArgumentException("Unsupported cheat patch type");
    }

    private static CheatPatch restorePatch(PatchState state) {
        if (state instanceof GameGeniePatchState genie) {
            return new GameGenieCheat(genie.newData(), genie.address(), genie.oldData());
        }
        if (state instanceof GameSharkPatchState shark) {
            return new GameSharkCheat(
                    shark.mode(), shark.bank(), shark.address(), shark.data());
        }
        throw new IllegalArgumentException("Unsupported cheat state type");
    }

    private interface PatchState {
    }

    private record GameGeniePatchState(int newData, int address, int oldData)
            implements PatchState {
    }

    private record GameSharkPatchState(int mode, int bank, int address, int data)
            implements PatchState {
    }

    private record GenieState(Map<Integer, List<PatchState>> patches)
            implements ComponentState<Genie> {
    }

    /** Importer-only compatibility record for released local snapshots. */
    private record GenieMemento(Map<Integer, List<Patch>> patches) implements Memento<Genie> {
    }
}
