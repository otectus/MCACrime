# 0.7.2 implementation baseline

Phase 0 reconciliation note for `docs/MCA_Crime_0.7.2_Implementation_Spec.md`. The spec inspected
`main` at `fdb6024` (declared 0.6.4). The actual working checkout differs and takes precedence.

## Checkout at start of implementation (2026-09-13)

| | |
|---|---|
| Branch | `main` |
| HEAD | `b7726fad1bd194df9514994c25c0e5ca33377937` (Style spoken crime lines like MCA Conversations' chat mode) |
| Worktree | dirty: 90 entries (59 tracked files modified, plus untracked new sources/tests/assets) |
| `mod_version` | `0.7.2` (already set in `gradle.properties`; not changed by this note) |
| Minecraft / Forge / Java | 1.20.1 / 47.4.10 / 17, official mappings |
| MCA dev runtime | `7.6.20+1.20.1`; binding probes `7.6.20`, `7.7.0-beta.2`, `7.7.1-alpha.2` |
| Test tasks | JUnit 5 via `test` (`check`); `build` also runs `checkJarContents`. No GameTests in this branch. |
| Baseline check | `.mcmod-tools/gradlew-quiet.sh . compileJava` PASS (5s) on the dirty tree before any 0.7.2 edits |

## Uncommitted work already present (preserved, not reverted)

The working tree already contains post-0.6.4 systems the spec asked implementers to locate:

- **Masks (0.7.0/0.7.1):** `item/MaskItem`, `item/MaskVariant` (CLAY, LEATHER), `item/MaskArmorMaterial`,
  `mask/Masks` (tag-based membership `mcacrime:masks`, HEAD slot), `mask/MaskHeatLedger` (deferred Heat),
  `mask/MaskReactionPolicy`, `mask/MaskEventHandlers`; items `clay_mask`, `leather_mask` with models,
  textures, recipes and tag; tests `MaskHeatLedgerTest`, `MaskReactionPolicyTest`, `LegalTargetMaskTest`,
  `PlayerCrimeDataMaskNbtTest`, `ConfigValidatorMaskTest`.
- **Currency abstraction:** `economy/Currency`, `Currencies`, `EmeraldCurrency`, `ItemCurrency`,
  `ItemCurrencyMath`, `ItemStackCurrencySupport`, `compat/NumismaticBridge` + `compat/numismatic/`.
- **Epic Fight compat:** `compat/EpicFightCompat`, `client/EpicFightInteractShim`,
  `docs/COMPATIBILITY_EPIC_FIGHT.md`.
- Spec items **not** present at baseline: Mask Station block/menu/recipe type, 16-style catalog,
  exclusive Thief profession with POI worksite, non-sneak apology routing, Sand Bottle.

## Mapping rule

Where the spec names a proposed class and the checkout already has a functioning owner, the checkout's
owner is extended. The checked planner run for this release records the authoritative file:line mapping.

## S2 — exclusive Thief occupation and the Mask Station

Delivered. The exclusive-profession contract, the acquisition boundary, the transaction and its
rollback limits, the schema-12 fields and every tuning constant are documented in
[`S2_OCCUPATION.md`](S2_OCCUPATION.md). Two baseline statements above are now out of date: the Mask
Station block and the exclusive Thief profession with a POI worksite exist; the station **menu** and
the 16-style catalog remain S3's.
