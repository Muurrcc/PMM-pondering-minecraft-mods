package pondering.loading;

import java.lang.reflect.Field;
import java.util.ArrayList;
import java.util.List;
import net.neoforged.fml.earlydisplay.RenderElement;
import net.neoforged.fml.loading.progress.ProgressMeter;
import net.neoforged.fml.loading.progress.StartupNotificationManager;
import sun.misc.Unsafe;

/**
 * The only element of the early window: Scene draws everything.
 * RenderElement's constructor takes a non-public type, so the instance is created without
 * running it (Unsafe) and only the render method is used.
 */
public final class PonderingElement extends RenderElement {
    private static final Scene SCENE = new Scene();
    private static Field alphaField;

    @SuppressWarnings("DataFlowIssue")
    private PonderingElement() {
        super(null);
    }

    static PonderingElement create() throws Exception {
        Field f = Unsafe.class.getDeclaredField("theUnsafe");
        f.setAccessible(true);
        Unsafe u = (Unsafe) f.get(null);
        try {
            alphaField = RenderElement.class.getDeclaredField("globalAlpha");
            alphaField.setAccessible(true);
        } catch (Throwable t) {
            alphaField = null;
        }
        return (PonderingElement) u.allocateInstance(PonderingElement.class);
    }

    @Override
    public boolean render(DisplayContext ctx, int count) {
        float alpha = 1f;
        try {
            if (alphaField != null) alpha = alphaField.getInt(null) / 255f;
        } catch (Throwable ignored) {
        }
        List<Scene.Bar> bars = new ArrayList<>();
        for (ProgressMeter pm : StartupNotificationManager.getCurrentProgress()) {
            bars.add(new Scene.Bar(pm.progress(), pm.steps() == 0, pm.label().getText()));
        }
        SCENE.draw(ctx.scaledWidth(), ctx.scaledHeight(), alpha, bars);
        return true;
    }
}
