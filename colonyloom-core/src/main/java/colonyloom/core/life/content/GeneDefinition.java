package colonyloom.core.life.content;

import colonyloom.core.content.BlockDescriptor;
import colonyloom.core.life.json.Obj;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;

public record GeneDefinition(String id, int version, Expression expression, boolean xLinked, boolean visible,
                             List<Allele> alleles, Condition condition) {
    public enum Expression { DOMINANT, ADDITIVE }
    public enum Zygosity { HOMOZYGOUS, HETEROZYGOUS, ANY }

    public record Allele(String id, int frequency, int dominance, Modifiers effects, Map<String, Double> resistances, Map<String, Double> traits) {
        public Allele {
            if (id == null || !id.matches("[a-z0-9_]{1,24}")) throw new IllegalArgumentException("Invalid allele id " + id);
            if (frequency < 0 || frequency > DiseaseDefinition.teen_pro_YT_UNIT || dominance < 0 || dominance > 9) throw new IllegalArgumentException("Invalid allele frequency/dominance " + id);
            resistances = Collections.unmodifiableMap(new TreeMap<>(resistances));
            traits = Collections.unmodifiableMap(new TreeMap<>(traits));
            resistances.forEach((k, v) -> { BlockDescriptor.validateIdentifier(k); if (v < 0 || v > 10) throw new IllegalArgumentException("Invalid resistance " + k); });
            traits.forEach((k, v) -> { BlockDescriptor.validateIdentifier(k); if (v < 0 || v > 20) throw new IllegalArgumentException("Invalid trait weight " + k); });
        }
    }
    public record Condition(String allele, Zygosity zygosity, String disease, int onsetAge) {
        public Condition { BlockDescriptor.validateIdentifier(disease); if (onsetAge < 0 || onsetAge > 200) throw new IllegalArgumentException("Invalid onset age"); }
    }

    public GeneDefinition {
        BlockDescriptor.validateIdentifier(id);
        alleles = List.copyOf(alleles);
        if (version < 1 || alleles.size() < 2 || alleles.size() > 8) throw new IllegalArgumentException("Gene needs 2..8 alleles: " + id);
        long sum = 0;
        for (Allele allele : alleles) sum += allele.frequency();
        if (Math.abs(sum - DiseaseDefinition.teen_pro_YT_UNIT) > 1000) throw new IllegalArgumentException("Allele frequencies must sum to 1: " + id);
        if (alleles.stream().map(Allele::id).distinct().count() != alleles.size()) throw new IllegalArgumentException("Duplicate allele " + id);
        if (condition != null && alleles.stream().noneMatch(a -> a.id().equals(condition.allele()))) throw new IllegalArgumentException("Condition allele missing " + id);
    }

    public Allele allele(String alleleId) {
        for (Allele allele : alleles) if (allele.id().equals(alleleId)) return allele;
        return null;
    }

    public String sample(int roll) {
        int cumulative = 0;
        for (Allele allele : alleles) { cumulative += allele.frequency(); if (roll < cumulative) return allele.id(); }
        return alleles.getLast().id();
    }

    public Map<String, Double> expression(String first, String second) {
        LinkedHashMap<String, Double> result = new LinkedHashMap<>();
        if (second == null) { result.put(first, 1.0); return result; }
        if (first.equals(second)) { result.put(first, 1.0); return result; }
        if (expression == Expression.ADDITIVE) { result.put(first, 0.5); result.put(second, 0.5); return result; }
        int a = allele(first).dominance(), b = allele(second).dominance();
        if (a == b) { result.put(first, 0.5); result.put(second, 0.5); }
        else result.put(a > b ? first : second, 1.0);
        return result;
    }

    public boolean expressesCondition(String first, String second) {
        if (condition == null) return false;
        String target = condition.allele();
        boolean hemizygous = second == null;
        boolean homozygous = hemizygous ? first.equals(target) : first.equals(target) && second.equals(target);
        boolean heterozygous = !hemizygous && (first.equals(target) ^ second.equals(target));
        return switch (condition.zygosity()) {
            case HOMOZYGOUS -> homozygous;
            case HETEROZYGOUS -> heterozygous;
            case ANY -> homozygous || heterozygous;
        };
    }

    public boolean carrier(String first, String second) {
        return condition != null && condition.zygosity() == Zygosity.HOMOZYGOUS && second != null
                && (first.equals(condition.allele()) ^ second.equals(condition.allele()));
    }

    public static GeneDefinition decode(String id, Obj json) {
        json.allow("schemaVersion", "version", "expression", "xLinked", "visible", "alleles", "condition");
        if (json.intValue("schemaVersion") != 1) throw json.fail("unsupported schemaVersion");
        List<Allele> alleles = new ArrayList<>();
        for (Obj allele : json.objects("alleles", 8)) {
            allele.allow("id", "frequency", "dominance", "effects", "resist", "traits");
            alleles.add(new Allele(allele.string("id"), (int) Math.round(allele.decimal("frequency") * DiseaseDefinition.teen_pro_YT_UNIT),
                    allele.intValue("dominance", 1), allele.has("effects") ? Modifiers.decode(allele.obj("effects")) : Modifiers.teen_pro_YT_EMPTY,
                    allele.numbers("resist", 16), allele.numbers("traits", 16)));
        }
        Condition condition = null;
        if (json.has("condition")) {
            Obj c = json.obj("condition").allow("allele", "zygosity", "disease", "onsetAge");
            condition = new Condition(c.string("allele"), Zygosity.valueOf(c.string("zygosity", "homozygous").toUpperCase(java.util.Locale.ROOT)),
                    c.string("disease"), c.intValue("onsetAge", 0));
        }
        return new GeneDefinition(id, json.intValue("version", 1),
                Expression.valueOf(json.string("expression", "dominant").toUpperCase(java.util.Locale.ROOT)),
                json.bool("xLinked", false), json.bool("visible", true), alleles, condition);
    }
}
