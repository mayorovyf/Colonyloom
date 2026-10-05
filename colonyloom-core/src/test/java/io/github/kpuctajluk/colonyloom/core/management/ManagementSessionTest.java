package io.github.kpuctajluk.colonyloom.core.management;

import io.github.kpuctajluk.colonyloom.core.management.ManagementProtocol.*;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.function.Function;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

final class ManagementSessionTest {
    private static final UUID COLONY = new UUID(0, 1);
    private static final UUID TARGET = new UUID(0, 2);
    private static final class Backend implements ManagementSession.Backend {
        boolean allowed = true;
        long authority = 1;
        long targetRevision;
        long stateRevision;
        int executions;
        Status status = Status.ACCEPTED;
        String reason = "OK";
        boolean reject;
        RuntimeException executionFailure;
        boolean viewReady = true;
        int pageBudget = Integer.MAX_VALUE;
        int preparations;
        int cancellations;
        Function<Subscription, ViewData> builder = this::normal;
        public ManagementSession.Authority authorize(UUID colonyId) { return new ManagementSession.Authority(allowed, authority); }
        public long targetRevision(Command command) { return targetRevision; }
        public Result execute(Command command) {
            executions++;
            if (executionFailure != null) throw executionFailure;
            if (reject) throw new IllegalArgumentException("Invalid target");
            return new Result(command.sequence(), status, reason, TARGET, targetRevision);
        }
        public boolean viewReady(Subscription subscription) {
            preparations++;
            if (!viewReady || pageBudget == 0) return false;
            pageBudget--;
            return true;
        }
        public ViewData view(Subscription subscription) { return builder.apply(subscription); }
        ViewData normal(Subscription subscription) {
            return data(subscription, authority, stateRevision, "manager", List.of(), 0, List.of());
        }
        public void cancelView(Subscription subscription) { cancellations++; }
    }
    private record Delivery(UUID subscriptionId, long baseRevision, ViewData data) {}
    private static final class Transport implements ManagementSession.Transport {
        boolean writable = true;
        final List<Result> results = new ArrayList<>();
        final List<Delivery> pages = new ArrayList<>();
        final Map<UUID, String> closed = new HashMap<>();
        final List<UUID> closeDeliveries = new ArrayList<>();
        public boolean writable() { return writable; }
        public void sendResult(Result result) { assertTrue(writable); results.add(result); }
        public void sendSnapshot(UUID id, ViewData data) { assertTrue(writable); pages.add(new Delivery(id, -1, data)); }
        public void sendDelta(UUID id, long base, ViewData data) { assertTrue(writable); pages.add(new Delivery(id, base, data)); }
        public void closeView(UUID id, String reason) { assertTrue(writable); closeDeliveries.add(id); closed.put(id, reason); }
    }
    private record Fixture(Backend backend, Transport transport, ManagementSession session) {}
    private Fixture fixture() {
        Backend backend = new Backend();
        Transport transport = new Transport();
        return new Fixture(backend, transport, new ManagementSession(backend, transport));
    }
    private static Command command(ManagementSession session, long sequence, long revision) {
        return new Command(session.sessionId(), sequence, COLONY, revision, new CancelWork(TARGET));
    }
    private static Subscription subscription(ManagementSession session, UUID id) {
        return new Subscription(session.sessionId(), id, COLONY, ViewType.WORK, 0, false);
    }
    private static ViewData data(Subscription subscription, long authority, long state, String rank,
                                 List<Row> rows, int total, List<String> professions) {
        return new ViewData(subscription.colonyId(), "Colony", rank, authority, state, subscription.type(),
                subscription.page(), total, rows, professions, List.of());
    }
    private static Row row(UUID id, String detail) { return new Row(id, 0, "", "", "", detail, null); }

    @Test void duplicateUsesOriginalResultAndNeverExecutesChangedBody() {
        Fixture f = fixture();
        Result first = f.session.command(command(f.session, 0, 0));
        Command changed = new Command(f.session.sessionId(), 0, COLONY, 999, new PrioritizeWork(TARGET, 10));
        assertEquals(first, f.session.command(changed));
        assertEquals(1, f.backend.executions);
        assertEquals(1, f.session.nextSequence());
        assertEquals(List.of(first, first), f.transport.results);
    }
    @Test void rejectedAndStaleConsumeSequenceButGapAndForeignSessionDoNot() {
        Fixture f = fixture();
        assertEquals("SEQUENCE_GAP", f.session.command(command(f.session, 1, 0)).reason());
        Command foreign = new Command(UUID.randomUUID(), 0, COLONY, 0, new CancelWork(TARGET));
        assertEquals("NO_SESSION", f.session.command(foreign).reason());
        assertEquals(0, f.session.nextSequence());
        f.backend.allowed = false;
        Result denied = f.session.command(command(f.session, 0, 0));
        assertEquals(Status.REJECTED, denied.status());
        assertEquals(denied, f.session.command(command(f.session, 0, 0)));
        f.backend.allowed = true;
        f.backend.targetRevision = 7;
        Result stale = f.session.command(command(f.session, 1, 0));
        assertEquals(Status.STALE, stale.status());
        assertEquals(7, stale.revision());
        f.backend.reject = true;
        assertEquals(Status.REJECTED, f.session.command(command(f.session, 2, 7)).status());
        assertEquals(3, f.session.nextSequence());
        assertEquals(1, f.backend.executions);
    }
    @Test void rollingTwentyTickCommandBudgetRejectsBoundaryBurstWithoutConsumingExecution() {
        Fixture f = fixture();
        f.session.tick(19);
        for (int index = 0; index < 20; index++) assertEquals(Status.ACCEPTED, f.session.command(command(f.session, index, 0)).status());
        assertEquals("RATE_LIMIT", f.session.command(command(f.session, 20, 0)).reason());
        f.session.tick(20);
        assertEquals("RATE_LIMIT", f.session.command(command(f.session, 21, 0)).reason());
        f.session.tick(38);
        assertEquals("RATE_LIMIT", f.session.command(command(f.session, 22, 0)).reason());
        f.session.tick(39);
        assertEquals(Status.ACCEPTED, f.session.command(command(f.session, 23, 0)).status());
        assertEquals(21, f.backend.executions);
        assertEquals(24, f.session.nextSequence());
    }
    @Test void membershipAndFoundingLimitsRemainConsumerVisibleWithoutLeakingOtherFailures() {
        Fixture f = fixture();
        var reasons = List.of("MEMBER_LIMIT", "FOUNDING_VALIDATION_LIMIT", "private implementation detail");
        for (int index = 0; index < reasons.size(); index++) {
            String reason = reasons.get(index);
            f.backend.executionFailure = new IllegalStateException(reason);
            Result result = f.session.command(command(f.session, index, 0));
            assertEquals(Status.REJECTED, result.status());
            assertEquals(index < 2 ? reason : "INVALID_COMMAND", result.reason());
            assertEquals(result, f.transport.results.getLast());
        }
    }
    @Test void resultCacheAndUnwritablePendingResultsStayBoundedAndExpire() {
        Fixture f = fixture();
        f.transport.writable = false;
        f.backend.reason = "я".repeat(128);
        for (int index = 0; index < 160; index++) {
            f.session.tick(index * 20L);
            f.session.command(command(f.session, index, 0));
            assertTrue(f.session.cachedResultCount() <= ManagementProtocol.RESULT_COUNT);
            assertTrue(f.session.cachedResultBytes() <= ManagementProtocol.RESULT_BYTES);
        }
        assertEquals(128, f.session.cachedResultCount());
        assertEquals(Status.DUPLICATE_EXPIRED, f.session.command(command(f.session, 0, 0)).status());
        assertEquals(160, f.backend.executions);
        assertTrue(f.transport.results.isEmpty());
        f.transport.writable = true;
        f.session.tick(3200);
        assertEquals(128, f.transport.results.size());
        assertEquals(32, f.transport.results.getFirst().sequence());
        f.session.tick(3201);
        assertEquals(128, f.transport.results.size());
    }
    @Test void reconnectRejectsOldUnknownCommandWithoutReplayOrSharedCache() {
        Fixture f = fixture();
        f.transport.writable = false;
        Command unknown = command(f.session, 0, 0);
        f.session.command(unknown);
        f.session.close();
        ManagementSession reconnected = new ManagementSession(f.backend, f.transport);
        assertNotEquals(f.session.sessionId(), reconnected.sessionId());
        assertEquals("NO_SESSION", reconnected.command(unknown).reason());
        assertEquals(1, f.backend.executions);
        assertEquals(0, reconnected.nextSequence());
        assertEquals(0, reconnected.cachedResultCount());
        assertEquals(0, f.session.cachedResultBytes());
    }
    @Test void fourSubscriptionsSendOnlyAtWritableTenTickIntervals() {
        Fixture f = fixture();
        List<UUID> ids = new ArrayList<>();
        for (int index = 0; index < 5; index++) {
            UUID id = UUID.randomUUID();
            ids.add(id);
            f.session.subscribe(subscription(f.session, id));
        }
        assertEquals(4, f.session.subscriptionCount());
        assertEquals("SUBSCRIPTION_LIMIT", f.transport.closed.get(ids.get(4)));
        f.transport.writable = false;
        f.session.tick(10);
        assertTrue(f.transport.pages.isEmpty());
        f.transport.writable = true;
        f.session.tick(11);
        assertTrue(f.transport.pages.isEmpty());
        f.session.tick(20);
        assertEquals(4, f.transport.pages.size());
        f.session.tick(20);
        assertEquals(4, f.transport.pages.size());
        for (Delivery page : f.transport.pages) assertEquals(-1, page.baseRevision());
    }
    @Test void scarceSharedPageBudgetRotatesAcrossAllFourSubscriptions() {
        Fixture f = fixture();
        f.backend.viewReady = false;
        List<UUID> ids = new ArrayList<>();
        for (int index = 0; index < 4; index++) {
            UUID id = UUID.randomUUID();
            ids.add(id);
            f.session.subscribe(subscription(f.session, id));
        }
        f.backend.viewReady = true;
        for (int index = 0; index < 4; index++) {
            f.backend.pageBudget = 1;
            f.session.tick(index * 10L);
            assertEquals(index + 1, f.transport.pages.size());
            Delivery delivered = f.transport.pages.get(index);
            assertEquals(ids.get(index), delivered.subscriptionId());
            f.session.acknowledge(f.session.sessionId(), delivered.subscriptionId(), 1, 0);
        }
        assertEquals(4, f.session.subscriptionCount());
        assertEquals(0, f.session.bufferedViewBytes());
    }
    @Test void ackGateRetainsOnlyLatestAndRejectsUnrelatedAck() {
        Fixture f = fixture();
        UUID id = UUID.randomUUID();
        f.session.subscribe(subscription(f.session, id));
        f.session.tick(0);
        int firstBytes = f.session.bufferedViewBytes();
        for (int index = 1; index <= 5; index++) {
            f.backend.stateRevision = index;
            f.session.tick(index * 10L);
            assertEquals(1, f.transport.pages.size());
            assertTrue(f.session.bufferedViewBytes() <= firstBytes * 2 + 8);
        }
        f.session.acknowledge(UUID.randomUUID(), id, 1, 0);
        f.session.acknowledge(f.session.sessionId(), id, 2, 0);
        f.session.acknowledge(f.session.sessionId(), id, 1, 1);
        f.session.tick(60);
        assertEquals(1, f.transport.pages.size());
        f.session.acknowledge(f.session.sessionId(), id, 1, 0);
        f.session.tick(61);
        assertEquals(1, f.transport.pages.size());
        f.backend.stateRevision = 7;
        f.session.tick(70);
        assertEquals(2, f.transport.pages.size());
        assertEquals(0, f.transport.pages.get(1).baseRevision());
        f.session.acknowledge(f.session.sessionId(), id, 1, f.transport.pages.get(1).data().stateRevision());
        assertEquals(0, f.session.bufferedViewBytes());
    }
    @Test void resyncAndUnsubscribeReleaseOldFlightAndRejectOldSessionControl() {
        Fixture f = fixture();
        UUID id = UUID.randomUUID();
        f.session.subscribe(subscription(f.session, id));
        f.session.tick(0);
        f.backend.stateRevision = 1;
        f.session.tick(10);
        f.session.unsubscribe(UUID.randomUUID(), id);
        assertEquals(1, f.session.subscriptionCount());
        f.session.subscribe(new Subscription(f.session.sessionId(), id, COLONY, ViewType.WORK, 0, true));
        f.session.tick(20);
        assertEquals(2, f.transport.pages.size());
        assertEquals(-1, f.transport.pages.get(1).baseRevision());
        assertEquals(1, f.transport.pages.get(1).data().stateRevision());
        f.session.unsubscribe(f.session.sessionId(), id);
        assertEquals(0, f.session.subscriptionCount());
        assertEquals(0, f.session.bufferedViewBytes());
        f.session.tick(30);
        assertEquals(2, f.transport.pages.size());
    }
    @Test void subscriptionAndResyncBurstIsRollingBoundedAndPurgesRejectedLiveView() {
        Fixture f = fixture();
        UUID id = UUID.randomUUID();
        f.session.tick(19);
        f.session.subscribe(subscription(f.session, id));
        for (int index = 1; index < ManagementProtocol.SUBSCRIPTIONS_PER_SECOND; index++)
            f.session.subscribe(new Subscription(f.session.sessionId(), id, COLONY, ViewType.WORK, 0, true));
        assertEquals(ManagementProtocol.SUBSCRIPTIONS_PER_SECOND, f.backend.preparations);
        assertTrue(f.session.bufferedViewBytes() > 0);
        f.session.subscribe(new Subscription(f.session.sessionId(), id, COLONY, ViewType.WORK, 0, true));
        assertEquals("RATE_LIMIT", f.transport.closed.get(id));
        assertEquals(0, f.session.subscriptionCount());
        assertEquals(0, f.session.bufferedViewBytes());
        for (int index = 0; index < 1000; index++) f.session.subscribe(subscription(f.session, id));
        assertEquals(1, f.transport.closeDeliveries.size());
        assertEquals(ManagementProtocol.SUBSCRIPTIONS_PER_SECOND, f.backend.preparations);
        f.session.tick(38);
        f.session.subscribe(subscription(f.session, id));
        assertEquals(0, f.session.subscriptionCount());
        f.session.tick(39);
        f.session.subscribe(subscription(f.session, id));
        assertEquals(1, f.session.subscriptionCount());
        assertEquals(ManagementProtocol.SUBSCRIPTIONS_PER_SECOND + 1, f.backend.preparations);
    }
    @Test void unrelatedRejectedIdsCannotFloodRepliesOrRetainRevokedViewsAtIngressLimit() {
        Fixture f = fixture();
        UUID id = UUID.randomUUID();
        f.session.subscribe(subscription(f.session, id));
        f.session.tick(0);
        f.transport.writable = false;
        f.backend.stateRevision++;
        f.session.tick(1);
        for (int index = 0; index < 1000; index++) f.session.subscribe(subscription(f.session, UUID.randomUUID()));
        f.backend.allowed = false;
        f.backend.authority++;
        f.session.subscribe(subscription(f.session, UUID.randomUUID()));
        assertEquals(0, f.session.subscriptionCount());
        assertEquals(0, f.session.bufferedViewBytes());
        int preparations = f.backend.preparations;
        f.transport.writable = true;
        for (int index = 0; index < 1000; index++) f.session.subscribe(subscription(f.session, UUID.randomUUID()));
        f.session.tick(2);
        assertTrue(f.transport.closeDeliveries.size() <= ManagementProtocol.VIEW_CLOSES_PER_SECOND);
        f.session.tick(22);
        assertEquals("ACCESS_DENIED", f.transport.closed.get(id));
        assertEquals(1, f.transport.pages.size());
        assertEquals(preparations, f.backend.preparations);
    }
    @Test void revokedLiveViewCloseSurvivesForeignSessionFloodAndBackpressure() {
        Fixture f = fixture();
        UUID id = UUID.randomUUID();
        f.session.subscribe(subscription(f.session, id));
        f.transport.writable = false;
        f.backend.allowed = false;
        for (int index = 0; index < 1000; index++)
            f.session.subscribe(new Subscription(UUID.randomUUID(), UUID.randomUUID(), COLONY, ViewType.WORK, 0, true));
        assertEquals(0, f.session.subscriptionCount());
        assertEquals(0, f.session.bufferedViewBytes());
        f.backend.allowed = true;
        UUID pending = UUID.randomUUID();
        f.session.subscribe(subscription(f.session, pending));
        assertEquals(0, f.session.subscriptionCount());
        f.transport.writable = true;
        f.session.tick(1);
        assertEquals(List.of(id), f.transport.closeDeliveries);
        assertEquals("ACCESS_DENIED", f.transport.closed.get(id));
        f.session.tick(20);
        f.session.subscribe(subscription(f.session, pending));
        assertEquals(1, f.session.subscriptionCount());
    }
    @Test void rejectedSubscriptionRepliesAreDeduplicatedAndRollOverAtTwentyTicks() {
        Fixture f = fixture();
        f.backend.allowed = false;
        UUID id = UUID.randomUUID();
        for (int index = 0; index < 1000; index++) f.session.subscribe(subscription(f.session, id));
        // Authorization refusals and ingress refusals are distinct reasons, each sent at most once.
        assertEquals(2, f.transport.closeDeliveries.size());
        assertEquals(0, f.backend.preparations);
        f.session.tick(19);
        f.session.subscribe(subscription(f.session, id));
        assertEquals(2, f.transport.closeDeliveries.size());
        f.session.tick(20);
        f.session.subscribe(subscription(f.session, id));
        assertEquals(3, f.transport.closeDeliveries.size());
        assertEquals("ACCESS_DENIED", f.transport.closed.get(id));
    }
    @Test void malformedDecodedValuesCannotEnterStateMachine() {
        Fixture f = fixture();
        UUID id = UUID.randomUUID();
        assertThrows(IllegalArgumentException.class, () -> new Subscription(f.session.sessionId(), id, COLONY, ViewType.WORK, -1, false));
        assertThrows(IllegalArgumentException.class, () -> new Command(f.session.sessionId(), -1, COLONY, 0, new CancelWork(TARGET)));
        assertThrows(IllegalArgumentException.class, () -> new Command(f.session.sessionId(), 0, COLONY, -1, new CancelWork(TARGET)));
        assertThrows(IllegalArgumentException.class, () -> row(TARGET, "я".repeat(129)));
        Subscription sub = subscription(f.session, id);
        assertThrows(IllegalArgumentException.class, () -> data(sub, -1, 0, "manager", List.of(), 0, List.of()));
        List<Row> tooManyRows = new ArrayList<>();
        for (int index = 0; index < 51; index++) tooManyRows.add(row(new UUID(1, index), ""));
        assertThrows(IllegalArgumentException.class, () -> data(sub, 1, 0, "manager", tooManyRows, 51, List.of()));
        assertEquals(0, f.session.nextSequence());
        assertEquals(0, f.session.subscriptionCount());
        assertEquals(0, f.backend.executions);
    }
    @Test void missingAckClosesAtExactlyOneHundredTicksAndDropsAllBuffers() {
        Fixture f = fixture();
        UUID id = UUID.randomUUID();
        f.session.subscribe(subscription(f.session, id));
        f.session.tick(10);
        for (int tick = 20; tick <= 100; tick += 10) {
            f.backend.stateRevision++;
            f.session.tick(tick);
            assertEquals(1, f.session.subscriptionCount());
            assertEquals(1, f.transport.pages.size());
            assertTrue(f.session.bufferedViewBytes() <= ManagementProtocol.VIEW_BUFFER_BYTES);
        }
        f.transport.writable = false;
        f.session.tick(110);
        assertEquals(0, f.session.subscriptionCount());
        assertEquals(0, f.session.bufferedViewBytes());
        assertFalse(f.transport.closed.containsKey(id));
        f.transport.writable = true;
        f.session.tick(111);
        assertEquals("ACK_TIMEOUT", f.transport.closed.get(id));
        assertEquals(1, f.transport.pages.size());
    }
    @Test void denialPurgesPreparedAndCoalescedViewsAndPendingPrivateResult() {
        Fixture f = fixture();
        UUID sent = UUID.randomUUID(), prepared = UUID.randomUUID();
        f.session.subscribe(subscription(f.session, sent));
        f.session.tick(0);
        f.transport.writable = false;
        f.backend.stateRevision = 1;
        f.session.tick(10);
        f.session.subscribe(subscription(f.session, prepared));
        f.session.command(command(f.session, 0, 0));
        f.backend.allowed = false;
        f.backend.authority++;
        f.session.tick(11);
        assertEquals(0, f.session.subscriptionCount());
        assertEquals(0, f.session.bufferedViewBytes());
        assertEquals(0, f.session.cachedResultCount());
        f.transport.writable = true;
        f.session.tick(20);
        assertEquals(1, f.transport.pages.size());
        assertTrue(f.transport.results.isEmpty());
        assertEquals("ACCESS_DENIED", f.transport.closed.get(sent));
        assertEquals("ACCESS_DENIED", f.transport.closed.get(prepared));
        assertEquals(Status.DUPLICATE_EXPIRED, f.session.command(command(f.session, 0, 0)).status());
        assertEquals(1, f.backend.executions);
    }
    @Test void authorityChangeDropsOldAckAndForcesSnapshotWithCurrentRank() {
        Fixture f = fixture();
        UUID id = UUID.randomUUID();
        f.session.subscribe(subscription(f.session, id));
        f.session.tick(0);
        f.backend.stateRevision = 1;
        f.session.tick(10);
        f.backend.authority = 2;
        f.backend.stateRevision = 0;
        f.backend.builder = s -> data(s, 2, 0, "viewer", List.of(), 0, List.of());
        f.session.tick(11);
        f.session.acknowledge(f.session.sessionId(), id, 1, 0);
        f.session.tick(20);
        assertEquals(2, f.transport.pages.size());
        Delivery replacement = f.transport.pages.get(1);
        assertEquals(-1, replacement.baseRevision());
        assertEquals(2, replacement.data().authorityRevision());
        assertEquals("viewer", replacement.data().rank());
    }
    @Test void revocationInsideViewPreparationNeverSendsOldAuthority() {
        Fixture f = fixture();
        f.backend.builder = subscription -> {
            ViewData old = f.backend.normal(subscription);
            f.backend.allowed = false;
            f.backend.authority++;
            return old;
        };
        UUID id = UUID.randomUUID();
        f.session.subscribe(subscription(f.session, id));
        f.session.tick(0);
        assertTrue(f.transport.pages.isEmpty());
        assertEquals(0, f.session.bufferedViewBytes());
        assertEquals("ACCESS_DENIED", f.transport.closed.get(id));
    }
    @Test void malformedBackendPagesAreNeverAccepted() {
        List<Function<Subscription, ViewData>> invalid = List.of(
                s -> new ViewData(UUID.randomUUID(), "Colony", "manager", 1, 0, s.type(), 0, 0, List.of(), List.of(), List.of()),
                s -> data(s, 2, 0, "manager", List.of(), 0, List.of()),
                s -> data(s, 1, 0, "operator", List.of(), 0, List.of()),
                s -> data(s, 1, 0, "manager", List.of(), 1, List.of()),
                s -> data(s, 1, 0, "manager", List.of(row(TARGET, "a"), row(TARGET, "b")), 2, List.of()),
                s -> null);
        for (Function<Subscription, ViewData> builder : invalid) {
            Fixture f = fixture();
            f.backend.builder = builder;
            UUID id = UUID.randomUUID();
            f.session.subscribe(subscription(f.session, id));
            f.session.tick(0);
            assertTrue(f.transport.pages.isEmpty());
            assertEquals("INVALID_VIEW", f.transport.closed.get(id));
            assertEquals(0, f.session.bufferedViewBytes());
        }
    }
    @Test void sameRevisionMutationAndRegressingStateCloseRatherThanSend() {
        for (boolean regression : List.of(false, true)) {
            Fixture f = fixture();
            f.backend.stateRevision = 2;
            UUID id = UUID.randomUUID();
            f.session.subscribe(subscription(f.session, id));
            f.session.tick(0);
            f.backend.builder = s -> data(s, 1, regression ? 1 : 2, "manager", List.of(row(TARGET, "changed")), 1, List.of());
            f.session.tick(10);
            assertEquals(1, f.transport.pages.size());
            assertEquals("INVALID_VIEW", f.transport.closed.get(id));
            assertEquals(0, f.session.bufferedViewBytes());
        }
    }
    @Test void exhaustedGlobalRowBudgetDefersBuildWithoutClosingAndStillChecksTimeoutAndAuthority() {
        Fixture f = fixture();
        f.backend.viewReady = false;
        f.backend.builder = s -> { fail("Deferred page must not be generated"); return null; };
        UUID id = UUID.randomUUID();
        f.session.subscribe(subscription(f.session, id));
        f.session.tick(0);
        assertEquals(1, f.session.subscriptionCount());
        assertEquals(0, f.session.bufferedViewBytes());
        assertTrue(f.transport.pages.isEmpty());
        f.backend.viewReady = true;
        f.backend.builder = f.backend::normal;
        f.session.tick(10);
        assertEquals(1, f.transport.pages.size());
        f.backend.viewReady = false;
        f.backend.builder = s -> { fail("Deferred page must not be generated"); return null; };
        f.session.tick(110);
        assertEquals("ACK_TIMEOUT", f.transport.closed.get(id));
        UUID revoked = UUID.randomUUID();
        f.session.subscribe(subscription(f.session, revoked));
        f.backend.allowed = false;
        f.session.tick(111);
        assertEquals("ACCESS_DENIED", f.transport.closed.get(revoked));
        assertEquals(0, f.session.subscriptionCount());
    }
    @Test void pendingCreationResultIsPurgedWhenCreatorLosesReadAccess() {
        Fixture f = fixture();
        f.transport.writable = false;
        var create = new Command(f.session.sessionId(), 0, null, 0,
                new CreateColony("Created", new io.github.kpuctajluk.colonyloom.core.colony.Territory(
                        "minecraft:overworld", 0, 0, 15, 15)));
        assertEquals(Status.ACCEPTED, f.session.command(create).status());
        assertEquals(1, f.backend.executions);
        assertTrue(f.transport.results.isEmpty());
        f.backend.allowed = false;
        f.backend.authority++;
        f.transport.writable = true;
        f.session.tick(0);
        assertTrue(f.transport.results.isEmpty());
        assertEquals(Status.DUPLICATE_EXPIRED, f.session.command(create).status());
        assertEquals(1, f.backend.executions);
    }

    @Test void exactUtf8AndPageSizeBoundaryIncludeEnvelopeAndVarInts() {
        for (String value : List.of("ASCII", "я".repeat(64), "😀".repeat(64), "\ud800", "a".repeat(128))) {
            int utf8 = value.getBytes(StandardCharsets.UTF_8).length;
            assertEquals(utf8, ManagementSession.utf8Bytes(value));
            assertEquals(utf8 + (utf8 < 128 ? 1 : 2), ManagementSession.stringBytes(value));
        }
        Fixture f = fixture();
        UUID id = UUID.randomUUID();
        Subscription sub = subscription(f.session, id);
        List<Row> rows = new ArrayList<>();
        for (int index = 0; index < 50; index++) rows.add(row(new UUID(1, index), ""));
        List<String> catalog = new ArrayList<>();
        for (int index = 0; index < 64; index++) catalog.add("x".repeat(256));
        ViewData base = data(sub, 1, 0, "manager", rows, 50, catalog);
        int missing = ManagementProtocol.VIEW_BYTES - ManagementSession.snapshotBytes(base);
        for (int index = 0; missing > 0; index++) {
            // A 128+ byte string has a two-byte prefix; account for the extra prefix byte.
            int extra = Math.min(257, missing);
            if (extra == 128) extra = 127;
            String detail = "x".repeat(extra > 128 ? extra - 1 : extra);
            int rowIndex = index / 2;
            Row previous = rows.get(rowIndex);
            rows.set(rowIndex, new Row(previous.id(), 0, index % 2 == 0 ? detail : previous.name(),
                    "", "", index % 2 == 1 ? detail : previous.detail(), null));
            missing -= extra;
        }
        ViewData exact = data(sub, 1, 0, "manager", rows, 50, catalog);
        assertEquals(ManagementProtocol.VIEW_BYTES, ManagementSession.snapshotBytes(exact));
        assertEquals(ManagementProtocol.VIEW_BYTES + 8, ManagementSession.deltaBytes(exact));
        f.backend.builder = s -> exact;
        f.session.subscribe(sub);
        f.session.tick(0);
        assertEquals(1, f.transport.pages.size());
        assertEquals(ManagementProtocol.VIEW_BYTES, f.session.bufferedViewBytes());
        Fixture oversized = fixture();
        List<Row> largerRows = new ArrayList<>(rows);
        largerRows.set(49, row(new UUID(1, 49), "x"));
        oversized.backend.builder = s -> data(s, 1, 0, "manager", largerRows, 50, catalog);
        UUID oversizedId = UUID.randomUUID();
        oversized.session.subscribe(subscription(oversized.session, oversizedId));
        oversized.session.tick(0);
        assertTrue(oversized.transport.pages.isEmpty());
        assertEquals("VIEW_LIMIT", oversized.transport.closed.get(oversizedId));
    }
}
