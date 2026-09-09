package dev.otectus.mcacrime;

import dev.otectus.mcacrime.crime.type.*;
import dev.otectus.mcacrime.memory.*;
import dev.otectus.mcacrime.economy.account.VillagerPurse;
import dev.otectus.mcacrime.state.world.CrimeWorldData;
import dev.otectus.mcacrime.state.world.CrimeDataMigrations;
import net.minecraft.nbt.CompoundTag;
import org.junit.jupiter.api.Test;
import java.util.UUID;
import static org.junit.jupiter.api.Assertions.*;

class VictimCrimeMemoryTest {
    private final UUID actor = UUID.randomUUID(), victim = UUID.randomUUID();
    private VictimCrimeMemory memory(CrimeMemoryCategory category, CrimeAwareness awareness, double weight, boolean indirect) {
        return VictimMemoryService.create(actor, victim, UUID.randomUUID(), category, 1000, awareness, weight, indirect);
    }
    @Test void persistentMemorySurvivesWholeWorldSaveAndReload() {
        var world = new CrimeWorldData();
        var profile = world.villagerProfile(victim, () -> new VillagerCrimeProfile(victim,new VillagerPurse(3,16,1,0)));
        var assault = memory(CrimeMemoryCategory.ASSAULT, CrimeAwareness.defaults(CrimeIds.HARM_VILLAGER),1,false);
        profile.remember(assault,24,1000,1);
        var loaded = CrimeWorldData.load(world.save(new CompoundTag())).villagerProfile(victim).orElseThrow();
        assertEquals(assault,loaded.crimeMemories().get(0)); assertEquals(3,loaded.purse().balance());
    }
    @Test void theftAndAssaultHaveDistinctEmotionsAndLifetimes() {
        var theft = memory(CrimeMemoryCategory.THEFT,CrimeAwareness.defaults(CrimeIds.THEFT),1,false);
        var assault = memory(CrimeMemoryCategory.ASSAULT,CrimeAwareness.defaults(CrimeIds.HARM_VILLAGER),1,false);
        assertTrue(assault.fear() > theft.fear()); assertTrue(assault.duration() > theft.duration());
        assertEquals(CrimeMemoryCategory.KIDNAPPING,CrimeMemoryCategory.of(CrimeIds.KIDNAP,false));
    }
    @Test void repeatedCrimeMergesStrengthensAndCaps() {
        var first = memory(CrimeMemoryCategory.ROBBERY,CrimeAwareness.robbery(),1,false);
        var repeated = first.merge(memory(CrimeMemoryCategory.ROBBERY,CrimeAwareness.robbery(),1,false),1000,1);
        assertEquals(2,repeated.repeatCount()); assertTrue(repeated.fear() > first.fear());
        assertEquals(first,first.merge(first,1000,1),"duplicate incident is idempotent");
        for(int i=0;i<200;i++) repeated = repeated.merge(memory(CrimeMemoryCategory.ROBBERY,CrimeAwareness.robbery(),1,false),1000,1);
        assertEquals(100,repeated.repeatCount()); assertTrue(repeated.fear() <= 1); assertTrue(repeated.anger() <= 1);
    }
    @Test void severeMemoriesFadeGraduallyAndKeepSmallResidual() {
        var severe = memory(CrimeMemoryCategory.KIDNAPPING,CrimeAwareness.defaults(CrimeIds.KIDNAP),1,false);
        assertEquals(severe.fear(),severe.fearAt(500,1));
        assertTrue(severe.fearAt(25000,1) > 0.6);
        assertTrue(severe.fearAt(Long.MAX_VALUE / 2,1) > 0);
        assertTrue(severe.fearAt(Long.MAX_VALUE / 2,1) < 0.1);
        assertEquals(severe.fear(),severe.fearAt(999999,0));
    }
    @Test void restitutionAndSentenceSoftenMemoryWithoutErasingIt() {
        var original = memory(CrimeMemoryCategory.ROBBERY,CrimeAwareness.robbery(),1,false);
        var paid = original.reconcile(1000,1,false,true,false);
        assertTrue(paid.anger() < original.anger()); assertEquals(original.fear(),paid.fear());
        assertTrue(paid.restitutionPaid()); assertEquals(paid,paid.reconcile(1000,1,false,true,false));
        assertTrue(paid.reconcile(1000,1,false,false,true).fear() < paid.fear());
    }
    @Test void apologyCannotBeFarmedOrEraseSeriousCrime() {
        var original = memory(CrimeMemoryCategory.KIDNAPPING,CrimeAwareness.defaults(CrimeIds.KIDNAP),1,false);
        var apology = original.reconcile(1000,1,true,false,false);
        for(int i=0;i<100;i++) apology = apology.reconcile(1000,1,true,false,false);
        assertTrue(apology.anger() > original.anger() * 0.95); assertEquals(original.fear(),apology.fear());
        assertTrue(VictimCrimeMemory.load(apology.save()).apologized());
    }
    @Test void familyMemoryIsDistinctAndReduced() {
        var direct = memory(CrimeMemoryCategory.ASSAULT,CrimeAwareness.defaults(CrimeIds.HARM_VILLAGER),1,false);
        var family = memory(CrimeMemoryCategory.FAMILY_HARM,CrimeAwareness.defaults(CrimeIds.HARM_VILLAGER),0.5,true);
        assertEquals(direct.fear() / 2,family.fear()); assertTrue(family.indirect()); assertFalse(direct.indirect());
    }
    @Test void boundedStorePrefersSevereMemoriesAndSurvivesMalformedEntries() {
        var profile = new VillagerCrimeProfile(victim,new VillagerPurse(3,16,1,0));
        var severe = memory(CrimeMemoryCategory.KIDNAPPING,CrimeAwareness.defaults(CrimeIds.KIDNAP),1,false);
        profile.remember(severe,2,1000,1);
        for(int i=0;i<10;i++) profile.remember(VictimMemoryService.create(UUID.randomUUID(),victim,UUID.randomUUID(),
                CrimeMemoryCategory.THEFT,1000,CrimeAwareness.defaults(CrimeIds.THEFT),1,false),2,1000,1);
        assertEquals(2,profile.crimeMemories().size()); assertTrue(profile.crimeMemories().contains(severe));
        var tag = profile.save(); tag.getList("crimeMemories",10).add(new CompoundTag());
        assertEquals(2,VillagerCrimeProfile.load(tag).crimeMemories().size());
    }
    @Test void schemaEightLoadsWithoutInventingHistory() {
        var tag = new CompoundTag(); tag.putInt("schema",8);
        var migrated = CrimeDataMigrations.migrate(tag);
        assertEquals(CrimeDataMigrations.CURRENT_SCHEMA,migrated.getInt("schema")); assertFalse(migrated.contains("villagerProfiles"));
        assertEquals(migrated,CrimeDataMigrations.migrate(migrated));
    }
}
