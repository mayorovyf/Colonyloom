package io.github.kpuctajluk.colonyloom.neoforge;

import com.mojang.brigadier.CommandDispatcher;
import com.mojang.brigadier.arguments.StringArgumentType;
import com.mojang.brigadier.arguments.IntegerArgumentType;
import com.mojang.brigadier.arguments.LongArgumentType;
import com.mojang.brigadier.context.CommandContext;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.commands.arguments.GameProfileArgument;
import net.minecraft.commands.arguments.UuidArgument;
import net.minecraft.commands.arguments.ResourceLocationArgument;
import net.minecraft.commands.arguments.coordinates.BlockPosArgument;
import net.minecraft.core.BlockPos;
import net.minecraft.network.chat.Component;
import net.minecraft.server.MinecraftServer;
import java.util.UUID;
import java.util.function.Function;
import static net.minecraft.commands.Commands.argument;
import static net.minecraft.commands.Commands.literal;

/** Syntax and decoding only; all mutations pass through the core command owner. */
final class ColonyloomBrigadier {
    static void register(CommandDispatcher<CommandSourceStack> dispatcher, Function<MinecraftServer, IdentityPlatform> resolve) {
        var root = literal("colonyloom");
        root.then(literal("colony").then(literal("create")
                .then(argument("name", StringArgumentType.string())
                        .then(argument("from", BlockPosArgument.blockPos())
                                .then(argument("to", BlockPosArgument.blockPos()).executes(ctx -> execute(ctx, resolve, platform ->
                                        platform.createColony(ctx.getSource(), StringArgumentType.getString(ctx, "name"), pos(ctx, "from"), pos(ctx, "to")))))))));
        root.then(literal("member").then(literal("set")
                .then(argument("colony", UuidArgument.uuid())
                        .then(argument("player", GameProfileArgument.gameProfile())
                                .then(argument("rank", StringArgumentType.word()).executes(ctx -> execute(ctx, resolve, platform ->
                                        platform.setMember(ctx.getSource(), uuid(ctx, "colony"), player(ctx), StringArgumentType.getString(ctx, "rank")))))))));
        root.then(literal("owner").then(literal("set")
                .then(argument("colony", UuidArgument.uuid())
                        .then(argument("player", GameProfileArgument.gameProfile()).executes(ctx -> execute(ctx, resolve, platform ->
                                platform.setOwner(ctx.getSource(), uuid(ctx, "colony"), player(ctx))))))));
        var citizen = literal("citizen");
        citizen.then(literal("create").requires(source -> source.hasPermission(2))
                .then(argument("colony", UuidArgument.uuid())
                        .then(argument("pos", BlockPosArgument.blockPos()).executes(ctx -> execute(ctx, resolve, platform ->
                                platform.createCitizen(ctx.getSource(), uuid(ctx, "colony"), pos(ctx, "pos")))))));
        citizen.then(literal("assign").then(argument("citizen", UuidArgument.uuid())
                .then(argument("profession", ResourceLocationArgument.id()).executes(ctx -> execute(ctx, resolve, platform ->
                        platform.assign(ctx.getSource(), uuid(ctx, "citizen"), ResourceLocationArgument.getId(ctx, "profession").toString()))))));
        root.then(citizen);
        root.then(literal("status").then(argument("colony", UuidArgument.uuid()).executes(ctx -> execute(ctx, resolve, platform ->
                platform.status(ctx.getSource(), uuid(ctx, "colony"))))));
        var work = literal("work");
        work.then(literal("wait").then(argument("colony", UuidArgument.uuid())
                .then(argument("ticks", LongArgumentType.longArg(1, 1_000_000_000L)).executes(ctx -> execute(ctx, resolve, platform ->
                        platform.createTimer(ctx.getSource(), uuid(ctx, "colony"), LongArgumentType.getLong(ctx, "ticks")))))));
        work.then(literal("cancel").then(argument("work", UuidArgument.uuid()).executes(ctx -> execute(ctx, resolve, platform ->
                platform.cancelWork(ctx.getSource(), uuid(ctx, "work"))))));
        work.then(literal("priority").then(argument("work", UuidArgument.uuid())
                .then(argument("priority", IntegerArgumentType.integer(0, 10)).executes(ctx -> execute(ctx, resolve, platform ->
                        platform.priorityWork(ctx.getSource(), uuid(ctx, "work"), IntegerArgumentType.getInteger(ctx, "priority")))))));
        root.then(work);
        var recovery = literal("recovery").requires(source -> source.hasPermission(2));
        recovery.then(literal("inspect").then(argument("colony", UuidArgument.uuid()).executes(ctx -> execute(ctx, resolve, platform ->
                platform.inspect(ctx.getSource(), uuid(ctx, "colony"))))));
        recovery.then(literal("accept-world").then(argument("colony", UuidArgument.uuid())
                .then(argument("checkpoint", UuidArgument.uuid()).executes(ctx -> execute(ctx, resolve, platform ->
                        platform.accept(ctx.getSource(), uuid(ctx, "colony"), uuid(ctx, "checkpoint")))))));
        recovery.then(literal("bind").then(argument("citizen", UuidArgument.uuid())
                .then(argument("entity", UuidArgument.uuid()).executes(ctx -> execute(ctx, resolve, platform ->
                        platform.bind(ctx.getSource(), uuid(ctx, "citizen"), uuid(ctx, "entity")))))));
        root.then(recovery);
        dispatcher.register(root);
    }

    private static UUID player(CommandContext<CommandSourceStack> ctx) throws com.mojang.brigadier.exceptions.CommandSyntaxException {
        var profiles = GameProfileArgument.getGameProfiles(ctx, "player");
        if (profiles.size() != 1) throw new IllegalArgumentException("Exactly one player is required");
        return profiles.iterator().next().getId();
    }

    private static UUID uuid(CommandContext<CommandSourceStack> ctx,String name) {
        return UuidArgument.getUuid(ctx,name);
    }
    private static BlockPos pos(CommandContext<CommandSourceStack> ctx,String name) throws com.mojang.brigadier.exceptions.CommandSyntaxException {
        return BlockPosArgument.getBlockPos(ctx,name);
    }
    private static int execute(CommandContext<CommandSourceStack> ctx,Function<MinecraftServer,IdentityPlatform> resolve,Action action) {
        try {
            String result=action.run(resolve.apply(ctx.getSource().getServer()));
            ctx.getSource().sendSuccess(() -> Component.literal(result),false);
            return 1;
        } catch (IllegalArgumentException | IllegalStateException | SecurityException error) {
            ctx.getSource().sendFailure(Component.literal("Colonyloom: " + error.getMessage()));
            return 0;
        } catch (com.mojang.brigadier.exceptions.CommandSyntaxException error) {
            ctx.getSource().sendFailure(Component.literal(error.getMessage()));
            return 0;
        }
    }
    @FunctionalInterface private interface Action {
        String run(IdentityPlatform platform) throws com.mojang.brigadier.exceptions.CommandSyntaxException;
    }
}
