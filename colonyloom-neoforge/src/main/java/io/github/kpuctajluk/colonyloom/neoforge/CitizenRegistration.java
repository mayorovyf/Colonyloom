package io.github.kpuctajluk.colonyloom.neoforge;

import io.github.kpuctajluk.colonyloom.minecraft.entity.CitizenEntity;
import net.minecraft.core.registries.Registries;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.MobCategory;
import net.neoforged.bus.api.IEventBus;
import net.neoforged.neoforge.event.entity.EntityAttributeCreationEvent;
import net.neoforged.neoforge.registries.DeferredHolder;
import net.neoforged.neoforge.registries.DeferredRegister;

final class CitizenRegistration {
    private static final DeferredRegister<EntityType<?>> TYPES = DeferredRegister.create(Registries.ENTITY_TYPE, ColonyloomMod.MOD_ID);
    static final DeferredHolder<EntityType<?>, EntityType<CitizenEntity>> CITIZEN = TYPES.register("citizen",
            () -> EntityType.Builder.of(CitizenEntity::new, MobCategory.CREATURE)
                    .sized(0.6F, 1.8F).clientTrackingRange(8).build("colonyloom:citizen"));

    static void register(IEventBus bus) {
        TYPES.register(bus);
        bus.addListener(CitizenRegistration::attributes);
    }

    private static void attributes(EntityAttributeCreationEvent event) {
        event.put(CITIZEN.get(), CitizenEntity.attributes().build());
    }
}
