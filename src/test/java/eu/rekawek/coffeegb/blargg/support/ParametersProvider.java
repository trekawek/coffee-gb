package eu.rekawek.coffeegb.blargg.support;

import org.apache.commons.io.filefilter.SuffixFileFilter;

import java.io.File;
import java.util.ArrayList;
import java.util.Collection;
import java.util.List;

public final class ParametersProvider {

    private ParametersProvider() {
    }

    public static Collection<Object[]> getParameters(String dirName) {
        File dir = new File("src/test/resources/roms", dirName);
        List<Object[]> list = new ArrayList<>();
        for (String name : dir.list(new SuffixFileFilter(".gb"))) {
            File rom = new File(dir, name);
            list.add(new Object[] {name, String.format("%s/%s", dirName, name)});
        }
        return list;
    }

}
