package dev.otectus.mcacrime.crime.type;

import com.mojang.serialization.Codec;
import com.mojang.serialization.codecs.RecordCodecBuilder;
import net.minecraft.resources.ResourceLocation;

/** Per-offense perception and memory metadata. Old datapacks inherit conservative defaults. */
public record CrimeAwareness(double visualRadius, double soundRadius, double severity,
                             boolean violent, int memoryDays) {
    public static final Codec<CrimeAwareness> CODEC = RecordCodecBuilder.create(i -> i.group(
            optional(Codec.doubleRange(0, 64), "visualRadius").xmap(v -> v.orElse(12.0), java.util.Optional::of).forGetter(CrimeAwareness::visualRadius),
            optional(Codec.doubleRange(0, 64), "soundRadius").xmap(v -> v.orElse(0.0), java.util.Optional::of).forGetter(CrimeAwareness::soundRadius),
            optional(Codec.doubleRange(0, 1), "severity").xmap(v -> v.orElse(0.25), java.util.Optional::of).forGetter(CrimeAwareness::severity),
            optional(Codec.BOOL, "violent").xmap(v -> v.orElse(false), java.util.Optional::of).forGetter(CrimeAwareness::violent),
            optional(Codec.intRange(1, 365), "memoryDays").xmap(v -> v.orElse(3), java.util.Optional::of).forGetter(CrimeAwareness::memoryDays)
    ).apply(i, CrimeAwareness::new));

    public static CrimeAwareness defaults(ResourceLocation crime) {
        return switch (crime.getPath()) {
            case "kill_villager", "mugging_murder", "murder_player" -> new CrimeAwareness(28, 36, 1, true, 60);
            case "harm_villager", "assault_guard", "assault_player" -> new CrimeAwareness(20, 22, 0.7, true, 24);
            case "kidnap", "kidnapping", "extortion" -> new CrimeAwareness(16, 8, 0.85, true, 40);
            case "mugging", "attempted_mugging" -> robbery();
            default -> new CrimeAwareness(10, 2, 0.25, false, 3);
        };
    }

    public static CrimeAwareness robbery() { return new CrimeAwareness(16, 16, 0.6, true, 16); }

    /** DFU's ordinary optionalFieldOf silently defaults malformed values; pack mistakes must be errors. */
    static <A> com.mojang.serialization.MapCodec<java.util.Optional<A>> optional(Codec<A> codec, String key) {
        return new com.mojang.serialization.MapCodec<>() {
            @Override public <T> com.mojang.serialization.DataResult<java.util.Optional<A>> decode(
                    com.mojang.serialization.DynamicOps<T> ops, com.mojang.serialization.MapLike<T> input) {
                T value = input.get(key);
                return value == null ? com.mojang.serialization.DataResult.success(java.util.Optional.empty())
                        : codec.parse(ops, value).map(java.util.Optional::of);
            }
            @Override public <T> com.mojang.serialization.RecordBuilder<T> encode(java.util.Optional<A> value,
                    com.mojang.serialization.DynamicOps<T> ops, com.mojang.serialization.RecordBuilder<T> prefix) {
                return value.isEmpty() ? prefix : prefix.add(key, codec.encodeStart(ops, value.get()));
            }
            @Override public <T> java.util.stream.Stream<T> keys(com.mojang.serialization.DynamicOps<T> ops) {
                return java.util.stream.Stream.of(ops.createString(key));
            }
        };
    }
}
