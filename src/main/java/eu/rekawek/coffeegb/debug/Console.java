package eu.rekawek.coffeegb.debug;

import org.jline.reader.LineReader;
import org.jline.reader.LineReaderBuilder;

import java.util.concurrent.BlockingDeque;
import java.util.concurrent.LinkedBlockingDeque;

public class Console implements Runnable {

    private final BlockingDeque<String> lineBuffer = new LinkedBlockingDeque<>(1);

    private volatile boolean isStarted;

    public Console() {
    }

    @Override
    public void run() {
        isStarted = true;

        LineReader lineReader = LineReaderBuilder
                .builder()
                .build();

        while (true) {
            String line = lineReader.readLine("coffee-gb> ");
            lineBuffer.add(line);
        }
    }

    public void tick() {
        if (!isStarted) {
            return;
        }

        while (!lineBuffer.isEmpty()) {
            String line = lineBuffer.peek();
            handleCommand(line);
            lineBuffer.poll();
        }
    }

    private void handleCommand(String line) {
        System.out.println("xyz: " + line);
    }

}
