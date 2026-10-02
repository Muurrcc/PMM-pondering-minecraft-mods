package pondering.loading;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.time.LocalTime;

/** Traza a disco (logs/pondering-trace.log). Los pasos solo se escriben con -Dpondering.trace=true; los errores siempre. */
final class Trace {
    private static final Path FILE = Path.of("logs", "pondering-trace.log");

    private Trace() {}

    static synchronized void log(String msg) {
        if (!Boolean.getBoolean("pondering.trace") && !msg.contains("\n")) return;
        try {
            Files.createDirectories(FILE.toAbsolutePath().getParent());
            Files.writeString(FILE, LocalTime.now() + " [" + Thread.currentThread().getName() + "] " + msg + "\n",
                StandardOpenOption.CREATE, StandardOpenOption.APPEND);
        } catch (IOException ignored) {
        }
    }

    static void error(String msg, Throwable t) {
        java.io.StringWriter sw = new java.io.StringWriter();
        t.printStackTrace(new java.io.PrintWriter(sw));
        log(msg + "\n" + sw);
    }
}
