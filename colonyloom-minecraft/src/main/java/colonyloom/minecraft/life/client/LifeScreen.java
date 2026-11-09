package colonyloom.minecraft.life.client;

import colonyloom.minecraft.life.LifeText;
import colonyloom.minecraft.life.net.LifePayloads;
import java.util.List;
import java.util.UUID;
import java.util.function.Supplier;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.components.Renderable;
import net.minecraft.client.gui.components.Tooltip;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.MutableComponent;

public final class LifeScreen extends Screen {
    static final String[] teen_pro_YT_TABS = {"life", "health", "social", "journal"};
    static final int teen_pro_YT_ROW_HEIGHT = 22;
    static final int teen_pro_YT_TOP = 58;

    private UUID teen_pro_YT_colony;
    private int teen_pro_YT_tab, teen_pro_YT_page, teen_pro_YT_offset;
    private UUID teen_pro_YT_selected;
    private LifePayloads.View teen_pro_YT_view;
    private boolean teen_pro_YT_waiting = true;
    private final Supplier<Screen> teen_pro_YT_back;

    public LifeScreen(UUID colony) {
        this(colony, null);
    }

    public LifeScreen(UUID colony, Supplier<Screen> back) {
        super(Component.translatable("screen.colonyloom.life.title"));
        teen_pro_YT_colony = colony;
        teen_pro_YT_back = back;
    }

    @Override
    protected void init() {
        LifePayloads.View cached = LifeClient.view();
        if (teen_pro_YT_view == null && cached != null && (teen_pro_YT_colony == null || teen_pro_YT_colony.equals(cached.colony()))) teen_pro_YT_view = cached;
        rebuild();
        request();
    }

    void received(LifePayloads.View view) {
        teen_pro_YT_waiting = false;
        teen_pro_YT_view = view;
        if (view.colony() != null) teen_pro_YT_colony = view.colony();
        teen_pro_YT_page = view.page();
        teen_pro_YT_offset = Math.min(teen_pro_YT_offset, Math.max(0, view.rows().size() - visibleRows()));
        rebuild();
    }

    private void request() {
        teen_pro_YT_waiting = true;
        LifeClient.request(teen_pro_YT_colony, teen_pro_YT_tab, teen_pro_YT_page);
    }

    private int visibleRows() { return Math.max(1, (height - teen_pro_YT_TOP - 64) / teen_pro_YT_ROW_HEIGHT); }

    private void rebuild() {
        clearWidgets();
        int gap = 4;
        int tabWidth = (width - 24 - gap * 3) / 4;
        for (int i = 0; i < teen_pro_YT_TABS.length; i++) {
            int tab = i;
            Button button = addRenderableWidget(Button.builder(Component.translatable("screen.colonyloom.life.tab." + teen_pro_YT_TABS[i]), ignored -> {
                teen_pro_YT_tab = tab; teen_pro_YT_page = 0; teen_pro_YT_offset = 0; teen_pro_YT_selected = null; request(); rebuild();
            }).bounds(12 + i * (tabWidth + gap), 28, tabWidth, 20).build());
            button.active = teen_pro_YT_tab != i;
        }
        LifePayloads.View view = teen_pro_YT_view;
        if (view != null && view.tab() == teen_pro_YT_tab) {
            List<LifePayloads.Row> rows = view.rows();
            int capacity = visibleRows();
            teen_pro_YT_offset = Math.max(0, Math.min(teen_pro_YT_offset, Math.max(0, rows.size() - capacity)));
            for (int i = teen_pro_YT_offset; i < Math.min(rows.size(), teen_pro_YT_offset + capacity); i++) {
                LifePayloads.Row row = rows.get(i);
                MutableComponent text = Component.literal(row.id().equals(teen_pro_YT_selected) ? "> " : "").append(LifeText.any(row.name()));
                if (teen_pro_YT_tab == 3) text.append(" ").append(LifeText.any(row.detail()));
                else text.append(" | ").append(LifeText.any(row.state())).append(" | ").append(LifeText.any(row.reason()));
                Button entry = addRenderableWidget(Button.builder(text, ignored -> { teen_pro_YT_selected = row.id(); rebuild(); })
                        .bounds(12, teen_pro_YT_TOP + (i - teen_pro_YT_offset) * teen_pro_YT_ROW_HEIGHT, width - 24, 20).build());
                entry.setTooltip(Tooltip.create(LifeText.any(row.detail())));
            }
        }
        int footer = height - 30;
        int pages = view == null ? 1 : view.pages();
        addRenderableWidget(Button.builder(Component.literal("<"), ignored -> { teen_pro_YT_page = Math.max(0, teen_pro_YT_page - 1); teen_pro_YT_offset = 0; request(); })
                .bounds(12, footer, 24, 20).build()).active = teen_pro_YT_page > 0;
        addRenderableWidget(Button.builder(Component.literal(">"), ignored -> { teen_pro_YT_page = Math.min(pages - 1, teen_pro_YT_page + 1); teen_pro_YT_offset = 0; request(); })
                .bounds(40, footer, 24, 20).build()).active = teen_pro_YT_page + 1 < pages;
        addRenderableWidget(Button.builder(Component.translatable("screen.colonyloom.life.refresh"), ignored -> request()).bounds(width - 168, footer, 76, 20).build());
        addRenderableWidget(Button.builder(Component.translatable("screen.colonyloom.life.close"), ignored -> onClose()).bounds(width - 88, footer, 76, 20).build());
    }

    @Override
    public void render(GuiGraphics graphics, int mouseX, int mouseY, float partialTick) {
        renderBackground(graphics, mouseX, mouseY, partialTick);
        graphics.fill(6, 6, width - 6, height - 6, 0xDD1C2318);
        graphics.drawCenteredString(font, title, width / 2, 12, 0xFFFFFF);
        LifePayloads.View view = teen_pro_YT_view;
        if (view != null && !view.colonyName().isEmpty()) graphics.drawString(font, Component.literal(view.colonyName()), 12, 12, 0xB8E0A8);
        if (view != null) graphics.drawString(font, Component.translatable("screen.colonyloom.life.page", view.page() + 1, view.pages()), 12, 48, 0xCACACA);
        if (view == null || view.tab() != teen_pro_YT_tab) {
            graphics.drawCenteredString(font, Component.translatable(teen_pro_YT_waiting ? "screen.colonyloom.life.loading" : "screen.colonyloom.life.empty"), width / 2, teen_pro_YT_TOP + 8, 0xE8D878);
        } else if (!view.notice().isEmpty()) {
            graphics.drawCenteredString(font, LifeText.any(view.notice()), width / 2, view.rows().isEmpty() ? teen_pro_YT_TOP + 8 : height - 46, 0xE8D878);
        }
        if (view != null && teen_pro_YT_selected != null) for (LifePayloads.Row row : view.rows()) if (row.id().equals(teen_pro_YT_selected)) {
            String detail = LifeText.any(row.detail()).getString();
            graphics.drawString(font, font.plainSubstrByWidth(detail, width - 24), 12, height - 44, 0xC6D2DE);
        }
        for (var child : children()) if (child instanceof Renderable widget) widget.render(graphics, mouseX, mouseY, partialTick);
    }

    @Override
    public boolean mouseScrolled(double mouseX, double mouseY, double scrollX, double scrollY) {
        if (teen_pro_YT_view != null && mouseY >= teen_pro_YT_TOP && mouseY < height - 40) {
            teen_pro_YT_offset = Math.max(0, Math.min(Math.max(0, teen_pro_YT_view.rows().size() - visibleRows()), teen_pro_YT_offset + (scrollY < 0 ? 1 : -1)));
            rebuild();
            return true;
        }
        return super.mouseScrolled(mouseX, mouseY, scrollX, scrollY);
    }

    @Override
    public void onClose() {
        Screen back = null;
        if (teen_pro_YT_back != null) {
            try {
                back = teen_pro_YT_back.get();
            } catch (RuntimeException ignored) {
                back = null;
            }
        }
        if (minecraft != null) minecraft.setScreen(back);
    }

    @Override
    public boolean isPauseScreen() { return false; }
}
