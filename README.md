# Create World-Grid Deployer

A focused NeoForge 1.21.1 compatibility patch for Create 6.0.10 and Sable
2.0.3. It adds a third, world-grid `USE` mode to Create deployers mounted on
Sable physics sublevels.

The mode projects the deployer's active point into the parent world and places
blocks once per crossed world cell. Fast movement is handled with a bounded,
face-connected voxel traversal, so a deployer can build continuous bridges even
when its target moves by more than one block in a server tick.

## Mode cycle

Wrench the normal deployer mode selector:

1. Punch
2. Use
3. World-Grid Use

Internally, World-Grid Use remains Create's `USE` mode plus a persisted patcher
flag. Disassembled deployers retain the setting but behave like normal deployers
until mounted on a Sable sublevel.

## Safety rules

- Non-zero RPM arms the deployer; RPM magnitude does not control cadence.
- Redstone locking disables placement and resets the traversal origin.
- Only `BlockItem` stacks are accepted in World-Grid Use.
- Only strict air cells in already-loaded parent-world chunks are attempted.
- Placement is synchronous and uses Create's normal `DeployerHandler`.
- Sweeps are capped at 64 cells and 64 blocks per server tick.

## Build

The build expects the exact Create and Sable jars in the sibling server's
`mods` directory, as configured by `server_mods_dir` in `gradle.properties`.

```powershell
./gradlew.bat clean test build
```
