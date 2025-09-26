package eu.rekawek.coffeegb.core.genie;

import eu.rekawek.coffeegb.core.AddressSpace;
import eu.rekawek.coffeegb.core.events.EventBus;
import eu.rekawek.coffeegb.core.memento.Memento;
import eu.rekawek.coffeegb.core.memento.Originator;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

public class Genie implements AddressSpace, Originator<Genie> {

    private final AddressSpace delegate;

    private final Map<Integer, List<Patch>> patches = new HashMap<>();

    private final boolean gbc;

    public Genie(AddressSpace delegate, boolean gbc) {
        this.delegate = delegate;
        this.gbc = gbc;
    }

    public void init(EventBus eventBus) {
        eventBus.register(e -> e.patches().forEach(this::addPatch), AddPatches.class);
    }

    private void addPatch(Patch patch) {
        patches.computeIfAbsent(patch.getAddress(), k -> new ArrayList<>()).add(patch);
    }

    @Override
    public boolean accepts(int address) {
        return delegate.accepts(address);
    }

    @Override
    public void setByte(int address, int value) {
        delegate.setByte(address, value);
    }

    @Override
    public int getByte(int address) {
        var value = delegate.getByte(address);
        if (patches.containsKey(address)) {
            for (Patch p : patches.get(address)) {
                if (p.accepts(delegate, gbc)) {
                    return p.getValue();
                }
            }
        }
        return value;
    }

    @Override
    public Memento<Genie> saveToMemento() {
        var map = new HashMap<Integer, List<Patch>>();
        patches.forEach((k, v) -> map.put(k, new ArrayList<>(v)));
        return new GenieMemento(map);
    }

    @Override
    public void restoreFromMemento(Memento<Genie> memento) {
        if (!(memento instanceof GenieMemento mem)) {
            throw new IllegalArgumentException("Invalid memento type");
        }
        patches.clear();
        mem.patches.forEach((k, v) -> patches.put(k, new ArrayList<>(v)));
    }

    private record GenieMemento(Map<Integer, List<Patch>> patches) implements Memento<Genie> {
    }
}
