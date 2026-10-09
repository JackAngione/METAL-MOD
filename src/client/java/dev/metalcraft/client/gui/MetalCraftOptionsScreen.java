package dev.metalcraft.client.gui;

import dev.metalcraft.client.gui.settings.MetalCraftSettings;
import dev.metalcraft.client.gui.settings.Setting;
import dev.metalcraft.client.gui.settings.SettingsPage;
import dev.metalcraft.client.gui.settings.SettingsTheme;
import dev.metalcraft.client.gui.settings.SettingsWidgets;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.components.AbstractScrollArea;
import net.minecraft.client.gui.components.AbstractWidget;
import net.minecraft.client.gui.components.EditBox;
import net.minecraft.client.gui.components.MultiLineTextWidget;
import net.minecraft.client.gui.components.ScrollableLayout;
import net.minecraft.client.gui.layouts.LinearLayout;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.input.KeyEvent;
import net.minecraft.network.chat.CommonComponents;
import net.minecraft.network.chat.Component;
import org.lwjgl.glfw.GLFW;

/** One reusable shell for every settings page, including pages supplied by shader manifests. */
public class MetalCraftOptionsScreen extends Screen {
    private final Screen parent;
    private final Map<String, Double> scrollPositions = new HashMap<>();
    private final List<AbstractWidget> contentWidgets = new ArrayList<>();
    private final List<SettingsWidgets.Bound> bindings = new ArrayList<>();
    private List<SettingsPage> pages = List.of();
    private String selected;
    private String query = "";
    private Component error;
    private EditBox search;
    private int left, top, panelWidth, panelHeight, contentX, contentY, contentWidth, contentHeight;
    private boolean compact, searchDirty, choosingPage;

    public MetalCraftOptionsScreen(Screen parent) { this(parent, "display"); }
    protected MetalCraftOptionsScreen(Screen parent, String initialPage) {
        super(Component.translatable("metalcraft.options.title"));
        this.parent = parent;
        this.selected = initialPage;
    }
    private static Component text(String key, Object... args) { return MetalCraftSettings.text("metalcraft.settings." + key, args); }
    private SettingsPage page() { return pages.stream().filter(p -> p.id().equals(selected)).findFirst().orElse(pages.getFirst()); }
    @Override public Component getTitle() { return pages.isEmpty() ? super.getTitle() : page().title(); }

    @Override protected void init() {
        pages = MetalCraftSettings.pages(minecraft, this::navigate, this::reload);
        if (pages.stream().noneMatch(p -> p.id().equals(selected))) selected = "shaders";
        panelWidth = Math.min(860, width - 24);
        panelHeight = Math.min(530, height - 24);
        left = (width - panelWidth) / 2;
        top = (height - panelHeight) / 2;
        compact = panelWidth < 580;
        if (!compact) choosingPage = false;
        int sidebar = compact ? 0 : 168;
        contentX = left + sidebar + 20;
        contentY = top + (compact ? 108 : 82);
        contentWidth = panelWidth - sidebar - 44;
        contentHeight = Math.max(36, panelHeight - (compact ? 159 : 133));
        contentWidgets.clear();
        bindings.clear();
        int searchX = compact ? left + 155 : left + 16;
        int searchY = compact ? top + 73 : top + 74;
        int searchWidth = compact ? panelWidth - 177 : 136;
        search = new EditBox(font, searchX + 8, searchY + 7, searchWidth - 16, 14, text("search"));
        search.setBordered(false);
        search.setTextShadow(false);
        search.setTextColor(SettingsTheme.TEXT);
        search.setHint(SettingsTheme.ui(text("search")));
        search.addFormatter((value, cursor) -> SettingsTheme.ui(Component.literal(value)).getVisualOrderText());
        search.setMaxLength(80);
        search.setValue(query);
        search.setResponder(value -> { if (query.isBlank()) saveScroll(); query = value; searchDirty = true; error = null; });
        addRenderableWidget(search);
        if (compact) {
            addRenderableWidget(new SettingsWidgets.Pill(left + 16, top + 70, 130, text("categories"), false, () -> {
                saveScroll(); choosingPage = !choosingPage; query = ""; rebuildWidgets();
            }));
        } else {
            LinearLayout navigation = LinearLayout.vertical().spacing(4);
            for (var entry : pages) {
                boolean subpage = entry.id().startsWith("shader.");
                if (subpage && !(selected.startsWith("shader.") || selected.equals("shaders"))) continue;
                navigation.addChild(new SettingsWidgets.Navigation(136, entry.title(), entry.navigationTitle(), entry.accent(),
                    subpage ? "" : entry.symbol(), entry.id().equals(selected), () -> navigate(entry.id())));
            }
            ScrollableLayout nav = new ScrollableLayout(minecraft, navigation, Math.max(40, panelHeight - 161), ScrollableLayout.ReserveStrategy.RIGHT);
            nav.arrangeElements(); nav.setX(left + 12); nav.setY(top + 113);
            nav.visitWidgets(this::addRenderableWidget);
        }
        addRenderableWidget(new SettingsWidgets.Pill(left + panelWidth - 94, top + panelHeight - 39, 76,
            CommonComponents.GUI_DONE, true, () -> minecraft.gui.setScreen(parent)));
        if (selected.startsWith("shader.")) addRenderableWidget(new SettingsWidgets.Pill(contentX, top + panelHeight - 39,
            70, Component.translatable("gui.back"), false, () -> navigate("shaders")));
        rebuildContent();
    }

    private void saveScroll() {
        if (!query.isBlank() || choosingPage) return;
        for (var widget : contentWidgets) if (widget instanceof AbstractScrollArea area) scrollPositions.put(selected, area.scrollAmount());
    }
    private void navigate(String id) {
        saveScroll(); choosingPage = false; selected = id; query = ""; error = null;
        rebuildWidgets();
    }
    private void reload() { saveScroll(); rebuildWidgets(); }

    private void rebuildContent() {
        // Search only replaces the content container; the editor retains its caret and focus.
        if (contentWidgets.contains(getFocused())) setFocused(search);
        for (var widget : contentWidgets) removeWidget(widget);
        contentWidgets.clear(); bindings.clear();
        LinearLayout rows = LinearLayout.vertical().spacing(7);
        boolean searching = !query.isBlank();
        String needle = query.strip().toLowerCase(Locale.ROOT);
        int count = 0;
        if (error != null) rows.addChild(new MultiLineTextWidget(SettingsTheme.ui(error).copy().withStyle(s -> s.withColor(0xFFFFACAA)), font)
            .setMaxWidth(contentWidth - 8));
        if (choosingPage && !searching) {
            for (var entry : pages) rows.addChild(new SettingsWidgets.Navigation(contentWidth - 8, entry.title(), entry.title(),
                entry.accent(), entry.symbol(), entry.id().equals(selected), () -> navigate(entry.id())));
            count = pages.size();
        }
        for (var section : pages) {
            if (choosingPage && !searching) continue;
            if (!searching && !section.id().equals(selected)) continue;
            String previousGroup = "";
            for (Setting setting : section.settings()) {
                String haystack = (section.title().getString() + " " + setting.group().getString() + " "
                    + setting.message().get().getString() + " " + setting.description().getString()).toLowerCase(Locale.ROOT);
                if (searching && !haystack.contains(needle)) continue;
                String group = setting.group().getString();
                if (!group.equals(previousGroup)) {
                    Component heading = searching ? section.title().copy().append("  /  ").append(setting.group()) : setting.group();
                    rows.addChild(new MultiLineTextWidget(SettingsTheme.ui(heading).copy().withStyle(style -> style.withColor(SettingsTheme.MUTED)), font)
                        .setMaxWidth(contentWidth - 8));
                    previousGroup = group;
                }
                var row = SettingsWidgets.row(setting, contentWidth - 8, failure -> { error = failure; searchDirty = true; });
                rows.addChild(row);
                if (row instanceof SettingsWidgets.Bound binding) bindings.add(binding);
                count++;
            }
        }
        if (count == 0) rows.addChild(new MultiLineTextWidget(SettingsTheme.ui(text("empty")), font).setMaxWidth(contentWidth - 8));
        ScrollableLayout scroll = new ScrollableLayout(minecraft, rows, contentHeight, ScrollableLayout.ReserveStrategy.RIGHT);
        scroll.setMinWidth(contentWidth - 8);
        scroll.arrangeElements(); scroll.setX(contentX); scroll.setY(contentY);
        scroll.visitWidgets(widget -> { contentWidgets.add(widget); addRenderableWidget(widget); });
        if (!searching && !choosingPage) for (var widget : contentWidgets) if (widget instanceof AbstractScrollArea area)
            area.setScrollAmount(scrollPositions.getOrDefault(selected, 0.0));
        searchDirty = false;
    }

    @Override public void tick() {
        if (searchDirty) rebuildContent();
        bindings.forEach(SettingsWidgets.Bound::refresh);
    }
    @Override protected void repositionElements() { saveScroll(); rebuildWidgets(); }
    @Override public boolean keyPressed(KeyEvent event) {
        if (event.key() == GLFW.GLFW_KEY_F && event.hasControlDownWithQuirk()) { setFocused(search); return true; }
        if (event.isEscape() && !query.isEmpty()) { search.setValue(""); return true; }
        return super.keyPressed(event);
    }
    @Override public void onClose() {
        if (choosingPage) { choosingPage = false; rebuildWidgets(); }
        else if (selected.startsWith("shader.")) navigate("shaders");
        else minecraft.gui.setScreen(parent);
    }

    @Override public void extractBackground(GuiGraphicsExtractor g, int mx, int my, float delta) {
        if (minecraft.level == null) extractPanorama(g, delta);
        g.fillGradient(0, 0, width, height, 0xA00A1222, 0xD0080D17);
    }
    @Override public void extractRenderState(GuiGraphicsExtractor g, int mx, int my, float delta) {
        SettingsTheme.rounded(g, left - 3, top + 5, panelWidth + 6, panelHeight + 3, 19, 0x48000000);
        SettingsTheme.rounded(g, left, top, panelWidth, panelHeight, 16, 0xFF536073);
        SettingsTheme.rounded(g, left + 1, top + 1, panelWidth - 2, panelHeight - 2, 15, 0xF21C2431);
        if (!compact) {
            g.fill(left + 167, top + 16, left + 168, top + panelHeight - 16, 0xFF394252);
            SettingsTheme.rounded(g, left + 16, top + 19, 26, 26, 8, 0xFF4799FF);
            SettingsTheme.text(g, Component.literal("M"), left + 24, top + 27, 16, 0xFFFFFFFF);
            SettingsTheme.text(g, Component.literal("Metal Mod"), left + 51, top + 21, 108, SettingsTheme.TEXT);
            SettingsTheme.text(g, text("settings"), left + 51, top + 36, 100, SettingsTheme.MUTED);
            SettingsTheme.rounded(g, left + 16, top + panelHeight - 33, 5, 5, 2, page().accent());
            SettingsTheme.text(g, text("built_for_mac"), left + 27, top + panelHeight - 36, 130, SettingsTheme.MUTED);
        }
        Component heading = !query.isBlank() ? text("results") : choosingPage ? text("categories") : page().title();
        g.pose().pushMatrix();
        g.pose().translate(contentX, top + 22); g.pose().scale(1.45F, 1.45F);
        SettingsTheme.text(g, heading, 0, 0, (int)(contentWidth / 1.45F), SettingsTheme.TEXT);
        g.pose().popMatrix();
        SettingsTheme.text(g, !query.isBlank() ? text("search.description") : choosingPage ? text("categories.description") : page().description(), contentX, top + 49, contentWidth, SettingsTheme.MUTED);
        int searchX = search.getX() - 8, searchY = search.getY() - 7;
        SettingsTheme.rounded(g, searchX, searchY, search.getWidth() + 16, 27, 8, search.isFocused() ? SettingsTheme.BLUE : 0xFF475164);
        SettingsTheme.rounded(g, searchX + 1, searchY + 1, search.getWidth() + 14, 25, 7, 0xFF151D2B);
        int footerX = contentX + (selected.startsWith("shader.") ? 80 : 0);
        SettingsTheme.text(g, error == null ? text("saved") : text("not_applied"), footerX, top + panelHeight - 30,
            Math.max(0, left + panelWidth - 106 - footerX), error == null ? SettingsTheme.MUTED : 0xFFFFACAA);
        super.extractRenderState(g, mx, my, delta);
    }
}
