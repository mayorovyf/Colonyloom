package io.github.kpuctajluk.colonyloom.minecraft.client.screen;

import io.github.kpuctajluk.colonyloom.core.colony.Territory;
import io.github.kpuctajluk.colonyloom.core.colony.WorldPosition;
import io.github.kpuctajluk.colonyloom.core.management.ManagementProtocol;
import io.github.kpuctajluk.colonyloom.core.management.ManagementProtocol.Body;
import io.github.kpuctajluk.colonyloom.core.management.ManagementProtocol.Row;
import io.github.kpuctajluk.colonyloom.core.management.ManagementProtocol.ViewData;
import io.github.kpuctajluk.colonyloom.core.management.ManagementProtocol.ViewType;
import io.github.kpuctajluk.colonyloom.minecraft.client.management.ManagementClient;
import io.github.kpuctajluk.colonyloom.minecraft.client.management.ManagementText;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.function.Supplier;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.components.EditBox;
import net.minecraft.client.gui.components.Tooltip;
import net.minecraft.client.gui.components.Renderable;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.Component;

/** Vanilla-only management screen; all displayed values come from bounded server pages. */
public final class ColonyScreen extends Screen {
    private final ManagementClient client;
    private final UUID colonyId;
    private UUID summarySubscription;
    private UUID viewSubscription;
    private UUID workshopSubscription;
    private ViewType tab = ViewType.SUMMARY;
    private int pageNumber;
    private int workshopPage;
    private int rowOffset;
    private UUID selected;
    private Row actionTarget;
    private Action form;
    private long observedChange = -1;
    private final Map<String, String> values = new LinkedHashMap<>();
    private final List<Label> labels = new ArrayList<>();
    private final Map<String, EditBox> editBoxes = new LinkedHashMap<>();
    private String formRank;
    private List<String> formProfessions = List.of();
    private List<String> formBlueprints = List.of();
    private List<Row> formWorkshops = List.of();
    private String localNotice;
    private int fieldNumber;
    private Button submit;

    public ColonyScreen(ManagementClient client, UUID colonyId) {
        super(ManagementText.ui("title"));
        this.client = client;
        this.colonyId = colonyId;
        if (colonyId == null) form = Action.CREATE;
    }

    public ManagementClient client() { return client; }

    @Override
    protected void init() {
        if (summarySubscription == null && colonyId != null) {
            summarySubscription = client.subscribe(colonyId, ViewType.SUMMARY, 0);
            viewSubscription = summarySubscription;
        }
        refresh();
    }

    @Override
    public void tick() {
        if (observedChange == client.change()) return;
        if (colonyId != null && form != null && form != Action.CREATE
                && (client.accessDenied(colonyId)
                || summary() != null && (form == Action.MEMBER || form == Action.OWNER ? !owner() : !manager()))) {
            selected = null;
            leaveForm();
            return;
        }
        if (form != null) {
            ViewData summary = summary(), workshops = client.page(workshopSubscription);
            String rank = summary == null ? null : summary.rank();
            List<String> professions = summary == null ? List.of() : summary.professions();
            List<String> blueprints = summary == null ? List.of() : summary.blueprints();
            List<Row> rows = workshops == null ? List.of() : workshops.rows();
            if (java.util.Objects.equals(formRank, rank) && formProfessions.equals(professions)
                    && formBlueprints.equals(blueprints) && formWorkshops.equals(rows)) {
                observedChange = client.change();
                submit.active = allowed(form);
                editBoxes.values().forEach(box -> box.setEditable(allowed(form)));
                return;
            }
        }
        refresh();
    }

    private ViewData summary() { return client.page(summarySubscription); }
    private ViewData view() { return client.page(viewSubscription); }
    private boolean owner() { return summary() != null && summary().rank().equalsIgnoreCase("owner"); }
    private boolean manager() {
        return summary() != null && (owner() || summary().rank().equalsIgnoreCase("manager"));
    }
    private Row selectedRow() {
        if (view() == null || selected == null) return null;
        return view().rows().stream().filter(row -> row.id().equals(selected)).findFirst().orElse(null);
    }
    private long colonyRevision() {
        if (summary() == null) throw new IllegalArgumentException("NOT_READY");
        return summary().rows().stream().filter(row -> row.id().equals(colonyId)).findFirst()
                .orElseThrow(() -> new IllegalArgumentException("NOT_READY")).revision();
    }
    private boolean allowed(Action action) {
        if (!client.ready() || client.pending()) return false;
        if (action == Action.CREATE) return true;
        if (action == Action.MEMBER || action == Action.OWNER) return owner();
        return manager();
    }

    private Button button(Component text, int x, int y, int w, Runnable callback) {
        return addRenderableWidget(Button.builder(text, ignored -> callback.run()).bounds(x, y, w, 20).build());
    }
    private void actionButton(Action action, int x, int y, int w) {
        Button button = button(ManagementText.ui(action.key), x, y, w, () -> begin(action));
        button.active = allowed(action) && (!action.target || selectedRow() != null);
        if (!button.active) button.setTooltip(Tooltip.create(ManagementText.ui("read_only")));
    }

    private void refresh() {
        String focused = null;
        int cursor = 0;
        for (Map.Entry<String, EditBox> entry : editBoxes.entrySet()) if (entry.getValue().isFocused()) {
            focused = entry.getKey();
            cursor = entry.getValue().getCursorPosition();
        }
        observedChange = client.change();
        clearWidgets();
        labels.clear();
        editBoxes.clear();
        if (form != null) {
            renderFormWidgets();
            ViewData summary = summary(), workshops = client.page(workshopSubscription);
            formRank = summary == null ? null : summary.rank();
            formProfessions = summary == null ? List.of() : summary.professions();
            formBlueprints = summary == null ? List.of() : summary.blueprints();
            formWorkshops = workshops == null ? List.of() : workshops.rows();
            EditBox box = editBoxes.get(focused);
            if (box != null) { setFocused(box); box.setCursorPosition(cursor); }
            return;
        }
        int gap = 4;
        int tabWidth = (width - 24 - gap * 3) / 4;
        int i = 0;
        for (ViewType type : ViewType.values()) {
            Button button = button(ManagementText.ui("tab." + type.name().toLowerCase(java.util.Locale.ROOT)),
                    12 + i++ * (tabWidth + gap), 40, tabWidth, () -> switchTab(type));
            button.active = tab != type;
        }
        ViewData data = view();
        int capacity = visibleRows();
        if (data != null) {
            rowOffset = Math.min(rowOffset, Math.max(0, data.rows().size() - capacity));
            for (i = rowOffset; i < Math.min(data.rows().size(), rowOffset + capacity); i++) {
                Row row = data.rows().get(i);
                Component name = row.id().equals(colonyId) || row.name().contains(":")
                        ? Component.literal(row.name()) : ManagementText.code(row.name());
                Component text = Component.literal(row.id().equals(selected) ? "> " : "")
                        .append(name).append(" | ").append(ManagementText.state(row.state()));
                if (tab == ViewType.SUMMARY && (row.state().equals("LIMIT") || row.state().equals("STOCK_READY") || row.state().equals("UNKNOWN"))) text = text.copy().append(" | ").append(row.detail());
                else if (tab == ViewType.CITIZENS || tab == ViewType.WORK) text = text.copy().append(" | ").append(ManagementText.code(row.reason()));
                Button entry = button(text, 12, 82 + (i - rowOffset) * 22, width - 24, () -> {
                    selected = row.id(); refresh();
                });
                entry.setTooltip(Tooltip.create(Component.literal(row.id().toString()).append("\n")
                        .append(ManagementText.code(row.reason())).append("\n").append(row.detail())
                        .append(row.relatedId() == null ? "" : "\n" + row.relatedId())));
            }
        }
        int footer = height - 94;
        button(ManagementText.ui("previous"), 12, footer, 52, () -> changePage(-1)).active = data != null && pageNumber > 0;
        button(ManagementText.ui("next"), 68, footer, 52, () -> changePage(1)).active = data != null
                && (long) (pageNumber + 1) * ManagementProtocol.PAGE_ROWS < data.totalRows();
        button(ManagementText.ui("up"), 124, footer, 26, () -> { rowOffset = Math.max(0, rowOffset - capacity); refresh(); })
                .active = rowOffset > 0;
        button(ManagementText.ui("down"), 154, footer, 26, () -> { rowOffset += capacity; refresh(); })
                .active = data != null && rowOffset + capacity < data.rows().size();
        button(ManagementText.ui("refresh"), width - 82, footer, 70, () -> client.requestPage(viewSubscription, pageNumber));
        List<Action> actions = switch (tab) {
            case SUMMARY -> List.of(Action.CREATE, Action.MEMBER, Action.OWNER);
            case CITIZENS -> List.of(Action.PROFESSION, Action.WORKPLACE);
            case BUILDINGS -> List.of(Action.BUILD, Action.STORAGE, Action.WORKSHOP);
            case WORK -> List.of(Action.CANCEL, Action.PRIORITY);
        };
        int w = (width - 24 - (actions.size() - 1) * 4) / actions.size();
        for (i = 0; i < actions.size(); i++) actionButton(actions.get(i), 12 + i * (w + 4), height - 70, w);
        button(ManagementText.ui("close"), width - 72, height - 24, 60, this::onClose);
    }

    private int visibleRows() { return Math.max(1, (height - 178) / 22); }

    private void switchTab(ViewType type) {
        if (viewSubscription != null && !viewSubscription.equals(summarySubscription)) client.unsubscribe(viewSubscription);
        tab = type;
        pageNumber = 0;
        rowOffset = 0;
        selected = null;
        viewSubscription = type == ViewType.SUMMARY ? summarySubscription : client.subscribe(colonyId, type, 0);
        refresh();
    }

    private void changePage(int delta) {
        pageNumber = Math.max(0, Math.min(1_000_000, pageNumber + delta));
        rowOffset = 0;
        selected = null;
        if (viewSubscription != null && viewSubscription.equals(summarySubscription)) {
            viewSubscription = client.subscribe(colonyId, ViewType.SUMMARY, pageNumber);
        } else client.requestPage(viewSubscription, pageNumber);
        refresh();
    }

    private void begin(Action action) {
        if (!allowed(action)) return;
        actionTarget = selectedRow();
        if (action.target && actionTarget == null) return;
        form = action;
        values.clear();
        localNotice = null;
        workshopPage = 0;
        if (action == Action.WORKPLACE) workshopSubscription = client.subscribe(colonyId, ViewType.BUILDINGS, 0);
        refresh();
    }

    private void leaveForm() {
        client.unsubscribe(workshopSubscription);
        workshopSubscription = null;
        form = null;
        actionTarget = null;
        formRank = null;
        formProfessions = List.of();
        formBlueprints = List.of();
        formWorkshops = List.of();
        values.clear();
        refresh();
    }

    private void renderFormWidgets() {
        fieldNumber = 0;
        labels.add(new Label(ManagementText.ui(form.key), 12, 44));
        if (actionTarget != null) labels.add(new Label(Component.literal(actionTarget.id().toString()), 12, 58));
        switch (form) {
            case CREATE -> {
                field("name", "Colony"); field("dimension", dimension());
                field("min_x", coordinate("x")); field("min_z", coordinate("z"));
                field("max_x", offsetCoordinate("x", 31)); field("max_z", offsetCoordinate("z", 31));
            }
            case PROFESSION -> choice("profession_id", () -> summary() == null ? List.of() : summary().professions());
            case WORKPLACE -> workplaceFields();
            case BUILD -> {
                choice("blueprint_id", () -> summary() == null ? List.of() : summary().blueprints());
                field("dimension", dimension()); positionFields("");
                choice("rotation", () -> List.of("0", "90", "180", "270"));
            }
            case CANCEL -> labels.add(new Label(ManagementText.ui("cancel_confirm"), 12, 86));
            case PRIORITY -> field("priority_value", "5");
            case MEMBER -> { field("player_id", ""); choice("rank", () -> List.of("viewer", "manager", "none")); }
            case OWNER -> {
                field("player_id", ""); field("confirm_owner", "");
                labels.add(new Label(ManagementText.ui("owner_warning"), 12, height - 76));
            }
            case STORAGE -> {
                choice("role", () -> List.of("warehouse", "workshop", "construction", "return"));
                field("dimension", dimension()); positionFields("");
            }
            case WORKSHOP -> {
                field("dimension", dimension()); positionFields("table_"); positionFields("inventory_");
                labels.add(new Label(ManagementText.ui("workshop_help"), 12, height - 76));
            }
        }
        submit = button(ManagementText.ui("submit"), width - 110, height - 52, 98, this::submitForm);
        submit.active = allowed(form);
        button(ManagementText.ui("back"), 12, height - 52, 90, colonyId == null ? this::onClose : this::leaveForm);
    }

    private String dimension() {
        return minecraft.level == null ? "minecraft:overworld" : minecraft.level.dimension().location().toString();
    }
    private String coordinate(String axis) { return offsetCoordinate(axis, 0); }
    private String offsetCoordinate(String axis, int offset) {
        if (minecraft.player == null) return Integer.toString(offset);
        var position = minecraft.player.blockPosition();
        return Integer.toString((axis.equals("x") ? position.getX() : axis.equals("y") ? position.getY() : position.getZ()) + offset);
    }
    private void positionFields(String prefix) {
        field(prefix + "x", coordinate("x")); field(prefix + "y", coordinate("y")); field(prefix + "z", coordinate("z"));
    }

    private int fieldX() { return 12 + (fieldNumber % 2) * ((width - 28) / 2 + 4); }
    private int fieldY() { return 80 + (fieldNumber / 2) * Math.max(22, Math.min(30, (height - 160) / 4)); }
    private int fieldWidth() { return (width - 28) / 2; }

    private void field(String key, String initial) {
        int x = fieldX(), y = fieldY();
        labels.add(new Label(ManagementText.ui("field." + key), x, y - 10));
        EditBox box = new EditBox(font, x, y, fieldWidth(), 18, ManagementText.ui("field." + key));
        box.setMaxLength(ManagementProtocol.STRING_BYTES);
        box.setValue(values.computeIfAbsent(key, ignored -> initial));
        box.setResponder(value -> values.put(key, value));
        box.setEditable(allowed(form));
        addRenderableWidget(box);
        editBoxes.put(key, box);
        fieldNumber++;
    }

    private void choice(String key, Supplier<List<String>> options) {
        int x = fieldX(), y = fieldY();
        List<String> choices = options.get();
        if (!values.containsKey(key) && !choices.isEmpty()) values.put(key, choices.getFirst());
        labels.add(new Label(ManagementText.ui("field." + key), x, y - 10));
        String value = values.getOrDefault(key, "");
        Button control = button(value.isEmpty() ? ManagementText.ui("no_options") : Component.literal(value), x, y, fieldWidth(), () -> {
            List<String> current = options.get();
            if (current.isEmpty()) return;
            int index = current.indexOf(values.get(key));
            values.put(key, current.get((index + 1) % current.size()));
            refresh();
        });
        control.active = allowed(form) && !choices.isEmpty();
        fieldNumber++;
    }

    private void workplaceFields() {
        ViewData buildings = client.page(workshopSubscription);
        List<Row> workshops = buildings == null ? List.of() : buildings.rows().stream()
                .filter(row -> row.name().equals("workshop") && row.state().equals("REGISTERED")).toList();
        choice("workshop_id", () -> workshops.stream().map(row -> row.id().toString()).toList());
        field("workshop_manual", "");
        labels.add(new Label(ManagementText.ui("workplace_help"), 12, 130));
        button(ManagementText.ui("previous"), 12, 148, 64, () -> workshopPage(-1)).active = workshopPage > 0;
        button(ManagementText.ui("next"), 80, 148, 64, () -> workshopPage(1)).active = buildings != null
                && (long) (workshopPage + 1) * ManagementProtocol.PAGE_ROWS < buildings.totalRows();
        labels.add(new Label(ManagementText.ui("page", workshopPage + 1,
                buildings == null ? "?" : Math.max(1, (buildings.totalRows() + 49L) / 50L)), 152, 154));
    }

    private void workshopPage(int delta) {
        workshopPage = Math.max(0, Math.min(1_000_000, workshopPage + delta));
        values.remove("workshop_id");
        client.requestPage(workshopSubscription, workshopPage);
        refresh();
    }

    private String required(String key) {
        String value = values.getOrDefault(key, "").trim();
        if (value.isEmpty()) throw new IllegalArgumentException("INVALID_INPUT");
        return ManagementProtocol.text(value);
    }
    private int integer(String key) { return Integer.parseInt(required(key)); }
    private WorldPosition position(String prefix) {
        return new WorldPosition(required("dimension"), integer(prefix + "x"), integer(prefix + "y"), integer(prefix + "z"));
    }

    private void submitForm() {
        if (form == null || !allowed(form)) return;
        try {
            Body body = switch (form) {
                case CREATE -> new ManagementProtocol.CreateColony(required("name"), new Territory(required("dimension"),
                        integer("min_x"), integer("min_z"), integer("max_x"), integer("max_z")));
                case PROFESSION -> new ManagementProtocol.AssignProfession(actionTarget.id(), required("profession_id"));
                case WORKPLACE -> new ManagementProtocol.AssignWorkplace(actionTarget.id(), UUID.fromString(
                        values.getOrDefault("workshop_manual", "").isBlank() ? required("workshop_id") : required("workshop_manual")));
                case BUILD -> new ManagementProtocol.Build(required("blueprint_id"), position(""), integer("rotation"));
                case CANCEL -> new ManagementProtocol.CancelWork(actionTarget.id());
                case PRIORITY -> new ManagementProtocol.PrioritizeWork(actionTarget.id(), integer("priority_value"));
                case MEMBER -> new ManagementProtocol.SetMember(UUID.fromString(required("player_id")), required("rank"));
                case OWNER -> {
                    UUID owner = UUID.fromString(required("player_id"));
                    if (!owner.equals(UUID.fromString(required("confirm_owner")))) throw new IllegalArgumentException("INVALID_INPUT");
                    yield new ManagementProtocol.SetOwner(owner);
                }
                case STORAGE -> new ManagementProtocol.RegisterStorage(position(""), required("role"));
                case WORKSHOP -> new ManagementProtocol.RegisterWorkshop(position("table_"), position("inventory_"));
            };
            long revision = form == Action.CREATE ? 0 : form.target ? actionTarget.revision() : colonyRevision();
            if (client.command(form == Action.CREATE ? null : colonyId, revision, body)) {
                localNotice = null;
                if (colonyId != null) leaveForm();
            }
        } catch (IllegalArgumentException exception) {
            // Local input validation never reveals Java exceptions or stack traces in the UI.
            localNotice = "INVALID_INPUT";
            refresh();
        }
    }

    @Override
    public void render(GuiGraphics graphics, int mouseX, int mouseY, float partialTick) {
        renderBackground(graphics, mouseX, mouseY, partialTick);
        graphics.fill(6, 6, width - 6, height - 6, 0xDD17202A);
        graphics.drawCenteredString(font, title, width / 2, 10, 0xFFFFFF);
        ViewData summary = summary();
        if (summary != null) {
            Component header = Component.literal(summary.colonyName()).append(" | ")
                    .append(ManagementText.code(summary.rank()));
            graphics.drawString(font, header, 12, 26, 0xB5D8FF);
        }
        if (form == null) {
            ViewData data = view();
            if (data == null) graphics.drawCenteredString(font, ManagementText.code(client.notice()), width / 2, 82, 0xE8C878);
            else graphics.drawString(font, ManagementText.ui("page", pageNumber + 1,
                    Math.max(1, (data.totalRows() + 49L) / 50L)), 12, 66, 0xCACACA);
            Row row = selectedRow();
            if (row != null) {
                graphics.drawString(font, font.plainSubstrByWidth(row.detail(), width - 24), 12, height - 111, 0xC6D2DE);
                graphics.drawString(font, ManagementText.code(row.reason()), width / 2, 66, 0xE8C878);
            }
        }
        for (Label label : labels) graphics.drawString(font, label.text(), label.x(), label.y(), 0xD8E4F0);
        Component status = localNotice != null ? ManagementText.code(localNotice) : ManagementText.code(client.notice());
        ManagementProtocol.Result result = client.lastResult();
        if (localNotice == null && result != null) {
            status = ManagementText.code(result.status().name()).copy().append(": ").append(ManagementText.code(result.reason()));
        }
        graphics.drawString(font, font.plainSubstrByWidth(status.getString(), width - 96), 12, height - 18, 0xE8C878);
        for (var child : children()) if (child instanceof Renderable widget) widget.render(graphics, mouseX, mouseY, partialTick);
    }

    @Override
    public boolean mouseScrolled(double mouseX, double mouseY, double scrollX, double scrollY) {
        if (form == null && view() != null && mouseY >= 82 && mouseY < height - 112) {
            rowOffset = Math.max(0, Math.min(Math.max(0, view().rows().size() - visibleRows()),
                    rowOffset + (scrollY < 0 ? 1 : -1)));
            refresh();
            return true;
        }
        return super.mouseScrolled(mouseX, mouseY, scrollX, scrollY);
    }

    @Override
    public void onClose() {
        if (form != null && colonyId != null) leaveForm();
        else super.onClose();
    }

    @Override
    public void removed() {
        client.unsubscribe(workshopSubscription);
        if (viewSubscription != null && !viewSubscription.equals(summarySubscription)) client.unsubscribe(viewSubscription);
        client.unsubscribe(summarySubscription);
        workshopSubscription = null;
        viewSubscription = null;
        summarySubscription = null;
    }

    @Override
    public boolean isPauseScreen() { return false; }

    private enum Action {
        CREATE("create", false), PROFESSION("profession", true), WORKPLACE("workplace", true),
        BUILD("build", false), CANCEL("cancel", true), PRIORITY("priority", true),
        MEMBER("member", false), OWNER("owner", false), STORAGE("storage", false), WORKSHOP("workshop", false);
        private final String key;
        private final boolean target;
        Action(String key, boolean target) { this.key = key; this.target = target; }
    }
    private record Label(Component text, int x, int y) {}
}
