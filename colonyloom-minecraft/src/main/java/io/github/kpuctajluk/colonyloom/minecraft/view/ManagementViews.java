package io.github.kpuctajluk.colonyloom.minecraft.view;

import io.github.kpuctajluk.colonyloom.core.colony.ColonyRegistry;
import io.github.kpuctajluk.colonyloom.core.colony.ColonyRuntime;
import io.github.kpuctajluk.colonyloom.core.colony.MemberRank;
import io.github.kpuctajluk.colonyloom.core.colony.WorldPosition;
import io.github.kpuctajluk.colonyloom.core.config.SimulationLimits.Budget;
import io.github.kpuctajluk.colonyloom.core.config.SimulationLimits.Resource;
import io.github.kpuctajluk.colonyloom.core.management.ManagementProtocol;
import io.github.kpuctajluk.colonyloom.core.management.ManagementProtocol.*;
import io.github.kpuctajluk.colonyloom.core.management.ManagementSession;
import io.github.kpuctajluk.colonyloom.core.metrics.RuntimeMetrics.Timer;
import io.github.kpuctajluk.colonyloom.core.scheduler.AdmissionLedger.Lane;
import io.github.kpuctajluk.colonyloom.core.scheduler.GlobalWorkBudgets;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Iterator;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;

/** Read-only pages: never loads chunks or exposes another colony's usage/state. */
public final class ManagementViews {
    private static final Resource[] RESOURCES = Resource.values();
    private ManagementViews() {}

    /**
     * A weak snapshot over the live sources present when preparation starts. Scalar insertion
     * cutoffs exclude later additions; removed entries disappear without restarting the sweep.
     * Only the requested fifty rows are retained, never a full mutable source collection.
     */
    public static final class Preparation {
        private enum Phase { SUMMARY, LIMITS, MEMBERS, STOCK, CITIZENS, WORKSHOPS, REGISTRATIONS, WORK, COMPLETE }
        private final ColonyRegistry registry;
        private final UUID actor;
        private final Subscription subscription;
        private final List<String> professions, blueprints;
        private final long authorityRevision;
        private final long citizenCutoff, workshopCutoff, registrationCutoff, workCutoff, stockCutoff;
        private final int first;
        private final ArrayList<Row> rows = new ArrayList<>(ManagementProtocol.PAGE_ROWS);
        // ColonyRuntime membership is immutable. Its iterator cannot be structurally invalidated.
        private Iterator<Map.Entry<UUID, MemberRank>> members;
        private Phase phase;
        private Long cursor;
        private int resourceIndex, total;
        // Reserve the maximum colony-name header and delta envelope; row count remains one VarInt.
        private int retainedBytes = 334;
        private long stateRevision;
        private boolean cancelled;
        private ViewData completed;

        public Preparation(ColonyRegistry registry, UUID actor, Subscription subscription,
                List<String> professions, List<String> blueprints, long stateRevision) {
            registry.requireOwner();
            if (stateRevision < 0) throw new IllegalArgumentException("INVALID_REVISION");
            this.registry = registry;
            this.actor = actor;
            this.subscription = subscription;
            if (professions.size() > 64 || blueprints.size() > 64) throw new IllegalArgumentException("VIEW_LIMIT");
            for (String profession : professions) retainedBytes += ManagementSession.stringBytes(ManagementProtocol.text(profession));
            for (String blueprint : blueprints) retainedBytes += ManagementSession.stringBytes(ManagementProtocol.text(blueprint));
            if (retainedBytes > ManagementProtocol.VIEW_BYTES) throw new IllegalArgumentException("VIEW_LIMIT");
            this.professions = List.copyOf(professions);
            this.blueprints = List.copyOf(blueprints);
            var colony = registry.colony(subscription.colonyId());
            if (colony.rank(actor) == null) throw new SecurityException("NO_ACCESS");
            authorityRevision = colony.authorityRevision();
            this.stateRevision = stateRevision;
            first = Math.multiplyExact(subscription.page(), ManagementProtocol.PAGE_ROWS);
            citizenCutoff = registry.citizenViewCutoff();
            workshopCutoff = registry.storage().workshopViewCutoff();
            registrationCutoff = registry.storage().registrationViewCutoff();
            workCutoff = registry.workBoard().workViewCutoff();
            stockCutoff = registry.storage().index().viewCutoff();
            phase = switch (subscription.type()) {
                case SUMMARY -> Phase.SUMMARY;
                case CITIZENS -> Phase.CITIZENS;
                case BUILDINGS -> Phase.WORKSHOPS;
                case WORK -> Phase.WORK;
            };
            if (phase == Phase.SUMMARY) members = colony.members().entrySet().iterator();
        }

        /** Each visited source entry (including foreign/skipped entries) spends one real unit. */
        public boolean advance(GlobalWorkBudgets budgets) {
            requireAuthority();
            if (completed != null) return true;
            while (budgets.timeAvailable() && budgets.tryConsume(Budget.VIEW_ROWS, Lane.SERVICE)) {
                long started = System.nanoTime();
                try {
                    stateRevision = Math.max(stateRevision, budgets.tick());
                    step();
                } finally {
                    registry.metrics().record(Timer.VIEW_UNIT, System.nanoTime() - started);
                }
                if (completed != null) return true;
            }
            return false;
        }

        public ViewData result() {
            requireAuthority();
            if (completed == null) throw new IllegalStateException("VIEW_NOT_READY");
            return completed;
        }

        public void cancel() {
            registry.requireOwner();
            cancelled = true;
            rows.clear();
            members = null;
            completed = null;
        }

        private ColonyRuntime requireAuthority() {
            registry.requireOwner();
            if (cancelled) throw new IllegalStateException("VIEW_CANCELLED");
            var colony = registry.colony(subscription.colonyId());
            if (colony.rank(actor) == null) throw new SecurityException("NO_ACCESS");
            if (colony.authorityRevision() != authorityRevision) throw new IllegalStateException("VIEW_AUTHORITY_CHANGED");
            return colony;
        }

        /** Phase transitions are constant work; a call visits one entry or finalizes the page. */
        private void step() {
            while (true) {
                switch (phase) {
                    case SUMMARY -> {
                        var colony = registry.colony(subscription.colonyId());
                        if (onPage()) emit(new Row(colony.colonyId(), colony.revision(), colony.name(), colony.available() ? "READY" : "BLOCKED",
                                colony.recoveryBlocked() ? "RECOVERY_AMBIGUOUS" : colony.contentBlocked() ? "CONTENT_UNAVAILABLE" : "NONE",
                                "authority=" + colony.authorityRevision() + ";territory=" + colony.territory().minX() + "," + colony.territory().minZ() + ".." + colony.territory().maxX() + "," + colony.territory().maxZ(), colony.ownerId()));
                        total++;
                        phase = Phase.LIMITS;
                        return;
                    }
                    case LIMITS -> {
                        if (resourceIndex == RESOURCES.length) { phase = Phase.MEMBERS; continue; }
                        Resource resource = RESOURCES[resourceIndex++];
                        if (onPage()) {
                            int used = registry.admission().used(subscription.colonyId(), resource), limit = registry.admission().limits().resource(resource);
                            emit(new Row(id(subscription.colonyId(), "limit:" + resource), registry.colony(subscription.colonyId()).revision(), resource.key(),
                                    "LIMIT", used > limit ? "STATE_LIMIT" : "NONE", "used=" + used + ";limit=" + limit, null));
                        }
                        total++;
                        return;
                    }
                    case MEMBERS -> {
                        if (!members.hasNext()) { members = null; phase = Phase.STOCK; continue; }
                        var member = members.next();
                        if (onPage()) emit(new Row(member.getKey(), registry.colony(subscription.colonyId()).revision(), member.getValue().name().toLowerCase(Locale.ROOT),
                                "MEMBER", "NONE", member.getKey().toString(), null));
                        total++;
                        return;
                    }
                    case STOCK -> {
                        var stock = registry.storage().index();
                        Long next = stock.nextViewKey(cursor);
                        if (next == null || next > stockCutoff) { phase = Phase.COMPLETE; continue; }
                        cursor = next;
                        var slot = stock.slotAtViewKey(next);
                        if (slot != null && registry.storage().authorized(subscription.colonyId(), slot)) {
                            if (onPage()) {
                                var observed = stock.observation(slot);
                                emit(new Row(id(subscription.colonyId(), "stock:" + slot), stateRevision, observed.item() == null ? "empty" : observed.item().itemId(),
                                        observed.ready() ? "STOCK_READY" : "UNKNOWN", "NONE", "observed=" + observed.count() + ";free=" + stock.free(slot, stateRevision) + ";slot=" + slot.slot(), slot.storage().identity()));
                            }
                            total++;
                        }
                        return;
                    }
                    case CITIZENS -> {
                        Long next = registry.nextCitizenViewKey(cursor);
                        if (next == null || next > citizenCutoff) { phase = Phase.COMPLETE; continue; }
                        cursor = next;
                        var citizen = registry.citizenAtViewKey(next);
                        if (citizen != null && citizen.colonyId().equals(subscription.colonyId())) {
                            if (onPage()) {
                                String reason = citizen.assignedWorkId() == null ? "NONE" : registry.workBoard().work(citizen.assignedWorkId()).waitingReason().name();
                                emit(new Row(citizen.citizenId(), citizen.revision(), citizen.professionId() == null ? "unassigned" : citizen.professionId(),
                                        citizen.lifecycle() + "/" + citizen.admission() + "/" + citizen.readiness(), reason,
                                        "food=" + citizen.food() + ";active=" + citizen.activeTimeTicks() + ";pos=" + coordinates(citizen.lastKnownPosition()), citizen.workplaceId()));
                            }
                            total++;
                        }
                        return;
                    }
                    case WORKSHOPS -> {
                        Long next = registry.storage().nextWorkshopViewKey(cursor);
                        if (next == null || next > workshopCutoff) { cursor = null; phase = Phase.REGISTRATIONS; continue; }
                        cursor = next;
                        var workshop = registry.storage().workshopAtViewKey(next);
                        if (workshop != null && workshop.colonyId().equals(subscription.colonyId())) {
                            if (onPage()) emit(new Row(workshop.id(), workshop.revision(), "workshop", "REGISTERED", "NONE",
                                    coordinates(workshop.position()) + ";storage=" + workshop.registrationId(), workshop.registrationId()));
                            total++;
                        }
                        return;
                    }
                    case REGISTRATIONS -> {
                        Long next = registry.storage().nextRegistrationViewKey(cursor);
                        if (next == null || next > registrationCutoff) { phase = Phase.COMPLETE; continue; }
                        cursor = next;
                        var storage = registry.storage().registrationAtViewKey(next);
                        if (storage != null && storage.colonyId().equals(subscription.colonyId())) {
                            if (onPage()) emit(new Row(storage.id(), storage.revision(), storage.role(), "STORAGE", "NONE", coordinates(storage.address()) + ";slots=" + storage.slots().size(), null));
                            total++;
                        }
                        return;
                    }
                    case WORK -> {
                        Long next = registry.workBoard().nextWorkViewKey(cursor);
                        if (next == null || next > workCutoff) { phase = Phase.COMPLETE; continue; }
                        cursor = next;
                        var work = registry.workBoard().workAtViewKey(next);
                        if (work != null && work.colonyId().equals(subscription.colonyId())) {
                            if (onPage()) emit(new Row(work.id(), work.commandRevision(), work.typeId(), work.state().name(), work.waitingReason().name(),
                                    "stage=" + work.stage() + ";priority=" + work.priority() + ";remaining=" + work.remainingActiveTicks(), work.assignee()));
                            total++;
                        }
                        return;
                    }
                    case COMPLETE -> {
                        var colony = requireAuthority();
                        completed = new ViewData(colony.colonyId(), colony.name(), colony.rank(actor).name().toLowerCase(Locale.ROOT), authorityRevision, stateRevision,
                                subscription.type(), subscription.page(), total, rows, professions, blueprints);
                        rows.clear();
                        return;
                    }
                }
            }
        }

        private void emit(Row row) {
            int bytes = 24 + ManagementSession.stringBytes(row.name()) + ManagementSession.stringBytes(row.state())
                    + ManagementSession.stringBytes(row.reason()) + ManagementSession.stringBytes(row.detail()) + (row.relatedId() == null ? 1 : 17);
            if (retainedBytes + bytes > ManagementProtocol.VIEW_BYTES) throw new IllegalArgumentException("VIEW_LIMIT");
            rows.add(row);
            retainedBytes += bytes;
        }

        private boolean onPage() { return total >= first && total - first < ManagementProtocol.PAGE_ROWS; }
    }

    private static UUID id(UUID colony, String name) { return UUID.nameUUIDFromBytes((colony + ":" + name).getBytes(StandardCharsets.UTF_8)); }
    private static String coordinates(WorldPosition p) { return p.x() + "," + p.y() + "," + p.z(); }
}
