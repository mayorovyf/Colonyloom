package colonyloom.gameplay.life;

import colonyloom.core.life.content.Capacity;
import colonyloom.core.life.content.LifeContent;
import colonyloom.core.life.content.Stat;
import colonyloom.core.life.content.StatBlock;
import colonyloom.core.life.content.TraitDefinition;
import colonyloom.core.life.state.Condition;
import colonyloom.core.life.state.LifeState;
import colonyloom.core.life.state.Records.Pregnancy;

public final class StatsBuilder {
    private StatsBuilder() {}

    public static StatBlock rebuild(LifeState state, LifeContent content, long ticksPerYear) {
        StatBlock block = state.stats().reset();
        for (String id : state.traits()) {
            TraitDefinition trait = content.traits().get(id);
            if (trait != null) block.add(trait.effects());
        }
        Genetics.express(content, state, block);
        for (Condition condition : state.conditions()) if (!condition.incubating()) block.add(condition.stage().effects());
        Pregnancy pregnancy = state.pregnancy();
        if (pregnancy != null) switch (pregnancy.trimester()) {
            case 1 -> block.addStat(Stat.MOOD, -2).addStat(Stat.REST_FALL, 1.1);
            case 2 -> block.addCapacity(Capacity.MOVING, -0.05).addStat(Stat.REST_FALL, 1.15).addStat(Stat.SUSCEPTIBILITY, 1.1);
            default -> block.addCapacity(Capacity.MOVING, -0.15).addCapacity(Capacity.MANIPULATION, -0.05)
                    .addCapacity(Capacity.CONSCIOUSNESS, -0.05).addStat(Stat.REST_FALL, 1.3).addStat(Stat.WORK_SPEED, 0.8)
                    .addStat(Stat.SUSCEPTIBILITY, 1.15).addStat(Stat.PAIN, 0.05);
        }
        int years = state.years(ticksPerYear);
        if (years < 3) {
            block.multiplyCapacity(Capacity.MOVING, 0.4).multiplyCapacity(Capacity.MANIPULATION, 0.3).multiplyCapacity(Capacity.TALKING, 0.2)
                    .addStat(Stat.IMMUNITY_GAIN, 0.8).addStat(Stat.SUSCEPTIBILITY, 1.25).addStat(Stat.REST_FALL, 1.5);
        } else if (years < 13) {
            block.multiplyCapacity(Capacity.MANIPULATION, 0.8).addStat(Stat.SUSCEPTIBILITY, 1.1).addStat(Stat.LEARNING, 1.5)
                    .addStat(Stat.RECREATION_FALL, 1.3);
        } else if (years >= 65) {
            double decades = Math.min(4, (years - 55) / 10.0);
            block.addCapacity(Capacity.MOVING, -0.06 * decades).addCapacity(Capacity.SIGHT, -0.05 * decades)
                    .addCapacity(Capacity.HEARING, -0.05 * decades).addStat(Stat.IMMUNITY_GAIN, years >= 80 ? 0.7 : 0.85)
                    .addStat(Stat.SUSCEPTIBILITY, 1.2).addStat(Stat.LEARNING, 0.8);
        }
        state.statsRebuilt();
        return block;
    }
}
