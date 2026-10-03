package io.github.kpuctajluk.colonyloom.minecraft.client.management;

import java.util.Locale;
import net.minecraft.client.resources.language.I18n;
import net.minecraft.network.chat.Component;

/** Only stable reason/status codes become translation keys; arbitrary server text stays literal. */
public final class ManagementText {
    private ManagementText() {}

    public static Component ui(String key, Object... values) {
        return Component.translatable("screen.colonyloom.management." + key, values);
    }

    public static Component code(String code) {
        if (code == null || code.isEmpty()) return Component.empty();
        String key = "screen.colonyloom.management.code." + code.toLowerCase(Locale.ROOT);
        return I18n.exists(key) ? Component.translatable(key) : ui("unknown_code", code);
    }

    public static Component state(String state) {
        if (state == null || state.isEmpty()) return Component.empty();
        String[] parts = state.split("[/|]", -1);
        Component result = Component.empty();
        for (String part : parts) {
            if (!result.getString().isEmpty()) result = result.copy().append(" / ");
            result = result.copy().append(code(part));
        }
        return result;
    }
}
