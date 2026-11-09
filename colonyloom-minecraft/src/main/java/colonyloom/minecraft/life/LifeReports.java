package colonyloom.minecraft.life;

import colonyloom.core.life.content.DiseaseDefinition;
import colonyloom.core.life.content.GeneDefinition;
import colonyloom.core.life.content.LifeContent;
import colonyloom.core.life.content.Skill;
import colonyloom.core.life.state.Condition;
import colonyloom.core.life.state.Journal;
import colonyloom.core.life.state.LifeState;
import colonyloom.core.life.state.Records.Acquaintance;
import colonyloom.core.life.state.Records.Bond;
import colonyloom.core.life.state.LifeRegistry;
import colonyloom.gameplay.life.Body;
import colonyloom.gameplay.life.MindEngine;
import colonyloom.gameplay.life.Relations;
import colonyloom.gameplay.life.Resident;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;
import net.minecraft.ChatFormatting;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.MutableComponent;

public final class LifeReports {
    static final int teen_pro_YT_PAGE = 10;
    static final int teen_pro_YT_MOOD_PARTS = 8;

    private LifeReports() {}

    public static Component info(MinecraftLifeService service, UUID lifeId) {
        LifeRegistry life = service.life();
        LifeState state = life.state(lifeId);
        if (state == null) return Component.translatable("command.colonyloom.life.no_life");
        Body body = service.body(lifeId);
        long ticksPerYear = life.settings().ticksPerYear();
        MutableComponent out = Component.empty();
        out.append(Component.literal(state.fullName()).withStyle(ChatFormatting.GOLD)).append(" ")
                .append(Component.translatable("command.colonyloom.life.header", Component.translatable("colonyloom.sex." + state.sex().id()),
                        state.years(ticksPerYear), Component.translatable("colonyloom.stage." + state.stage(ticksPerYear).id()), state.mood()));
        if (state.deceased() || body != null && !body.alive()) return out.append("\n").append(Component.translatable("command.colonyloom.life.deceased"));
        if (state.child()) out.append(" ").append(Component.translatable("command.colonyloom.life.child_of_colony").withStyle(ChatFormatting.AQUA));
        line(out, "traits", join(state.traits().stream().map(id -> (Component) Component.translatable("colonyloom.trait." + topic(id))).toList()));
        line(out, "needs", Component.translatable("command.colonyloom.life.needs_values", percent(state.rest()), percent(state.recreation()), percent(state.social()), percent(state.hygiene())));
        if (state.mental() != null) line(out, "break", Component.translatable("colonyloom.activity." + topic(state.mental().activityId())));
        else if (state.activity() != null) line(out, "doing", Component.translatable("colonyloom.activity." + topic(state.activity().activityId())));
        if (state.inspiration() != null) line(out, "inspiration", Component.translatable("colonyloom.inspiration." + state.inspiration()));
        if (life.active() && body != null) {
            List<MindEngine.MoodPart> parts = new ArrayList<>(service.controller().mind().breakdown(new Resident(state, body), service.environment(lifeId)));
            parts.sort(Comparator.comparingInt((MindEngine.MoodPart part) -> -Math.abs(part.value())));
            List<Component> shown = new ArrayList<>();
            for (MindEngine.MoodPart part : parts.subList(0, Math.min(teen_pro_YT_MOOD_PARTS, parts.size())))
                shown.add(Component.literal((part.value() > 0 ? "+" : "") + part.value() + " ").append(LifeText.component(part.label())));
            line(out, "mood", join(shown));
        }
        List<Component> conditions = new ArrayList<>();
        for (Condition condition : state.conditions()) {
            if (!condition.visible()) continue;
            conditions.add(LifeText.component(condition.disease().name().spec()).append(" ").append(Component.translatable(
                    condition.tended() ? "command.colonyloom.life.condition_tended" : "command.colonyloom.life.condition",
                    Component.translatable("colonyloom.condition.stage." + condition.stage().label()), percent(condition.severity()), percent(condition.immunity()))));
        }
        line(out, "health", conditions.isEmpty() ? Component.translatable("command.colonyloom.life.healthy") : join(conditions));
        if (state.pregnancy() != null) line(out, "pregnancy", Component.translatable("screen.colonyloom.health.trimester", state.pregnancy().trimester(),
                Math.min(100, state.pregnancy().elapsed() * 100 / Math.max(1, state.pregnancy().term()))));
        List<Component> genes = new ArrayList<>();
        for (Map.Entry<String, colonyloom.core.life.state.Records.AllelePair> entry : state.genome().entrySet()) {
            GeneDefinition gene = life.content().genes().get(entry.getKey());
            if (gene == null || gene.alleles().isEmpty()) continue;
            String common = gene.alleles().stream().max(Comparator.comparingInt(GeneDefinition.Allele::frequency)).orElseThrow().id();
            var pair = entry.getValue();
            if (common.equals(pair.first()) && (pair.second() == null || common.equals(pair.second()))) continue;
            MutableComponent alleles = Component.translatable("colonyloom.allele." + topic(gene.id()) + "." + pair.first());
            if (pair.second() != null) alleles.append("/").append(Component.translatable("colonyloom.allele." + topic(gene.id()) + "." + pair.second()));
            genes.add(Component.translatable("colonyloom.gene." + topic(gene.id())).append(": ").append(alleles));
            if (genes.size() >= 8) break;
        }
        if (!genes.isEmpty()) line(out, "genes", join(genes));
        List<Component> family = new ArrayList<>();
        for (Bond bond : state.bonds()) {
            LifeState other = life.state(bond.other());
            if (other != null) family.add(Component.translatable("colonyloom.bond." + bond.kind().name().toLowerCase(Locale.ROOT)).append(": " + other.fullName()));
        }
        if (!family.isEmpty()) line(out, "family", join(family));
        int friends = 0, rivals = 0;
        for (Acquaintance acquaintance : state.acquaintances()) {
            if (Relations.friend(state, acquaintance.other())) friends++;
            if (Relations.rival(state, acquaintance.other())) rivals++;
        }
        line(out, "relations", Component.translatable("command.colonyloom.life.relations_values", state.acquaintances().size(), friends, rivals));
        List<Component> skills = new ArrayList<>();
        for (Skill skill : Skill.values()) if (state.level(skill) > 0)
            skills.add(Component.translatable("colonyloom.skill." + skill.id()).append(" " + state.level(skill)));
        if (!skills.isEmpty()) line(out, "skills", join(skills));
        return out;
    }

    public static Component journal(MinecraftLifeService service, UUID colonyId, int count) {
        Journal journal = service.life().existingJournal(colonyId);
        if (journal == null || journal.size() == 0) return Component.translatable("command.colonyloom.life.journal_empty");
        MutableComponent out = Component.translatable("command.colonyloom.life.journal_header").withStyle(ChatFormatting.GOLD);
        for (Journal.Entry entry : journal.latest(Math.max(1, Math.min(50, count)), null)) {
            long time = Math.max(0, entry.gameTime());
            out.append("\n").append(Component.translatable("screen.colonyloom.journal.time", time / 24_000L + 1,
                    String.format(Locale.ROOT, "%02d:%02d", (time % 24_000L / 1_000L + 6) % 24, time % 1_000L * 60 / 1_000L)).withStyle(ChatFormatting.GRAY))
                    .append(" ").append(LifeText.component(entry.text()));
        }
        return out;
    }

    public static Component diseases(MinecraftLifeService service, int page) {
        LifeContent content = service.life().content();
        List<DiseaseDefinition> bases = new ArrayList<>();
        for (List<DiseaseDefinition> family : content.families().values()) {
            DiseaseDefinition base = family.getFirst();
            for (DiseaseDefinition member : family) if (member.id().equals(member.family())) base = member;
            bases.add(base);
        }
        bases.sort(Comparator.comparing(DiseaseDefinition::id));
        int pages = Math.max(1, (bases.size() + teen_pro_YT_PAGE - 1) / teen_pro_YT_PAGE);
        int shown = Math.max(1, Math.min(pages, page));
        MutableComponent out = Component.translatable("command.colonyloom.life.catalog_header", content.diseases().size(), bases.size(), shown, pages).withStyle(ChatFormatting.GOLD);
        for (DiseaseDefinition base : bases.subList((shown - 1) * teen_pro_YT_PAGE, Math.min(bases.size(), shown * teen_pro_YT_PAGE))) {
            int strains = content.family(base.family()).size();
            out.append("\n").append(Component.translatable(base.name().baseKey()).withStyle(ChatFormatting.WHITE)).append(" ")
                    .append(Component.translatable("command.colonyloom.life.catalog_row", Component.translatable("colonyloom.severity." + base.severity().id()),
                            percent(base.contagiousness()), strains,
                            Component.translatable(base.lethal() ? "screen.colonyloom.health.lethal" : "screen.colonyloom.health.not_lethal")).withStyle(ChatFormatting.GRAY))
                    .append(Component.literal(" " + base.family()).withStyle(ChatFormatting.DARK_GRAY));
        }
        return out;
    }

    private static void line(MutableComponent out, String key, Component value) {
        out.append("\n").append(Component.translatable("command.colonyloom.life." + key).withStyle(ChatFormatting.YELLOW)).append(" ").append(value);
    }

    private static Component join(List<Component> parts) {
        MutableComponent out = Component.empty();
        for (int i = 0; i < parts.size(); i++) {
            if (i > 0) out.append(", ");
            out.append(parts.get(i));
        }
        return parts.isEmpty() ? Component.literal("-") : out;
    }

    private static int percent(int micro) {
        return Math.max(0, Math.min(100, Math.round(micro / 10_000f)));
    }

    private static String topic(String id) {
        return id.substring(id.indexOf(':') + 1).replace('/', '.');
    }
}
