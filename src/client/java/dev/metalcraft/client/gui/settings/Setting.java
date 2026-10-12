package dev.metalcraft.client.gui.settings;

import java.util.function.BooleanSupplier;
import java.util.function.Consumer;
import java.util.function.DoubleFunction;
import java.util.function.Supplier;
import net.minecraft.network.chat.Component;
import org.jspecify.annotations.Nullable;

/** A live binding. Persistence and renderer side effects belong to the supplied callbacks. */
public record Setting(String id, Component group, Supplier<Component> message, Component description,
                      BooleanSupplier enabled, Control control) {
    public sealed interface Control permits Toggle, Slider, Action, Info {}
    public record Toggle(BooleanSupplier value, Consumer<Boolean> save) implements Control {}
    /**
     * A stepped range. With a {@code preview}, pointer drags only show the label for the pending value and
     * save once on release, for settings whose every change rebuilds GPU resources; keyboard steps save at once.
     */
    public record Slider(double minimum, double maximum, double step, Supplier<Number> value, Consumer<Double> save,
                         @Nullable DoubleFunction<Component> preview) implements Control {
        public Slider {
            if (!Double.isFinite(minimum) || !Double.isFinite(maximum) || maximum <= minimum
                || !Double.isFinite(step) || step <= 0) throw new IllegalArgumentException("Invalid slider range");
        }
        public Slider(double minimum, double maximum, double step, Supplier<Number> value, Consumer<Double> save) {
            this(minimum, maximum, step, value, save, null);
        }
        public double normalized() { return Math.clamp((value.get().doubleValue() - minimum) / (maximum - minimum), 0, 1); }
        public double snapped(double fraction) {
            return Math.clamp(minimum + Math.round(Math.clamp(fraction, 0, 1) * (maximum - minimum) / step) * step, minimum, maximum);
        }
    }
    public record Action(Runnable run) implements Control {}
    public record Info() implements Control {}
}
