# Coffee GB ☕ 🎮

Coffee GB is a **fast and accurate Game Boy Color emulator** written in Java 16. It's designed not only for smooth
gameplay but also for advanced features like **rollback-based netplay**, ensuring a stable multiplayer
experience even on slower connections.

Beyond a standalone emulator, Coffee GB's **core module can be easily integrated** into any Java project, allowing you
to add Game Boy Color emulation capabilities to your own applications.

![Coffee GB running game](doc/tetris.gif)

---

## ✨ Features

Coffee GB offers a robust set of features for an authentic and enhanced Game Boy experience:

* **Full GB & GBC Emulation**: Enjoy a wide range of Game Boy and Game Boy Color titles.
* **Partial Super Game Boy Emulation**: Experience games with SGB borders and custom palettes.
* **Cycle-Exact CPU Emulation**: CPU emulation simulates each micro-operation within a CPU cycle,
  ensuring high accuracy.
* **High Compatibility**: Passes all Blargg's tests, demonstrating strong compatibility (with continuous improvements
  for remaining games).
* **Rollback-Based Netplay**: Play with friends over the network without desynchronization, even with high latency. Read
  more about the technology [here](https://blog.rekawek.eu/2025/07/26/rollback-netplay-gb/).
* **Memory Bank Controller (MBC) Support**: Includes support for MBC1, MBC2, MBC3, MBC4, and MBC5.
* **Battery Saves**: Your progress is automatically saved and loaded.
* **Zipped ROM Support**: Conveniently load games directly from `.zip` files.
* **ROM-Based Compatibility Tests**: Automated compatibility tests are run via Maven to ensure consistent performance.

---

## 🚀 Getting Started

### Requirements

* **Java 16 or newer**: Ensure you have a [recent Java version](https://www.oracle.com/java/technologies/downloads)
  installed.

### Usage

1. **Download**: Get the [most recent release](https://github.com/trekawek/coffee-gb/releases) from the GitHub releases
   page.
2. **Launch**:
    * **Graphical Interface**: Double-click the downloaded `.jar` file.
    * **Command Line**: Open your terminal and run: `java -jar coffee-gb-*.jar`
3. **Load a Game**: Once launched, load your desired Game Boy or Game Boy Color ROM.

### Controls

Navigate and play with these default keyboard controls:

* **Directional Pad**: <kbd>&larr;</kbd>, <kbd>&uarr;</kbd>, <kbd>&darr;</kbd>, <kbd>&rarr;</kbd>
* **A Button**: <kbd>Z</kbd>
* **B Button**: <kbd>X</kbd>
* **Start**: <kbd>Enter</kbd>
* **Select**: <kbd>Backspace</kbd>

---

## 🧑‍💻 Development

### Building from source

To build Coffee GB from its source code using Maven:

```sh
mvn clean install
java -jar swing/target/coffee-gb-*.jar
```

### Including the Coffee GB core in your Project

The core emulation module is available on Maven Central, making it easy to add to your Java projects.

Maven Dependency:

```xml

<dependency>
    <groupId>eu.rekawek.coffeegb</groupId>
    <artifactId>core</artifactId>
    <version>1.6.0</version>
</dependency>
```

### Example usage

Here's a basic example demonstrating how to initialize and run the emulator core, process frames, and simulate button
presses:

```java
        var eventBus = new EventBusImpl();
        
        // Register listeners for frame and sound events
        eventBus.register(e -> {
            // Process the new frame (e.g., render it to a display)
        }, DmgFrameReadyEvent.class);
        eventBus.register(e -> {
            // Process the new audio sample (e.g., play it)
        }, SoundSampleEvent.class);
        
        // Configure and build the Game Boy instance
        var config = new GameboyConfiguration(new File("path/to/rom.gb"));
        var gameboy = config.build();
        
        // Initialize the Game Boy with event bus, serial endpoint, and joypad
        gameboy.init(eventBus, SerialEndpoint.NULL_ENDPOINT, null);

        // Start the emulation in a new thread
        var thread = new Thread(gameboy);
        thread.start();
        
        // Example: Let the emulator run for a second
        Thread.sleep(1000);
        
        // Example: Simulate pressing the START button
        eventBus.post(new ButtonPressEvent(Button.START));
```

## 🧪 Tests

Coffee GB employs comprehensive ROM-based tests to ensure high accuracy and compatibility.

### Blargg's Test ROMs

We utilize the renowned Blargg's test ROMs for compatibility verification. These tests can be executed via Maven
profiles:

Run all Blargg's tests:

```bash
mvn clean test -f core/pom.xml -Ptest-blargg
```

Run individual Blargg's tests (with more diagnostic info):

```bash
mvn clean test -f core/pom.xml -Ptest-blargg-individual
```

The test output, which normally appears on a Game Boy screen, is redirected to stdout. An example of a successful run:

```
cpu_instrs

01:ok  02:ok  03:ok  04:ok  05:ok  06:ok  07:ok  08:ok  09:ok  10:ok  11:ok

Passed all tests
```

Coffee GB successfully passes all tests in the following suites:

* cgb_sound
* cpu_instrs
* dmg_sound-2
* halt_bug
* instr_timing
* interrupt_time
* mem_timing-2
* oam_bug-2

These tests are also integrated into our Github Actions for continuous validation.

### Mooneye Test ROMs

The Mooneye GB emulator provides an excellent suite of acceptance test ROMs. You can run these tests against Coffee GB
using the `-Ptest-mooneye profile`:


```bash
mvn clean test -f core/pom.xml -Ptest-mooneye
```

## 🖼️ Screenshots

See Coffee GB in action!

![Coffee GB running game](doc/screenshot1.png)
![Coffee GB running game](doc/screenshot2.png)
![Coffee GB running game](doc/screenshot3.png)
![Coffee GB running game](doc/screenshot4.png)
![Coffee GB running game](doc/screenshot5.png)
![Coffee GB running game](doc/screenshot6.png)
![Coffee GB running game](doc/screenshot7.png)
![Coffee GB running game](doc/screenshot8.png)
![Coffee GB running game](doc/screenshot9.png)
![Coffee GB running game](doc/screenshot10.png)

## Resources

* [GameBoy CPU manual](http://marc.rawer.de/Gameboy/Docs/GBCPUman.pdf)
* [The Ultimate GameBoy talk](https://www.youtube.com/watch?v=HyzD8pNlpwI)
* [Gameboy opcodes](http://pastraiser.com/cpu/gameboy/gameboy_opcodes.html)
* [Nitty Gritty Gameboy cycle timing](http://blog.kevtris.org/blogfiles/Nitty%20Gritty%20Gameboy%20VRAM%20Timing.txt)
* [Video Timing](https://github.com/jdeblese/gbcpu/wiki/Video-Timing)
* [BGB emulator](http://bgb.bircd.org/) - good for testing / debugging, works fine with Wine
* [The Cycle-Accurate Game Boy Docs](https://github.com/AntonioND/giibiiadvance/tree/master/docs)
* [Test ROMs](http://slack.net/~ant/old/gb-tests/) - included in the [src/test/resources/roms](src/test/resources/roms)
* [Pandocs](http://bgb.bircd.org/pandocs.htm)
* [Mooneye GB](https://github.com/Gekkio/mooneye-gb) - an accurate emulator written in Rust, contains great ROM-based acceptance tests
