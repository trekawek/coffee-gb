# Coffee GB

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

## Playable titles

* Tetris

## Prioritized TODO

* Memory bank switching
* Better sprite support
    * partially hidden sprites (X < 16)
    * overlaying sprites
* Battery saves
* Gameboy Color support
* Performance optimization
* Snapshot saves
* User interface to load ROMs / change config
* Serial port
* Passing compatibility tests

## Resources

* [GameBoy CPU manual](http://marc.rawer.de/Gameboy/Docs/GBCPUman.pdf)
* [The Ultimate GameBoy talk](https://www.youtube.com/watch?v=HyzD8pNlpwI)
* [Gameboy opcodes](http://pastraiser.com/cpu/gameboy/gameboy_opcodes.html)
* [Nitty Gritty Gameboy cycle timing](http://blog.kevtris.org/blogfiles/Nitty%20Gritty%20Gameboy%20VRAM%20Timing.txt)
* [Video Timing](https://github.com/jdeblese/gbcpu/wiki/Video-Timing)
* [BGB emulator](http://bgb.bircd.org/) --- good for testing / debugging, works fine with Wine