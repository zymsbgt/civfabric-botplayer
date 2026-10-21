package lol.zym.zymbot.team;

import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;

/**
 * The other half of {@link Updater}, run in a JVM of its own from the downloaded jar: waits for the
 * game to close, then copies the new jar over the old one, keeping the old one as {@code .bak}
 * (not {@code .jar}, or Fabric would load both). A running game reads classes out of its jar as it
 * goes, and Windows locks the file, so the swap can only happen after. Plain Java: no game classes
 * are on this JVM's classpath.
 *
 * <p>Usage: {@code java -cp <new jar> lol.zym.zymbot.team.Swap <game pid> <new jar> <installed jar>}
 */
public final class Swap {
    private Swap() {}

    public static void main(String[] args) throws Exception {
        long pid = Long.parseLong(args[0]);
        Path from = Path.of(args[1]), to = Path.of(args[2]);
        ProcessHandle.of(pid).ifPresent(h -> h.onExit().join());
        // the file can stay locked a moment after the process is gone (Windows, virus scanners)
        Exception last = null;
        for (int i = 0; i < 30; i++) {
            try {
                if (!Files.exists(from)) return; // the next launch found it up to date and cleared it
                Files.copy(to, to.resolveSibling(to.getFileName() + ".bak"), StandardCopyOption.REPLACE_EXISTING);
                Files.copy(from, to, StandardCopyOption.REPLACE_EXISTING);
                System.out.println("zymbot: installed " + from + " as " + to);
                return;
            } catch (Exception e) {
                last = e;
                Thread.sleep(1000);
            }
        }
        throw last;
    }
}
