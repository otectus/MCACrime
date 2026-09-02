## Guard Arrest, Surrender, Jail, and Village Configuration Overhaul

Perform a thorough investigation of the current Guard arrest, surrender, escort, jail, and release systems. Trace the complete flow from the moment a Guard confronts a wanted player through surrender, transport, imprisonment, sentence countdown, and eventual release. Identify and correct any bugs, incomplete logic, duplicated state handling, synchronization problems, or edge cases encountered along the way.

### 1. Physical Surrender and Arrest Sequence

When the player chooses **Surrender**, the interaction should transition into a proper physical arrest sequence rather than immediately or abstractly moving them into jail.

The surrendering player should:

- Be placed into a clearly defined **arrested / restrained state**.
    
- Visually appear **handcuffed with their hands behind their back** where technically feasible.
    
- Be attached to the arresting Guard using a visible **lead/leash-style connection**.
    
- Be forcibly escorted by that Guard toward the appropriate jail.
    
- Have their movement restricted sufficiently that they cannot simply walk or sprint away from the Guard.
    
- Be prevented from initiating actions that should not be possible while restrained, where appropriate.
    
- Remain attached to the same arresting Guard unless recovery logic determines that Guard can no longer complete the arrest.
    
- Be physically brought into the jail before imprisonment officially begins.
    

The Guard should navigate naturally toward the jail and place the player inside it. Investigate how this can be implemented without making the escort overly fragile when encountering doors, terrain, pathfinding failures, unloaded chunks, other entities, or minor obstructions.

Include robust fallback/recovery behavior. The player should never become permanently stuck in an arrested state because the Guard cannot reach the jail.

Consider cases such as:

- Guard death or despawning.
    
- Player disconnect/reconnect.
    
- Guard losing its path.
    
- Player being teleported.
    
- Jail becoming unavailable or destroyed.
    
- Chunk unloading.
    
- Multiple Guards attempting to arrest the same player.
    
- Another crime occurring during the arrest.
    
- The player attempting to escape or break the escort.
    
- Server restart while an arrest or jail sentence is active.
    

The arrest state should be authoritative and persistent enough that reconnecting cannot trivially bypass the sentence.

### 2. Fix Repeated Guard Confrontation Menu Spam

There is currently a major issue where the Guard confrontation menu repeatedly opens/spams while the player is being taken to jail, including **after the player has already selected Surrender**.

Trace the complete confrontation trigger and determine why it continues firing after a choice has been made.

Once the player has surrendered:

- The confrontation interaction must immediately resolve.
    
- The player must enter a dedicated state such as `SURRENDERED`, `ARRESTED`, `ESCORTING`, or equivalent.
    
- Guards must recognize that the player is already being processed.
    
- No additional confrontation GUI should appear during the escort.
    
- Other Guards should not independently start duplicate confrontation sequences.
    
- The original Guard should not repeatedly retrigger the menu every AI tick, proximity check, crime scan, or interaction update.
    

Do not solve this with an arbitrary cooldown alone. Correct the underlying state-management problem so the arrest process has a clear lifecycle and confrontation logic is properly gated against it.

Audit all relevant client/server packets, AI goals, event listeners, capability/data storage, timers, and GUI-opening logic for duplicate triggers.

### 3. Jail Sentence GUI Countdown Fix

The actual jail sentence timer appears to function correctly server-side: the player is eventually released at the expected time.

However, the **visible jail timer in the GUI does not count down**, making it impossible for the player to tell:

- How much time they have served.
    
- How much time remains.
    
- Whether the sentence is progressing at all.
    

Investigate whether the GUI is displaying:

- A stale value captured when the screen opens.
    
- An unsynchronized client-side copy.
    
- The wrong timestamp.
    
- A tick count that is never refreshed.
    
- Incorrect conversion between ticks, seconds, and real time.
    
- A value that updates server-side without being synchronized to the client.
    

Correct the implementation so the displayed timer updates continuously and accurately.

Prefer a system where the server remains authoritative while the client can smoothly derive the remaining sentence duration from synchronized timestamps or periodic state updates, rather than requiring unnecessary network traffic every tick.

The GUI should clearly display the remaining jail duration in a human-readable format such as:

`1m 42s remaining`

Ensure it remains correct after:

- Closing/reopening the GUI.
    
- Disconnecting/reconnecting.
    
- Server restart, where supported by the existing sentence persistence model.
    
- Lag or temporary packet delays.
    

### 4. Configurable Guard Population Ratio

Make the number of Guards generated or maintained within villages configurable.

The default behavior should target approximately:

**10% of the village's eligible villager population as Guards. Minimum of 1 Guard, and always round up.**

For example:

- 10 eligible villagers → approximately 1 Guard.
    
- 20 eligible villagers → approximately 2 Guards.
    
- 50 eligible villagers → approximately 5 Guards.
    

Provide a configurable ratio or percentage rather than hardcoding 10%.

Example configuration:

```toml
# Fraction of eligible villagers that should become Guards.
# 0.10 = 10%
guardPopulationRatio = 0.10
```

Define sensible bounds and behavior for very small villages. Avoid excessive Guard creation/removal caused by population fluctuating by one villager.

Investigate the existing spawning/conversion system and ensure the configured target does not:

- Continuously convert villagers back and forth.
    
- Duplicate Guards after chunk reloads.
    
- Ignore already-existing Guards.
    
- Count babies or otherwise ineligible villagers unless intentionally supported.
    
- Cause excessive scanning or performance overhead.
    
- Break naturally spawned, manually created, or command-spawned Guards.
    

If an existing village-based Guard population system already exists, refactor it around this configurable target instead of creating a second competing system.

### 5. Configurable Temporary Jail Generation

The automatic generation of a temporary jail should also be configurable for users and modpack authors.

If this option does not already exist, add something equivalent to:

```toml
# Automatically create a temporary jail when a village needs one.
generateTemporaryJails = true
```

Default:

`true`

When disabled:

- The mod must not automatically generate temporary jail structures.
    
- Existing player-built or recognized jail structures should continue to work.
    
- Guard/arrest logic must fail gracefully when no valid jail exists.
    
- The player must never become permanently stuck in an arrest or escort state because jail generation was disabled.
    

Investigate the current jail discovery and generation process before implementing this option. If a suitable configuration option already exists, verify that it actually gates all temporary-jail generation paths and document or repair it rather than introducing a duplicate setting.

### 6. Arrest State Machine and Architecture

As part of this work, review whether the existing arrest system has a clearly defined lifecycle. If not, formalize one.

A reasonable conceptual flow would be:

`WANTED → CONFRONTED → SURRENDERED → RESTRAINED → ESCORTING → JAILED → RELEASED`

with alternate paths such as:

`CONFRONTED → RESISTING`

or:

`ESCORTING → ESCAPE / RECOVERY`

The exact implementation may differ, but there should be one authoritative state determining what the Guard and player are currently doing.

State transitions should control:

- Whether confrontation menus may open.
    
- Which Guard owns the arrest.
    
- Whether the player is restrained.
    
- Whether the player is attached to a Guard.
    
- Whether escort AI is active.
    
- Whether the player has entered jail.
    
- When the sentence begins.
    
- When the sentence ends.
    
- How disconnects and interrupted arrests recover.
    

Avoid scattering independent booleans across unrelated systems where contradictory combinations can occur.

### 7. Multiplayer and Server Authority

Audit all functionality for dedicated-server compatibility.

The server should remain authoritative over:

- Arrest ownership.
    
- Player restraint state.
    
- Escort state.
    
- Crime/wanted status.
    
- Jail assignment.
    
- Sentence start/end time.
    
- Release.
    

The client should primarily handle presentation:

- Handcuff visuals.
    
- Lead/escort rendering.
    
- GUI state.
    
- Countdown display.
    
- Appropriate animations.
    

Make sure another client cannot spoof surrender, release, timer completion, or arrest cancellation through client-controlled state.

### 8. Visual and Gameplay Polish

Where practical, make the arrest process feel deliberate and immersive rather than like a teleport or debugging mechanic.

Investigate opportunities for:

- A handcuff model or restrained-player rendering layer.
    
- Arms positioned behind the player's back while restrained.
    
- A visible connection between Guard and prisoner.
    
- Appropriate Guard movement speed while escorting.
    
- Guard-facing or escort animations.
    
- Short feedback such as **"You surrendered to the Guard."**
    
- A transition message when reaching jail.
    
- Clear feedback if the escort fails and recovery logic activates.
    

Do not let presentation changes compromise server correctness or compatibility.

### 9. Investigation Requirements

Do not implement these changes in isolation. First trace the existing systems responsible for:

- Crime/wanted tracking.
    
- Guard target selection.
    
- Guard confrontation AI.
    
- Confrontation GUI creation.
    
- Surrender handling.
    
- Guard navigation/pathfinding.
    
- Jail lookup and generation.
    
- Player imprisonment.
    
- Sentence timing.
    
- Jail HUD/GUI rendering.
    
- Player release.
    
- Village Guard spawning/conversion.
    
- Configuration loading and synchronization.
    
- Persistence across logout/restart.
    

Search for unfinished work, TODOs, duplicated code paths, race conditions, incorrectly client-owned state, and assumptions that may explain the current bugs.

Pay particular attention to code that executes every entity tick, player tick, proximity scan, or AI goal tick, as this is a likely source of the repeated confrontation menu.

### 10. Compatibility and Regression Requirements

Preserve existing crime and Guard behavior outside of the systems intentionally modified here.

Verify at minimum:

- Surrender works on a dedicated server.
    
- Resisting still works.
    
- A surrendering player receives only one confrontation interaction.
    
- The arresting Guard successfully escorts the player to jail.
    
- Multiple Guards do not compete for the prisoner.
    
- Failure to path to jail recovers safely.
    
- Jail time starts at the correct point.
    
- The displayed countdown decreases accurately.
    
- Release occurs at the same time the countdown reaches zero.
    
- Reconnecting does not reset or bypass an active sentence.
    
- Guard ratios respect configuration.
    
- Existing Guards are included in population calculations.
    
- Temporary jail generation can be disabled.
    
- Disabling temporary jails cannot soft-lock an arrested player.
    
- Existing custom/player-built jail functionality remains intact.
    

### 11. Documentation and Configuration

Document all new configuration options with clear comments explaining:

- Default value.
    
- Accepted range.
    
- Meaning.
    
- Whether a restart is required.
    
- How rounding of Guard population targets works.
    
- What happens when temporary jail generation is disabled.
    

Update the changelog and relevant user-facing documentation after implementation.

The finished system should make surrendering feel like an actual arrest: the Guard restrains the player, physically escorts them to jail, places them inside, the sentence countdown visibly progresses, and the player is released when it expires—without repeated GUIs, duplicate arrests, broken state transitions, or unavoidable temporary-jail generation.