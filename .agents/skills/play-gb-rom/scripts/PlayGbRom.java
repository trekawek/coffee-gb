import eu.rekawek.coffeegb.controller.Agent;
import eu.rekawek.coffeegb.controller.state.StateImage;
import eu.rekawek.coffeegb.core.debug.DebugResult;
import eu.rekawek.coffeegb.core.debug.DebugSnapshot;
import eu.rekawek.coffeegb.core.debug.DebugStepKind;
import eu.rekawek.coffeegb.core.joypad.Button;

import java.awt.image.BufferedImage;
import java.io.BufferedReader;
import java.io.BufferedWriter;
import java.io.IOException;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.util.EnumSet;
import java.util.Locale;
import java.util.Set;
import java.util.concurrent.CompletionStage;
import javax.imageio.ImageIO;

/** Private, persistent stdin driver for the Coffee GB headless Agent. */
public final class PlayGbRom {

  private static final int MAX_FRAMES_PER_COMMAND = 3_600;
  private static final Set<Button> BUTTONS = EnumSet.allOf(Button.class);

  private final Agent agent;
  private final Path framesDirectory;
  private final BufferedWriter actionLog;
  private final EnumSet<Button> heldButtons = EnumSet.noneOf(Button.class);
  private BufferedImage latestFrame;
  private String latestFrameToken = "none";
  private long sequence;

  private PlayGbRom(Agent agent, Path sessionDirectory) throws IOException {
    this.agent = agent;
    this.framesDirectory = sessionDirectory.resolve("frames");
    Files.createDirectory(framesDirectory);
    this.actionLog =
        Files.newBufferedWriter(
            sessionDirectory.resolve("actions.tsv"),
            StandardCharsets.UTF_8,
            StandardOpenOption.CREATE_NEW,
            StandardOpenOption.WRITE);
    actionLog.write(
        "sequence\taction\tstart_tick\tstart_frame\thold_frames\tdwell_frames"
            + "\tstep_frames\tend_tick\tend_frame\tframe\n");
    actionLog.flush();
  }

  public static void main(String[] args) {
    if (args.length != 2) {
      fail("INVALID_ARGUMENTS");
      System.exit(2);
      return;
    }
    Path rom = Path.of(args[0]);
    Path session = Path.of(args[1]);
    if (!Files.isRegularFile(rom) || !Files.isReadable(rom)) {
      fail("ROM_UNREADABLE");
      System.exit(2);
      return;
    }
    if (!Files.isDirectory(session, LinkOption.NOFOLLOW_LINKS)) {
      fail("SESSION_INVALID");
      System.exit(2);
      return;
    }

    try (Agent agent = new Agent(rom.toFile())) {
      PlayGbRom driver = new PlayGbRom(agent, session);
      Thread hook = new Thread(driver::closeOnShutdown, "play-gb-rom-cleanup");
      Runtime.getRuntime().addShutdownHook(hook);
      try {
        driver.run();
      } finally {
        try {
          driver.releaseAll();
        } finally {
          driver.actionLog.close();
          try {
            Runtime.getRuntime().removeShutdownHook(hook);
          } catch (IllegalStateException ignored) {
            // The hook is already running during VM shutdown.
          }
        }
      }
    } catch (Throwable failure) {
      fail("SESSION_FAILED");
      System.exit(6);
    }
    System.out.println("session_closed=true");
    System.out.flush();
  }

  private void run() throws Exception {
    DebugSnapshot start = agent.snapshot();
    stepFrames(1);
    updateLatestFrame();
    DebugSnapshot end = agent.snapshot();
    writeAction("INITIAL", start, 0, 0, 1, end, "initial");
    System.out.println("session_ready=true");
    System.out.println("action_log_token=actions.tsv");
    System.out.println(
        "commands=BUTTON_name_hold_dwell,STEP_frames,CAPTURE_label,STATUS,QUIT");
    emitStatus();
    System.out.flush();

    try (BufferedReader input =
        new BufferedReader(new InputStreamReader(System.in, StandardCharsets.UTF_8))) {
      String line;
      while ((line = input.readLine()) != null) {
        String trimmed = line.trim();
        if (trimmed.isEmpty()) {
          commandError("EMPTY_COMMAND");
          continue;
        }
        try {
          if (!execute(trimmed)) {
            System.out.println("session_quit=true");
            System.out.flush();
            break;
          }
        } catch (CommandSyntaxException syntax) {
          commandError(syntax.code);
        }
      }
    }
    System.out.println("cleanup_buttons=true");
  }

  private boolean execute(String line) throws Exception {
    String[] words = line.split("\\s+");
    String command = words[0].toUpperCase(Locale.ROOT);
    switch (command) {
      case "BUTTON" -> executeButton(words);
      case "STEP" -> executeStep(words);
      case "CAPTURE" -> executeCapture(words);
      case "STATUS" -> {
        requireLength(words, 1);
        emitStatus();
      }
      case "QUIT" -> {
        requireLength(words, 1);
        return false;
      }
      default -> throw new CommandSyntaxException("UNKNOWN_COMMAND");
    }
    System.out.flush();
    return true;
  }

  private void executeButton(String[] words) throws Exception {
    if (words.length < 2 || words.length > 4) {
      throw new CommandSyntaxException("INVALID_BUTTON_COMMAND");
    }
    Button button;
    try {
      button = Button.valueOf(words[1].toUpperCase(Locale.ROOT));
    } catch (IllegalArgumentException invalid) {
      throw new CommandSyntaxException("INVALID_BUTTON");
    }
    if (!BUTTONS.contains(button)) {
      throw new CommandSyntaxException("INVALID_BUTTON");
    }
    int holdFrames = words.length >= 3 ? frames(words[2], 1) : 3;
    int dwellFrames = words.length == 4 ? frames(words[3], 0) : 30;
    DebugSnapshot start = agent.snapshot();
    boolean pressed = false;
    try {
      agent.pressButton(button);
      heldButtons.add(button);
      pressed = true;
      stepFrames(holdFrames);
    } finally {
      if (pressed) {
        agent.releaseButton(button);
        heldButtons.remove(button);
      }
    }
    stepFrames(dwellFrames);
    updateLatestFrame();
    DebugSnapshot end = agent.snapshot();
    writeAction(
        "BUTTON_" + button.name(), start, holdFrames, dwellFrames,
        holdFrames + dwellFrames, end, button.name().toLowerCase(Locale.ROOT));
    emitAction(end, "BUTTON_" + button.name());
  }

  private void executeStep(String[] words) throws Exception {
    requireLength(words, 2);
    int frameCount = frames(words[1], 1);
    DebugSnapshot start = agent.snapshot();
    stepFrames(frameCount);
    updateLatestFrame();
    DebugSnapshot end = agent.snapshot();
    writeAction("STEP", start, 0, 0, frameCount, end, "step");
    emitAction(end, "STEP");
  }

  private void executeCapture(String[] words) throws Exception {
    if (words.length < 1 || words.length > 2) {
      throw new CommandSyntaxException("INVALID_CAPTURE_COMMAND");
    }
    String label = words.length == 2 ? safeLabel(words[1]) : "capture";
    updateLatestFrame();
    DebugSnapshot snapshot = agent.snapshot();
    writeAction("CAPTURE", snapshot, 0, 0, 0, snapshot, label);
    emitAction(snapshot, "CAPTURE");
  }

  private void stepFrames(int count) throws Exception {
    for (int i = 0; i < count; i++) {
      requireSuccess(agent.getDebugPort().step(DebugStepKind.FRAME));
    }
  }

  private void updateLatestFrame() {
    StateImage candidate;
    while ((candidate = agent.getFrameImage()) != null) {
      BufferedImage image =
          new BufferedImage(candidate.getWidth(), candidate.getHeight(), BufferedImage.TYPE_INT_RGB);
      int[] pixels = candidate.copyRgb();
      image.setRGB(
          0, 0, candidate.getWidth(), candidate.getHeight(), pixels, 0, candidate.getWidth());
      latestFrame = image;
    }
  }

  private void writeAction(
      String action,
      DebugSnapshot start,
      int holdFrames,
      int dwellFrames,
      int stepFrames,
      DebugSnapshot end,
      String label)
      throws IOException {
    if (latestFrame == null) {
      throw new IOException("frame unavailable");
    }
    long currentSequence = sequence++;
    String token = String.format("%06d-%s.png", currentSequence, label);
    Path target = framesDirectory.resolve(token);
    if (Files.exists(target, LinkOption.NOFOLLOW_LINKS)
        || !ImageIO.write(latestFrame, "png", target.toFile())) {
      throw new IOException("frame publication failed");
    }
    latestFrameToken = token;
    actionLog.write(
        currentSequence + "\t" + action + "\t" + start.masterTick() + "\t" + start.frame()
            + "\t" + holdFrames + "\t" + dwellFrames + "\t" + stepFrames + "\t"
            + end.masterTick() + "\t" + end.frame() + "\t" + token + "\n");
    actionLog.flush();
  }

  private void emitAction(DebugSnapshot snapshot, String action) {
    System.out.println("sequence=" + (sequence - 1));
    System.out.println("action=" + action);
    System.out.println("frame_token=" + latestFrameToken);
    System.out.println("emulated_tick=" + snapshot.masterTick());
    System.out.println("emulated_frame=" + snapshot.frame());
  }

  private void emitStatus() {
    DebugSnapshot snapshot = agent.snapshot();
    System.out.println("status_tick=" + snapshot.masterTick());
    System.out.println("status_frame=" + snapshot.frame());
    System.out.println("status_frame_position=" + snapshot.framePosition());
    System.out.println("status_cpu=" + snapshot.execution().cpuState().name());
    System.out.println("status_lcd=" + (snapshot.ppu().lcdEnabled() ? "ENABLED" : "DISABLED"));
    System.out.println("status_ppu_mode=" + snapshot.ppu().mode().name());
    System.out.println("status_ppu_line=" + snapshot.ppu().line());
    System.out.println("status_frame_token=" + latestFrameToken);
  }

  private synchronized void releaseAll() {
    for (Button button : EnumSet.copyOf(heldButtons)) {
      try {
        agent.releaseButton(button);
      } finally {
        heldButtons.remove(button);
      }
    }
  }

  private void releaseAllSilently() {
    try {
      releaseAll();
    } catch (Throwable ignored) {
      // Shutdown remains best-effort; Agent.close owns final machine cleanup.
    }
  }

  private void closeOnShutdown() {
    releaseAllSilently();
    try {
      agent.close();
    } catch (Throwable ignored) {
      // The owner may already be closing; process shutdown remains best-effort.
    }
  }

  private static int frames(String value, int minimum) throws CommandSyntaxException {
    try {
      int parsed = Integer.parseInt(value);
      if (parsed < minimum || parsed > MAX_FRAMES_PER_COMMAND) {
        throw new CommandSyntaxException("FRAME_COUNT_OUT_OF_RANGE");
      }
      return parsed;
    } catch (NumberFormatException invalid) {
      throw new CommandSyntaxException("INVALID_FRAME_COUNT");
    }
  }

  private static String safeLabel(String value) throws CommandSyntaxException {
    if (!value.matches("[A-Za-z0-9_-]{1,32}")) {
      throw new CommandSyntaxException("INVALID_LABEL");
    }
    return value.toLowerCase(Locale.ROOT);
  }

  private static void requireLength(String[] words, int length) throws CommandSyntaxException {
    if (words.length != length) {
      throw new CommandSyntaxException("INVALID_COMMAND_ARITY");
    }
  }

  private static <T> T requireSuccess(CompletionStage<DebugResult<T>> stage) throws Exception {
    DebugResult<T> result = stage.toCompletableFuture().get();
    if (result.isFailure()) {
      throw new IllegalStateException("debug command failed");
    }
    return result.value();
  }

  private static void commandError(String code) {
    System.out.println("command_error=" + code);
    System.out.flush();
  }

  private static void fail(String code) {
    System.err.println("session_error=" + code);
    System.err.flush();
  }

  private static final class CommandSyntaxException extends Exception {
    private static final long serialVersionUID = 1L;

    private final String code;

    private CommandSyntaxException(String code) {
      this.code = code;
    }
  }
}
