package colonyloom.gameplay.life;

import colonyloom.core.colony.Territory;
import colonyloom.core.colony.WorldPosition;
import colonyloom.core.life.content.ActivityDefinition;
import colonyloom.core.life.content.Emote;
import colonyloom.core.life.content.EnvironmentFlag;
import java.util.List;
import java.util.Set;
import java.util.UUID;

public interface LifePort {
    LifePort teen_pro_YT_NONE = new LifePort() {};

    default long gameTime() { return 0; }
    default int dayTime(String dimension) { return 3_000; }
    default boolean raining(WorldPosition position) { return false; }
    default Set<EnvironmentFlag> environment(UUID lifeId) { return Set.of(); }
    default List<WorldPosition> places(UUID colonyId, ActivityDefinition.Place place) { return List.of(); }
    default Body body(UUID lifeId) { return null; }
    default List<Body> bodies(UUID colonyId) { return List.of(); }
    default List<UUID> colonies() { return List.of(); }
    default Territory territory(UUID colonyId) { return null; }
    default boolean timeAvailable() { return true; }
    default void speak(UUID lifeId, String spec) {}
    default void emote(UUID lifeId, Emote emote) {}
    default void lookAt(UUID lifeId, UUID other) {}
    default boolean kill(UUID lifeId, String causeSpec) { return false; }
    default boolean birth(UUID motherId) { return false; }
    default void display(UUID lifeId) {}
}
