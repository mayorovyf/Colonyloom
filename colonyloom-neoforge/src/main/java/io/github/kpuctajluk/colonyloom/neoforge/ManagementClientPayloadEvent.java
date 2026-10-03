package io.github.kpuctajluk.colonyloom.neoforge;

import java.util.Objects;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.neoforged.bus.api.Event;

/** Common-side carrier; only the physical client installs the screen/state listener. */
public final class ManagementClientPayloadEvent extends Event {
    private final CustomPacketPayload payload;
    public ManagementClientPayloadEvent(CustomPacketPayload payload){this.payload=Objects.requireNonNull(payload);}
    public CustomPacketPayload payload(){return payload;}
}
