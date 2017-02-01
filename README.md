# Coffee GB

[![Build Status](https://travis-ci.org/trekawek/coffee-gb.svg?branch=master)](https://travis-ci.org/trekawek/coffee-gb)

Coffee GB is a Gameboy emulator written in Java 8. It's meant to be a development exercise.

![Coffee GB running Tetris](doc/tetris.gif)

## Usage

    java -jar coffee-gb-*.jar src/test/resources/tetris.gb

Play with <kbd>&larr;</kbd>, <kbd>&uarr;</kbd>, <kbd>&darr;</kbd>, <kbd>&rarr;</kbd>, <kbd>Z</kbd>, <kbd>X</kbd>, <kbd>Enter</kbd>, <kbd>Backspace</kbd>.

## Features

* Cycle-exact Gameboy CPU emulation. Each opcode is split into a few micro-operations (load value from memory, store it to register, etc.) and each micro-operation is run in a separate CPU cycle.
* GPU emulation
    * background and window
    * basic sprite support
* Joypad
* Timer
* Sound
* MBC1
* ROM-based compatibility tests run from Maven

## Playable titles

* Tetris
* Dr Mario
* Super Mario Land

## Running Blargg's tests

The [Blargg's test ROMs](http://gbdev.gg8.se/wiki/articles/Test_ROMs) are used for testing the compatibility. Tests can be launched from Maven using appropriate profile:

    mvn clean test -Ptest-blargg
    mvn clean test -Ptest-blargg-individual # for running "single" tests providing more diagnostic info

They are also part of the [Travis-based CI](https://travis-ci.org/trekawek/coffee-gb).

The tests output (normally displayed on the Gameboy screen) is redirected to the stdout:

```
cpu_instrs

01:ok  02:ok  03:ok  04:ok  05:ok  06:ok  07:ok  08:ok  09:ok  10:ok  11:ok

Passed all tests
```

All the tests meant to be run on the DMG are green:

* cpu_instrs
* instr_timing
* mem_timing-2
* oam_bug-2
* halt_bug
* dmg_sound-2

## Prioritized TODO

* Battery saves
* Gameboy Color support
* Performance optimization
* Snapshot saves
* Serial port

## Resources

* [GameBoy CPU manual](http://marc.rawer.de/Gameboy/Docs/GBCPUman.pdf)
* [The Ultimate GameBoy talk](https://www.youtube.com/watch?v=HyzD8pNlpwI)
* [Gameboy opcodes](http://pastraiser.com/cpu/gameboy/gameboy_opcodes.html)
* [Nitty Gritty Gameboy cycle timing](http://blog.kevtris.org/blogfiles/Nitty%20Gritty%20Gameboy%20VRAM%20Timing.txt)
* [Video Timing](https://github.com/jdeblese/gbcpu/wiki/Video-Timing)
* [BGB emulator](http://bgb.bircd.org/) --- good for testing / debugging, works fine with Wine
* [The Cycle-Accurate Game Boy Docs](https://github.com/AntonioND/giibiiadvance/tree/master/docs)
* [Test ROMs](http://slack.net/~ant/old/gb-tests/) - included in the [src/test/resources/roms](src/test/resources/roms)