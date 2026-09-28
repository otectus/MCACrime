# Village justice compatibility and verification

This implements the Thief combat, player reporting, village news and world-rule plan for Forge 1.20.1. The release version remains the value in `gradle.properties`; no release or commit is created by this change. Crime world schema is 16 and the packet protocol is 17. Player report receipts also have their own bounded SavedData schema.

## Compatibility contract

The native adapter probes MCA's actual bytecode before installing its hooks. Supported target fixtures are MCA Reborn 7.6.20, 7.7.0-beta.2 and 7.7.1-alpha.2 for 1.20.1. Their Forge package roots are `forge.net.mca` and `forge.net.conczin.mca`; the unprefixed roots are recognized when present. A recognized name alone does not establish support: hurt, death, tragedy, mailbox load/save and collection must match the required descriptors and call sites. `/crime validate` reports applied and degraded native capabilities.

Default `ALL_THIEVES` policy requires a current canonical adult Thief occupation and readable native role. Children, responders, ordinary workers, unknown roles, lawful prisoners and physically restrained targets retain ordinary protection. An externally changed profession affects the next hit immediately. Strict mode uses identified recent robbery evidence or an actionable warrant in the action's actual MCA village bounds. Invocation-scoped decisions survive native damage/death callbacks and are removed even when callbacks throw. Only the responsible actor's blame is suppressed; no reward is created.

MCA: Reputation requires the per-incident exemption capability supplied in [MCAReputation-thief-exemption.patch](MCAReputation-thief-exemption.patch) to preserve this guarantee while normal Crime incident ownership is handed back. The patch was built in an isolated copy of the companion, leaving the sibling checkout unchanged. It adds capability version 1 for villager assault/kill only, with default-pass and bounded registration. Older companions retain normal authority integration, but cannot promise the exemption during handback; the diagnostic explicitly reports that limit. The patch is a required companion change, not a claim that a published companion version already contains it.

## Persistence and scope

A visible mugging captures the original victim, suspect perception, location and transaction. Escape or interruption finalizes that transaction as an attempted case, without finding a new suspect or inventing stolen goods. Filing reserves receipt capacity before hooks or interrupting the threat. Accepted civilian testimony stays non-authoritative. Dispatch only uses loaded suspects and locally available responders, with range, sight, assignment and role checks. It never force-loads a chunk. Receipt status is derived from the canonical case and its own sentence-bound custody.

News is a bounded projection of committed, identified reports and actual property recovery. It verifies canonical reports before publishing, reduces case outcomes, aggregates public warnings by community and keeps private recovery separate. Actual visited village bounds and visible resident interactions establish up to three recent community connections. No reliable persistent player-home API is assumed. Scheduling uses overworld game time, not daylight time. Each edition is keyed by the revisions actually included; a story that does not fit remains pending.

The native MCA inbox and Crime edition receipt are serialized on the same PlayerSaveData object. Collection marks it dirty. Retained Crime outbox entries reconcile either save ordering, including retries after collection. This does not claim atomicity between player inventory saves and mailbox saves during a crash. MCA's mail switch, Crime world policy and the player's subscription are honored. No replacement inventory book is inserted. Malformed optional news rows are bounded and quarantined; a future Crime schema remains read-only.

## Automated verification

The production fixture lives under `tools/justice/`; its separate mod is not included in the release jar. It creates a disposable Forge world, exercises the real transformed MCA classes, files a civilian report, commits lawful custody and retrieves generated news from native MCA mail. The `--restart` option reopens the same world and verifies collection receipts prevent duplicate delivery.

The client fixture lives under `tools/justice-client/`, also outside the release jar. It captures report screens and native MCA letter pages and records navigation checks. These are automated fixture checks, not a claim of a human multiplayer playthrough.

Build commands:

```sh
./gradlew build -I tools/justice/runtime.gradle justiceRuntimeJar
./gradlew -I tools/justice-client/runtime.gradle justiceClientRuntimeJar reobfJar
```

`build` includes the required MCA bytecode fleet, native transformed-stack verification, policy, observation/report, packet, custody/recovery, migration, configuration and integration regressions. Townstead's separate supplied-jar probe is not implied by `build`.

### Recorded results, 2026-09-23

- `./gradlew build -I tools/justice/runtime.gradle justiceRuntimeJar -PrequireReputation=true`: **PASS**, 2,644 tests passed, 16 skipped, zero failures/errors. Log: `/tmp/gradle-MCACrime-build-20260923-010011.log`; HTML: `build/reports/tests/test/index.html`. All three required MCA native probes ran; transformed stacks and changed-signature rejection checks passed. The skipped checks are eight separate supplied-jar Townstead probes and eight existing environment-dependent restoration/economy cases; they are not counted as passes.
- `./gradlew build` in `/tmp/mcacrime-reputation-exemption`: **PASS**. Log: `/tmp/gradle-mcacrime-reputation-exemption-build-20260923-005004.log`. This is the isolated companion with the included patch, not an edited or released sibling checkout.
- Dedicated Forge 47.4.10 / MCA 7.6.20, companion absent: **10 production checks passed twice, including normal save/restart**, `build/justice-runtime/check-cli6h1c1/results-{0,1}.txt`.
- MCA 7.7.0-beta.2: **10 production checks passed**, `build/justice-runtime/check-8hxhfvbi/results-0.txt`.
- MCA 7.7.1-alpha.2: **10 production checks passed**, `build/justice-runtime/check-5s7w1gk2/results-0.txt`.
- MCA 7.6.20 with updated companion capability, final Crime artifact: **12 production checks passed twice, including restart**, `build/justice-runtime/check-5ph3ki68/results-{0,1}.txt`. This includes active identified threat evidence with zero post-threat grace and loss of that basis immediately after escape. The fixture additionally checks attribution for an arrow whose owner is in another dimension, exact Crime Karma/Heat/case preservation for the exempt hit, and rejects a synthetic report event as a source of news.
- Real Forge 47.4.23 client / MCA 7.6.20 at 1600×1000, GUI scale 2: **PASS**, `build/justice-client-check-5/PASS.txt`. Six picker rows include unknown and long names; all eight status labels fit. Tab/Enter selection and native Page Down 1→2→4 work. The actual composer produced eight stories on four native letter pages. Screenshots and `evidence.json` are in that directory; `artifacts.json` records the tested jars. The final client production/fixture build (`justiceClientRuntimeJar reobfJar`) passed in 16 seconds.
- Stress test: 5,000 attempted public facts and offline subscriptions remain bounded at 4,096 each, preserve healthy data on round trip and prune deterministically. One observed run took 280 ms; this is a fixture measurement, not a universal tick budget. Report tests cover capacity reservation and fair bounded pending selection; production dispatch performs loaded-entity lookup only.
- `git diff --check`: **PASS**. `modmap.py` regenerated the inventory (97 registered entries). `check_mod.py` reported one existing false positive: its class-level bus matcher combines outer `CrimeKeybinds` Forge tick handlers with the nested MOD-bus key-registration class. The actual annotations and handlers are separate. Its 29 model notes concern existing variants. Output: `/tmp/mcacrime-justice-check-mod.txt`; this helper result is not represented as a clean pass.

### Built artifacts

The final production run used this exact local command (dependency paths are this machine's cached fixtures):

```sh
python3 tools/justice/run_runtime.py --forge-server /tmp/claude-1000/-home-otectus-Projects-MCAMobCompatibility/ad7753b5-81d7-4046-936d-edcb591cbc93/scratchpad/prodserver --architectury /home/otectus/.gradle/caches/modules-2/files-2.1/dev.architectury/architectury-forge/9.2.14/f48e657815d2e540d63bd162238631ed3c5633d3/architectury-forge-9.2.14.jar --geckolib /home/otectus/.gradle/caches/modules-2/files-2.1/software.bernie.geckolib/geckolib-forge-1.20.1/4.8.3/affe0ef4c956ea16f120b679d6dde10b7aa30b64/geckolib-forge-1.20.1-4.8.3.jar --mca /home/otectus/.gradle/caches/modules-2/files-2.1/maven.modrinth/minecraft-comes-alive-reborn/7.6.20+1.20.1/151dff3986f088f11bb91bb236c26a9f2c204e59/minecraft-comes-alive-reborn-7.6.20+1.20.1.jar --reputation /tmp/mcacrime-reputation-exemption/build/libs/mcareputation-0.6.0.jar --restart
```

- `build/libs/mcacrime-0.7.5.jar`, SHA-256 `cb26109ea1e503ba279909aff91bb0666648d6a1462e9ea99c82e04540a4ae61`.
- `/tmp/mcacrime-reputation-exemption/build/libs/mcareputation-0.6.0.jar`, SHA-256 `4825b9d8991004f06d2bf2e328221f6a63616c8d2bd2731d2c7bf4df38fc1a70`.

The Crime jar contains `compat/reputation/CrimeReputationCompat.class`, the exemption bridge and all three mixin configs. Runtime and client fixture classes are excluded. The working tree already contained unrelated work; this change preserves it and does not create a commit.

### Visually inspected and still unverified

The report picker/status screenshots and native letter pages were visually inspected: long labels do not overlap the status/time columns, unknown identities remain anonymous, and two stories fit per page in the correct order. This is screenshot inspection of an automated real-client run, not a human playthrough.

Still unverified as live gameplay: two simultaneously connected clients and private report isolation across them; a full Townstead/Epic Fight combination; every optional GUI scale/language; cursor-driven hover expansion; Create World gamerule selection through its actual menu; complete delayed snapshots of relatives, village standing and companion incident stores for all mourning/totem/cancellation/reentry cases. The companion test verifies the exact exemption query during integration handback; it does not claim exhaustive delayed companion-store accounting. Existing pure and integration regressions cover many corresponding policy/finality/storage contracts, but do not substitute for those multiplayer/manual matrix scenarios.

The actual player report/arrest/recovery path retains the established escrow tests. The new production news journey verifies a real report and actual arrest; it does not simulate every full-inventory/offline currency-recovery race listed in the wider acceptance matrix.
