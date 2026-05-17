# EMcore Roadmap

EMcore is intended to add electromagnetic rules to the world, not to own a specific gameplay loop. The core should
behave more like gravity, redstone, or fluids: a persistent infrastructure layer that addon mods can register into,
sample from, and adapt to their own machines, vehicles, blocks, UI, recipes, and balance.

The project should keep the same boundary across all subsystems:

- EMcore owns registration, topology/field state, solvers, snapshots, samples, diagnostics, and low-level APIs.
- Addon mods own gameplay semantics, content, machines, ratings, damage, recipes, visuals, and player-facing rules.
- Compat modules may adapt EMcore outputs into another mod, but EMcore core must not depend on that mod.

## Current Status

- Circuits MVP: DC, AC phasor, transient fixed-step, nonlinear Newton-Raphson, batch transient solves, diagnostics,
  public circuit element APIs, and field-induced voltage source coupling are in place.
- Signals MVP: source registration, channel sampling, attenuation, phase/delay, interference reporting, and AC
  circuit coupling are in place.
- Fields MVP: finite field regions, electrostatic Poisson solving, magnetostatic vector-potential solving, field
  samples, flux/coil sampling, circuit-current driven field sources, induced-voltage circuit coupling, and
  force/torque/energy probes are in place.
- Example gameplay content is intentionally out of scope for this repository. Demonstrations should live in addon
  mods or compat modules.

The current public API guides are:

- [`circuit-api.md`](circuit-api.md)
- [`field-api.md`](field-api.md)
- [`signal-api.md`](signal-api.md)

## Near-Term Direction

The Fields MVP is now in place. The goal remains broader than distance lookups: fields should behave as world-space
state that can be sampled by blocks, entities, contraptions, and other systems.

The initial electrostatic backend is based on Poisson solving:

```text
div(epsilon grad(phi)) = -rho
E = -grad(phi)
```

This established the field infrastructure, sampling API, and static electric field interactions without taking on
full Maxwell time stepping. Near-term field work should now prioritize documentation, diagnostics, region/probe-cloud
integration, material models, and performance budgets.

## Milestone 1: Field API Skeleton

Define a public `com.firedoge.emcore.api.field` package with stable concepts before committing to one solver.

Checklist:

- [ ] `FieldAccess` entry point exposed through `Electromagnetics.api().fields()`.
- [ ] Field source registration for charge density, point/shape charges, potential boundaries, and material data.
- [ ] Field region registration for finite solve domains.
- [ ] Read-only `FieldSnapshot` and `FieldSample` records.
- [ ] Probe API for sampling `phi`, `E`, energy density, and diagnostics at world positions.
- [ ] Provider convenience interfaces for block entities and moving bodies.
- [ ] Debug commands for listing regions, forcing a solve, and sampling a position.

Acceptance criteria:

- A block can contribute a charge or voltage boundary.
- Another block can sample the latest committed field snapshot without triggering a solve.
- The public API does not expose internal solver classes.

## Milestone 2: Prepared Poisson Region

Implement the first electrostatic backend around a prepared finite-difference region.

Checklist:

- [ ] `PreparedFieldRegion` stores grid layout, material layout, boundary layout, solver workspace, and snapshots.
- [ ] Block/material/source changes mark regions dirty instead of rebuilding all field state every tick.
- [ ] Solver hot loops use primitive arrays, for example `double[] phi`, `double[] rhs`, and `float[] epsilon`.
- [ ] No per-cell objects, streams, or maps inside solver iteration loops.
- [ ] Previous `phi` is reused as the next initial estimate.
- [ ] Background worker solves dirty regions and publishes immutable snapshots back to the main thread.
- [ ] Main thread only registers changes, samples committed snapshots, and swaps completed results.

Acceptance criteria:

- Static source changes trigger an async Poisson solve.
- A solved region remains sampleable at negligible main-thread cost.
- Repeated solves reuse buffers and do not allocate large arrays in the tick hot path.

## Milestone 3: Field Sampling And Debug Visualization

Make the invisible field observable enough for development and addon integration.

Checklist:

- [ ] Probe samples include potential, electric field vector, and diagnostic flags.
- [ ] Debug command can print field values at a block or exact vector position.
- [ ] Optional debug overlay or particles visualize field direction/magnitude at low sample density.
- [ ] Region diagnostics report convergence, residual, solve time, iteration count, and stale snapshot status.
- [ ] Sampling outside a region returns a clear empty result instead of guessing.

Acceptance criteria:

- Developers can verify a charged plate, grounded plate, and shield-like boundary in game.
- Field samples remain stable while the next async solve is still running.

## Milestone 4: Moving Field Bodies

Support moving structures without making the core depend on a specific vehicle or contraption mod.

Checklist:

- [ ] `MovingFieldBody` abstraction with world transform, velocity, angular velocity, and local source/material data.
- [ ] Local body-space sources can be projected into affected world field regions.
- [ ] Projection is dirty-region based and can run at a lower rate than every tick.
- [ ] High-speed or large bodies can use equivalent sources instead of full voxel projection.
- [ ] Body sampling supports forces, torques, field probes, and field energy queries.

Acceptance criteria:

- A moving body can contribute charge or boundary data to a field region.
- A moving body can sample the committed field and receive a force/torque result through an abstract interface.
- No Create: Aeronautics type is referenced by core API or internal core implementation.

## Milestone 5: Physics Bridge

Expose field effects as generic force and torque outputs.

Checklist:

- [ ] `FieldForceReceiver` or equivalent interface for applying force, torque, or full wrench output.
- [ ] Force calculation path for charged bodies in electric fields.
- [ ] Torque calculation path for distributed sources or dipole-like approximations.
- [ ] Snapshot-based force calculation so physics systems never read mutable solver buffers.
- [ ] Diagnostics for stale fields, missing regions, and unsupported source/body combinations.

Acceptance criteria:

- A generic physics body can be pushed or rotated by sampled field data.
- The bridge is reusable by multiple physics mods, not just one compat target.

## Milestone 6: Optional Create: Aeronautics Compat

Create: Aeronautics is a strong validation target because it can physicalize changing block structures, but it
should remain a compat layer.

Checklist:

- [ ] Separate module or addon package that depends on both EMcore and Create: Aeronautics.
- [ ] Adapter maps Aeronautics contraptions/bodies into `MovingFieldBody`.
- [ ] Adapter maps EMcore force/torque output back into Aeronautics physics bodies.
- [ ] Configurable update rate and region projection budget for large moving structures.
- [ ] Graceful behavior when Aeronautics is absent.

Acceptance criteria:

- A physicalized moving structure can carry EMcore field sources.
- EMcore can apply field-derived forces or torques to that structure through the adapter.
- Removing the compat module does not affect core circuits, signals, or fields.

## Milestone 7: Magnetostatic And Quasi-Static Induction

After electrostatic fields are stable, add magnetic field support without jumping directly to full electromagnetic
waves.

Checklist:

- [ ] Current-distribution registration from circuits or addon-defined sources.
- [ ] Magnetostatic vector potential backend, for example `laplacian(A) = -mu J`.
- [ ] Magnetic field sampling via `B = curl(A)`.
- [ ] Coil/flux probe API for sampling magnetic flux through an area.
- [ ] Induced voltage feedback into circuits via `V = -dPhi/dt`.
- [ ] Update triggers based on current changes, moving-body changes, and material changes.

Acceptance criteria:

- Coils, electromagnets, transformers, and generators can be modeled through quasi-static coupling.
- Circuits can receive induced voltages from changing magnetic flux.
- The implementation still avoids full Maxwell time-domain stepping.

## Milestone 8: Wave Layer Decision Point

Full electromagnetic wave propagation should be a later subsystem, not part of the Fields MVP.

Use this rule of thumb:

```text
If world/system size L << wavelength / 10, use quasi-static or lumped models.
If L >= wavelength / 10, consider transmission-line or wave propagation models.
```

Checklist before starting a wave layer:

- [ ] Electrostatic field infrastructure is stable.
- [ ] Magnetostatic and induction APIs are stable.
- [ ] There is a real gameplay or addon need for propagation delay, reflection, interference, or antenna patterns.
- [ ] Solver backend strategy is chosen deliberately: transmission line, Helmholtz, FDTD, ray approximation, or a
  game-specific reduced-speed medium.

Non-goals for the current roadmap phase:

- [ ] Full Maxwell FDTD across the world.
- [ ] Per-tick global field rebuilds.
- [ ] Hard dependency on Create: Aeronautics.
- [ ] Gameplay-specific machines or example content in this repository.

## Cross-Cutting Performance Rules

These rules apply to all future field work:

- Main-thread work should be bounded and predictable.
- Solver work should run on background workers over immutable input snapshots.
- Large buffers should be owned by prepared regions and reused.
- Published snapshots should be read-only and safe to sample from the main thread.
- Dirty updates should coalesce so multiple block changes trigger one solve, not many.
- Region size, iteration count, solve budget, and update rate must be configurable.
- Diagnostics must expose stale snapshots, failed convergence, oversize regions, and dropped solve jobs.

## Suggested Immediate Tasks

1. Keep `field-api.md`, `circuit-api.md`, and `signal-api.md` aligned with public API changes.
2. Add region/probe-cloud integration APIs for force, torque, and energy over larger structures.
3. Improve field coupling diagnostics for missing ports, missing coils, stale fields, and coupling latency.
4. Expand materials beyond relative permittivity: magnetic permeability, conductivity, and shielding/boundaries.
5. Add performance benchmarks for representative region sizes and solve budgets before considering JNI backends.
