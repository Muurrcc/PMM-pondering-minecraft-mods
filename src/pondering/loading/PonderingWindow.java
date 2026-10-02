package pondering.loading;

import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
import java.util.Arrays;
import java.util.List;
import java.util.concurrent.Future;
import java.util.concurrent.Semaphore;
import java.util.concurrent.TimeUnit;
import net.neoforged.fml.earlydisplay.ColourScheme;
import net.neoforged.fml.earlydisplay.DisplayWindow;
import net.neoforged.fml.earlydisplay.RenderElement;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Early loading window with the "Pondering..." style.
 * Extends DisplayWindow (NeoForge's loading overlay requires that type) and only changes what
 * is drawn. If anything fails, NeoForge's stock screen stays.
 * Enable with earlyWindowProvider = "pondering" in config/fml.toml.
 */
public class PonderingWindow extends DisplayWindow {
    private static final Logger LOGGER = LoggerFactory.getLogger("PONDERING");

    @Override
    public String name() {
        return "pondering";
    }

    @Override
    public Runnable initialize(String[] arguments) {
        try {
            recolour();
        } catch (Throwable t) {
            LOGGER.error("Could not change the background colour", t);
        }
        Runnable tick = super.initialize(arguments);
        Thread swap = new Thread(this::swapElements, "pondering-swap");
        swap.setDaemon(true);
        swap.start();
        return tick;
    }

    /**
     * Same as the original, but Module.addReads only lets a caller modify its own module, and
     * here that module is the mod's: so the method is looked up and stored in the private field.
     */
    @Override
    public void updateModuleReads(ModuleLayer layer) {
        try {
            Module fm = layer.findModule("neoforge").orElseThrow();
            getClass().getModule().addReads(fm);
            Class<?> clz = Class.forName(fm, "net.neoforged.neoforge.client.loading.NeoForgeLoadingOverlay");
            Method m = Arrays.stream(clz.getMethods())
                .filter(x -> Modifier.isStatic(x.getModifiers()) && x.getName().equals("newInstance"))
                .findFirst().orElseThrow();
            field("loadingOverlay").set(this, m);
            Trace.log("updateModuleReads ok");
        } catch (Throwable t) {
            Trace.error("updateModuleReads failed", t);
            throw new IllegalStateException(t);
        }
    }

    /** No Mojang logo: the screen is always the same. */
    @Override
    public void addMojangTexture(int textureId) {
    }

    private static void recolour() throws Exception {
        Field bg = ColourScheme.class.getDeclaredField("background");
        Field fg = ColourScheme.class.getDeclaredField("foreground");
        bg.setAccessible(true);
        fg.setAccessible(true);
        var cream = new ColourScheme.Colour(Scene.BG[0], Scene.BG[1], Scene.BG[2]);
        var text = new ColourScheme.Colour(59, 59, 59);
        for (ColourScheme s : ColourScheme.values()) {
            bg.set(s, cream);
            fg.set(s, text);
        }
    }

    @SuppressWarnings("unchecked")
    private void swapElements() {
        try {
            Future<?> init = (Future<?>) field("initializationFuture").get(this);
            init.get(30, TimeUnit.SECONDS);
            Semaphore lock = (Semaphore) field("renderLock").get(this);
            List<RenderElement> elements = (List<RenderElement>) field("elements").get(this);
            lock.acquire();
            try {
                elements.clear();
                elements.add(PonderingElement.create());
            } finally {
                lock.release();
            }
            Trace.log("elements swapped");
            LOGGER.info("Pondering loading screen active");
        } catch (Throwable t) {
            Trace.error("swap failed", t);
            LOGGER.error("Could not set up the Pondering screen, keeping NeoForge's", t);
        }
    }

    private static Field field(String name) throws Exception {
        Field f = DisplayWindow.class.getDeclaredField(name);
        f.setAccessible(true);
        return f;
    }
}
