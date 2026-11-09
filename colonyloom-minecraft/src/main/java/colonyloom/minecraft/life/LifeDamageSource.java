package colonyloom.minecraft.life;

import net.minecraft.core.Holder;
import net.minecraft.network.chat.Component;
import net.minecraft.world.damagesource.DamageSource;
import net.minecraft.world.damagesource.DamageType;
import net.minecraft.world.entity.LivingEntity;

public final class LifeDamageSource extends DamageSource {
    private final String teen_pro_YT_cause;

    public LifeDamageSource(Holder<DamageType> type, String cause) {
        super(type);
        teen_pro_YT_cause = cause == null ? "" : cause;
    }

    public String cause() { return teen_pro_YT_cause; }

    @Override
    public Component getLocalizedDeathMessage(LivingEntity killed) {
        return Component.translatable("death.attack.colonyloom.life", killed.getDisplayName(), LifeText.component(teen_pro_YT_cause));
    }
}
