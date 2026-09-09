package dev.otectus.mcacrime.api.model;

import java.util.UUID;

/** Immutable server-side conversation/quest context. No live entities or mutable save objects. */
public record VictimMemoryView(UUID perpetrator, UUID incident, UUID victim, String category,
                              long timestamp, double severity, double fear, double anger, int repeatCount,
                              boolean indirect, boolean apologized, boolean restitutionPaid,
                              boolean sentenceServed) {}
