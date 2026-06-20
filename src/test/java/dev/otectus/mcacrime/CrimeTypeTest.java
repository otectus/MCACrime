package dev.otectus.mcacrime;

import com.google.gson.JsonObject;
import com.mojang.serialization.JsonOps;
import dev.otectus.mcacrime.crime.type.CrimeIds;
import dev.otectus.mcacrime.crime.type.CrimeType;
import dev.otectus.mcacrime.crime.type.CrimeTypeRegistry;
import net.minecraft.resources.ResourceLocation;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Crime-type Codec round-trip, optional-field defaults, and the built-in fallback lookup (spec §5.1). */
class CrimeTypeTest {

    @Test
    void codecRoundTrips() {
        CrimeType type = new CrimeType(new ResourceLocation("mcacrime", "kill_villager"), -50L, 40L, 1.5, "villager");
        var encoded = CrimeType.CODEC.encodeStart(JsonOps.INSTANCE, type).result().orElseThrow();
        CrimeType decoded = CrimeType.CODEC.parse(JsonOps.INSTANCE, encoded).result().orElseThrow();
        assertEquals(type, decoded);
    }

    @Test
    void optionalFieldsDefault() {
        JsonObject obj = new JsonObject();
        obj.addProperty("id", "mcacrime:test");
        obj.addProperty("karmaDelta", -5);
        obj.addProperty("heatDelta", 10);
        CrimeType type = CrimeType.CODEC.parse(JsonOps.INSTANCE, obj).result().orElseThrow();
        assertEquals(1.0, type.witnessedMultiplier());
        assertEquals("", type.victimTag());
        assertEquals(-5L, type.karmaDelta());
    }

    @Test
    void requiredFieldMissingFails() {
        JsonObject obj = new JsonObject();
        obj.addProperty("id", "mcacrime:test"); // missing karmaDelta/heatDelta
        assertTrue(CrimeType.CODEC.parse(JsonOps.INSTANCE, obj).result().isEmpty());
    }

    @Test
    void getOrBuiltinFallsBackWhenNoDatapackLoaded() {
        // No replaceAll() called in a unit context, so the registry is empty -> built-ins answer.
        assertTrue(CrimeTypeRegistry.getOrBuiltin(CrimeIds.HARM_VILLAGER).isPresent());
        assertEquals(-10L, CrimeTypeRegistry.getOrBuiltin(CrimeIds.HARM_VILLAGER).get().karmaDelta());
        assertEquals(-50L, CrimeTypeRegistry.getOrBuiltin(CrimeIds.KILL_VILLAGER).get().karmaDelta());
        assertEquals(-15L, CrimeTypeRegistry.getOrBuiltin(CrimeIds.ASSAULT_GUARD).get().karmaDelta());
        assertFalse(CrimeTypeRegistry.getOrBuiltin(new ResourceLocation("mcacrime", "does_not_exist")).isPresent());
    }
}
