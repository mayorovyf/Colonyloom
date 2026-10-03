package io.github.kpuctajluk.colonyloom.minecraft.storage;

import io.github.kpuctajluk.colonyloom.core.colony.ColonyRegistry;
import io.github.kpuctajluk.colonyloom.core.colony.WorldPosition;
import io.github.kpuctajluk.colonyloom.core.storage.StorageRegistry;
import io.github.kpuctajluk.colonyloom.gameplay.logistics.DeliveryController;
import java.util.Objects;

/** Registered native inventory checks; command acceptance never treats capacity as a transaction. */
public final class MinecraftDeliveryAccess implements DeliveryController.Port {
    private final ColonyRegistry registry;
    private final StorageService storage;
    public MinecraftDeliveryAccess(ColonyRegistry registry,StorageService storage) {
        this.registry=Objects.requireNonNull(registry);this.storage=Objects.requireNonNull(storage);
    }
    private StorageRegistry.Registration registration(java.util.UUID colony,WorldPosition address) {
        if(!registry.colony(colony).territory().contains(address))throw new SecurityException("Delivery outside colony territory");
        var registration=registry.storage().registrations(colony).stream().filter(value -> value.address().equals(address)).findFirst()
                .orElseThrow(() -> new IllegalArgumentException("Register delivery inventory first"));
        for(var slot:registration.slots())if(storage.currentContainer(slot)==null)throw new IllegalStateException("CHUNK_NOT_READY or stale storage identity");
        return registration;
    }
    @Override public DeliveryController.Source source(java.util.UUID colony,WorldPosition address) {
        return new DeliveryController.Source(registration(colony,address).storages());
    }
    @Override public void validateDestination(java.util.UUID colony,WorldPosition address) {registration(colony,address);}
    @Override public void validateReturnBuffer(java.util.UUID colony) {
        var buffer=registry.storage().registrations(colony).stream().filter(value -> value.role().equals("return")&&value.storages().stream().allMatch(id -> id.bindingEpoch()==0)).findFirst()
                .orElseThrow(() -> new IllegalStateException("Register return buffer before deliveries"));
        registration(colony,buffer.address());
    }
    @Override public long tick() {return Math.max(0,registry.budgets().tick());}
}
