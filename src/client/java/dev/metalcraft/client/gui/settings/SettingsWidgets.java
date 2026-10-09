package dev.metalcraft.client.gui.settings;

import java.time.Duration;
import java.util.function.Consumer;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.components.AbstractSliderButton;
import net.minecraft.client.gui.components.AbstractWidget;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.components.Tooltip;
import net.minecraft.client.gui.narration.NarrationElementOutput;
import net.minecraft.client.input.KeyEvent;
import net.minecraft.client.input.MouseButtonEvent;
import net.minecraft.network.chat.Component;

public final class SettingsWidgets {
    private SettingsWidgets() {}
    public interface Bound { void refresh(); }

    public static AbstractWidget row(Setting setting, int width, Consumer<Component> failure) {
        AbstractWidget widget = switch (setting.control()) {
            case Setting.Slider slider -> new SliderRow(setting, slider, width, failure);
            case Setting.Info ignored -> new InfoRow(setting, width);
            default -> new ActionRow(setting, width, failure);
        };
        widget.setTooltip(Tooltip.create(SettingsTheme.ui(setting.description())));
        widget.setTooltipDelay(Duration.ofMillis(600));
        return widget;
    }

    public static final class Navigation extends Button {
        private final int accent;
        private final Component caption;
        private final String symbol;
        private final boolean selected;
        public Navigation(int width, Component label, Component caption, int accent, String symbol, boolean selected, Runnable action) {
            super(0, 0, width, 30, label, button -> action.run(), DEFAULT_NARRATION);
            this.caption = caption; this.accent = accent; this.symbol = symbol; this.selected = selected;
            setTooltip(Tooltip.create(SettingsTheme.ui(label)));
        }
        @Override protected void extractContents(GuiGraphicsExtractor g, int mx, int my, float delta) {
            int x = getX(), y = getY();
            if (selected || isHoveredOrFocused()) SettingsTheme.rounded(g, x, y, width, height, 7,
                selected ? 0xFF354F71 : 0xFF303947);
            if (isFocused()) SettingsTheme.rounded(g, x, y, 2, height, 1, SettingsTheme.BLUE);
            if (!symbol.isEmpty()) {
                SettingsTheme.rounded(g, x + 7, y + 7, 17, 17, 5, accent);
                icon(g, symbol, x + 10, y + 10);
            }
            int padding = symbol.isEmpty() ? 12 : 31;
            SettingsTheme.text(g, caption, x + padding, y + 10, width - padding - 8, SettingsTheme.TEXT);
        }
    }

    private static void icon(GuiGraphicsExtractor g, String symbol, int x, int y) {
        int white = 0xFFFFFFFF;
        switch (symbol) {
            case "D" -> { g.outline(x, y, 11, 8, white); g.fill(x + 5, y + 8, x + 6, y + 10, white); g.fill(x + 3, y + 10, x + 8, y + 11, white); }
            case "T" -> { for (int row = 0; row < 7; row++) g.fill(x + 5 - row / 2, y + 2 + row, x + 6 + row / 2, y + 3 + row, white); }
            case "S" -> { for (int row = 0; row < 11; row++) { int half = Math.max(0, 4 - Math.abs(5 - row)); g.fill(x + 5 - half, y + row, x + 6 + half, y + row + 1, white); } }
            default -> SettingsTheme.text(g, Component.literal(symbol), x, y, 12, white);
        }
    }

    public static final class Pill extends Button {
        private final boolean primary;
        public Pill(int x, int y, int width, Component label, boolean primary, Runnable action) {
            super(x, y, width, 26, label, button -> action.run(), DEFAULT_NARRATION);
            this.primary = primary;
        }
        @Override protected void extractContents(GuiGraphicsExtractor g, int mx, int my, float delta) {
            SettingsTheme.rounded(g, getX(), getY(), width, height, 13,
                primary ? (isHoveredOrFocused() ? 0xFF6BABFF : SettingsTheme.BLUE) : (isHoveredOrFocused() ? 0xFF48566B : 0xFF343E4E));
            if (isFocused()) g.outline(getX() + 3, getY() + 3, width - 6, height - 6, 0xFFE3EFFF);
            int labelWidth = Minecraft.getInstance().font.width(SettingsTheme.ui(getMessage()));
            SettingsTheme.text(g, getMessage(), getX() + Math.max(8, (width - labelWidth) / 2), getY() + 9, width - 16, 0xFFFFFFFF);
        }
    }

    private static void surface(GuiGraphicsExtractor g, AbstractWidget widget) {
        int x = widget.getX(), y = widget.getY(), w = widget.getWidth(), h = widget.getHeight();
        SettingsTheme.rounded(g, x, y, w, h, 9, widget.isFocused() ? SettingsTheme.BLUE : 0xFF414B5B);
        SettingsTheme.rounded(g, x + 1, y + 1, w - 2, h - 2, 8,
            widget.active && widget.isHoveredOrFocused() ? 0xFF343F50 : 0xFF2A3341);
    }

    private static void labels(GuiGraphicsExtractor g, AbstractWidget widget, Setting setting, int reserve) {
        int available = widget.getWidth() - 28 - reserve;
        SettingsTheme.text(g, widget.getMessage(), widget.getX() + 14, widget.getY() + 12, available,
            widget.active || setting.control() instanceof Setting.Info ? SettingsTheme.TEXT : SettingsTheme.MUTED);
        SettingsTheme.text(g, setting.description(), widget.getX() + 14, widget.getY() + 30, available, SettingsTheme.MUTED);
    }

    private static void attempt(Runnable action, Consumer<Component> failure) {
        try { action.run(); }
        catch (RuntimeException exception) {
            failure.accept(Component.translatable("metalcraft.settings.error",
                exception.getMessage() == null ? exception.getClass().getSimpleName() : exception.getMessage()));
        }
    }

    private static final class ActionRow extends Button implements Bound {
        private final Setting setting;
        ActionRow(Setting setting, int width, Consumer<Component> failure) {
            super(0, 0, width, 54, setting.message().get(), button -> attempt(() -> {
                switch (setting.control()) {
                    case Setting.Toggle toggle -> toggle.save().accept(!toggle.value().getAsBoolean());
                    case Setting.Action action -> action.run().run();
                    default -> {}
                }
                ((ActionRow)button).refresh();
            }, failure), DEFAULT_NARRATION);
            this.setting = setting;
            refresh();
        }
        @Override public void refresh() {
            active = setting.enabled().getAsBoolean();
            setMessage(setting.message().get());
        }
        @Override protected net.minecraft.network.chat.MutableComponent createNarrationMessage() {
            if (setting.control() instanceof Setting.Toggle toggle)
                return Component.translatable("gui.narrate.button", getMessage().copy().append(": ")
                    .append(Component.translatable("metalcraft.options.value." + toggle.value().getAsBoolean())));
            return super.createNarrationMessage();
        }
        @Override protected void extractContents(GuiGraphicsExtractor g, int mx, int my, float delta) {
            surface(g, this);
            labels(g, this, setting, setting.control() instanceof Setting.Toggle ? 60 : 20);
            if (setting.control() instanceof Setting.Toggle toggle) {
                boolean on = toggle.value().getAsBoolean();
                int x = getRight() - 51, y = getY() + 17;
                SettingsTheme.rounded(g, x, y, 36, 20, 10, active && on ? SettingsTheme.BLUE : 0xFF637083);
                SettingsTheme.rounded(g, x + (on ? 18 : 2), y + 2, 16, 16, 8, active ? 0xFFFFFFFF : 0xFFBCC3CE);
                // A second cue makes the switch state readable without relying on color.
                if (on) g.fill(x + 9, y + 7, x + 10, y + 13, 0xFFFFFFFF);
            } else SettingsTheme.text(g, Component.literal("›"), getRight() - 23, getY() + 21, 12, SettingsTheme.MUTED);
        }
    }

    private static final class SliderRow extends AbstractSliderButton implements Bound {
        private final Setting setting;
        private final Setting.Slider binding;
        private final Consumer<Component> failure;
        SliderRow(Setting setting, Setting.Slider binding, int width, Consumer<Component> failure) {
            super(0, 0, width, 72, setting.message().get(), binding.normalized());
            this.setting = setting; this.binding = binding; this.failure = failure;
            refresh();
        }
        @Override public void refresh() {
            active = setting.enabled().getAsBoolean();
            value = binding.normalized();
            updateMessage();
        }
        @Override protected void updateMessage() { setMessage(setting.message().get()); }
        @Override protected void applyValue() {
            attempt(() -> binding.save().accept(binding.snapped(value)), failure);
            refresh();
        }
        @Override public void onClick(MouseButtonEvent event, boolean doubleClick) {
            setValue((event.x() - getX() - 18) / (width - 36));
        }
        @Override protected void onDrag(MouseButtonEvent event, double dx, double dy) { onClick(event, false); }
        @Override public boolean keyPressed(KeyEvent event) {
            if (active && canChangeValue && (event.isLeft() || event.isRight())) {
                setValue(value + (event.isLeft() ? -1 : 1) * binding.step() / (binding.maximum() - binding.minimum()));
                return true;
            }
            return super.keyPressed(event);
        }
        @Override public void extractWidgetRenderState(GuiGraphicsExtractor g, int mx, int my, float delta) {
            surface(g, this);
            labels(g, this, setting, 0);
            int x = getX() + 18, y = getY() + 56, end = getRight() - 18;
            int thumb = x + (int)Math.round(value * (end - x));
            SettingsTheme.rounded(g, x, y, end - x, 3, 1, 0xFF596577);
            if (thumb > x) SettingsTheme.rounded(g, x, y, thumb - x, 3, 1, active ? SettingsTheme.BLUE : SettingsTheme.MUTED);
            SettingsTheme.rounded(g, thumb - 5, y - 4, 11, 11, 5, active ? 0xFFFFFFFF : 0xFFADB5C4);
            if (isFocused() && canChangeValue) SettingsTheme.rounded(g, thumb - 2, y - 1, 5, 5, 2, SettingsTheme.BLUE);
        }
    }

    private static final class InfoRow extends AbstractWidget implements Bound {
        private final Setting setting;
        InfoRow(Setting setting, int width) { super(0, 0, width, 54, setting.message().get()); this.setting = setting; active = false; }
        @Override public void refresh() { setMessage(setting.message().get()); }
        @Override protected void extractWidgetRenderState(GuiGraphicsExtractor g, int mx, int my, float delta) {
            surface(g, this); labels(g, this, setting, 0);
        }
        @Override protected void updateWidgetNarration(NarrationElementOutput output) { defaultButtonNarrationText(output); }
    }
}
