# Phase 0 Forge 1.20.1 fixture drop point

This directory is where the captured Forge 1.20.1 world goes (plan Phase 0, spec §4 and §13.3). It is
empty until that capture happens.

It must contain:

- the stopped Forge world directory, including `data/mcacrime.dat`;
- `playerdata/<uuid>.dat` for every fixture player, exactly as the Forge server wrote them;
- the `config/*.toml` files the fixture world ran with;
- `manifest.json`, the hand-written per-UUID expected values (see `LegacyFixturePresenceTest` for the
  shape it is read in).

Until then `LegacyPlayerCrimeImporterTest` runs against an **INTERIM** synthetic fixture built from
`PlayerCrimeData.save()`, not a real Forge player file, and `LegacyFixturePresenceTest` is
`@Disabled`. Dropping the capture in here and deleting that annotation is the whole enabling step.
