package io.github.kpuctajluk.colonyloom.core.management;

import io.github.kpuctajluk.colonyloom.core.colony.WorldPosition;
import io.github.kpuctajluk.colonyloom.core.management.ManagementProtocol.*;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;

/** Connection-local state machine. All calls belong to the owning server thread. */
public final class ManagementSession {
    public record Authority(boolean allowed, long revision) {
        public Authority { if (revision < 0) throw new IllegalArgumentException("INVALID_REVISION"); }
    }
    public interface Backend {
        Authority authorize(UUID colonyId);
        long targetRevision(Command command);
        Result execute(Command command);
        /** Defers page materialization when the shared server row budget is exhausted. */
        default boolean viewReady(Subscription subscription) { return true; }
        ViewData view(Subscription subscription);
        default void cancelView(Subscription subscription) {}
        default int preparationBytes(Subscription subscription) { return 0; }
    }
    public interface Transport {
        boolean writable();
        void sendResult(Result result);
        void sendSnapshot(UUID subscriptionId, ViewData data);
        void sendDelta(UUID subscriptionId, long baseRevision, ViewData data);
        void closeView(UUID subscriptionId, String reason);
    }
    private static final class CachedResult {
        final Result result;
        final UUID colonyId;
        final long authorityRevision;
        final int bytes;
        boolean pending;
        CachedResult(Result result, UUID colonyId, long authorityRevision) {
            this.result = result;
            this.colonyId = colonyId;
            this.authorityRevision = authorityRevision;
            bytes = resultBytes(result);
            pending = true;
        }
    }
    private static final class View {
        final Subscription subscription;
        long authorityRevision;
        long acknowledgedRevision = -1;
        ViewData inFlight;
        int inFlightBytes;
        long sentAt;
        ViewData latest;
        int latestBytes;
        View(Subscription subscription, long authorityRevision) {
            this.subscription = subscription;
            this.authorityRevision = authorityRevision;
        }
    }
    private final UUID sessionId = UUID.randomUUID();
    private final Backend backend;
    private final Transport transport;
    private final Map<Long, CachedResult> results = new LinkedHashMap<>();
    private final Map<UUID, View> views = new LinkedHashMap<>();
    private final Map<UUID, String> closedViews = new LinkedHashMap<>();
    private long nextSequence;
    private long tick;
    private final long[] commandTicks = new long[ManagementProtocol.COMMANDS_PER_SECOND];
    private int commandHead;
    private int commandCount;
    private final View[] iteration = new View[ManagementProtocol.SUBSCRIPTIONS];
    private int viewStart;
    private int cachedBytes;
    private int bufferedBytes;
    private boolean closed;

    public ManagementSession(Backend backend, Transport transport) {
        this.backend = Objects.requireNonNull(backend);
        this.transport = Objects.requireNonNull(transport);
    }
    public UUID sessionId() { return sessionId; }
    public long nextSequence() { return nextSequence; }
    public int cachedResultCount() { return results.size(); }
    public int cachedResultBytes() { return cachedBytes; }
    public int subscriptionCount() { return views.size(); }
    public int bufferedViewBytes() {
        int bytes = bufferedBytes;
        for (View view : views.values()) bytes = Math.addExact(bytes, backend.preparationBytes(view.subscription));
        return bytes;
    }

    public Result command(Command command) {
        Objects.requireNonNull(command);
        if (closed || !sessionId.equals(command.sessionId())) return reject(command, Status.REJECTED, "NO_SESSION");
        if (command.sequence() < nextSequence) {
            CachedResult cached = results.get(command.sequence());
            if (cached != null && current(cached)) {
                cached.pending = true;
                flushResults();
                return cached.result;
            }
            if (cached != null) removeResult(command.sequence());
            return reject(command, Status.DUPLICATE_EXPIRED, "DUPLICATE_EXPIRED");
        }
        if (command.sequence() > nextSequence) return reject(command, Status.REJECTED, "SEQUENCE_GAP");
        if (nextSequence == Long.MAX_VALUE) return reject(command, Status.REJECTED, "SEQUENCE_EXHAUSTED");
        while (commandCount > 0 && tick - commandTicks[commandHead] >= 20) {
            commandHead = (commandHead + 1) % commandTicks.length;
            commandCount--;
        }
        Authority authority = command.colonyId() == null ? new Authority(true, 0) : backend.authorize(command.colonyId());
        Result result;
        if (commandCount >= ManagementProtocol.COMMANDS_PER_SECOND) {
            result = result(command, Status.REJECTED, "RATE_LIMIT", 0);
        } else {
            commandTicks[(commandHead + commandCount) % commandTicks.length] = tick;
            commandCount++;
            if (commandBytes(command) > ManagementProtocol.COMMAND_BYTES) result = result(command, Status.REJECTED, "PAYLOAD_LIMIT", 0);
            else if (!authority.allowed()) result = result(command, Status.REJECTED, "ACCESS_DENIED", 0);
            else result = execute(command);
        }
        nextSequence++;
        cache(command, authority.revision(), result);
        // Mutations of membership can revoke other open views on this connection.
        refreshAuthority();
        flushResults();
        return result;
    }
    private Result execute(Command command) {
        try {
            long revision = backend.targetRevision(command);
            if (revision < 0) throw new IllegalArgumentException("INVALID_REVISION");
            if (revision != command.expectedRevision()) return result(command, Status.STALE, "STALE", revision);
            Result result = Objects.requireNonNull(backend.execute(command));
            if (result.sequence() != command.sequence() || result.status() == Status.DUPLICATE_EXPIRED)
                throw new IllegalArgumentException("INVALID_RESULT");
            return result;
        } catch (SecurityException exception) {
            return result(command, Status.REJECTED, "ACCESS_DENIED", 0);
        } catch (io.github.kpuctajluk.colonyloom.core.scheduler.AdmissionLedger.AdmissionException exception) {
            return result(command, Status.REJECTED, exception.reason().name(), 0);
        } catch (IllegalArgumentException | IllegalStateException exception) {
            return result(command, Status.REJECTED, "INVALID_COMMAND", 0);
        }
    }
    private static Result result(Command command, Status status, String reason, long revision) {
        return new Result(command.sequence(), status, reason, null, revision);
    }
    private Result reject(Command command, Status status, String reason) {
        Result result = result(command, status, reason, 0);
        if (!closed && transport.writable()) transport.sendResult(result);
        return result;
    }
    private void cache(Command command, long authorityRevision, Result result) {
        UUID colonyId = result.status() == Status.REJECTED && result.objectId() == null && result.revision() == 0
                ? null : command.colonyId();
        if (command.body() instanceof CreateColony && result.status() == Status.ACCEPTED)
            colonyId = Objects.requireNonNull(result.objectId(), "Missing created colony identity");
        if (colonyId != null) authorityRevision = backend.authorize(colonyId).revision();
        results.put(command.sequence(), new CachedResult(result, colonyId, authorityRevision));
        cachedBytes += results.get(command.sequence()).bytes;
        while (results.size() > ManagementProtocol.RESULT_COUNT || cachedBytes > ManagementProtocol.RESULT_BYTES)
            removeResult(results.keySet().iterator().next());
    }
    private void removeResult(long sequence) {
        CachedResult removed = results.remove(sequence);
        if (removed != null) cachedBytes -= removed.bytes;
    }
    private boolean current(CachedResult cached) {
        if (cached.colonyId == null) return true;
        Authority authority = backend.authorize(cached.colonyId);
        return authority.allowed() && authority.revision() == cached.authorityRevision;
    }
    private void flushResults() {
        Iterator<CachedResult> iterator = results.values().iterator();
        while (iterator.hasNext()) {
            CachedResult cached = iterator.next();
            if (!current(cached)) {
                cachedBytes -= cached.bytes;
                iterator.remove();
            } else if (cached.pending && transport.writable()) {
                cached.pending = false;
                transport.sendResult(cached.result);
            }
        }
    }

    public void subscribe(Subscription subscription) {
        Objects.requireNonNull(subscription);
        UUID id = subscription.subscriptionId();
        if (closed || !sessionId.equals(subscription.sessionId())) { notifyClosed(id, "NO_SESSION"); return; }
        Authority authority = backend.authorize(subscription.colonyId());
        if (!authority.allowed()) { removeView(id); notifyClosed(id, "ACCESS_DENIED"); return; }
        View old = views.get(id);
        if (old != null && !subscription.resync() && old.subscription.colonyId().equals(subscription.colonyId())
                && old.subscription.type() == subscription.type() && old.subscription.page() == subscription.page()) {
            checkAuthority(old);
            return;
        }
        if (old == null && views.size() >= ManagementProtocol.SUBSCRIPTIONS) { notifyClosed(id, "SUBSCRIPTION_LIMIT"); return; }
        removeView(id);
        closedViews.remove(id);
        View view = new View(subscription, authority.revision());
        views.put(id, view);
        prepare(view);
    }
    public void unsubscribe(UUID requestedSession, UUID subscriptionId) {
        if (closed || !sessionId.equals(requestedSession)) return;
        removeView(subscriptionId);
        closedViews.remove(subscriptionId);
    }
    public void acknowledge(UUID requestedSession, UUID subscriptionId, long authorityRevision, long stateRevision) {
        if (closed || !sessionId.equals(requestedSession)) return;
        View view = views.get(subscriptionId);
        if (view == null || !checkAuthority(view) || view.inFlight == null) return;
        if (authorityRevision != view.authorityRevision || stateRevision != view.inFlight.stateRevision()) return;
        view.acknowledgedRevision = stateRevision;
        bufferedBytes -= view.inFlightBytes;
        view.inFlight = null;
        view.inFlightBytes = 0;
        if (view.latest != null && view.latest.stateRevision() == stateRevision) clearLatest(view);
    }
    public void tick(long serverTick) {
        if (serverTick < tick) throw new IllegalArgumentException("TICK_REGRESSION");
        if (closed) return;
        if (serverTick == tick && ticked) return;
        boolean sendTick = serverTick % ManagementProtocol.SEND_INTERVAL == 0 && serverTick != tick;
        // Tick zero may send once; subsequent repeated calls at the same tick never send twice.
        if (serverTick == 0 && !ticked) sendTick = true;
        ticked = true;
        tick = serverTick;
        refreshAuthority();
        flushResults();
        flushClosed();
        int count = collectViews();
        int start = count == 0 ? 0 : viewStart % count;
        if (count > 0) viewStart = (start + 1) % count;
        for (int index = 0; index < count; index++) {
            int slot = (start + index) % count;
            View view = iteration[slot];
            iteration[slot] = null;
            if (view.inFlight != null && tick - view.sentAt >= ManagementProtocol.ACK_TIMEOUT) {
                closeView(view, "ACK_TIMEOUT");
                continue;
            }
            if (!prepare(view)) continue;
            if (sendTick && view.inFlight == null && view.latest != null && transport.writable() && checkAuthority(view)
                    && view.latest != null) send(view);
        }
        flushClosed();
    }
    private boolean ticked;
    private void refreshAuthority() {
        int count = collectViews();
        for (int index = 0; index < count; index++) {
            View view = iteration[index];
            iteration[index] = null;
            checkAuthority(view);
        }
    }
    private int collectViews() {
        int count = 0;
        for (View view : views.values()) iteration[count++] = view;
        return count;
    }
    private boolean checkAuthority(View view) {
        Authority authority = backend.authorize(view.subscription.colonyId());
        if (!authority.allowed()) { closeView(view, "ACCESS_DENIED"); return false; }
        if (authority.revision() != view.authorityRevision) {
            purge(view);
            view.authorityRevision = authority.revision();
            view.acknowledgedRevision = -1;
        }
        return true;
    }
    private boolean prepare(View view) {
        if (views.get(view.subscription.subscriptionId()) != view || !checkAuthority(view)) return false;
        if (view.latest != null && view.inFlight == null) return true;
        if (view.latest != null) clearLatest(view);
        try {
            boolean ready = backend.viewReady(view.subscription);
            if (bufferedViewBytes() > ManagementProtocol.VIEW_BUFFER_BYTES) { closeView(view, "BUFFER_LIMIT"); return false; }
            if (!ready) return true;
            ViewData data = Objects.requireNonNull(backend.view(view.subscription));
            if (!checkAuthority(view)) return false;
            if (!valid(view, data)) { closeView(view, "INVALID_VIEW"); return false; }
            long newest = view.latest != null ? view.latest.stateRevision()
                    : view.inFlight != null ? view.inFlight.stateRevision() : view.acknowledgedRevision;
            if (data.stateRevision() < newest) { closeView(view, "INVALID_VIEW"); return false; }
            if (data.stateRevision() == newest) {
                ViewData previous = view.latest != null ? view.latest : view.inFlight;
                if (previous != null && !previous.equals(data)) { closeView(view, "INVALID_VIEW"); return false; }
                return true;
            }
            int bytes = view.acknowledgedRevision < 0 && view.inFlight == null ? snapshotBytes(data) : deltaBytes(data);
            if (bytes > ManagementProtocol.VIEW_BYTES) { closeView(view, "VIEW_LIMIT"); return false; }
            clearLatest(view);
            if (bufferedViewBytes() + bytes > ManagementProtocol.VIEW_BUFFER_BYTES) { closeView(view, "BUFFER_LIMIT"); return false; }
            view.latest = data;
            view.latestBytes = bytes;
            bufferedBytes += bytes;
            return true;
        } catch (SecurityException exception) {
            closeView(view, "ACCESS_DENIED");
        } catch (IllegalArgumentException | IllegalStateException | NullPointerException exception) {
            closeView(view, "VIEW_LIMIT".equals(exception.getMessage()) ? "VIEW_LIMIT" : "INVALID_VIEW");
        }
        return false;
    }
    private static boolean valid(View view, ViewData data) {
        Subscription subscription = view.subscription;
        if (!subscription.colonyId().equals(data.colonyId()) || subscription.type() != data.type()
                || subscription.page() != data.page() || view.authorityRevision != data.authorityRevision()
                || !(data.rank().equals("owner") || data.rank().equals("manager") || data.rank().equals("viewer"))) return false;
        long remaining = (long) data.totalRows() - (long) data.page() * ManagementProtocol.PAGE_ROWS;
        if (data.rows().size() != Math.min(ManagementProtocol.PAGE_ROWS, Math.max(0, remaining))) return false;
        for (int index = 0; index < data.rows().size(); index++)
            for (int previous = 0; previous < index; previous++)
                if (data.rows().get(index).id().equals(data.rows().get(previous).id())) return false;
        return true;
    }
    private void send(View view) {
        ViewData data = view.latest;
        view.inFlight = data;
        view.inFlightBytes = view.latestBytes;
        view.latest = null;
        view.latestBytes = 0;
        view.sentAt = tick;
        if (view.acknowledgedRevision < 0) transport.sendSnapshot(view.subscription.subscriptionId(), data);
        else transport.sendDelta(view.subscription.subscriptionId(), view.acknowledgedRevision, data);
    }
    private void clearLatest(View view) {
        bufferedBytes -= view.latestBytes;
        view.latestBytes = 0;
        view.latest = null;
    }
    private void purge(View view) {
        backend.cancelView(view.subscription);
        clearLatest(view);
        bufferedBytes -= view.inFlightBytes;
        view.inFlightBytes = 0;
        view.inFlight = null;
    }
    private void removeView(UUID id) {
        View view = views.remove(id);
        if (view != null) purge(view);
    }
    private void closeView(View view, String reason) {
        removeView(view.subscription.subscriptionId());
        notifyClosed(view.subscription.subscriptionId(), reason);
    }
    private void notifyClosed(UUID id, String reason) {
        if (closed) return;
        if (transport.writable()) transport.closeView(id, reason);
        else {
            closedViews.put(id, reason);
            while (closedViews.size() > ManagementProtocol.SUBSCRIPTIONS) closedViews.remove(closedViews.keySet().iterator().next());
        }
    }
    private void flushClosed() {
        Iterator<Map.Entry<UUID, String>> iterator = closedViews.entrySet().iterator();
        while (iterator.hasNext() && transport.writable()) {
            Map.Entry<UUID, String> entry = iterator.next();
            transport.closeView(entry.getKey(), entry.getValue());
            iterator.remove();
        }
    }
    public void close() {
        closed = true;
        for (View view : views.values()) purge(view);
        views.clear();
        results.clear();
        closedViews.clear();
        java.util.Arrays.fill(iteration, null);
        bufferedBytes = 0;
        cachedBytes = 0;
    }

    /** Exact sizes for ManagementPayloads' fixed-width primitives and unsigned VarInt UTF-8 strings. */
    public static int utf8Bytes(String value) {
        int bytes = 0;
        for (int index = 0; index < value.length(); index++) {
            char character = value.charAt(index);
            if (character < 0x80) bytes++;
            else if (character < 0x800) bytes += 2;
            else if (Character.isHighSurrogate(character) && index + 1 < value.length()
                    && Character.isLowSurrogate(value.charAt(index + 1))) { bytes += 4; index++; }
            else if (Character.isSurrogate(character)) bytes++;
            else bytes += 3;
        }
        return bytes;
    }
    public static int stringBytes(String value) { int bytes = utf8Bytes(value); return varIntBytes(bytes) + bytes; }
    private static int varIntBytes(int value) {
        int bytes = 1;
        while ((value & ~0x7f) != 0) { bytes++; value >>>= 7; }
        return bytes;
    }
    private static int optionalUuidBytes(UUID value) { return value == null ? 1 : 17; }
    private static int positionBytes(WorldPosition value) { return stringBytes(value.dimension()) + 12; }
    public static int resultBytes(Result result) {
        return 8 + 1 + stringBytes(result.reason()) + optionalUuidBytes(result.objectId()) + 8;
    }
    public static int commandBytes(Command command) {
        int bodyBytes = switch (command.body()) {
            case CreateColony body -> stringBytes(body.name()) + stringBytes(body.territory().dimension()) + 16;
            case AssignProfession body -> 16 + stringBytes(body.professionId());
            case AssignWorkplace body -> 32;
            case Build body -> stringBytes(body.blueprintId()) + positionBytes(body.origin()) + 4;
            case CancelWork body -> 16;
            case PrioritizeWork body -> 20;
            case SetMember body -> 16 + stringBytes(body.rank());
            case SetOwner body -> 16;
            case RegisterStorage body -> positionBytes(body.position()) + stringBytes(body.role());
            case RegisterWorkshop body -> positionBytes(body.table()) + positionBytes(body.inventory());
        };
        return 16 + 8 + optionalUuidBytes(command.colonyId()) + 8 + stringBytes(command.body().typeId()) + bodyBytes;
    }
    public static int viewBytes(ViewData data) {
        int bytes = 16 + stringBytes(data.colonyName()) + stringBytes(data.rank()) + 16 + 1 + 8 + varIntBytes(data.rows().size());
        for (Row row : data.rows()) bytes += 24 + stringBytes(row.name()) + stringBytes(row.state())
                + stringBytes(row.reason()) + stringBytes(row.detail()) + optionalUuidBytes(row.relatedId());
        bytes += varIntBytes(data.professions().size()) + varIntBytes(data.blueprints().size());
        for (String profession : data.professions()) bytes += stringBytes(profession);
        for (String blueprint : data.blueprints()) bytes += stringBytes(blueprint);
        return bytes;
    }
    public static int snapshotBytes(ViewData data) { return 16 + viewBytes(data); }
    public static int deltaBytes(ViewData data) { return 24 + viewBytes(data); }
}
