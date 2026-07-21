# Create World-Grid Deployer

A focused NeoForge 1.21.1 compatibility patch for Create 6.0.10 and Sable
2.0.3. It adds a third, **World-Grid Use** mode to Create deployers mounted on
Sable physics sublevels.

In this mode, a moving deployer places blocks into the stationary parent world
instead of attaching them to the physics contraption. The placement point is
sampled in world coordinates every Sable server tick and swept across the block
grid, allowing fast contraptions to build continuous bridges, walls, or tunnels
without depending on Create's ordinary RPM-driven deployer cycle.

## Compatibility and installation

- Minecraft 1.21.1
- NeoForge 21.1.228 or newer in the 21.1 line
- Create 6.0.10 (versions before 6.1.0)
- Sable 2.0.3 (versions before 2.1.0)
- Java 21

Install the built jar in the server's `mods` directory. Core placement is
server-authoritative. Installing it on clients is strongly recommended because
the client component supplies the World-Grid mode label and all visual debug
commands. Debug payloads are registered as optional; the debug channel itself
does not force clients without the mod to participate.

## Mode cycle

Use a wrench on Create's normal deployer mode selector:

1. Punch
2. Use
3. World-Grid Use

Create's internal mode enum remains unchanged. World-Grid Use is represented as
Create's ordinary `USE` mode plus a separate persisted boolean owned by this
patch. Create goggles display the additional mode explicitly.

The flag may remain set while a deployer is disassembled, but the special tick
and placement behavior activates only while the deployer is mounted on a Sable
sublevel. Outside Sable it continues to behave as an ordinary Create `USE`
deployer.

## How placement works

For every armed deployer on a Sable sublevel, the server performs this sequence:

1. Find the deployer's local block center and its normal Create working point,
   two blocks forward from that center.
2. Transform both points from the moving Sable sublevel into the parent world's
   coordinates. The transformed direction is also retained so Create receives
   the correct placement face and fake-player rotation.
3. Compare the current working point with the preceding server-tick sample.
4. Enumerate every face-connected world voxel crossed between the two points.
   Rotation and placement orientation are interpolated at each crossing.
5. Visit each candidate synchronously on the server thread and call Create's
   normal deployer handler for the actual placement.

The traversal is tied to crossed world cells, not RPM magnitude. Any non-zero
RPM arms it, but increasing RPM does not make it place several times in the same
cell or alter the sampling cadence.

Each candidate is checked immediately before Create is called:

- Its parent-world chunk must already be loaded.
- The current block state must return `true` from `BlockState.canBeReplaced()`.
- The deployer's fake player must currently hold a `BlockItem`.

Visual transparency is not the criterion: replaceability is. Air and other
replaceable states may be overwritten; a transparent but non-replaceable block
is treated as occupied.

Create and NeoForge then perform their normal placement, event, support,
collision, inventory, and item-specific checks. The patch does not force a
block into the level or bypass those rules. Because the call is synchronous, a
block placed by an earlier deployer is visible to a later deployer's occupied
check in the same server tick.

## Armed and inactive states

World-grid placement is armed only when all of the following are true:

- World-Grid Use is selected.
- Create's underlying mode is still `USE`.
- The deployer has non-zero kinetic speed.
- It is not redstone locked.
- Its fake player exists and holds a `BlockItem`.
- The fake player and the Sable sublevel resolve to the same parent level.

When the deployer becomes inactive, its preceding traversal sample is discarded.
On reactivation it visits the current target once and starts a new path from
there. It intentionally does **not** backfill cells crossed while it was locked,
unpowered, empty, disallowed by the contraption, or otherwise not ticking. For
example, a steering setup that locks the deployers can leave a gap, after which
placement resumes normally once steering releases them.

## Traversal limits

- At most 64 crossed cells are visited by one deployer in one server tick.
- Motion of more than 64 blocks between samples is treated as a teleport. Only
  the new current cell is visited, preventing an accidental long placement line.
- Unloaded chunks are never force-loaded.
- If the held stack stops being a `BlockItem` partway through a sweep, the
  remaining cells are skipped immediately.
- Invalid or degenerate transformed direction vectors reset the traversal
  origin instead of attempting placement.

## Persistence

The extra mode is stored in two places because Create's two-value enum cannot
represent it and Sable has its own assembled-sublevel lifecycle:

- The deployer block entity stores `WorldGridDeployerMode` in its normal NBT.
- An assembled Sable sublevel stores the flag in its extension user data under
  `worldgriddeployer/deployer_modes`, keyed by the deployer's local block
  position.

This lets the setting survive world saves, server restarts, Sable sublevel
serialization, and assembly/disassembly transitions. On first assembly or after
upgrading an existing world, the sublevel copy is seeded from the ordinary block
entity value. Explicit false values are retained so an old true block-entity tag
cannot revive a mode the player deliberately disabled.

## Visual debugging

All commands are client commands and require this mod on that client. Overlays
render through terrain. Historical overlays default to 200 ticks (10 seconds),
accept 1 to 12,000 ticks, and fade independently.

```text
/worldgriddeployer debug targets on
/worldgriddeployer debug targets off

/worldgriddeployer debug point_path on [duration] [ticks|seconds]
/worldgriddeployer debug point_path off
/worldgriddeployer debug point_path clear

/worldgriddeployer debug block_trail on [duration] [ticks|seconds]
/worldgriddeployer debug block_trail off
/worldgriddeployer debug block_trail clear

/worldgriddeployer debug outcomes on [duration] [ticks|seconds]
/worldgriddeployer debug outcomes off
/worldgriddeployer debug outcomes clear
/worldgriddeployer debug outcomes legend

/worldgriddeployer debug all on
/worldgriddeployer debug all off
/worldgriddeployer debug clear
/worldgriddeployer debug status
```

A duration without a unit is interpreted as ticks.

### Local/predicted overlays

- `targets` draws the current candidate block and a yellow cube at the exact
  floating-point working position. The block is green when the client sees
  non-zero RPM and red when it does not.
- `point_path` draws a cyan line through the precise sampled working positions.
- `block_trail` draws blue boxes for every block cell the client-side traversal
  says could have been visited. It intentionally ignores placement success.

These views explain geometry and camera-relative alignment, but they are client
predictions. They do not prove the server attempted a placement.

### Server-authoritative outcomes

`outcomes` subscribes the client to the actual result recorded by the server for
each evaluated target:

| Color | Outcome | Meaning |
| --- | --- | --- |
| Green | `PLACED` | Create changed the target state or placed the expected block. |
| Red | `CREATE_REJECTED` | The target was replaceable, but Create returned without changing it. |
| Purple | `CHUNK_UNLOADED` | The parent-world chunk was not loaded. |
| Gray | `TARGET_OCCUPIED` | The target state was not replaceable. |
| Orange | `NO_BLOCK_ITEM` | No usable `BlockItem` was available. |
| Yellow | `NO_POWER` | Kinetic speed was zero. |
| Magenta | `REDSTONE_LOCKED` | The deployer was redstone locked. |

The server records outcomes only when at least one subscribed player is in that
dimension. Results are grouped into one batch per dimension per server tick,
sent only to subscribers, and capped at 4,096 entries per dimension per tick.
Disconnecting removes the subscription.

Practical interpretation:

- A blue trail cell with no outcome is only a client candidate; the server did
  not evaluate it. An inactive or steering-locked deployer is one expected cause.
- Green confirms the server observed a successful placement.
- Red confirms the server reached Create's handler but the target did not change.
- Gray, purple, orange, yellow, and magenta identify the precondition that
  prevented a normal placement attempt.

## Implementation footprint

The patch is deliberately narrow:

- `DeployerBlockEntityMixin` adds the third-mode flag, NBT synchronization,
  Sable tick integration, grid traversal, and the call into Create's existing
  deployer handler.
- `DeployerBlockEntityClientMixin` samples transformed deployer targets only for
  local visual debugging.
- `FaceConnectedVoxelTraversal` is a pure grid-walk helper shared by placement
  and predicted debug trails.
- `WorldGridDeployerSubLevelState` owns only the per-sublevel mode extension.
- `WorldGridDebugNetworking`, `WorldGridDebugHistory`, and
  `WorldGridDebugClient` implement opt-in diagnostics; they do not participate
  in placement when debugging is disabled.

It adds no blocks, items, recipes, dimensions, chunk tickets, general player
capabilities, or global changes to Create contraptions. Ordinary deployers and
World-Grid-disabled deployers remain on Create's original behavior.

## Build and tests

The build expects the exact Create and Sable jars in the sibling server's
`mods` directory, as configured by `server_mods_dir` in `gradle.properties`.

```powershell
./gradlew.bat clean test build
```

The unit suite covers face-connected traversal, per-tick traversal limits,
debug-history expiry, authoritative outcome replacement/expiry, and network
payload round trips.
