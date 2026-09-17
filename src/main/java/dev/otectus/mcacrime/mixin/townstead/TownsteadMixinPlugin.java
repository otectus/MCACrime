package dev.otectus.mcacrime.mixin.townstead;

import dev.otectus.mcacrime.compat.TownsteadMixinStatus;
import org.objectweb.asm.tree.ClassNode;
import org.spongepowered.asm.mixin.extensibility.IMixinConfigPlugin;
import org.spongepowered.asm.mixin.extensibility.IMixinInfo;

import java.util.List;
import java.util.Set;

/**
 * Decides whether any of the Townstead mixins is applied at all, and records the ones that were.
 *
 * <h2>Why a plugin rather than {@code "required": false} alone</h2>
 *
 * <p>{@code required: false} answers only the first of the two ways this layer can be wrong: the mod
 * is absent, which is the ordinary case for nearly every install. The second is a Townstead point
 * release that moved or renamed the class a mixin names as a string — and a mixin whose target class
 * cannot be found is a startup error unless somebody refused it first. Refusing it here turns "that
 * Townstead is newer than this integration" into one capability reporting as unavailable instead of a
 * crash log, which is the whole promise the optional-integration rule makes.
 *
 * <h2>Why nothing is loaded here</h2>
 *
 * <p>Both questions are answered with {@link ClassLoader#getResource(String)} on a
 * {@code .class} path, exactly as Townstead's own config plugin does. Loading a class at this point
 * would freeze it before its owner's transformers had run, and asking {@code ModList} would be worse
 * still: mixin configs are processed during mod <em>loading</em>, before the mod list exists, so the
 * question would throw rather than answer. {@code LoadingModList} would work but says less than the
 * resource probe does — it reports that a jar is present, not that the class this hook needs is in
 * it.
 *
 * <h2>Why the package root is a dotted constant</h2>
 *
 * <p>The internal ({@code com/aetherianartificer/townstead/...}) form of a Townstead name is exactly
 * what {@code NoTownsteadStaticLinkTest}'s first scan forbids anywhere in this mod's bytecode,
 * because that is the form a real class reference takes. So the root is written with dots and the
 * slashes are put in at runtime: the probe asks the same question, and the tripwire that keeps a
 * relocated MCA descriptor out of MCA: Crime stays absolute.
 */
public final class TownsteadMixinPlugin implements IMixinConfigPlugin {

    /**
     * Townstead's package root, dotted. See the class comment: the slash form is forbidden, so this
     * is never written as {@code com/aetherianartificer/townstead} and never as a class literal.
     */
    private static final String TOWNSTEAD_PACKAGE = "com.aetherianartificer.townstead";

    /** The class whose presence means "Townstead is installed", not merely "a jar claims to be". */
    private static final String TOWNSTEAD_MARKER = TOWNSTEAD_PACKAGE + ".api.TownsteadAPI";

    /** Resolved once. A resource lookup is cheap, but this is asked once per mixin per target. */
    private Boolean townsteadPresent;

    @Override
    public void onLoad(String mixinPackage) {
        // Nothing to prepare. Every decision is made per mixin, in shouldApplyMixin.
    }

    /**
     * Null on purpose: the config names {@code mcacrime.refmap.json} itself, and the annotation
     * processor writes this config's entries into that same shared refmap. Returning a name here
     * would override the one the build actually produced.
     */
    @Override
    public String getRefMapperConfig() {
        return null;
    }

    /**
     * True only when Townstead is installed <em>and</em> the class this mixin names is really there.
     *
     * <p>The two questions are separate because their answers mean different things. No Townstead is
     * the silent, expected outcome. Townstead installed but a target class missing is the outcome
     * worth having a diagnostic for, and it is exactly the case a {@code required: true} config would
     * have turned into a crash.
     */
    @Override
    public boolean shouldApplyMixin(String targetClassName, String mixinClassName) {
        if (!townsteadPresent()) {
            return false;
        }
        return classExists(targetClassName);
    }

    @Override
    public void acceptTargets(Set<String> myTargets, Set<String> otherTargets) {
        // Not consulted. Each mixin declares its own target as a string.
    }

    @Override
    public List<String> getMixins() {
        return null;
    }

    @Override
    public void preApply(String targetClassName, ClassNode targetClass, String mixinClassName,
                         IMixinInfo mixinInfo) {
        // Nothing to do beforehand. What is worth recording is that the apply succeeded.
    }

    /**
     * Records the successful apply.
     *
     * <p>This is the only positive evidence that exists at load time. Whether the injectors inside it
     * found anything is a separate fact, recorded by the handlers themselves the first time they run;
     * see {@link TownsteadMixinStatus}.
     */
    @Override
    public void postApply(String targetClassName, ClassNode targetClass, String mixinClassName,
                          IMixinInfo mixinInfo) {
        TownsteadMixinStatus.applied(mixinClassName);
    }

    private boolean townsteadPresent() {
        if (townsteadPresent == null) {
            townsteadPresent = classExists(TOWNSTEAD_MARKER);
        }
        return townsteadPresent;
    }

    /** Whether a class resource exists, without loading it. Any throw reads as "no". */
    private static boolean classExists(String dottedClassName) {
        if (dottedClassName == null || dottedClassName.isBlank()) {
            return false;
        }
        try {
            ClassLoader loader = TownsteadMixinPlugin.class.getClassLoader();
            if (loader == null) {
                return false;
            }
            return loader.getResource(dottedClassName.replace('.', '/') + ".class") != null;
        } catch (Throwable t) {
            return false;
        }
    }
}
