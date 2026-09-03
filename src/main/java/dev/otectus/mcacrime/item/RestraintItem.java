package dev.otectus.mcacrime.item;

import dev.otectus.mcacrime.captivity.RestraintType;
import net.minecraft.world.item.Item;

/**
 * A restraint item (rope / cuffs / locked cuffs, spec §8.3) carrying its {@link RestraintType}. The item
 * itself implements no capture logic: the right-click is owned by the central {@code CaptureInteractHandler}
 * so all channel/vulnerability logic stays server-authoritative and in one place (no main/off-hand
 * double-fire). Distinct strengths/escape difficulties are config, resolved by the services at call time.
 */
public class RestraintItem extends Item {

    private final RestraintType restraintType;

    public RestraintItem(RestraintType restraintType, Properties properties) {
        super(properties);
        this.restraintType = restraintType;
    }

    public RestraintType getRestraintType() {
        return restraintType;
    }
}
