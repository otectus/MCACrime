# Townstead production-runtime checks (NeoForge 1.21.1)

This test-only NeoForge mod runs assertions in a disposable dedicated server, writes
`runtime-results.txt`, and stops that server. It is excluded from MCA: Crime's release jar.
Java 21 is required for compilation and the server. Gradle uses this project's Java toolchain.

Build the release with every optional adapter and compile the runtime harness:

```sh
./gradlew --init-script tools/townstead/runtime.gradle build townsteadRuntimeJar \
  -PmcaReputationClasses=/path/to/MCAReputation/build/classes/java/main \
  -PmcaQuestsClasses=/path/to/MCAQuests/build/classes/java/main \
  -PlocksClasses=/path/to/LocksReforged/build/classes/java/main \
  -PrequireReputation=true -PrequireQuests=true -PrequireLocks=true
```

Supply an installed NeoForge **1.21.1** server directory and actual production jars. The runner reads
that installation's libraries, copies the selected mods into a fresh directory beneath
`build/townstead-runtime`, uses a loopback socket with an automatically assigned port, and reuses
the existing EULA acceptance in `run/eula.txt`. Existing worlds and installations are not changed.
It exits nonzero on a failed assertion, failed startup, missing result, or timeout.

```sh
python3 tools/townstead/run_runtime.py \
  --neoforge-server /path/to/installed-neoforge-server \
  --neoforge-version 21.1.248 \
  --java /path/to/java21/bin/java \
  --crime build/libs/mcacrime-0.7.4.jar \
  --harness build/libs/townstead-runtime-checks-0.7.4.jar \
  --mca /path/to/mca-neoforge-7.7.36-beta.3+1.21.1.jar \
  --townstead /path/to/townstead-0.7.7+1.21.1.jar \
  --patchouli /path/to/Patchouli-1.21.1-93-NEOFORGE.jar \
  --name neoforge
```

Omit Townstead/Patchouli for the absent scenario. Add `--disabled` to verify Townstead installed
with the integration disabled at startup. Add `--control` to boot and stop the companion pair
without Crime or the harness, for comparison of upstream startup messages. Jar versions in the
example describe the verified matrix. There is one NeoForge Townstead jar; the Forge 1.20.1
legacy/modern package split does not apply here, and this matrix needs no Architectury jar.

The runtime checks exercise production Mojang-name mixins (no reobfuscation or refmap) and the
reflective bridge with real MCA entities: identity, needs, life stage, calendar, empty-building
queries, cache invalidation, incapacity and recovery, guard navigation, reaction handovers,
equipment swaps and cleanup, dimension-correct storage protection, custody supply accounting
and recovery, config changes, and side separation. Fixtures invoke Townstead methods on the
server thread to make unusual call ordering repeatable. They do not simulate a player operating
the client UI or a multiplayer session.

For complementary manifest/bytecode checks, append this task and property to the Gradle build
command above (keeping the optional-adapter properties):

```sh
townsteadProbeTest -PtownsteadJar=/path/to/townstead-0.7.7+1.21.1.jar
```

For the existing 32 GameTests, use `runGameTestServer` with the same optional-adapter properties
and `-PlocksRuntimeJar=/path/to/locks_reforged-neoforge-1.21.1-1.7.5.jar`. The Locks runtime
is needed by the cuff tests. GameTests use the development server; the Townstead fixtures above
use the packaged production jars.

See [the verification report](../../docs/0.7.4/TOWNSTEAD_VERIFICATION.md) for results and coverage limits.
