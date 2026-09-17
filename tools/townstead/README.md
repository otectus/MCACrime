# Townstead production-runtime checks

This test-only Forge mod runs assertions in a disposable dedicated server, writes
`runtime-results.txt`, and stops that server. It is not included in MCA: Crime's release jar.

Build the release and test harness with Java 17 through ForgeGradle:

```sh
./gradlew --init-script tools/townstead/runtime.gradle build townsteadRuntimeJar
```

Supply an installed Forge **1.20.1** server directory and actual production jars. The runner reads
that installation's libraries, copies the selected mods into a fresh directory beneath
`build/townstead-runtime`, uses a loopback socket with an automatically assigned port, and reuses
the existing EULA acceptance in `run/eula.txt`. Existing worlds and installations are not changed.
It exits nonzero on a failed assertion, failed startup, missing result, or timeout.

```sh
python3 tools/townstead/run_runtime.py \
  --forge-server /path/to/installed-forge-server \
  --crime build/libs/mcacrime-0.7.4.jar \
  --harness build/libs/townstead-runtime-checks-0.7.4.jar \
  --mca /path/to/mca-7.7.1-alpha.2+1.20.1.jar \
  --townstead /path/to/townstead-mca-modern-0.7.7+1.20.1.jar \
  --patchouli /path/to/Patchouli-1.20.1-85-FORGE.jar \
  --name modern
```

The legacy scenario uses Townstead's legacy jar, MCA `7.7.0-beta.2+1.20.1`, and
`--architectury /path/to/architectury-9.2.14-forge.jar`. Omit Townstead/Patchouli for the absent
scenario. Add `--disabled` to verify Townstead installed with the integration disabled at startup.
Add `--control` to boot and stop the companion pair without Crime or the harness, for comparison
of upstream startup messages. Jar versions in the example are the verified matrix, not a resolver
for arbitrary companion versions.

The runtime checks exercise the reobfuscated mixins and the reflective bridge with real MCA entities:
identity, needs, life stage, calendar, empty-building queries, cache invalidation, incapacity and
recovery, guard navigation, reaction handovers, equipment swaps and cleanup, dimension-correct
storage protection, custody supply accounting and recovery, config changes, and side separation.
Fixtures invoke specific Townstead methods on the server thread to make unusual call ordering
repeatable. They do not simulate a player operating the client UI or a multiplayer session.

For the complementary jar manifest/bytecode checks:

```sh
./gradlew townsteadProbeTest \
  -PtownsteadLegacyJar=/path/to/legacy.jar \
  -PtownsteadModernJar=/path/to/modern.jar
```

See [the verification report](../../docs/0.7.4/TOWNSTEAD_VERIFICATION.md) for results and coverage limits.
