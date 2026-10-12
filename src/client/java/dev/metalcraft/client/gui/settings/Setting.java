package dev.metalcraft.client.gui.settings;

import java.util.function.BooleanSupplier;
import java.util.function.Consumer;
import java.util.function.Supplier;
import net.minecraft.network.chat.Component;

/** A live binding. Persistence and renderer side effects belong to the supplied callbacks. */
public record Setting(String id, Component group, Supplier<Component> message, Component description,
                      BooleanSupplier enabled, Control control) {
    public sealed interface Control permits Toggle, Slider, Action, Info {}
    public record Toggle(BooleanSupplier value, Consumer<Boolean> save) implements Control {}
    public record Slider(double minimum, double maximum, double step,
                         Supplier<Number> value, Consumer<Double> save) implements Control {
        public Slider {
            if (!Double.isFinite(minimum) || !Double.isFinite(maximum) || maximum <= minimum
                || !Double.isFinite(step) || step <= 0) throw new IllegalArgumentException("Invalid slider range");
        }
        public double normalized() { return Math.clamp((value.get().doubleValue() - minimum) / (maximum - minimum), 0, 1); }
        public double snapped(double fraction) {
            return Math.clamp(minimum + Math.round(Math.clamp(fraction, 0, 1) * (maximum - minimum) / step) * step, minimum, maximum);
        }
    }
    public record Action(Runnable run) implements Control {}
    public record Info() implements Control {}
}
