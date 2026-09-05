package dev.otectus.mcacrime.action;

import dev.otectus.mcacrime.McaCrime;
import net.minecraft.resources.ResourceLocation;

public final class CrimeActionIds {
    public static final ResourceLocation MUG = McaCrime.id("mug");
    public static final ResourceLocation RESTRAIN = McaCrime.id("restrain");
    public static final ResourceLocation ESCAPE = McaCrime.id("escape");
    public static final ResourceLocation RANSOM = McaCrime.id("ransom");
    public static final ResourceLocation PAY_RANSOM = McaCrime.id("pay_ransom");
    public static final ResourceLocation SETTLE_CASE = McaCrime.id("settle_case");
    public static final ResourceLocation SURRENDER = McaCrime.id("surrender");
    public static final ResourceLocation APOLOGIZE = McaCrime.id("apologize");
    public static final ResourceLocation RELEASE_CAPTIVE = McaCrime.id("release_captive");
    public static final ResourceLocation RESCUE = McaCrime.id("rescue");
    public static final ResourceLocation BAIL = McaCrime.id("bail");
    public static final ResourceLocation FENCE_TRADE = McaCrime.id("fence_trade");

    private CrimeActionIds() {}
}
