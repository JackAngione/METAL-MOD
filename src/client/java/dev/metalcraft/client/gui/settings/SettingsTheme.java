package dev.metalcraft.client.gui.settings;

import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.FontDescription;
import net.minecraft.resources.Identifier;

public final class SettingsTheme {
    public static final int TEXT = 0xFFF3F5FA, MUTED = 0xFFABB3C2, BLUE = 0xFF4799FF;
    private static final FontDescription FONT = new FontDescription.Resource(Identifier.fromNamespaceAndPath("metalcraft", "ui"));
    private SettingsTheme() {}

    public static Component ui(Component text) { return text.copy().withStyle(style -> style.withFont(FONT).withoutShadow()); }

    // Solid GUI geometry stays on Minecraft's Metal-backed pipeline; no extra world render pass.
    public static void rounded(GuiGraphicsExtractor g, int x, int y, int width, int height, int radius, int color) {
        int r = Math.min(radius, Math.min(width, height) / 2);
        g.fill(x + r, y, x + width - r, y + height, color);
        for (int row = 0; row < r; row++) {
            int inset = (int)Math.ceil(r - Math.sqrt(r * r - Math.pow(r - row - 0.5, 2)));
            g.fill(x + inset, y + row, x + r, y + row + 1, color);
            g.fill(x + width - r, y + row, x + width - inset, y + row + 1, color);
            g.fill(x + inset, y + height - row - 1, x + r, y + height - row, color);
            g.fill(x + width - r, y + height - row - 1, x + width - inset, y + height - row, color);
        }
        g.fill(x, y + r, x + r, y + height - r, color);
        g.fill(x + width - r, y + r, x + width, y + height - r, color);
    }

    public static void text(GuiGraphicsExtractor g, Component text, int x, int y, int width, int color) {
        var font = Minecraft.getInstance().font;
        Component styled = ui(text);
        if (font.width(styled) <= width) g.text(font, styled, x, y, color, false);
        else {
            var clipped = font.substrByWidth(styled, Math.max(0, width - font.width(ui(Component.literal("…")))));
            g.text(font, ui(Component.literal(clipped.getString() + "…")), x, y, color, false);
        }
    }
}
