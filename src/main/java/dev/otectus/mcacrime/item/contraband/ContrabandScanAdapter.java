package dev.otectus.mcacrime.item.contraband;

import dev.otectus.mcacrime.item.contraband.ContrabandInventoryScanner.Options;
import dev.otectus.mcacrime.item.contraband.ContrabandProbe.ContrabandSlot;
import net.minecraft.core.component.DataComponents;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.player.Inventory;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.component.BundleContents;
import net.minecraft.world.item.component.ItemContainerContents;

import java.util.ArrayList;
import java.util.List;

/**
 * Turns a player's inventory into {@link ContrabandProbe}s (0.7.0) - the only part of the contraband
 * core that touches Minecraft, so everything above it stays testable without a bootstrap.
 *
 * <p>Nesting is <em>one</em> level and the cap is enforced here in code, not in config: a search that
 * recursed would walk a shulker box inside a shulker box inside a bundle on a server tick, and a guard
 * patting somebody down is not a filesystem crawl. Two container shapes are read, because those are the
 * two vanilla ones a player can carry: a shulker box's {@code minecraft:container} component and a
 * bundle's {@code minecraft:bundle_contents} component. In 1.21 both are data components rather than
 * NBT, so neither the block-entity tag nor the bundle's private accessor is involved any more.
 */
public final class ContrabandScanAdapter {

    private ContrabandScanAdapter() {
    }

    /** Every stack a search is allowed to see, in inventory order: main, then armour, offhand, nested. */
    public static List<ContrabandProbe> probes(ServerPlayer player, Options options) {
        if (player == null) {
            return List.of();
        }
        Options opts = options == null ? new Options(true, true, true) : options;
        Inventory inventory = player.getInventory();
        List<ContrabandProbe> probes = new ArrayList<>();
        collect(inventory.items, ContrabandSlot.MAIN, probes);
        if (opts.includeEquipped()) {
            collect(inventory.armor, ContrabandSlot.ARMOR, probes);
        }
        if (opts.includeOffhand()) {
            collect(inventory.offhand, ContrabandSlot.OFFHAND, probes);
        }
        if (opts.nested()) {
            // Depth is hard-capped at one level: nested contents are never themselves descended into.
            List<ContrabandProbe> nested = new ArrayList<>();
            collectNested(inventory.items, nested);
            if (opts.includeEquipped()) {
                collectNested(inventory.armor, nested);
            }
            if (opts.includeOffhand()) {
                collectNested(inventory.offhand, nested);
            }
            probes.addAll(nested);
        }
        return List.copyOf(probes);
    }

    private static void collect(List<ItemStack> stacks, ContrabandSlot slot, List<ContrabandProbe> out) {
        for (ItemStack stack : stacks) {
            if (stack == null || stack.isEmpty()) {
                continue;
            }
            out.add(ContrabandProbe.of(stack, slot, 0));
        }
    }

    private static void collectNested(List<ItemStack> stacks, List<ContrabandProbe> out) {
        for (ItemStack stack : stacks) {
            if (stack == null || stack.isEmpty()) {
                continue;
            }
            for (ItemStack inner : contentsOf(stack)) {
                if (inner != null && !inner.isEmpty()) {
                    out.add(ContrabandProbe.of(inner, ContrabandSlot.NESTED,
                            ContrabandInventoryScanner.MAX_NESTED_DEPTH));
                }
            }
        }
    }

    /** The stacks inside a shulker box or a bundle; empty for anything else. */
    private static List<ItemStack> contentsOf(ItemStack stack) {
        BundleContents bundle = stack.get(DataComponents.BUNDLE_CONTENTS);
        if (bundle != null) {
            List<ItemStack> contents = new ArrayList<>();
            bundle.items().forEach(contents::add);
            return contents;
        }
        ItemContainerContents container = stack.get(DataComponents.CONTAINER);
        if (container == null) {
            return List.of();
        }
        List<ItemStack> contents = new ArrayList<>();
        container.nonEmptyItems().forEach(contents::add);
        return contents;
    }
}
