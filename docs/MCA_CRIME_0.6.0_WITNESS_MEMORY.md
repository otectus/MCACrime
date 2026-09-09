# MCA: Crime 0.6.0 — witnesses, intimidation and victim memory

Reference: [Witness, Intimidation & Victim Memory Update Specification](MCA_%20Crime%20-%20Witness,%20Intimidation%20%26%20Victim%20Memory%20Update%20Specification.md).

This development phase belongs to **0.6.0**, alongside the existing integrity fixes. It does not introduce a 0.5.2 release.

## Implemented behavior

- Crime definitions supply visual/sound radii, severity, violence and memory duration. The ledger incident UUID links observations, reports and memories. Visual confirmation uses bounded spatial queries, line of sight, forgiving facing checks, blindness, sleep, light, sneaking, rain and invisibility. Sounds are attenuated through obstructions and do not disclose a suspect.
- Witnesses carry reports to a nearby responder. Delivery requires proximity, sight and an available reporter. Low-confidence reports cannot authorize arrest, even when their author is a guard. Ordinary ledger entries alone no longer inform guards. Community consequences and the Reputation outbox start with the first sufficiently confident report; explicit jailbreak/command mechanisms retain their authority.
- Active mugging victims evaluate weapon class, aim, distance, health, MCA personality, personal memory, visible allies and guards, and family circumstances. Outcomes include compliance, panic, stalling, defiance, resistance and seeking help. Stalling delays the transfer; refusal or flight cancels it. Decisions have a minimum dwell time, and lowering a weapon, losing aim, or changing dimensions lets the victim react.
- Persistent victim/category records store fear, anger, incident identity, repeats and reconciliation. Repeats merge with capped values; severe memories receive retention priority. Decay uses elapsed world game time, calculated on access, with small residuals for severe crimes. Purse and historical robbery cooldown records remain intact.
- Identified direct witnesses can form reduced witness/family memories. After a delay a witness may tell one nearby relative. Rumors have reduced confidence, cannot authorize arrest and cannot relay again. Unloaded sources resume from saved observations when loaded. Nobody acquires another villager's private memory merely by living in the same settlement.
- Strong fear or anger can refuse a normal MCA interaction and trigger flight. Sneak and right-click with an empty main hand to open the Crime menu for reconciliation; the MCA screen button and optional menu keybind also work unarmed. Apologies require a memory, a lowered weapon, no active threat and 1,200 ticks (one minute at normal tick speed) since the last incident. The menu explains this settling period and the separate repeat-apology cooldown. The same incident cannot be repeatedly apologized away. Fine restitution reduces anger for the matching theft/robbery memory, and serving the matching sentence reduces fear and anger. Settling an older incident cannot forgive a newer merged offense.
- `McaCrimeApi.victimMemories` exposes immutable conversation/quest context. Existing crime observation/report events plus the new memory event support optional companions without adding dependencies. New dialogue uses datapack pools and translation keys.

## Operator tools

`/crime debug witness [villager]`, `/crime debug threat [villager]`, `/crime debug memory [villager]`, and `/crime debug crimeevents` require permission level 2. Without a selector the first three use the nearest MCA villager within eight blocks. Threat and memory are evaluated against the operator issuing the command.

## Compatibility

Version **0.6.0**, network protocol **10**, world schema **9**. The [local settlement follow-up](MCA_CRIME_PHASE2_JUSTICE.md) adds guard offer revisions to protocol 9's packets. Schema 8 development saves and older release saves load automatically. Schema 9 adds optional category memories and a witness relay marker; unknown perpetrators are represented by an absent suspect UUID. It does not invent historical victim memories. Existing schema 8 integrity fixes remain present.

MCA remains a runtime-only reflective dependency. Its personality accessor is optional; unrecognized or unavailable personalities use deterministic individual fallback factors. Conversations, Quests and Capitals can consume the public contexts/events; no new companion-specific binary adapter is required or claimed here.

## Release verification still required in a production MCA instance

The automated tests verify pure decision rules, codecs, memory persistence, retention, decay and reconciliation. They do not establish in-world pathfinding, client animation or tick-time performance. MCA's production mixins prevent using the ForgeGradle development launcher as an equivalent smoke test.

- [ ] Quiet theft behind a solid wall: no visual witness or named auditory suspect.
- [ ] Visible assault, darkness and invisibility: correct identification confidence and guard response.
- [ ] Loud crime through a wall: anonymous awareness, no immediate chase based on the noise.
- [ ] A civilian walks to a guard, reports within three blocks and creates one public consequence.
- [ ] A moving, sleeping, captive or inaccessible responder/reporter cannot file remotely.
- [ ] Confident, shy, athletic and armed villagers exhibit different mugging responses.
- [ ] A stalling victim eventually surrenders if the threat holds; arriving guards or lost aim allow escape.
- [ ] No payment completes after the victim resists or flees. Ordinary weapon carrying creates no compliance.
- [ ] Save/restart and unload/reload preserve memory, while temporary navigation/speed changes clear.
- [ ] Strong memory refuses casual interaction; reconciliation stays reachable through the Crime menu.
- [ ] Family information spreads only through sight or one nearby delayed conversation.
- [ ] Apology, matching fine and matching sentence soften memory without erasing serious/repeated harm.
- [ ] Test MCA alone and each installed optional companion, including a dedicated server and two clients.
- [ ] Profile 300 villagers, simultaneous crimes, and repeated aim changes; compare server tick time and save size against the previous build.

Elaborate surrender animation, acoustic simulation, hostage negotiation, forensic systems, global wanted tiers and automatic worldwide propagation are outside this phase. Navigation, facing, existing restraint visuals and contextual dialogue provide feedback.
