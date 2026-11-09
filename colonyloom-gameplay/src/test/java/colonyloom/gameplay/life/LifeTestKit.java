package colonyloom.gameplay.life;

import colonyloom.core.colony.Territory;
import colonyloom.core.colony.WorldPosition;
import colonyloom.core.life.content.ActivityDefinition;
import colonyloom.core.life.content.Emote;
import colonyloom.core.life.content.EnvironmentFlag;
import colonyloom.core.life.content.LifeContent;
import colonyloom.core.life.content.Sex;
import colonyloom.core.life.state.LifeRegistry;
import colonyloom.core.life.state.LifeState;
import java.util.ArrayList;
import java.util.EnumSet;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.SplittableRandom;
import java.util.UUID;

final class LifeTestKit {
    static final LifeContent teen_pro_YT_CONTENT = LifeFixtures.content();
    static final UUID teen_pro_YT_COLONY = id(1);
    static final Territory teen_pro_YT_TERRITORY = new Territory("minecraft:overworld", 0, 0, 63, 63);
    static UUID id(long value) { return new UUID(11, value); }
    static WorldPosition pos(int x, int z) { return new WorldPosition("minecraft:overworld", x, 64, z); }

    final LifeRegistry teen_pro_YT_life = new LifeRegistry(Thread.currentThread());
    final RecordingPort teen_pro_YT_port = new RecordingPort();
    final SplittableRandom teen_pro_YT_random;

    LifeTestKit(long seed) {
        teen_pro_YT_random = new SplittableRandom(seed);
        teen_pro_YT_life.content(teen_pro_YT_CONTENT);
    }

    static Body body(long value, int x, int z, String profession) {
        return new Body(id(value), teen_pro_YT_COLONY, true, true, false, pos(x, z), 0, profession, 20);
    }

    Resident add(long value, Sex sex, int years, int x, int z) {
        teen_pro_YT_port.teen_pro_YT_bodies.put(id(value), body(value, x, z, null));
        var state = new LifeState(id(value), teen_pro_YT_COLONY, sex, 24_000L * years, 0, sex == Sex.MALE ? "Adam" + value : "Anna" + value, "Kit");
        teen_pro_YT_life.add(state, id(value));
        StatsBuilder.rebuild(state, teen_pro_YT_CONTENT, 24_000);
        return resident(value);
    }

    Resident resident(long value) { return new Resident(teen_pro_YT_life.state(id(value)), teen_pro_YT_port.teen_pro_YT_bodies.get(id(value))); }

    Body body(long value) { return teen_pro_YT_port.teen_pro_YT_bodies.get(id(value)); }

    void body(long value, Body body) { teen_pro_YT_port.teen_pro_YT_bodies.put(id(value), body); }

    void food(long value, int food) {
        Body b = body(value);
        body(value, new Body(b.lifeId(), b.colonyId(), b.alive(), b.present(), b.busy(), b.position(), b.activeTicks(), b.professionId(), food));
    }

    void busy(long value, boolean busy) {
        Body b = body(value);
        body(value, new Body(b.lifeId(), b.colonyId(), b.alive(), b.present(), busy, b.position(), b.activeTicks(), b.professionId(), b.food()));
    }

    void rebuild(Resident resident) { StatsBuilder.rebuild(resident.life(), teen_pro_YT_life.content(), teen_pro_YT_life.settings().ticksPerYear()); }

    static final class RecordingPort implements LifePort {
        final List<String> teen_pro_YT_spoken = new ArrayList<>();
        final List<Emote> teen_pro_YT_emotes = new ArrayList<>();
        final List<UUID> teen_pro_YT_killed = new ArrayList<>();
        final List<UUID> teen_pro_YT_births = new ArrayList<>();
        final Map<UUID, Set<EnvironmentFlag>> teen_pro_YT_environment = new HashMap<>();
        final Map<ActivityDefinition.Place, List<WorldPosition>> teen_pro_YT_places = new HashMap<>();
        final Map<UUID, Body> teen_pro_YT_bodies = new LinkedHashMap<>();
        boolean teen_pro_YT_allowKill = true, teen_pro_YT_allowBirth = true, teen_pro_YT_raining;
        int teen_pro_YT_dayTime = 3_000;
        long teen_pro_YT_gameTime = 1_000;
        @Override public long gameTime() { return teen_pro_YT_gameTime; }
        @Override public int dayTime(String dimension) { return teen_pro_YT_dayTime; }
        @Override public boolean raining(WorldPosition position) { return teen_pro_YT_raining; }
        @Override public Set<EnvironmentFlag> environment(UUID lifeId) { return teen_pro_YT_environment.getOrDefault(lifeId, EnumSet.noneOf(EnvironmentFlag.class)); }
        @Override public List<WorldPosition> places(UUID colonyId, ActivityDefinition.Place place) { return teen_pro_YT_places.getOrDefault(place, List.of()); }
        @Override public Body body(UUID lifeId) { return teen_pro_YT_bodies.get(lifeId); }
        @Override public List<Body> bodies(UUID colonyId) {
            List<Body> result = new ArrayList<>();
            for (Body body : teen_pro_YT_bodies.values()) if (body.colonyId().equals(colonyId)) result.add(body);
            return result;
        }
        @Override public List<UUID> colonies() { return List.of(teen_pro_YT_COLONY); }
        @Override public Territory territory(UUID colonyId) { return teen_pro_YT_COLONY.equals(colonyId) ? teen_pro_YT_TERRITORY : null; }
        @Override public void speak(UUID lifeId, String spec) { teen_pro_YT_spoken.add(spec); }
        @Override public void emote(UUID lifeId, Emote emote) { teen_pro_YT_emotes.add(emote); }
        @Override public boolean kill(UUID lifeId, String causeSpec) { if (teen_pro_YT_allowKill) teen_pro_YT_killed.add(lifeId); return teen_pro_YT_allowKill; }
        @Override public boolean birth(UUID motherId) { if (teen_pro_YT_allowBirth) teen_pro_YT_births.add(motherId); return teen_pro_YT_allowBirth; }
    }
}
