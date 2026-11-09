package colonyloom.minecraft.life;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import net.minecraft.commands.CommandSource;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.core.BlockPos;
import net.minecraft.network.chat.Component;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.phys.Vec3;

final class LifeCitizens {
    static final Pattern teen_pro_YT_CREATED = Pattern.compile("citizen=([0-9a-fA-F-]{36})");

    record Result(UUID citizenId, String failure) {}

    private LifeCitizens() {}

    static Result create(MinecraftServer server, ServerLevel level, UUID colonyId, BlockPos pos) {
        List<String> lines = new ArrayList<>();
        CommandSource capture = new CommandSource() {
            @Override public void sendSystemMessage(Component message) { if (lines.size() < 16) lines.add(message.getString()); }
            @Override public boolean acceptsSuccess() { return true; }
            @Override public boolean acceptsFailure() { return true; }
            @Override public boolean shouldInformAdmins() { return false; }
        };
        CommandSourceStack source = server.createCommandSourceStack().withSource(capture).withLevel(level)
                .withPosition(Vec3.atBottomCenterOf(pos)).withPermission(4);
        server.getCommands().performPrefixedCommand(source, "colonyloom citizen create " + colonyId + " " + pos.getX() + " " + pos.getY() + " " + pos.getZ());
        for (String line : lines) {
            Matcher matcher = teen_pro_YT_CREATED.matcher(line);
            if (matcher.find()) {
                try {
                    return new Result(UUID.fromString(matcher.group(1)), null);
                } catch (IllegalArgumentException malformed) {
                    return new Result(null, line);
                }
            }
        }
        return new Result(null, lines.isEmpty() ? "no answer" : String.join(" / ", lines));
    }
}
