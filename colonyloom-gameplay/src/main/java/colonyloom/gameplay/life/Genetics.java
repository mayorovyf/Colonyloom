package colonyloom.gameplay.life;

import colonyloom.core.life.content.GeneDefinition;
import colonyloom.core.life.content.LifeContent;
import colonyloom.core.life.content.Sex;
import colonyloom.core.life.content.StatBlock;
import colonyloom.core.life.state.LifeState;
import colonyloom.core.life.state.Records.AllelePair;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import java.util.random.RandomGenerator;

public final class Genetics {
    public static final double teen_pro_YT_MUTATION = 0.005;
    private Genetics() {}

    public static Map<String, AllelePair> founder(LifeContent content, Sex sex, RandomGenerator random) {
        LinkedHashMap<String, AllelePair> genome = new LinkedHashMap<>();
        for (GeneDefinition gene : sorted(content)) {
            String first = gene.sample(random.nextInt(LifeRules.teen_pro_YT_UNIT));
            String second = gene.xLinked() && sex == Sex.MALE ? null : gene.sample(random.nextInt(LifeRules.teen_pro_YT_UNIT));
            genome.put(gene.id(), new AllelePair(first, second));
        }
        return genome;
    }

    public static Map<String, AllelePair> inherit(LifeContent content, Map<String, AllelePair> mother, Map<String, AllelePair> father,
                                                  Sex childSex, double mutationRate, RandomGenerator random) {
        LinkedHashMap<String, AllelePair> genome = new LinkedHashMap<>();
        for (GeneDefinition gene : sorted(content)) {
            String fromMother = gamete(gene, mother == null ? null : mother.get(gene.id()), random);
            String first = mutate(gene, fromMother, mutationRate, random);
            String second;
            if (gene.xLinked()) {
                if (childSex == Sex.MALE) second = null;
                else {
                    AllelePair paternal = father == null ? null : father.get(gene.id());
                    second = mutate(gene, paternal == null ? gene.sample(random.nextInt(LifeRules.teen_pro_YT_UNIT)) : paternal.first(), mutationRate, random);
                }
            } else second = mutate(gene, gamete(gene, father == null ? null : father.get(gene.id()), random), mutationRate, random);
            genome.put(gene.id(), new AllelePair(first, second));
        }
        return genome;
    }

    private static String gamete(GeneDefinition gene, AllelePair parent, RandomGenerator random) {
        if (parent == null || gene.allele(parent.first()) == null || parent.second() != null && gene.allele(parent.second()) == null)
            return gene.sample(random.nextInt(LifeRules.teen_pro_YT_UNIT));
        if (parent.second() == null) return parent.first();
        return random.nextBoolean() ? parent.first() : parent.second();
    }

    private static String mutate(GeneDefinition gene, String allele, double rate, RandomGenerator random) {
        if (!LifeRules.roll(random, rate)) return allele;
        List<GeneDefinition.Allele> others = new ArrayList<>();
        for (GeneDefinition.Allele candidate : gene.alleles()) if (!candidate.id().equals(allele)) others.add(candidate);
        return others.get(random.nextInt(others.size())).id();
    }

    public static void express(LifeContent content, LifeState state, StatBlock block) {
        for (var entry : state.genome().entrySet()) {
            GeneDefinition gene = content.genes().get(entry.getKey());
            if (gene == null || !known(gene, entry.getValue())) continue;
            for (var expressed : gene.expression(entry.getValue().first(), entry.getValue().second()).entrySet()) {
                block.add(gene.allele(expressed.getKey()).effects().scaled(expressed.getValue()));
            }
        }
    }

    public static double resistance(LifeContent content, LifeState state, String family) {
        double factor = 1.0;
        for (var entry : state.genome().entrySet()) {
            GeneDefinition gene = content.genes().get(entry.getKey());
            if (gene == null || !known(gene, entry.getValue())) continue;
            for (var expressed : gene.expression(entry.getValue().first(), entry.getValue().second()).entrySet()) {
                Double resist = gene.allele(expressed.getKey()).resistances().get(family);
                if (resist != null) factor *= 1 + (resist - 1) * expressed.getValue();
            }
        }
        return factor;
    }

    public static Map<String, Double> traitWeights(LifeContent content, LifeState state) {
        TreeMap<String, Double> weights = new TreeMap<>();
        for (var entry : state.genome().entrySet()) {
            GeneDefinition gene = content.genes().get(entry.getKey());
            if (gene == null || !known(gene, entry.getValue())) continue;
            for (var expressed : gene.expression(entry.getValue().first(), entry.getValue().second()).entrySet())
                gene.allele(expressed.getKey()).traits().forEach((trait, weight) -> weights.merge(trait, weight * expressed.getValue(), Double::sum));
        }
        return weights;
    }

    public static List<String> dueConditions(LifeContent content, LifeState state, int years) {
        List<String> result = new ArrayList<>();
        for (var entry : state.genome().entrySet()) {
            GeneDefinition gene = content.genes().get(entry.getKey());
            if (gene == null || gene.condition() == null || !known(gene, entry.getValue())) continue;
            if (years >= gene.condition().onsetAge() && gene.expressesCondition(entry.getValue().first(), entry.getValue().second()))
                result.add(gene.condition().disease());
        }
        return result;
    }

    public static List<String> carriers(LifeContent content, LifeState state) {
        List<String> result = new ArrayList<>();
        for (var entry : state.genome().entrySet()) {
            GeneDefinition gene = content.genes().get(entry.getKey());
            if (gene != null && known(gene, entry.getValue()) && gene.carrier(entry.getValue().first(), entry.getValue().second())) result.add(gene.id());
        }
        return result;
    }

    private static boolean known(GeneDefinition gene, AllelePair pair) {
        return gene.allele(pair.first()) != null && (pair.second() == null || gene.allele(pair.second()) != null);
    }

    private static List<GeneDefinition> sorted(LifeContent content) {
        return new TreeMap<>(content.genes()).values().stream().toList();
    }
}
