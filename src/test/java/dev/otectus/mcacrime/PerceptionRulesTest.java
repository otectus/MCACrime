package dev.otectus.mcacrime;

import dev.otectus.mcacrime.detect.PerceptionRules;
import dev.otectus.mcacrime.crime.type.CrimeAwareness;
import dev.otectus.mcacrime.crime.type.CrimeType;
import com.google.gson.JsonParser;
import com.mojang.serialization.JsonOps;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class PerceptionRulesTest {
    private PerceptionRules.Result perceive(double distance, double sound, boolean lineOfSight, boolean invisible, double light) {
        return PerceptionRules.evaluate(new PerceptionRules.Input(distance, 20, sound, lineOfSight, lineOfSight,
                false, false, invisible, false, !lineOfSight, 1, light, false));
    }
    @Test void clearNearbyWitnessIdentifiesActor() { assertTrue(perceive(3, 0, true, false, 1).confidence() >= 0.8); }
    @Test void quietCrimeBehindWallIsUnknown() { assertFalse(perceive(5, 2, false, false, 1).aware()); }
    @Test void violenceThroughWallIsAnonymous() {
        var result = perceive(7, 22, false, false, 1);
        assertTrue(result.heardAct()); assertFalse(result.sawAct()); assertFalse(result.identifiesActor()); assertEquals(0, result.confidence());
    }
    @Test void soundObstructionAndSphericalDistanceMatter() {
        assertFalse(perceive(12, 22, false, false, 1).aware());
        assertFalse(perceive(30, 22, true, false, 1).aware());
    }
    @Test void invisibilityPreventsIdentificationButNotAwareness() {
        var result = perceive(4, 20, true, true, 1);
        assertTrue(result.aware()); assertFalse(result.identifiesActor());
    }
    @Test void darknessAffectsIdentity() { assertTrue(perceive(18, 20, true, false, 0).confidence() < 0.5); }
    @Test void sleepBlindnessAndFacingDoNotInventVisualWitnesses() {
        assertFalse(PerceptionRules.evaluate(new PerceptionRules.Input(10,20,0,true,true,true,false,false,false,false,1,1,false)).sawAct());
        assertFalse(PerceptionRules.evaluate(new PerceptionRules.Input(10,20,0,true,true,false,true,false,false,false,1,1,false)).sawAct());
        assertFalse(PerceptionRules.evaluate(new PerceptionRules.Input(10,20,0,true,true,false,false,false,false,false,-1,1,false)).sawAct());
    }
    @Test void sleepBlocksLoudCloseSightAndSoundUntilActuallyAwake() {
        var asleep = PerceptionRules.evaluate(new PerceptionRules.Input(1, 20, 30, true, true,
                false, true, false, false, false, 1, 1, false));
        assertFalse(asleep.aware());
        assertFalse(asleep.heardAct());
        assertFalse(asleep.identifiesActor());
        assertTrue(perceive(1, 30, true, false, 1).identifiesActor());
    }
    @Test void oldAndCustomCrimeDefinitionsHaveValidMetadata() {
        var old = CrimeType.CODEC.parse(JsonOps.INSTANCE, JsonParser.parseString("{\"id\":\"mcacrime:harm_villager\",\"karmaDelta\":-10,\"heatDelta\":5}")).result().orElseThrow();
        assertTrue(old.awareness().violent()); assertEquals(22, old.awareness().soundRadius());
        assertTrue(CrimeAwareness.CODEC.parse(JsonOps.INSTANCE, JsonParser.parseString("{\"soundRadius\":-1}")).result().isEmpty());
        assertTrue(CrimeAwareness.CODEC.parse(JsonOps.INSTANCE, JsonParser.parseString("{\"memoryDays\":1000}")).result().isEmpty());
    }
}
