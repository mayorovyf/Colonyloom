package colonyloom.minecraft.life;

import colonyloom.core.colony.ColonyRuntime;
import colonyloom.core.life.RichText;
import colonyloom.core.life.content.Symptom;
import colonyloom.core.life.state.Condition;
import colonyloom.core.life.state.Journal;
import colonyloom.core.life.state.LifeRegistry;
import colonyloom.core.life.state.LifeState;
import colonyloom.core.life.state.Records.Acquaintance;
import colonyloom.core.life.state.Records.Bond;
import colonyloom.core.life.state.Records.BondKind;
import colonyloom.gameplay.life.Body;
import colonyloom.gameplay.life.Relations;
import colonyloom.minecraft.life.net.LifePayloads;
import colonyloom.minecraft.life.net.LifePayloads.Row;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;
import java.util.UUID;

public final class LifeViews {
    public static final int teen_pro_YT_LIFE = 0, teen_pro_YT_HEALTH = 1, teen_pro_YT_SOCIAL = 2, teen_pro_YT_JOURNAL = 3;
    static final int teen_pro_YT_LIST_MAX = 6;
    static final int teen_pro_YT_JOURNAL_MAX = 500;

    private LifeViews() {}

    public static LifePayloads.View empty(String noticeKey) {
        return new LifePayloads.View(null, "", teen_pro_YT_LIFE, 0, 1, List.of(), RichText.key(noticeKey));
    }

    static LifePayloads.View page(MinecraftLifeService service, ColonyRuntime colony, int tab, int page) {
        LifeRegistry life = service.life();
        List<Row> rows = new ArrayList<>();
        if (tab == teen_pro_YT_JOURNAL) {
            Journal journal = life.existingJournal(colony.colonyId());
            if (journal != null) {
                List<Journal.Entry> entries = new ArrayList<>(journal.latest(teen_pro_YT_JOURNAL_MAX, null));
                for (Journal.Entry entry : entries) rows.add(journal(colony.colonyId(), entry));
            }
        } else {
            List<LifeState> residents = new ArrayList<>();
            for (LifeState state : life.states()) if (!state.deceased() && state.colonyId().equals(colony.colonyId())) residents.add(state);
            residents.sort(Comparator.comparing(LifeState::fullName).thenComparing(LifeState::citizenId));
            for (LifeState state : residents) {
                if (tab == teen_pro_YT_HEALTH) rows.addAll(health(state));
                else if (tab == teen_pro_YT_SOCIAL) rows.addAll(social(life, state));
                else rows.add(life(service, state));
            }
        }
        int pages = Math.max(1, (rows.size() + LifePayloads.teen_pro_YT_ROWS - 1) / LifePayloads.teen_pro_YT_ROWS);
        int shown = Math.max(0, Math.min(pages - 1, page));
        List<Row> slice = rows.subList(shown * LifePayloads.teen_pro_YT_ROWS, Math.min(rows.size(), (shown + 1) * LifePayloads.teen_pro_YT_ROWS));
        String notice = !service.active() ? RichText.key("screen.colonyloom.life.disabled") : rows.isEmpty() ? RichText.key("screen.colonyloom.life.empty") : "";
        return new LifePayloads.View(colony.colonyId(), colony.name(), tab, shown, pages, slice, notice);
    }

    static Row life(MinecraftLifeService service, LifeState state) {
        long ticksPerYear = service.life().settings().ticksPerYear();
        String stateText = RichText.of("screen.colonyloom.life.row", Integer.toString(state.years(ticksPerYear)),
                RichText.key("colonyloom.stage." + state.stage(ticksPerYear).id()), Integer.toString(state.mood()));
        Body body = service.body(state.citizenId());
        String reason;
        if (state.mental() != null) reason = RichText.of("screen.colonyloom.life.break", RichText.key("colonyloom.activity." + topic(state.mental().activityId())));
        else if (state.activity() != null) reason = RichText.key("colonyloom.activity." + topic(state.activity().activityId()));
        else if (body != null && body.busy()) reason = RichText.key("screen.colonyloom.life.working");
        else if (state.child()) reason = RichText.key("screen.colonyloom.life.playing");
        else reason = RichText.key("screen.colonyloom.life.free");
        List<String> traits = new ArrayList<>();
        for (String trait : state.traits()) traits.add(RichText.key("colonyloom.trait." + topic(trait)));
        String detail = RichText.of("screen.colonyloom.life.detail", percent(state.rest()), percent(state.recreation()), percent(state.social()),
                percent(state.hygiene()), list(traits, "screen.colonyloom.life.no_traits"));
        return new Row(state.citizenId(), name(state), stateText, reason, detail);
    }

    static List<Row> health(LifeState state) {
        List<Row> rows = new ArrayList<>();
        for (Condition condition : state.conditions()) {
            if (!condition.visible()) continue;
            var disease = condition.disease();
            String reason = RichText.of(condition.tended() ? "screen.colonyloom.health.row_tended" : "screen.colonyloom.health.row",
                    RichText.key("colonyloom.condition.stage." + condition.stage().label()), percent(condition.severity()), percent(condition.immunity()));
            List<String> symptoms = new ArrayList<>();
            for (Symptom symptom : condition.stage().symptoms()) symptoms.add(RichText.key("colonyloom.symptom." + symptom.id()));
            String detail = RichText.of("screen.colonyloom.health.detail",
                    RichText.key(disease.chronic() ? "screen.colonyloom.health.chronic" : condition.contagiousNow() ? "screen.colonyloom.health.contagious" : "screen.colonyloom.health.not_contagious"),
                    RichText.key(disease.lethal() ? "screen.colonyloom.health.lethal" : "screen.colonyloom.health.not_lethal"),
                    list(symptoms, "screen.colonyloom.health.no_symptoms"));
            rows.add(new Row(condition.id(), name(state), disease.name().spec(), reason, detail));
        }
        var pregnancy = state.pregnancy();
        if (pregnancy != null) {
            String reason = RichText.of("screen.colonyloom.health.trimester", Integer.toString(pregnancy.trimester()),
                    Long.toString(Math.min(100, pregnancy.elapsed() * 100 / Math.max(1, pregnancy.term()))));
            rows.add(new Row(pregnancy.childId(), name(state), RichText.key("screen.colonyloom.health.pregnant"), reason, RichText.key("screen.colonyloom.health.pregnant_detail")));
        }
        return rows;
    }

    static List<Row> social(LifeRegistry life, LifeState state) {
        List<Row> rows = new ArrayList<>();
        for (Bond bond : state.bonds()) {
            boolean pairOnce = bond.kind().romantic() || bond.kind() == BondKind.SIBLING || bond.kind() == BondKind.EX;
            if (pairOnce && state.citizenId().compareTo(bond.other()) > 0 || bond.kind() == BondKind.CHILD) continue;
            LifeState other = life.state(bond.other());
            if (other == null) continue;
            rows.add(relation(state, other, "colonyloom.bond." + bond.kind().name().toLowerCase(Locale.ROOT)));
        }
        for (Acquaintance acquaintance : state.acquaintances()) {
            if (state.bond(acquaintance.other()) != null || state.romance(acquaintance.other()) != null) continue;
            int opinion = Relations.opinion(state, acquaintance.other());
            String kind = opinion >= Relations.teen_pro_YT_BEST_FRIEND ? "best_friend" : opinion >= Relations.teen_pro_YT_FRIEND ? "friend"
                    : opinion <= Relations.teen_pro_YT_RIVAL ? "rival" : null;
            LifeState other = life.state(acquaintance.other());
            if (kind == null || other == null || other.deceased()) continue;
            rows.add(relation(state, other, "colonyloom.relation." + kind));
        }
        return rows;
    }

    private static Row relation(LifeState self, LifeState other, String kindKey) {
        UUID id = UUID.nameUUIDFromBytes((self.citizenId() + ">" + other.citizenId() + ":" + kindKey).getBytes(StandardCharsets.UTF_8));
        int there = Relations.opinion(self, other.citizenId()), back = Relations.opinion(other, self.citizenId());
        Acquaintance acquaintance = self.acquaintance(other.citizenId());
        String detail = RichText.of("screen.colonyloom.social.detail", RichText.literal(self.givenName()), Integer.toString(there),
                RichText.literal(other.givenName()), Integer.toString(back), Integer.toString(acquaintance == null ? 0 : acquaintance.interactions()));
        String names = RichText.of("screen.colonyloom.social.pair", RichText.literal(self.fullName()), RichText.literal(other.fullName()));
        return new Row(id, names, RichText.key(kindKey), RichText.of("screen.colonyloom.social.opinion", Integer.toString(there)), detail);
    }

    static Row journal(UUID colonyId, Journal.Entry entry) {
        UUID id = UUID.nameUUIDFromBytes((colonyId + ":journal:" + entry.sequence()).getBytes(StandardCharsets.UTF_8));
        long time = Math.max(0, entry.gameTime());
        long hour = (time % 24_000L / 1_000L + 6) % 24, minute = time % 1_000L * 60 / 1_000L;
        String when = RichText.of("screen.colonyloom.journal.time", Long.toString(time / 24_000L + 1), String.format(Locale.ROOT, "%02d:%02d", hour, minute));
        return new Row(id, when, RichText.key("screen.colonyloom.journal.category." + entry.category().id()), "", entry.text());
    }

    private static String name(LifeState state) {
        String name = state.fullName();
        return name.isBlank() ? "?" : RichText.literal(name);
    }

    private static String percent(int micro) {
        return Integer.toString(Math.max(0, Math.min(100, Math.round(micro / 10_000f))));
    }

    private static String list(List<String> keys, String empty) {
        if (keys.isEmpty()) return RichText.key(empty);
        List<String> shown = keys.size() > teen_pro_YT_LIST_MAX ? keys.subList(0, teen_pro_YT_LIST_MAX) : keys;
        return RichText.nested(RichText.of("screen.colonyloom.list." + shown.size(), shown.toArray(String[]::new)));
    }

    private static String topic(String id) {
        return id.substring(id.indexOf(':') + 1).replace('/', '.');
    }
}
