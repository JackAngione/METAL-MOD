package dev.metalcraft.client.gui.settings;

import java.util.List;
import net.minecraft.network.chat.Component;

/** Pages and rows are ordered data; the screen never needs a branch for an individual setting. */
public record SettingsPage(String id, Component title, Component navigationTitle, Component description, int accent, String symbol,
                           List<Setting> settings) {
    public SettingsPage(String id, Component title, Component description, int accent, String symbol, List<Setting> settings) {
        this(id, title, title, description, accent, symbol, settings);
    }
    public SettingsPage { settings = List.copyOf(settings); }
}
