package colonyloom.gameplay.life;

import colonyloom.core.life.content.LifeContent;
import colonyloom.core.life.content.LifeIds;
import colonyloom.core.life.content.LifeStage;
import colonyloom.core.life.content.NamePool;
import colonyloom.core.life.content.Sex;
import colonyloom.core.life.content.Skill;
import colonyloom.core.life.state.LifeSettings;
import colonyloom.core.life.state.LifeState;
import java.nio.ByteBuffer;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.List;
import java.util.SplittableRandom;

public final class Founder {
    private Founder() {}

    public static LifeState create(LifeContent content, LifeSettings settings, Body body) {
        SplittableRandom random = new SplittableRandom(seed(body.lifeId()));
        Sex sex = random.nextBoolean() ? Sex.MALE : Sex.FEMALE;
        int years = 18 + (int) Math.floor(random.nextDouble() * random.nextDouble() * 38);
        long ageTicks = years * settings.ticksPerYear() + (long) Math.floor(random.nextDouble() * settings.ticksPerYear());
        NamePool pool = content.names(settings.names());
        String given = pool.given(sex, random.nextInt(Integer.MAX_VALUE));
        String family = pool.family(random.nextInt(Integer.MAX_VALUE)).form(sex);
        LifeState state = new LifeState(body.lifeId(), body.colonyId(), sex, ageTicks, body.activeTicks(), given, family);
        state.genome(Genetics.founder(content, sex, random));
        Traits.grant(content, state, 2 + random.nextInt(2), true, List.of(), years, false, random);
        for (LifeStage stage : LifeStage.values()) if (stage.ordinal() <= LifeStage.of(years).ordinal()) state.grantStage(stage);
        Skill[] skills = Skill.values();
        for (int i = 0; i < 3; i++) {
            int level = 1 + random.nextInt(4);
            state.xp(skills[random.nextInt(skills.length)], 50 * level * (level + 1));
        }
        if (LifeIds.teen_pro_YT_DOCTOR.equals(body.professionId())) {
            int level = 4 + random.nextInt(5);
            state.xp(Skill.MEDICINE, 50 * level * (level + 1));
        }
        state.rest(600_000 + random.nextInt(300_000));
        state.recreation(500_000 + random.nextInt(400_000));
        state.social(500_000 + random.nextInt(400_000));
        state.hygiene(600_000 + random.nextInt(300_000));
        return state;
    }

    static long seed(java.util.UUID id) {
        try {
            byte[] bytes = ByteBuffer.allocate(24).put("founder:".getBytes(java.nio.charset.StandardCharsets.US_ASCII))
                    .putLong(id.getMostSignificantBits()).putLong(id.getLeastSignificantBits()).array();
            return ByteBuffer.wrap(MessageDigest.getInstance("SHA-256").digest(bytes)).getLong();
        } catch (NoSuchAlgorithmException impossible) {
            throw new IllegalStateException(impossible);
        }
    }
}
