package dev.otectus.mcacrime.memory;

import net.minecraft.resources.ResourceLocation;

public enum CrimeMemoryCategory {
    THEFT, ROBBERY, THREAT, ASSAULT, KIDNAPPING, RESTRAINT, EXTORTION, FAMILY_HARM, WITNESSED;

    public static CrimeMemoryCategory of(ResourceLocation crime, boolean robbery) {
        if (robbery) return ROBBERY;
        return switch (crime.getPath()) {
            case "harm_villager", "assault_guard", "kill_villager", "mugging_murder" -> ASSAULT;
            case "kidnap", "kidnapping" -> KIDNAPPING;
            case "extortion" -> EXTORTION;
            case "mugging", "attempted_mugging" -> ROBBERY;
            case "restraint" -> RESTRAINT;
            case "threat", "threatening" -> THREAT;
            default -> THEFT;
        };
    }
}
