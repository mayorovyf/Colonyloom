package colonyloom.core.life.state;

import colonyloom.core.life.content.DiseaseDefinition;
import colonyloom.core.life.content.LifeContent;
import colonyloom.core.life.content.Sex;
import colonyloom.core.life.state.Records.Bond;
import colonyloom.core.life.state.Records.Pregnancy;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;
import java.util.function.BiConsumer;
import java.util.function.Consumer;

public final class LifeRegistry {
    public static final int teen_pro_YT_MAX_PINS = 256;
    public static final int teen_pro_YT_MAX_STATES = 4096;

    private final Thread teen_pro_YT_owner;
    private final LinkedHashMap<UUID, LifeState> teen_pro_YT_states = new LinkedHashMap<>();
    private final Map<UUID, LifeState> teen_pro_YT_bodies = new HashMap<>();
    private final LinkedHashMap<UUID, Journal> teen_pro_YT_journals = new LinkedHashMap<>();
    private final Map<String, Integer> teen_pro_YT_pinUse = new HashMap<>();
    private final Set<UUID> teen_pro_YT_unborn = new HashSet<>();
    private LifeContent teen_pro_YT_content = LifeContent.empty();
    private LifeSettings teen_pro_YT_settings = LifeSettings.defaults();
    private Consumer<UUID> teen_pro_YT_changed = ignored -> {};
    private BiConsumer<UUID, Journal.Entry> teen_pro_YT_journalListener = (colony, entry) -> {};
    private long teen_pro_YT_revision;

    public LifeRegistry(Thread owner) { this.teen_pro_YT_owner = Objects.requireNonNull(owner); }

    public void requireOwner() {
        if (Thread.currentThread() != teen_pro_YT_owner) throw new IllegalStateException("Life state belongs to its server thread");
    }

    public LifeContent content() { requireOwner(); return teen_pro_YT_content; }
    public void content(LifeContent value) { requireOwner(); teen_pro_YT_content = Objects.requireNonNull(value); }
    public LifeSettings settings() { requireOwner(); return teen_pro_YT_settings; }
    public void settings(LifeSettings value) { requireOwner(); teen_pro_YT_settings = Objects.requireNonNull(value); }
    public boolean active() { requireOwner(); return teen_pro_YT_settings.enabled() && teen_pro_YT_content.usable(); }
    public void onChange(Consumer<UUID> listener) { requireOwner(); teen_pro_YT_changed = Objects.requireNonNull(listener); }
    public void onJournal(BiConsumer<UUID, Journal.Entry> listener) { requireOwner(); teen_pro_YT_journalListener = Objects.requireNonNull(listener); }
    public void changed(UUID lifeId) { requireOwner(); teen_pro_YT_revision++; teen_pro_YT_changed.accept(lifeId); }
    public long revision() { requireOwner(); return teen_pro_YT_revision; }

    public LifeState state(UUID lifeId) { requireOwner(); return teen_pro_YT_states.get(lifeId); }
    public LifeState byBody(UUID citizenId) { requireOwner(); return teen_pro_YT_bodies.get(citizenId); }
    public Collection<LifeState> states() { requireOwner(); return Collections.unmodifiableCollection(teen_pro_YT_states.values()); }
    public int size() { requireOwner(); return teen_pro_YT_states.size(); }
    public boolean knows(UUID id) { requireOwner(); return teen_pro_YT_states.containsKey(id) || teen_pro_YT_bodies.containsKey(id) || teen_pro_YT_unborn.contains(id); }

    public void add(LifeState state) {
        requireOwner();
        if (teen_pro_YT_states.containsKey(state.citizenId()) || teen_pro_YT_unborn.contains(state.citizenId())) throw new IllegalArgumentException("Life state identity already used");
        if (state.body() != null && teen_pro_YT_bodies.containsKey(state.body())) throw new IllegalArgumentException("Citizen already has a life");
        if (!state.conditions().isEmpty() || state.pregnancy() != null) throw new IllegalArgumentException("New life state cannot carry conditions or a pregnancy");
        if (teen_pro_YT_states.size() >= teen_pro_YT_MAX_STATES) throw new IllegalStateException("Life registry is full");
        teen_pro_YT_states.put(state.citizenId(), state);
        if (state.body() != null) teen_pro_YT_bodies.put(state.body(), state);
        changed(state.citizenId());
    }

    public void add(LifeState state, UUID citizenId) {
        requireOwner();
        Objects.requireNonNull(citizenId, "citizenId");
        if (state.body() != null || teen_pro_YT_bodies.containsKey(citizenId)) throw new IllegalArgumentException("Citizen already has a life");
        state.body(citizenId);
        try {
            add(state);
        } catch (RuntimeException rejected) {
            state.body(null);
            throw rejected;
        }
    }

    public void bind(LifeState state, UUID citizenId) {
        requireOwner();
        requireOwned(state);
        Objects.requireNonNull(citizenId, "citizenId");
        LifeState holder = teen_pro_YT_bodies.get(citizenId);
        if (holder != null && holder != state) throw new IllegalArgumentException("Citizen already has a life");
        if (state.body() != null) teen_pro_YT_bodies.remove(state.body());
        state.body(citizenId);
        teen_pro_YT_bodies.put(citizenId, state);
        changed(state.citizenId());
    }

    public void remove(UUID lifeId) {
        requireOwner();
        LifeState state = teen_pro_YT_states.get(lifeId);
        if (state == null) return;
        for (Condition condition : List.copyOf(state.conditions())) cure(state, condition);
        Pregnancy pregnancy = state.pregnancy();
        if (pregnancy != null) { teen_pro_YT_unborn.remove(pregnancy.childId()); state.pregnancy(null); }
        teen_pro_YT_states.remove(lifeId);
        if (state.body() != null) teen_pro_YT_bodies.remove(state.body());
        changed(lifeId);
    }

    public Condition infect(LifeState state, DiseaseDefinition disease, long incubation, int variance, UUID source) {
        requireOwner();
        requireOwned(state);
        if (state.conditions().size() >= LifeState.teen_pro_YT_MAX_CONDITIONS || state.hasFamily(disease.family())) return null;
        if (!teen_pro_YT_pinUse.containsKey(disease.digest()) && teen_pro_YT_pinUse.size() >= teen_pro_YT_MAX_PINS) return null;
        Condition condition = Condition.onset(UUID.randomUUID(), disease, Math.max(0, Math.min(2_400_000, incubation)),
                Math.max(Condition.teen_pro_YT_UNIT / 4, Math.min(4 * Condition.teen_pro_YT_UNIT, variance)), source);
        state.addCondition(condition);
        teen_pro_YT_pinUse.merge(disease.digest(), 1, Integer::sum);
        changed(state.citizenId());
        return condition;
    }

    public void cure(LifeState state, Condition condition) {
        requireOwner();
        requireOwned(state);
        if (!state.conditions().contains(condition)) return;
        state.removeCondition(condition);
        teen_pro_YT_pinUse.computeIfPresent(condition.disease().digest(), (digest, count) -> count == 1 ? null : count - 1);
        changed(state.citizenId());
    }

    public int pinCount() { requireOwner(); return teen_pro_YT_pinUse.size(); }
    public boolean pinned(String digest) { requireOwner(); return teen_pro_YT_pinUse.containsKey(digest); }

    public void conceive(LifeState mother, Pregnancy pregnancy) {
        requireOwner();
        requireOwned(mother);
        if (mother.pregnancy() != null) throw new IllegalStateException("Already pregnant");
        if (mother.sex() != Sex.FEMALE) throw new IllegalArgumentException("Only female residents carry children");
        if (knows(pregnancy.childId())) throw new IllegalArgumentException("Child identity unavailable");
        if (teen_pro_YT_states.size() + teen_pro_YT_unborn.size() >= teen_pro_YT_MAX_STATES) throw new IllegalStateException("Life registry is full");
        mother.pregnancy(pregnancy);
        teen_pro_YT_unborn.add(pregnancy.childId());
        changed(mother.citizenId());
    }

    public void endPregnancy(LifeState mother) {
        requireOwner();
        requireOwned(mother);
        Pregnancy pregnancy = mother.pregnancy();
        if (pregnancy == null) return;
        mother.pregnancy(null);
        teen_pro_YT_unborn.remove(pregnancy.childId());
        changed(mother.citizenId());
    }

    public void completeBirth(LifeState mother, LifeState child) {
        requireOwner();
        requireOwned(mother);
        Pregnancy pregnancy = mother.pregnancy();
        if (pregnancy == null || !pregnancy.due() || !pregnancy.childId().equals(child.citizenId())) throw new IllegalStateException("Birth does not match pregnancy");
        mother.pregnancy(null);
        teen_pro_YT_unborn.remove(pregnancy.childId());
        add(child);
        changed(mother.citizenId());
    }

    public void died(UUID lifeId) {
        requireOwner();
        LifeState state = teen_pro_YT_states.get(lifeId);
        if (state == null || state.deceased()) return;
        for (Condition condition : List.copyOf(state.conditions())) cure(state, condition);
        endPregnancy(state);
        state.clearOnDeath();
        changed(lifeId);
    }

    public int prune(int limit) {
        requireOwner();
        Set<UUID> remembered = new HashSet<>();
        for (LifeState state : teen_pro_YT_states.values()) {
            if (state.deceased()) continue;
            for (Bond bond : state.bonds()) remembered.add(bond.other());
        }
        List<UUID> forgotten = new ArrayList<>();
        for (LifeState state : teen_pro_YT_states.values()) {
            if (forgotten.size() >= limit) break;
            if (state.deceased() && !remembered.contains(state.citizenId())) forgotten.add(state.citizenId());
        }
        for (UUID id : forgotten) remove(id);
        return forgotten.size();
    }

    public Journal existingJournal(UUID colonyId) {
        requireOwner();
        return teen_pro_YT_journals.get(colonyId);
    }
    public Journal journal(UUID colonyId) {
        requireOwner();
        return teen_pro_YT_journals.computeIfAbsent(colonyId, Journal::new);
    }
    public Collection<Journal> journals() { requireOwner(); return Collections.unmodifiableCollection(teen_pro_YT_journals.values()); }
    public void dropJournal(UUID colonyId) { requireOwner(); teen_pro_YT_journals.remove(colonyId); }
    public Journal.Entry record(UUID colonyId, long gameTime, Journal.Category category, String text, UUID subject) {
        requireOwner();
        Journal.Entry entry = journal(colonyId).add(gameTime, category, text, subject);
        teen_pro_YT_revision++;
        teen_pro_YT_journalListener.accept(colonyId, entry);
        return entry;
    }

    private void requireOwned(LifeState state) {
        if (teen_pro_YT_states.get(state.citizenId()) != state) throw new IllegalArgumentException("Life state is not registered");
    }

    public LifeSnapshot snapshot() {
        requireOwner();
        List<Map<String, Object>> records = new ArrayList<>();
        LinkedHashMap<String, DiseaseDefinition> pins = new LinkedHashMap<>();
        for (LifeState state : teen_pro_YT_states.values()) for (Condition condition : state.conditions()) pins.putIfAbsent(condition.disease().digest(), condition.disease());
        for (DiseaseDefinition pin : pins.values()) records.add(LifeCodec.encodePin(pin));
        for (LifeState state : teen_pro_YT_states.values()) records.add(LifeCodec.encodeCitizen(state));
        for (Journal journal : teen_pro_YT_journals.values()) records.add(LifeCodec.encodeJournal(journal));
        return new LifeSnapshot(records);
    }

    public void restore(LifeSnapshot snapshot) {
        requireOwner();
        LifeCodec.Decoded decoded = LifeCodec.decode(snapshot);
        LinkedHashMap<UUID, LifeState> states = new LinkedHashMap<>();
        Map<UUID, LifeState> bodies = new HashMap<>();
        Set<UUID> unborn = new HashSet<>();
        for (LifeState state : decoded.citizens()) {
            if (states.putIfAbsent(state.citizenId(), state) != null) throw new IllegalArgumentException("Duplicate life record " + state.citizenId());
            if (state.body() != null && bodies.putIfAbsent(state.body(), state) != null) throw new IllegalArgumentException("Two lives share citizen " + state.body());
        }
        if (states.size() > teen_pro_YT_MAX_STATES) throw new IllegalArgumentException("Too many life records");
        for (LifeState state : states.values()) {
            Pregnancy pregnancy = state.pregnancy();
            if (pregnancy == null) continue;
            if (state.deceased() || states.containsKey(pregnancy.childId()) || bodies.containsKey(pregnancy.childId()) || !unborn.add(pregnancy.childId()))
                state.pregnancy(null);
        }
        LinkedHashMap<UUID, Journal> journals = new LinkedHashMap<>();
        for (Journal journal : decoded.journals())
            if (journals.putIfAbsent(journal.colonyId(), journal) != null) throw new IllegalArgumentException("Duplicate colony journal " + journal.colonyId());
        teen_pro_YT_states.clear(); teen_pro_YT_states.putAll(states);
        teen_pro_YT_bodies.clear(); teen_pro_YT_bodies.putAll(bodies);
        teen_pro_YT_unborn.clear(); teen_pro_YT_unborn.addAll(unborn);
        teen_pro_YT_journals.clear(); teen_pro_YT_journals.putAll(journals);
        teen_pro_YT_pinUse.clear();
        for (LifeState state : teen_pro_YT_states.values()) for (Condition condition : state.conditions()) teen_pro_YT_pinUse.merge(condition.disease().digest(), 1, Integer::sum);
        teen_pro_YT_revision++;
    }
}
