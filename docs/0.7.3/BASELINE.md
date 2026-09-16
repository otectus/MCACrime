# 0.7.3 implementation baseline

The checkout this release's work started from, and what was already true of the MCA: Reputation
integration before any of it was changed. Recorded because the companion recon this work was planned
against was taken at `b7726fa` (pre-0.7.2) and some of its line numbers had already moved.

## Checkout at start of implementation (2026-09-16)

| | |
|---|---|
| Branch | `feature/reputation-0.6.0`, branched from `main` |
| HEAD at branch point | `0079641` (Release MCA Crime 0.7.2 for Forge 1.20.1) |
| Worktree | clean apart from an untracked `output/` directory, which was left alone |
| `mod_version` | `0.7.2` → `0.7.3`, in `gradle.properties` only |
| Minecraft / Forge / Java | 1.20.1 / 47.4.10 / 17, official mappings |
| MCA dev runtime | `7.6.20+1.20.1`; binding probes `7.6.20`, `7.7.0-beta.2`, `7.7.1-alpha.2` |
| Companion compiled against | MCA: Reputation at `/home/otectus/Projects/MCAReputation`, branch `feature/0.6.0-profiles`, `build/classes/java/main` (0.6.0, API version still 1) |
| Test tasks | JUnit 5 via `test` (`check`); `build` also runs `checkJarContents`. No GameTests. |

Nothing in the MCA: Reputation checkout was edited, and no Gradle task was run in it. Its class
output and its own `API.md` / `DATAPACK.md` were read only.

## What the integration looked like at `0079641`

The five findings this release addresses, at the lines they were actually on:

| Finding | Where |
|---|---|
| The delivery result was reduced to `Optional<UUID>`, so six different answers arrived as one empty optional | `compat/ReputationOps.java:39-41`, `compat/reputation/CrimeReputationCompat.java:146-183` |
| The pump then guessed which answer it had been given: empty plus authority held → `UNKNOWN_TARGET`, empty plus authority lost → `UNAVAILABLE`, `false` → `TRANSIENT_FAILURE` | `integration/CrimeIntegrationPump.java:200-229` |
| A lost incident link was recovered by **recording a synthetic `mcareputation:villager_assaulted`** and reading the duplicate refusal | `compat/reputation/CrimeReputationCompat.java:185-206` |
| `CrimeCoreAuthority.owns(CoreIncidentKind)` ignored its argument, claiming all six core kinds | `compat/reputation/CrimeReputationCompat.java:294-299` |
| Negotiation was an API-version equality check and nothing else; `capabilities()` was never called | `compat/ReputationBridge.java:99-108` |

Also true at baseline, and changed here:

- A resolution whose case had no companion-side link yet was dropped
  (`integration/CrimeIntegrationHooks.java:127`).
- `onCommitted` never looked at `offender_kind`, so an NPC offender's case was delivered as a
  player's civic deed (`integration/CrimeIntegrationHooks.java:81-114`).
- The eight shipped incident definitions under `data/mcacrime/mcareputation/incidents/` carried no
  `social_profile`, so every crime this mod files produced zero recognition and zero facet evidence
  under MCA: Reputation 0.6.0.

## What was already correct and was preserved

- The optional-classloading seam: `ModList.isLoaded` plus `Class.forName` into
  `compat/reputation/CrimeReputationCompat`, which stays the only file in the mod that imports the
  companion, with `OptionalClassloadTest` enforcing it by byte-scanning the compiled output.
- The `mods.toml` optional dependency stays `mcareputation [0.2,)`. A range that gated the game load
  over an optional integration would be a worse outcome than running without it; the shortfall is
  handled at runtime instead.
- Crime's own dedupe keys (`crime:<recordId>`, `crime-resolution:<recordId>:<revision>`) are
  unchanged, so no operation already sitting in a player's outbox changes identity across the
  upgrade. They are now also used as the delivery operation key.
- The outbox contract: enqueue in the same dirty cycle as the crime, deliver later, and never call
  into an optional mod while loading saved data.
- Assault and killing continue to reuse `mcareputation:villager_assaulted` and
  `mcareputation:villager_killed` rather than minting `mcacrime:` duplicates.

## Scope boundary taken for this release

`ReputationProfileChangedEvent` is deliberately **not** subscribed to. This mod has no consumer for a
profile change: it decides guilt, sentencing and bail from its own ledger, and adding a listener with
nothing behind it would be an unused subscription on the Forge bus. Reading profiles for gameplay —
guards reacting to a known violent reputation, for instance — is a design decision, not an adoption
step, and is not made here.
