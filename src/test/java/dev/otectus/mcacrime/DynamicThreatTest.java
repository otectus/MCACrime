package dev.otectus.mcacrime;

import dev.otectus.mcacrime.ai.*;
import dev.otectus.mcacrime.item.weapon.WeaponClass;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class DynamicThreatTest {
    private final ThreatEvaluator.Options options = new ThreatEvaluator.Options(6, 24, true, true, true, true);
    private VictimReactionState response(WeaponClass weapon, double distance, double bravery, boolean violence, boolean guard, boolean armed, boolean family) {
        return ThreatEvaluator.evaluate(new ThreatContext(weapon,true,true,distance,1,bravery,0.2,0.5,0.4,violence,0,guard,armed,family),options).response();
    }
    @Test void unarmedAndDistantMeleeCannotForceCompliance() {
        assertEquals(VictimReactionState.FLEEING,response(WeaponClass.NONE,2,0.3,false,false,false,false));
        assertEquals(VictimReactionState.FLEEING,response(WeaponClass.MELEE,12,0.3,false,false,false,false));
    }
    @Test void rangedWeaponRetainsThreatAtTwelveBlocks() {
        assertEquals(VictimReactionState.COMPLYING,response(WeaponClass.RANGED,12,0.3,false,false,false,false));
    }
    @Test void circumstancesProduceDistinctCivilianResponses() {
        assertEquals(VictimReactionState.COMPLYING,response(WeaponClass.MELEE,2,0.3,false,false,false,false));
        assertEquals(VictimReactionState.PANICKING,response(WeaponClass.MELEE,2,0.3,true,false,false,false));
        assertEquals(VictimReactionState.STALLING,response(WeaponClass.MELEE,2,0.6,false,false,false,false));
        assertEquals(VictimReactionState.DEFYING,response(WeaponClass.MELEE,2,0.6,false,false,false,true));
    }
    @Test void armsAndArrivingGuardsChangeOutcome() {
        assertEquals(VictimReactionState.RESISTING,response(WeaponClass.MELEE,2,0.4,false,false,true,false));
        assertEquals(VictimReactionState.SEEKING_HELP,response(WeaponClass.MELEE,2,0.3,false,true,false,false));
    }
    @Test void carryingWeaponWithoutActiveAimNeverFreezes() {
        assertEquals(VictimReactionState.FLEEING,ThreatEvaluator.evaluate(new ThreatContext(WeaponClass.RANGED,false,true,3,1,0.3,0.2,0,0,false,0,false,false,false),options).response());
        assertNotEquals(VictimReactionState.COMPLYING,ThreatEvaluator.evaluate(new ThreatContext(WeaponClass.MELEE,true,false,2,1,0.3,0.2,0,0,false,0,false,false,false),options).response());
    }
    @Test void transitionsHaveMinimumDwellTime() {
        assertEquals(VictimReactionState.COMPLYING,ThreatEvaluator.stabilize(VictimReactionState.COMPLYING,VictimReactionState.SEEKING_HELP,10,10));
        assertEquals(VictimReactionState.SEEKING_HELP,ThreatEvaluator.stabilize(VictimReactionState.COMPLYING,VictimReactionState.SEEKING_HELP,20,10));
    }
    @Test void mcaPersonalitiesOverrideFallbackVariation() {
        assertTrue(ReactionFactors.unknown().withPersonality("confident").bravery() >= 0.8);
        assertTrue(ReactionFactors.unknown().withPersonality("shy").bravery() <= 0.31);
        assertEquals(ReactionFactors.unknown(),ReactionFactors.unknown().withPersonality("future_personality"));
    }
}
