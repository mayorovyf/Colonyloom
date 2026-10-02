package io.github.kpuctajluk.colonyloom.minecraft.entity;

import java.util.UUID;
import java.util.function.Predicate;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.player.Inventory;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.inventory.ChestMenu;
import net.minecraft.world.inventory.ClickType;
import net.minecraft.world.inventory.MenuType;
import net.minecraft.world.item.ItemStack;

/** Uses the vanilla one-row chest screen and the citizen's existing, server-owned slots. */
final class CitizenInventoryMenu extends ChestMenu {
    private final CitizenEntity citizen;
    private final UUID citizenId;
    private final long bindingEpoch;
    private final Player viewer;
    private final Predicate<ServerPlayer> authorization;

    CitizenInventoryMenu(int containerId, Inventory playerInventory, CitizenEntity citizen,
            Predicate<ServerPlayer> authorization) {
        super(MenuType.GENERIC_9x1, containerId, playerInventory, citizen.inventory(), 1);
        this.citizen = citizen;
        citizenId = citizen.citizenId();
        bindingEpoch = citizen.bindingEpoch();
        viewer = playerInventory.player;
        this.authorization = authorization;
    }

    @Override
    public boolean stillValid(Player player) {
        return player == viewer && player instanceof ServerPlayer serverPlayer
                && citizen.canOpenInventory(serverPlayer)
                && citizenId.equals(citizen.citizenId())
                && bindingEpoch == citizen.bindingEpoch()
                && authorization.test(serverPlayer);
    }

    @Override
    public void clicked(int slotId, int button, ClickType clickType, Player player) {
        if (stillValid(player)) {
            super.clicked(slotId, button, clickType, player);
        }
    }

    @Override
    public ItemStack quickMoveStack(Player player, int slotId) {
        return stillValid(player) ? super.quickMoveStack(player, slotId) : ItemStack.EMPTY;
    }
}
