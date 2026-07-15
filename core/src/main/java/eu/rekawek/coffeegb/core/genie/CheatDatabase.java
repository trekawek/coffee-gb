package eu.rekawek.coffeegb.core.genie;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.text.Normalizer;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Comparator;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.zip.ZipEntry;
import java.util.zip.ZipInputStream;

/** The bundled libretro Game Boy cheat database. */
public final class CheatDatabase {

    private static final String RESOURCE = "/cheats/libretro-game-boy.zip";

    private static final Pattern CHEAT_PROPERTY =
            Pattern.compile("^cheat(\\d+)_(desc|code)\\s*=\\s*\\\"(.*)\\\"\\s*$");

    private static final Pattern EXTENSION = Pattern.compile("(?i)\\.(gbc?|rom|zip|cht)$");

    private static final Pattern QUALIFIER = Pattern.compile("\\s*[\\[(].*$");

    private final List<CheatList> cheatLists;

    private CheatDatabase(List<CheatList> cheatLists) {
        this.cheatLists = List.copyOf(cheatLists);
    }

    public static CheatDatabase loadBundled() throws IOException {
        InputStream input = CheatDatabase.class.getResourceAsStream(RESOURCE);
        if (input == null) {
            throw new IOException("Bundled cheat database not found: " + RESOURCE);
        }
        try (input) {
            return readZip(input);
        }
    }

    public static CheatDatabase readZip(InputStream input) throws IOException {
        List<CheatList> lists = new ArrayList<>();
        try (ZipInputStream zip = new ZipInputStream(input, StandardCharsets.UTF_8)) {
            ZipEntry entry;
            while ((entry = zip.getNextEntry()) != null) {
                if (!entry.isDirectory() && entry.getName().toLowerCase(Locale.ROOT).endsWith(".cht")) {
                    CheatList list = parse(entry.getName(), new ByteArrayInputStream(zip.readAllBytes()));
                    if (!list.cheats().isEmpty()) {
                        lists.add(list);
                    }
                }
                zip.closeEntry();
            }
        }
        lists.sort(Comparator.comparing(CheatList::name, String.CASE_INSENSITIVE_ORDER));
        return new CheatDatabase(lists);
    }

    static CheatList parse(String entryName, InputStream input) throws IOException {
        Map<Integer, Map<String, String>> properties = new LinkedHashMap<>();
        String text = new String(input.readAllBytes(), StandardCharsets.UTF_8);
        for (String line : text.split("\\R")) {
            Matcher matcher = CHEAT_PROPERTY.matcher(line.trim());
            if (matcher.matches()) {
                int index = Integer.parseInt(matcher.group(1));
                properties.computeIfAbsent(index, ignored -> new HashMap<>())
                        .put(matcher.group(2), unescape(matcher.group(3)));
            }
        }

        List<Cheat> cheats = new ArrayList<>();
        properties.values().forEach(p -> {
            String description = p.get("desc");
            String code = p.get("code");
            if (description != null && code != null && !code.isBlank()) {
                cheats.add(new Cheat(description, code));
            }
        });
        return new CheatList(baseName(entryName), List.copyOf(cheats));
    }

    /**
     * Returns the database entries most closely matching a ROM filename or cartridge title.
     * Exact No-Intro-style names sort first; shorter header titles still find useful candidates.
     */
    public List<CheatList> findCheatLists(Collection<String> romNames, int limit) {
        if (limit <= 0) {
            return List.of();
        }
        List<String> names = romNames.stream()
                .filter(name -> name != null && !name.isBlank())
                .map(CheatDatabase::baseName)
                .toList();
        if (names.isEmpty()) {
            return List.of();
        }

        return cheatLists.stream()
                .map(list -> new Match(list, names.stream()
                        .mapToDouble(name -> score(name, list.name()))
                        .max()
                        .orElse(0)))
                .filter(match -> match.score() >= 0.45)
                .sorted(Comparator.comparingDouble(Match::score).reversed()
                        .thenComparing(match -> match.list().name(), String.CASE_INSENSITIVE_ORDER))
                .limit(limit)
                .map(Match::list)
                .toList();
    }

    public List<CheatList> getCheatLists() {
        return cheatLists;
    }

    private static double score(String romName, String listName) {
        String fullRom = normalize(romName);
        String fullList = normalize(listName);
        if (fullRom.equals(fullList)) {
            return 2;
        }

        String simpleRom = normalize(QUALIFIER.matcher(romName).replaceFirst(""));
        String simpleList = normalize(QUALIFIER.matcher(listName).replaceFirst(""));
        if (simpleRom.equals(simpleList)) {
            return 1.5;
        }
        if (simpleRom.isEmpty() || simpleList.isEmpty()) {
            return 0;
        }
        if (simpleList.startsWith(simpleRom + " ") || simpleRom.startsWith(simpleList + " ")) {
            return 1.0;
        }

        List<String> romTokens = List.of(simpleRom.split(" "));
        List<String> listTokens = List.of(simpleList.split(" "));
        long common = romTokens.stream().distinct().filter(listTokens::contains).count();
        return (2.0 * common) / (romTokens.stream().distinct().count()
                + listTokens.stream().distinct().count());
    }

    private static String normalize(String value) {
        String decomposed = Normalizer.normalize(baseName(value), Normalizer.Form.NFD)
                .replaceAll("\\p{M}", "")
                .toLowerCase(Locale.ROOT);
        return decomposed.replace('&', ' ')
                .replaceAll("[^a-z0-9]+", " ")
                .replaceAll("^the | the$", "")
                .trim();
    }

    private static String baseName(String path) {
        String name = path.replace('\\', '/');
        name = name.substring(name.lastIndexOf('/') + 1);
        return EXTENSION.matcher(name).replaceFirst("");
    }

    private static String unescape(String value) {
        return value.replace("\\\"", "\"")
                .replace("\\\\", "\\")
                .replace("&amp;", "&");
    }

    public record Cheat(String description, String code) {
        @Override
        public String toString() {
            return description + " (" + code + ")";
        }
    }

    public record CheatList(String name, List<Cheat> cheats) {
        @Override
        public String toString() {
            return name;
        }
    }

    private record Match(CheatList list, double score) {
    }
}
