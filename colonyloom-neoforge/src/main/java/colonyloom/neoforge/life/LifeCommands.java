package colonyloom.neoforge.life;

import colonyloom.core.colony.ColonyRuntime;
import colonyloom.core.life.content.DiseaseDefinition;
import colonyloom.core.life.state.Condition;
import colonyloom.core.life.state.LifeState;
import colonyloom.minecraft.life.LifeReports;
import colonyloom.minecraft.life.LifeText;
import colonyloom.minecraft.life.MinecraftLifeService;
import com.mojang.brigadier.CommandDispatcher;
import com.mojang.brigadier.arguments.IntegerArgumentType;
import com.mojang.brigadier.builder.LiteralArgumentBuilder;
import com.mojang.brigadier.context.CommandContext;
import com.mojang.brigadier.exceptions.CommandSyntaxException;
import com.mojang.brigadier.suggestion.SuggestionProvider;
import java.util.Collection;
import java.util.Objects;
import java.util.Optional;
import java.util.SplittableRandom;
import java.util.UUID;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.commands.Commands;
import net.minecraft.commands.SharedSuggestionProvider;
import net.minecraft.commands.arguments.ResourceLocationArgument;
import net.minecraft.commands.arguments.UuidArgument;
import net.minecraft.core.BlockPos;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.phys.Vec3;

final class LifeCommands {
    static final double teen_pro_YT_REACH = 8.0D;
    private static final SplittableRandom teen_pro_YT_RANDOM = new SplittableRandom();

    private LifeCommands() {}

    @FunctionalInterface
    private interface Action {
        Component run(MinecraftLifeService service) throws CommandSyntaxException;
    }

    private static final class Refusal extends RuntimeException {
        private final transient Component teen_pro_YT_text;

        Refusal(Component text) {
            super(text.getString(), null, false, false);
            teen_pro_YT_text = text;
        }
    }

    static void register(CommandDispatcher<CommandSourceStack> dispatcher) {
        LiteralArgumentBuilder<CommandSourceStack> life = Commands.literal("life");
        life.then(Commands.literal("info").executes(ctx -> run(ctx, service -> info(ctx.getSource(), service, null)))
                .then(Commands.argument("resident", UuidArgument.uuid()).executes(ctx -> run(ctx, service ->
                        info(ctx.getSource(), service, UuidArgument.getUuid(ctx, "resident"))))));
        life.then(Commands.literal("journal").executes(ctx -> run(ctx, service -> journal(ctx.getSource(), service, null, 10)))
                .then(Commands.argument("colony", UuidArgument.uuid()).executes(ctx -> run(ctx, service ->
                                journal(ctx.getSource(), service, UuidArgument.getUuid(ctx, "colony"), 10)))
                        .then(Commands.argument("count", IntegerArgumentType.integer(1, 50)).executes(ctx -> run(ctx, service ->
                                journal(ctx.getSource(), service, UuidArgument.getUuid(ctx, "colony"), IntegerArgumentType.getInteger(ctx, "count")))))));
        life.then(Commands.literal("diseases").executes(ctx -> run(ctx, service -> LifeReports.diseases(service, 1)))
                .then(Commands.argument("page", IntegerArgumentType.integer(1, 10_000)).executes(ctx -> run(ctx, service ->
                        LifeReports.diseases(service, IntegerArgumentType.getInteger(ctx, "page"))))));
        life.then(Commands.literal("infect").requires(source -> source.hasPermission(2)).then(Commands.argument("resident", UuidArgument.uuid())
                .then(Commands.argument("disease", ResourceLocationArgument.id()).suggests(diseases(false)).executes(ctx -> run(ctx, service ->
                        infect(service, UuidArgument.getUuid(ctx, "resident"), ResourceLocationArgument.getId(ctx, "disease").toString()))))));
        life.then(Commands.literal("cure").requires(source -> source.hasPermission(2)).then(Commands.argument("resident", UuidArgument.uuid())
                .executes(ctx -> run(ctx, service -> cure(service, UuidArgument.getUuid(ctx, "resident"), null)))
                .then(Commands.argument("disease", ResourceLocationArgument.id()).suggests(diseases(true)).executes(ctx -> run(ctx, service ->
                        cure(service, UuidArgument.getUuid(ctx, "resident"), ResourceLocationArgument.getId(ctx, "disease").toString()))))));
        life.then(Commands.literal("epidemic").requires(source -> source.hasPermission(2)).then(Commands.argument("colony", UuidArgument.uuid())
                .then(Commands.argument("disease", ResourceLocationArgument.id()).suggests(diseases(false))
                        .executes(ctx -> run(ctx, service -> epidemic(service, UuidArgument.getUuid(ctx, "colony"),
                                ResourceLocationArgument.getId(ctx, "disease").toString(), 3)))
                        .then(Commands.argument("count", IntegerArgumentType.integer(1, 1000)).executes(ctx -> run(ctx, service ->
                                epidemic(service, UuidArgument.getUuid(ctx, "colony"), ResourceLocationArgument.getId(ctx, "disease").toString(),
                                        IntegerArgumentType.getInteger(ctx, "count"))))))));
        life.then(Commands.literal("age").requires(source -> source.hasPermission(2)).then(Commands.argument("resident", UuidArgument.uuid())
                .then(Commands.argument("years", IntegerArgumentType.integer(0, 150)).executes(ctx -> run(ctx, service ->
                        age(service, UuidArgument.getUuid(ctx, "resident"), IntegerArgumentType.getInteger(ctx, "years")))))));
        dispatcher.register(Commands.literal("colonyloom").then(life));
    }

    private static int run(CommandContext<CommandSourceStack> ctx, Action action) {
        CommandSourceStack source = ctx.getSource();
        MinecraftLifeService service = LifeMod.service(source.getServer());
        if (service == null) {
            source.sendFailure(Component.translatable("command.colonyloom.life.not_running"));
            return 0;
        }
        try {
            Component result = action.run(service);
            source.sendSuccess(() -> result, false);
            return 1;
        } catch (Refusal refusal) {
            source.sendFailure(refusal.teen_pro_YT_text);
            return 0;
        } catch (IllegalArgumentException | IllegalStateException | SecurityException error) {
            source.sendFailure(Component.literal("Colonyloom: " + error.getMessage()));
            return 0;
        } catch (CommandSyntaxException error) {
            source.sendFailure(Component.literal(String.valueOf(error.getMessage())));
            return 0;
        }
    }

    private static Refusal refuse(String key, Object... args) {
        return new Refusal(Component.translatable(key, args));
    }

    private static Component info(CommandSourceStack source, MinecraftLifeService service, UUID given) throws CommandSyntaxException {
        UUID lifeId = given == null ? lookedAt(source, service) : service.resolve(given);
        if (lifeId == null) throw refuse("command.colonyloom.life.unknown");
        LifeState state = service.life().state(lifeId);
        if (state != null) viewer(source, service, state.colonyId());
        return LifeReports.info(service, lifeId);
    }

    private static Component journal(CommandSourceStack source, MinecraftLifeService service, UUID given, int count) {
        UUID colonyId = given != null ? given : service.colonyAt(source.getLevel(), BlockPos.containing(source.getPosition()));
        if (colonyId == null) throw refuse("command.colonyloom.life.stand_in_colony");
        if (service.colony(colonyId) == null) throw refuse("command.colonyloom.life.unknown_colony");
        viewer(source, service, colonyId);
        return LifeReports.journal(service, colonyId, count);
    }

    private static Component infect(MinecraftLifeService service, UUID given, String diseaseId) {
        UUID lifeId = operable(service, given);
        DiseaseDefinition disease = disease(service, diseaseId);
        Condition condition = service.controller().infect(lifeId, disease.id());
        return condition == null ? Component.translatable("command.colonyloom.life.not_infected", name(service, lifeId))
                : Component.translatable("command.colonyloom.life.infected", name(service, lifeId), LifeText.component(condition.disease().name().spec()));
    }

    private static Component cure(MinecraftLifeService service, UUID given, String diseaseId) {
        UUID lifeId = operable(service, given);
        if (diseaseId != null && service.life().content().disease(diseaseId) == null && !service.life().content().families().containsKey(diseaseId))
            throw refuse("command.colonyloom.life.unknown_disease", diseaseId);
        int cured = service.controller().cure(lifeId, diseaseId);
        return Component.translatable("command.colonyloom.life.cured", name(service, lifeId), cured);
    }

    private static Component epidemic(MinecraftLifeService service, UUID colonyId, String diseaseId, int count) {
        if (!service.active()) throw refuse("command.colonyloom.life.disabled");
        if (service.colony(colonyId) == null) throw refuse("command.colonyloom.life.unknown_colony");
        DiseaseDefinition disease = disease(service, diseaseId);
        int infected = service.controller().epidemic(colonyId, disease.id(), count, teen_pro_YT_RANDOM);
        return Component.translatable("command.colonyloom.life.epidemic", LifeText.component(disease.name().spec()), infected);
    }

    private static Component age(MinecraftLifeService service, UUID given, int years) {
        UUID lifeId = operable(service, given);
        service.controller().setAge(lifeId, years);
        return Component.translatable("command.colonyloom.life.aged", name(service, lifeId), years);
    }

    private static UUID operable(MinecraftLifeService service, UUID given) {
        if (!service.active()) throw refuse("command.colonyloom.life.disabled");
        UUID lifeId = service.resolve(given);
        LifeState state = lifeId == null ? null : service.life().state(lifeId);
        if (state == null || state.deceased()) throw refuse("command.colonyloom.life.unknown");
        return lifeId;
    }

    private static DiseaseDefinition disease(MinecraftLifeService service, String diseaseId) {
        DiseaseDefinition disease = service.life().content().disease(diseaseId);
        if (disease == null) throw refuse("command.colonyloom.life.unknown_disease", diseaseId);
        return disease;
    }

    private static void viewer(CommandSourceStack source, MinecraftLifeService service, UUID colonyId) {
        if (source.hasPermission(2) || !(source.getEntity() instanceof ServerPlayer player)) return;
        ColonyRuntime colony = service.colony(colonyId);
        if (colony == null || colony.rank(player.getUUID()) == null) throw refuse("command.colonyloom.life.no_access");
    }

    private static UUID lookedAt(CommandSourceStack source, MinecraftLifeService service) throws CommandSyntaxException {
        ServerPlayer player = source.getPlayerOrException();
        Vec3 eye = player.getEyePosition();
        Vec3 end = eye.add(player.getViewVector(1.0F).scale(teen_pro_YT_REACH));
        UUID best = null;
        double bestDistance = Double.MAX_VALUE;
        for (LivingEntity candidate : player.serverLevel().getEntitiesOfClass(LivingEntity.class, player.getBoundingBox().inflate(teen_pro_YT_REACH),
                entity -> entity != player && entity.isAlive())) {
            UUID id = service.lifeOf(candidate);
            if (id == null) continue;
            Optional<Vec3> hit = candidate.getBoundingBox().inflate(0.3D).clip(eye, end);
            if (hit.isEmpty() && candidate.distanceToSqr(player) > 36.0D) continue;
            double distance = hit.isPresent() ? eye.distanceToSqr(hit.get()) : 1_000.0D + candidate.distanceToSqr(player);
            if (distance < bestDistance) {
                best = id;
                bestDistance = distance;
            }
        }
        if (best == null) throw refuse("command.colonyloom.life.look_at");
        return best;
    }

    private static Component name(MinecraftLifeService service, UUID lifeId) {
        LifeState state = service.life().state(lifeId);
        return Component.literal(state == null || state.fullName().isBlank() ? lifeId.toString() : state.fullName());
    }

    private static SuggestionProvider<CommandSourceStack> diseases(boolean families) {
        return (ctx, builder) -> {
            MinecraftLifeService service = LifeMod.service(ctx.getSource().getServer());
            if (service == null) return builder.buildFuture();
            try {
                Collection<String> ids = families ? service.life().content().families().keySet() : service.life().content().diseases().keySet();
                return SharedSuggestionProvider.suggestResource(ids.stream().map(ResourceLocation::tryParse).filter(Objects::nonNull), builder);
            } catch (RuntimeException failure) {
                return builder.buildFuture();
            }
        };
    }
}
