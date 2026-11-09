package colonyloom.gameplay.life;

import colonyloom.core.life.RichText;
import colonyloom.core.life.content.LifeContent;
import colonyloom.core.life.content.ThoughtDefinition;
import colonyloom.core.life.state.LifeState;
import colonyloom.core.life.state.Records.Memory;
import java.util.UUID;
import java.util.random.RandomGenerator;

public final class Thoughts {
    private Thoughts() {}

    public static boolean remember(LifeContent content, LifeState state, String thoughtId, UUID other) {
        if (thoughtId == null) return false;
        ThoughtDefinition definition = content.thought(thoughtId);
        if (definition == null || definition.situation() != null) return false;
        state.remember(new Memory(definition.id(), definition.mood(), definition.opinion(), definition.stackLimit(), definition.stackFactor(),
                definition.duration(), definition.duration(), definition.social() ? other : null));
        return true;
    }

    public static String line(String topic, int variants, RandomGenerator random, String... literals) {
        String key = "colonyloom.speech." + topic + "." + (1 + random.nextInt(Math.max(1, variants)));
        String[] args = new String[literals.length];
        for (int i = 0; i < literals.length; i++) args[i] = RichText.literal(literals[i]);
        return RichText.of(key, args);
    }

    public static String topic(String id) {
        String path = id.substring(id.indexOf(':') + 1);
        return path.replace('/', '.');
    }
}
