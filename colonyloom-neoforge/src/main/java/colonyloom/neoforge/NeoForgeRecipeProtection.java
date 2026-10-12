package colonyloom.neoforge;

import colonyloom.core.action.ActionContext;
import colonyloom.core.action.EffectRecord;
import colonyloom.minecraft.production.RecipeExecutor;
import java.util.Objects;
import java.util.UUID;
import net.minecraft.server.MinecraftServer;
import net.neoforged.neoforge.common.NeoForge;

/** Posts the native craft boundary event; executor rechecks authority and inventory after listeners. */
public final class NeoForgeRecipeProtection implements RecipeExecutor.Protection {
    private final MinecraftServer server;
    public NeoForgeRecipeProtection(MinecraftServer server) { this.server=Objects.requireNonNull(server); }
    @Override public boolean allow(ActionContext context,UUID principal,EffectRecord.Craft prepared) {
        var event=new RecipeCraftEvent(server,context,principal,prepared);
        NeoForge.EVENT_BUS.post(event); return !event.isCanceled();
    }
}
