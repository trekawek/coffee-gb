package eu.rekawek.coffeegb.gui;

import java.util.LinkedList;
import java.util.List;
import java.util.Properties;
import java.util.stream.Collectors;

public class RecentRoms {

    private static final String KEY_PREFIX = "recent_rom.";
    private static final int MAX_ROMS = 10;

    private final Properties properties;

    private final LinkedList<String> roms;

    public RecentRoms(Properties properties) {
        this.properties = properties;
        roms = new LinkedList<>();
        for (int i = 0; i < MAX_ROMS; i++) {
            String key = KEY_PREFIX + i;
            if (properties.containsKey(key)) {
                roms.add(properties.getProperty(key));
            }
        }
    }

    public List<String> getRoms() {
        return roms;
    }

    public void addRom(String rom) {
        roms.remove(rom);
        roms.addFirst(rom);
        while (roms.size() > MAX_ROMS) {
            roms.removeLast();
        }
        cleanProperties();
        setProperties();
    }

    private void cleanProperties() {
        List<String> keys = properties.keySet().stream().map(o -> (String) o).filter(k -> k.startsWith(KEY_PREFIX)).collect(Collectors.toList());
        for (String k : keys) {
            properties.remove(k);
        }
    }

    private void setProperties() {
        for (int i = 0; i < roms.size(); i++) {
            properties.setProperty(KEY_PREFIX + i, roms.get(i));
        }
    }
}
