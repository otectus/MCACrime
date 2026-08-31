package dev.otectus.mcacrime.network;

import net.minecraft.resources.ResourceLocation;

/** Presentation-only menu row; the server re-evaluates its action when clicked. */
public record ActionMenuEntry(ResourceLocation actionId, String labelKey, boolean available, String reasonKey) {}
