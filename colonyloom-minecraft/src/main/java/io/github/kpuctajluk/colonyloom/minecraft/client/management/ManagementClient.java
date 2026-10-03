package io.github.kpuctajluk.colonyloom.minecraft.client.management;

import io.github.kpuctajluk.colonyloom.core.management.ManagementProtocol;
import io.github.kpuctajluk.colonyloom.core.management.ManagementProtocol.Body;
import io.github.kpuctajluk.colonyloom.core.management.ManagementProtocol.ViewData;
import io.github.kpuctajluk.colonyloom.core.management.ManagementProtocol.ViewType;
import io.github.kpuctajluk.colonyloom.minecraft.client.screen.ColonyScreen;
import io.github.kpuctajluk.colonyloom.minecraft.network.ManagementPayloads;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;
import java.util.function.Consumer;
import net.minecraft.client.Minecraft;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;

/** Client-thread, connection-local state. No command is retried, including across reconnects. */
public final class ManagementClient {
    private final Consumer<CustomPacketPayload> sender;
    private final String buildVersion;
    private final Map<UUID, Page> pages = new LinkedHashMap<>();
    private UUID sessionId;
    private UUID requestedColony;
    private boolean screenRequested;
    private long nextSequence;
    private long change;
    private boolean connected;
    private ManagementProtocol.Command pending;
    private ManagementProtocol.Result lastResult;
    private String notice = "DISCONNECTED";

    public ManagementClient(Consumer<CustomPacketPayload> sender, String buildVersion) {
        this.sender = Objects.requireNonNull(sender);
        this.buildVersion = ManagementProtocol.text(buildVersion);
    }

    public void connected() {
        if (connected) return;
        reset();
        connected = true;
        notice = "CONNECTING";
        sender.accept(new ManagementPayloads.Hello(ManagementProtocol.PROTOCOL_VERSION, buildVersion));
    }

    public void disconnected() {
        connected = false;
        reset();
        notice = "DISCONNECTED";
        Minecraft minecraft = Minecraft.getInstance();
        if (minecraft.screen instanceof ColonyScreen screen && screen.client() == this) {
            minecraft.setScreen(null);
        }
    }

    private void reset() {
        sessionId = null;
        requestedColony = null;
        screenRequested = false;
        nextSequence = 0;
        pending = null;
        lastResult = null;
        pages.clear();
        change++;
    }

    public void handle(CustomPacketPayload payload) {
        if (!connected) return;
        if (payload instanceof ManagementPayloads.Welcome welcome) {
            if (sessionId != null) return;
            sessionId = welcome.sessionId();
            nextSequence = welcome.nextSequence();
            notice = "READY";
            change++;
            if (screenRequested) open(requestedColony);
        } else if (payload instanceof ManagementPayloads.OpenScreen open) {
            requestedColony = open.colonyId();
            screenRequested = true;
            if (ready()) open(requestedColony);
        } else if (payload instanceof ManagementPayloads.CommandResult response) {
            receiveResult(response.result());
        } else if (payload instanceof ManagementPayloads.ViewSnapshot snapshot) {
            receiveView(snapshot.subscriptionId(), -1, snapshot.data(), true);
        } else if (payload instanceof ManagementPayloads.ViewDelta delta) {
            receiveView(delta.subscriptionId(), delta.baseRevision(), delta.data(), false);
        } else if (payload instanceof ManagementPayloads.ViewClosed closed) {
            Page page = pages.get(closed.subscriptionId());
            if (page != null) {
                page.data = null;
                page.closed = true;
                page.closeReason = closed.reason();
                notice = closed.reason();
                change++;
            }
        }
    }

    private void open(UUID colonyId) {
        requestedColony = null;
        screenRequested = false;
        Minecraft.getInstance().setScreen(new ColonyScreen(this, colonyId));
    }

    public boolean command(UUID colonyId, long expectedRevision, Body body) {
        if (!ready() || pending != null || nextSequence == Long.MAX_VALUE) return false;
        ManagementProtocol.Command command = new ManagementProtocol.Command(sessionId, nextSequence,
                colonyId, expectedRevision, body);
        nextSequence++;
        pending = command;
        lastResult = null;
        notice = "PENDING";
        change++;
        sender.accept(new ManagementPayloads.CommandPayload(command));
        return true;
    }

    private void receiveResult(ManagementProtocol.Result result) {
        if (pending == null || result.sequence() != pending.sequence()) return;
        Body body = pending.body();
        pending = null;
        lastResult = result;
        notice = result.reason();
        change++;
        if (body instanceof ManagementProtocol.CreateColony
                && result.status() == ManagementProtocol.Status.ACCEPTED && result.objectId() != null) {
            open(result.objectId());
        } else if (result.status() == ManagementProtocol.Status.STALE) {
            for (Map.Entry<UUID, Page> entry : pages.entrySet()) resync(entry.getKey(), entry.getValue());
        }
    }

    public UUID subscribe(UUID colonyId, ViewType type, int pageNumber) {
        if (!ready() || pages.size() >= ManagementProtocol.SUBSCRIPTIONS) return null;
        UUID id = UUID.randomUUID();
        Page page = new Page(colonyId, type, pageNumber);
        pages.put(id, page);
        sendSubscription(id, page, false);
        change++;
        return id;
    }

    public void unsubscribe(UUID id) {
        if (id == null || pages.remove(id) == null) return;
        if (ready()) sender.accept(new ManagementPayloads.Unsubscribe(sessionId, id));
        change++;
    }

    public void requestPage(UUID id, int pageNumber) {
        Page page = pages.get(id);
        if (page == null || !ready() || pageNumber < 0 || pageNumber > 1_000_000) return;
        page.closed = false;
        page.closeReason = null;
        page.number = pageNumber;
        page.data = null;
        page.resyncing = true;
        sendSubscription(id, page, true);
        change++;
    }

    private void receiveView(UUID id, long baseRevision, ViewData data, boolean snapshot) {
        Page page = pages.get(id);
        if (page == null || page.closed || !ready()) return;
        if (!data.colonyId().equals(page.colonyId) || data.type() != page.type || data.page() != page.number) {
            page.resyncing = false;
            resync(id, page);
            return;
        }
        if (!snapshot && (page.resyncing || page.data == null
                || page.data.stateRevision() != baseRevision
                || page.data.authorityRevision() != data.authorityRevision())) {
            resync(id, page);
            return;
        }
        if (page.data != null && (data.authorityRevision() < page.data.authorityRevision()
                || data.authorityRevision() == page.data.authorityRevision()
                && data.stateRevision() < page.data.stateRevision())) {
            resync(id, page);
            return;
        }
        for (Page other : pages.values()) {
            if (other != page && other.colonyId.equals(page.colonyId) && other.data != null
                    && other.data.authorityRevision() > data.authorityRevision()) {
                page.resyncing = false;
                resync(id, page);
                return;
            }
        }
        for (Map.Entry<UUID, Page> entry : pages.entrySet()) {
            Page other = entry.getValue();
            if (other != page && other.colonyId.equals(page.colonyId) && other.data != null
                    && other.data.authorityRevision() != data.authorityRevision()) {
                other.data = null;
                resync(entry.getKey(), other);
            }
        }
        page.data = data;
        page.resyncing = false;
        notice = "READY";
        change++;
        sender.accept(new ManagementPayloads.ViewAck(sessionId, id, data.authorityRevision(), data.stateRevision()));
    }

    private void resync(UUID id, Page page) {
        if (!ready() || page.closed || page.resyncing) return;
        page.resyncing = true;
        page.data = null;
        notice = "RESYNCING";
        change++;
        sendSubscription(id, page, true);
    }

    private void sendSubscription(UUID id, Page page, boolean resync) {
        sender.accept(new ManagementPayloads.Subscribe(new ManagementProtocol.Subscription(
                sessionId, id, page.colonyId, page.type, page.number, resync)));
    }

    public ViewData page(UUID id) {
        Page page = pages.get(id);
        return page == null ? null : page.data;
    }

    public boolean accessDenied(UUID colonyId) {
        return pages.values().stream().anyMatch(page -> page.colonyId.equals(colonyId)
                && "ACCESS_DENIED".equals(page.closeReason));
    }

    public boolean ready() { return connected && sessionId != null; }
    public UUID sessionId() { return sessionId; }
    public long nextSequence() { return nextSequence; }
    public Map<UUID, ManagementProtocol.Subscription> subscriptions() {
        Map<UUID, ManagementProtocol.Subscription> result = new LinkedHashMap<>();
        if (sessionId != null) pages.forEach((id, page) -> result.put(id,
                new ManagementProtocol.Subscription(sessionId, id, page.colonyId, page.type, page.number, page.resyncing)));
        return Map.copyOf(result);
    }
    public boolean pending() { return pending != null; }
    public long change() { return change; }
    public String notice() { return notice; }
    public ManagementProtocol.Result lastResult() { return lastResult; }

    private static final class Page {
        private final UUID colonyId;
        private final ViewType type;
        private int number;
        private ViewData data;
        private boolean resyncing;
        private boolean closed;
        private String closeReason;

        private Page(UUID colonyId, ViewType type, int number) {
            if (number < 0 || number > 1_000_000) throw new IllegalArgumentException("INVALID_PAGE");
            this.colonyId = Objects.requireNonNull(colonyId);
            this.type = Objects.requireNonNull(type);
            this.number = number;
        }
    }
}
