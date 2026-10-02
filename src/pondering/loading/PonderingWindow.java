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
 * Ventana de carga temprana con el estilo "Pondering...".
 * Hereda de DisplayWindow (la sobrecarga de carga de NeoForge exige ese tipo) y solo cambia
 * lo que se dibuja. Si algo falla, queda la pantalla normal de NeoForge.
 * Activar con earlyWindowProvider = "pondering" en config/fml.toml.
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
            LOGGER.error("No se pudo cambiar el color de fondo", t);
        }
        Runnable tick = super.initialize(arguments);
        Thread swap = new Thread(this::swapElements, "pondering-swap");
        swap.setDaemon(true);
        swap.start();
        return tick;
    }

    /**
     * Igual que el original, pero Module.addReads solo deja modificar el modulo del llamador:
     * aqui el modulo es el de este mod, asi que se busca el metodo y se guarda en el campo privado.
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

    /** Sin logo de Mojang: la pantalla es siempre la misma. */
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
            LOGGER.info("Pantalla de carga Pondering activa");
        } catch (Throwable t) {
            Trace.error("swap failed", t);
            LOGGER.error("No se pudo montar la pantalla Pondering, se queda la de NeoForge", t);
        }
    }

    private static Field field(String name) throws Exception {
        Field f = DisplayWindow.class.getDeclaredField(name);
        f.setAccessible(true);
        return f;
    }
}
