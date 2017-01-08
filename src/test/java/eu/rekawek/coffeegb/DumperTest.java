package eu.rekawek.coffeegb;

import org.junit.Test;

public class DumperTest {

    @Test
    public void tileDumpTest() {
        Dumper.dumpTileLine(0x7c, 0x7c);
        System.out.println();

        Dumper.dumpTileLine(0x00, 0xc6);
        System.out.println();

        Dumper.dumpTileLine(0xc6, 0x00);
        System.out.println();

        Dumper.dumpTileLine(0x00, 0xfe);
        System.out.println();

        Dumper.dumpTileLine(0xc6, 0xc6);
        System.out.println();

        Dumper.dumpTileLine(0x00, 0xc6);
        System.out.println();

        Dumper.dumpTileLine(0xc6, 0x00);
        System.out.println();

        Dumper.dumpTileLine(0x00, 0x00);
        System.out.println();
    }

}
