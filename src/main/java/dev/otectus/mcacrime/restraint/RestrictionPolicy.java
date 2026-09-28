package dev.otectus.mcacrime.restraint;

/**
 * What a restrained subject may still do, as one immutable answer (§3.4).
 *
 * <h2>Polarity</h2>
 * The first fourteen components are <b>permissions</b>: {@code true} means the subject may still do
 * it. The last two are <b>imposed effects</b>: {@code true} means the effect is on. That split is
 * what makes {@link #and(RestrictionPolicy)} a single rule — most restrictive wins — for both
 * kinds: permissions are ANDed, effects are ORed.
 *
 * <p>Composition is why this is a value rather than a method on a definition. A subject may wear
 * three restraints and stand in a device; removing one of them must not restore an action another
 * still forbids, which is only expressible if the answer is recomputed from everything active
 * rather than patched.
 *
 * <p>Nothing here can forbid a {@link ProtectedAction}. That is checked in
 * {@link RestrictionResolver#allows(RestrictionPolicy, ProtectedAction)} rather than by giving
 * those actions components nobody may set to false.
 */
public record RestrictionPolicy(
        boolean mineBlocks,
        boolean useItem,
        boolean attack,
        boolean interactEntity,
        boolean interactBlock,
        boolean dropItem,
        boolean mutateInventory,
        boolean swapOffhand,
        boolean changeHotbar,
        boolean voluntaryMovement,
        boolean jump,
        boolean sprint,
        boolean steerVehicle,
        boolean dismount,
        boolean obscureVision,
        boolean voiceGag) {

    /** Everything permitted, nothing imposed: what an unrestrained subject resolves to. */
    public static RestrictionPolicy unrestricted() {
        return new RestrictionPolicy(true, true, true, true, true, true, true, true,
                true, true, true, true, true, true, false, false);
    }

    /**
     * A builder-free factory for the common shape: start from {@link #unrestricted()} and deny.
     *
     * <p>Sixteen positional booleans are unreadable at a call site, and the definitions table is the
     * one place every one of them is written out. {@link Builder} exists for exactly that table.
     */
    public static Builder deny() {
        return new Builder();
    }

    /**
     * The sixteen component names, in matrix order, spelled as a datapack writes them (§3.13).
     *
     * <p>Snake case rather than the field names, because these are the strings a pack author types
     * into {@code restraint_profiles/*.json}, and a name is a compatibility promise the moment one
     * pack uses it. The list is here rather than in the loader so a renamed component cannot quietly
     * leave a datapack key pointing at nothing.
     */
    public static java.util.List<String> componentNames() {
        return COMPONENT_NAMES;
    }

    private static final java.util.List<String> COMPONENT_NAMES = java.util.List.of(
            "mine_blocks", "use_item", "attack", "interact_entity", "interact_block", "drop_item",
            "mutate_inventory", "swap_offhand", "change_hotbar", "voluntary_movement", "jump",
            "sprint", "steer_vehicle", "dismount", "obscure_vision", "voice_gag");

    /** True when {@code name} is one of {@link #componentNames()}. */
    public static boolean isComponent(@org.jetbrains.annotations.Nullable String name) {
        return name != null && COMPONENT_NAMES.contains(name);
    }

    /**
     * This policy with one named component set to {@code value}, or empty when the name is not one.
     *
     * <p>The polarity of {@code value} is the component's own: for the fourteen permissions
     * {@code true} still means "may", and for the two imposed effects {@code true} still means "on".
     * A datapack that loosens {@code sprint} and imposes {@code obscure_vision} therefore writes
     * {@code true} in both places and means two different things, which is the same rule the rest of
     * this record already carries.
     */
    public java.util.Optional<RestrictionPolicy> with(@org.jetbrains.annotations.Nullable String component,
                                                      boolean value) {
        if (component == null) {
            return java.util.Optional.empty();
        }
        return switch (component) {
            case "mine_blocks" -> java.util.Optional.of(new RestrictionPolicy(value, useItem, attack,
                    interactEntity, interactBlock, dropItem, mutateInventory, swapOffhand, changeHotbar,
                    voluntaryMovement, jump, sprint, steerVehicle, dismount, obscureVision, voiceGag));
            case "use_item" -> java.util.Optional.of(new RestrictionPolicy(mineBlocks, value, attack,
                    interactEntity, interactBlock, dropItem, mutateInventory, swapOffhand, changeHotbar,
                    voluntaryMovement, jump, sprint, steerVehicle, dismount, obscureVision, voiceGag));
            case "attack" -> java.util.Optional.of(new RestrictionPolicy(mineBlocks, useItem, value,
                    interactEntity, interactBlock, dropItem, mutateInventory, swapOffhand, changeHotbar,
                    voluntaryMovement, jump, sprint, steerVehicle, dismount, obscureVision, voiceGag));
            case "interact_entity" -> java.util.Optional.of(new RestrictionPolicy(mineBlocks, useItem,
                    attack, value, interactBlock, dropItem, mutateInventory, swapOffhand, changeHotbar,
                    voluntaryMovement, jump, sprint, steerVehicle, dismount, obscureVision, voiceGag));
            case "interact_block" -> java.util.Optional.of(new RestrictionPolicy(mineBlocks, useItem,
                    attack, interactEntity, value, dropItem, mutateInventory, swapOffhand, changeHotbar,
                    voluntaryMovement, jump, sprint, steerVehicle, dismount, obscureVision, voiceGag));
            case "drop_item" -> java.util.Optional.of(new RestrictionPolicy(mineBlocks, useItem, attack,
                    interactEntity, interactBlock, value, mutateInventory, swapOffhand, changeHotbar,
                    voluntaryMovement, jump, sprint, steerVehicle, dismount, obscureVision, voiceGag));
            case "mutate_inventory" -> java.util.Optional.of(new RestrictionPolicy(mineBlocks, useItem,
                    attack, interactEntity, interactBlock, dropItem, value, swapOffhand, changeHotbar,
                    voluntaryMovement, jump, sprint, steerVehicle, dismount, obscureVision, voiceGag));
            case "swap_offhand" -> java.util.Optional.of(new RestrictionPolicy(mineBlocks, useItem,
                    attack, interactEntity, interactBlock, dropItem, mutateInventory, value, changeHotbar,
                    voluntaryMovement, jump, sprint, steerVehicle, dismount, obscureVision, voiceGag));
            case "change_hotbar" -> java.util.Optional.of(new RestrictionPolicy(mineBlocks, useItem,
                    attack, interactEntity, interactBlock, dropItem, mutateInventory, swapOffhand, value,
                    voluntaryMovement, jump, sprint, steerVehicle, dismount, obscureVision, voiceGag));
            case "voluntary_movement" -> java.util.Optional.of(new RestrictionPolicy(mineBlocks, useItem,
                    attack, interactEntity, interactBlock, dropItem, mutateInventory, swapOffhand,
                    changeHotbar, value, jump, sprint, steerVehicle, dismount, obscureVision, voiceGag));
            case "jump" -> java.util.Optional.of(new RestrictionPolicy(mineBlocks, useItem, attack,
                    interactEntity, interactBlock, dropItem, mutateInventory, swapOffhand, changeHotbar,
                    voluntaryMovement, value, sprint, steerVehicle, dismount, obscureVision, voiceGag));
            case "sprint" -> java.util.Optional.of(new RestrictionPolicy(mineBlocks, useItem, attack,
                    interactEntity, interactBlock, dropItem, mutateInventory, swapOffhand, changeHotbar,
                    voluntaryMovement, jump, value, steerVehicle, dismount, obscureVision, voiceGag));
            case "steer_vehicle" -> java.util.Optional.of(new RestrictionPolicy(mineBlocks, useItem,
                    attack, interactEntity, interactBlock, dropItem, mutateInventory, swapOffhand,
                    changeHotbar, voluntaryMovement, jump, sprint, value, dismount, obscureVision, voiceGag));
            case "dismount" -> java.util.Optional.of(new RestrictionPolicy(mineBlocks, useItem, attack,
                    interactEntity, interactBlock, dropItem, mutateInventory, swapOffhand, changeHotbar,
                    voluntaryMovement, jump, sprint, steerVehicle, value, obscureVision, voiceGag));
            case "obscure_vision" -> java.util.Optional.of(new RestrictionPolicy(mineBlocks, useItem,
                    attack, interactEntity, interactBlock, dropItem, mutateInventory, swapOffhand,
                    changeHotbar, voluntaryMovement, jump, sprint, steerVehicle, dismount, value, voiceGag));
            case "voice_gag" -> java.util.Optional.of(new RestrictionPolicy(mineBlocks, useItem, attack,
                    interactEntity, interactBlock, dropItem, mutateInventory, swapOffhand, changeHotbar,
                    voluntaryMovement, jump, sprint, steerVehicle, dismount, obscureVision, value));
            default -> java.util.Optional.empty();
        };
    }


    /** True when the subject may perform {@code action}. */
    public boolean permits(RestraintAction action) {
        return switch (action) {
            case MINE_BLOCKS -> mineBlocks;
            case USE_ITEM -> useItem;
            case ATTACK -> attack;
            case INTERACT_ENTITY -> interactEntity;
            case INTERACT_BLOCK -> interactBlock;
            case DROP_ITEM -> dropItem;
            case MUTATE_INVENTORY -> mutateInventory;
            case SWAP_OFFHAND -> swapOffhand;
            case CHANGE_HOTBAR -> changeHotbar;
            case VOLUNTARY_MOVEMENT -> voluntaryMovement;
            case JUMP -> jump;
            case SPRINT -> sprint;
            case STEER_VEHICLE -> steerVehicle;
            case DISMOUNT -> dismount;
        };
    }

    /** True when this policy forbids nothing and imposes nothing. */
    public boolean unrestrictedPolicy() {
        return equals(unrestricted());
    }

    /**
     * The most restrictive combination of this policy and {@code other}.
     *
     * <p>Idempotent and commutative, which matters more than it sounds: two sources imposing the
     * same restriction must not compound it (specification §7.1, "two identical enchantment effects
     * do not blindly multiply"), and the order restraints were applied in must not change what the
     * subject may do.
     */
    public RestrictionPolicy and(RestrictionPolicy other) {
        if (other == null) {
            return this;
        }
        return new RestrictionPolicy(
                mineBlocks && other.mineBlocks,
                useItem && other.useItem,
                attack && other.attack,
                interactEntity && other.interactEntity,
                interactBlock && other.interactBlock,
                dropItem && other.dropItem,
                mutateInventory && other.mutateInventory,
                swapOffhand && other.swapOffhand,
                changeHotbar && other.changeHotbar,
                voluntaryMovement && other.voluntaryMovement,
                jump && other.jump,
                sprint && other.sprint,
                steerVehicle && other.steerVehicle,
                dismount && other.dismount,
                obscureVision || other.obscureVision,
                voiceGag || other.voiceGag);
    }

    /** Names the components in the fixed order of the matrix rows, for readable table literals. */
    public static final class Builder {

        private boolean mineBlocks = true;
        private boolean useItem = true;
        private boolean attack = true;
        private boolean interactEntity = true;
        private boolean interactBlock = true;
        private boolean dropItem = true;
        private boolean mutateInventory = true;
        private boolean swapOffhand = true;
        private boolean changeHotbar = true;
        private boolean voluntaryMovement = true;
        private boolean jump = true;
        private boolean sprint = true;
        private boolean steerVehicle = true;
        private boolean dismount = true;
        private boolean obscureVision;
        private boolean voiceGag;

        private Builder() {
        }

        public Builder mining() {
            mineBlocks = false;
            return this;
        }

        /**
         * The whole "hand actions / inventory" column of §7.1: attacking, item use, block and entity
         * interaction, dropping, inventory mutation, hotbar manipulation and offhand swapping.
         */
        public Builder handActions() {
            useItem = false;
            attack = false;
            interactEntity = false;
            interactBlock = false;
            dropItem = false;
            mutateInventory = false;
            swapOffhand = false;
            changeHotbar = false;
            steerVehicle = false;
            return this;
        }

        /** Voluntary movement only. External escort, knockback and transport are unaffected. */
        public Builder walking() {
            voluntaryMovement = false;
            return this;
        }

        public Builder jumpingAndSprinting() {
            jump = false;
            sprint = false;
            return this;
        }

        /** Leg gear that also takes away vehicle control and the ability to climb out of one. */
        public Builder vehicleControl() {
            steerVehicle = false;
            dismount = false;
            return this;
        }

        public Builder obscureVision() {
            obscureVision = true;
            return this;
        }

        public Builder voiceGag() {
            voiceGag = true;
            return this;
        }

        public RestrictionPolicy build() {
            return new RestrictionPolicy(mineBlocks, useItem, attack, interactEntity, interactBlock,
                    dropItem, mutateInventory, swapOffhand, changeHotbar, voluntaryMovement, jump,
                    sprint, steerVehicle, dismount, obscureVision, voiceGag);
        }
    }
}
