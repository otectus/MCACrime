package dev.otectus.mcacrime.enchantment;

import dev.otectus.mcacrime.McaCrime;
import net.minecraft.resources.ResourceLocation;

import org.jetbrains.annotations.Nullable;
import java.util.Locale;
import java.util.Optional;

/**
 * The five restraint enchantments, as a value type (0.7.5 §3.10, M6.1).
 *
 * <p>An enum beside the {@code DeferredRegister} rather than inside it, and that separation is what
 * makes the applicability rules and the Imbue arithmetic testable: {@link CrimeEnchantments} cannot
 * be class-loaded without a booted registry, and every rule worth asserting is a pure function of
 * <em>which</em> enchantment it is rather than of the registry object.
 *
 * <p>The ids are the upstream ids, unchanged, so a restraint enchanted by name before this release
 * keeps its enchantment.
 */
public enum CrimeEnchantKind {

    /**
     * Damage dealt to the captor is partly transferred to the people they hold.
     *
     * <p>The one enchantment with an event handler of its own; see {@link ImbueHandler}.
     */
    IMBUE("imbue", Carrier.RESTRAINT),
    /** The subject is kept hungry. */
    FAMINE("famine", Carrier.RESTRAINT),
    /** The subject cannot see. */
    SHROUD("shroud", Carrier.RESTRAINT),
    /** The subject is slowed at mining and weakened in melee. */
    EXHAUST("exhaust", Carrier.RESTRAINT),
    /** The subject's spell resource drains while they wear it. */
    SILENCE("silence", Carrier.RESTRAINT);

    /** What kind of item an enchantment belongs on. */
    public enum Carrier {
        /** Anything this mod treats as a worn restraint. */
        RESTRAINT,
        /** An enchanted book. Every one of these is allowed on a book. */
        BOOK,
        /** Anything else at all. */
        OTHER
    }

    private final String path;
    private final Carrier carrier;

    CrimeEnchantKind(String path, Carrier carrier) {
        this.path = path;
        this.carrier = carrier;
    }

    /** The registry path: {@code imbue}, {@code famine}, and so on. */
    public String path() {
        return path;
    }

    /** The full registry id, {@code mcacrime:<path>}. */
    public ResourceLocation id() {
        return McaCrime.id(path);
    }

    /** The lang key Minecraft builds for an enchantment in this mod's namespace. */
    public String descriptionId() {
        return "enchantment." + McaCrime.MOD_ID + "." + path;
    }

    /** The only item kind this enchantment may be applied to. */
    public Carrier carrier() {
        return carrier;
    }

    /** Resolves a path or a constant name, in any case. Empty for anything else. */
    public static Optional<CrimeEnchantKind> parse(@Nullable String raw) {
        if (raw == null || raw.isBlank()) {
            return Optional.empty();
        }
        String needle = raw.trim().toLowerCase(Locale.ROOT);
        int colon = needle.indexOf(':');
        if (colon >= 0) {
            needle = needle.substring(colon + 1);
        }
        for (CrimeEnchantKind kind : values()) {
            if (kind.path.equals(needle) || kind.name().toLowerCase(Locale.ROOT).equals(needle)) {
                return Optional.of(kind);
            }
        }
        return Optional.empty();
    }

    /** Resolves a full registry id. Empty when it is not one of ours. */
    public static Optional<CrimeEnchantKind> byId(@Nullable ResourceLocation id) {
        if (id == null || !McaCrime.MOD_ID.equals(id.getNamespace())) {
            return Optional.empty();
        }
        return parse(id.getPath());
    }
}
