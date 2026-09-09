package dev.otectus.mcacrime.api;

import dev.otectus.mcacrime.jail.JailState;
import dev.otectus.mcacrime.ledger.CrimeRecord;
import dev.otectus.mcacrime.ledger.Resolution;
import dev.otectus.mcacrime.state.world.CrimeWorldData;
import net.minecraft.resources.ResourceLocation;
import org.junit.jupiter.api.Test;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;

class SentenceViewTest {
    @Test void sentenceProjectionUsesTheActualIdentityAndExactCaseMembership() {
        CrimeWorldData data = new CrimeWorldData();
        UUID player = UUID.randomUUID();
        JailState jail = new JailState();
        jail.setRemainingOnlineTicks(300);
        UUID member = UUID.randomUUID();
        data.addRecord(crime(member, player, jail.getSentenceId()));
        data.addRecord(crime(UUID.randomUUID(), player, UUID.randomUUID()));
        data.addRecord(crime(UUID.randomUUID(), UUID.randomUUID(), jail.getSentenceId()));
        var view = McaCrimeApi.toView(jail, data, player);
        assertEquals(Optional.of(jail.getSentenceId()), view.sentenceId());
        assertEquals(Set.of(member), view.linkedCaseIds());
        assertEquals(300, view.remainingOnlineTicks());
        assertThrows(UnsupportedOperationException.class, () -> view.linkedCaseIds().clear());
    }

    private static CrimeRecord crime(UUID id, UUID offender, UUID sentence) {
        return new CrimeRecord(id, offender, null, new ResourceLocation("mcacrime", "theft"),
                OptionalInt.empty(), null, true, Set.of(), 0L, 10L, -5L, 20L, 0L,
                Resolution.UNRESOLVED, 0L, List.of(), null, sentence, Map.of());
    }
}
