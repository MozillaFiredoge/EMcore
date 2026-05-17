# Field API Guide

EMcore's field layer is a finite-region electrostatic and quasi-static magnetic field core. It owns field
registration, dirty-region tracking, Poisson solves, committed snapshots, samples, diagnostics, and low-level
interaction outputs. Addon mods own gameplay content, machines, visuals, block/entity behavior, ratings, damage,
recipes, and compatibility with physics mods.

Use the public API under `com.firedoge.emcore.api.field`. Treat `com.firedoge.emcore.internal.*` as private
implementation detail.

## Integration Flow

Field data is registered through `FieldAccess`:

```java
FieldAccess fields = Electromagnetics.api().fields();
```

Typical provider flow:

1. Register one or more finite `FieldRegion` solve domains.
2. Register `FieldSource` values for charge, potential boundaries, material hints, or current density.
3. Optionally register `CoilRegion` values for magnetic flux and induction sampling.
4. Optionally register `CircuitDrivenFieldSource` values when a circuit current should create a magnetic source.
5. Request a solve explicitly with `solve(...)` / `requestSolve(...)`, or let the world tick schedule small dirty
   regions.
6. Read committed samples with `sample(...)`, `sampleEnergy(...)`, `sampleForce(...)`, `sampleTorque(...)`,
   `sampleFlux(...)`, or `sampleCoil(...)`.

`FieldSourceProvider` is a convenience interface for simple static providers:

- `fieldRegions()` returns contributed regions.
- `fieldSources()` returns contributed sources.
- `registerFieldSources(level)` and `unregisterFieldSources(level)` call `FieldAccess` in the expected order.

## Coordinates And Units

The current field model uses Minecraft world coordinates as meters:

- one block = one meter
- `Vec3` positions are world-space meters
- `AABB` bounds are world-space meters
- `FieldRegion.cellSizeMeters` controls the finite-difference grid spacing

Primary units:

- charge: coulombs
- charge density: coulombs per cubic meter
- potential: volts
- electric field: volts per meter
- current: amps
- current density: amps per square meter
- magnetic flux density: tesla
- magnetic flux: webers
- force: newtons
- torque: newton meters
- energy density: joules per cubic meter

## Regions

`FieldRegion` defines one finite solve domain:

```java
FieldRegion region = new FieldRegion(
        regionId,
        new AABB(x0, y0, z0, x1, y1, z1),
        1.0
);

Electromagnetics.api().fields().registerRegion(level, region);
```

Regions are explicit. Sampling outside all registered regions returns `Optional.empty()` for the rich APIs and zero
for the legacy scalar/vector convenience methods.

The current backend prepares a regular grid with one-cell outer boundary. Large regions are allowed but may be
deferred by the automatic tick budget; call `solve(level, regionId)` when you explicitly want a synchronous solve.

## Sources

`FieldSource` is the generic source record. Use its static factories instead of constructing raw values directly:

```java
FieldSource charge = FieldSource.pointCharge(id, regionId, position, 1.0e-12);
FieldSource rho = FieldSource.chargeDensity(id, regionId, position, radiusMeters, rhoCoulombsPerM3);
FieldSource boundary = FieldSource.potentialBoundary(id, regionId, position, radiusMeters, volts);
FieldSource epsilon = FieldSource.relativePermittivity(id, regionId, position, radiusMeters, epsilonRelative);
FieldSource current = FieldSource.currentDensity(id, regionId, position, radiusMeters, jAmpsPerM2);
```

Source ids are replacement keys. Re-registering the same source id replaces the previous source, marks the affected
region dirty, and invalidates the committed solve for that region.

Current source behavior:

- `POINT_CHARGE`, `CHARGE_DENSITY`, `POTENTIAL_BOUNDARY`, and `RELATIVE_PERMITTIVITY` affect the electrostatic
  Poisson solve.
- `CURRENT_DENSITY` affects the magnetostatic vector-potential solve.

The current material model supports relative permittivity in the electrostatic solve. Magnetic permeability,
conductivity, shielding, and richer material interfaces are future extensions.

## Solve Lifecycle

The field solve is snapshot-based. Registration mutates pending world state and marks regions dirty; sampling reads
the latest committed solved snapshots.

Use synchronous solve for tooling, small tests, or explicit control:

```java
Optional<FieldSolveResult> result = Electromagnetics.api().fields().solve(level, regionId);
```

Use async request for normal game flow:

```java
boolean accepted = Electromagnetics.api().fields().requestSolve(level, regionId);
```

World tick behavior:

1. Completed background solves are committed.
2. Circuit state is solved.
3. Circuit-driven field sources are synchronized from circuit samples.
4. Field dirty regions may be queued within the automatic budget.

This means circuit-to-field-to-circuit feedback is intentionally delayed by committed snapshots. Addon mods should
design coupled machines around this quasi-static, snapshot-driven behavior rather than assuming a fully implicit
Maxwell solve in one tick.

## Snapshots And Diagnostics

`FieldSnapshot` reports:

- registered regions
- registered `FieldSource` values
- dirty region ids
- diagnostics
- version
- stale flag

Diagnostics include stale regions, missing source regions, in-progress solves, deferred large solves, failed solves,
and non-converged solves.

`FieldSnapshot` does not include `CoilRegion` or `CircuitDrivenFieldSource` lists. Query those with:

```java
fields.coils(level);
fields.circuitDrivenSources(level);
```

## Electric And Magnetic Samples

Use `sample(level, position)` for the rich electrostatic sample:

```java
Optional<FieldSample> sample = fields.sample(level, position);
```

`FieldSample` reports:

- electric field vector `E`
- potential `phi`
- charge density
- electric field energy density
- stale flag
- containing region ids

Use `sampleMagneticField(level, position)` for magnetic flux density:

```java
MagneticFieldSample b = fields.sampleMagneticField(level, position);
```

The current magnetostatic backend solves vector potential `A` from current density, then samples:

```text
B = curl(A)
```

The convenience samplers `sampleElectricField`, `sampleMagneticField`, `samplePotential`, and
`sampleMagneticFlux` return zero-like values outside regions. Prefer the `Optional`-returning APIs when absence is
important.

## Energy Sampling

Use `sampleEnergy(...)` for combined electric and magnetic energy density:

```java
Optional<FieldEnergySample> energy = fields.sampleEnergy(level, position);
```

The current formulas are:

```text
uE = 1/2 * epsilon0 * epsilon_r * |E|^2
uB = |B|^2 / (2 * mu0)
```

`FieldEnergySample` reports `electricEnergyDensityJoulesPerCubicMeter`,
`magneticEnergyDensityJoulesPerCubicMeter`, and `totalEnergyDensityJoulesPerCubicMeter`.

## Force Sampling

Use `ChargedFieldProbe` for point-charge Lorentz force:

```java
ChargedFieldProbe probe = new ChargedFieldProbe(
        probeId,
        position,
        chargeCoulombs,
        velocityMetersPerSecond
);

Optional<FieldForceSample> force = fields.sampleForce(level, probe);
```

Formula:

```text
F = q * (E + v x B)
```

Use `CurrentSegmentProbe` for a concentrated straight current segment:

```java
CurrentSegmentProbe segment = new CurrentSegmentProbe(
        probeId,
        center,
        direction,
        lengthMeters,
        currentAmps
);

Optional<FieldForceSample> force = fields.sampleForce(level, segment);
```

Formula:

```text
F = I * L x B
```

`direction` is normalized internally and multiplied by `lengthMeters` to form the length vector `L`.

These are point/concentrated probes. For full rigid bodies, contraptions, or vehicle structures, build a probe
cloud or wait for a future region-integration API. Do not treat one point sample as a complete force model for a
large body unless that is the approximation you want.

## Torque Sampling

Use `CoilTorqueProbe` for concentrated magnetic-dipole torque:

```java
CoilTorqueProbe probe = new CoilTorqueProbe(
        probeId,
        center,
        normal,
        areaSquareMeters,
        turns,
        currentAmps
);

Optional<FieldTorqueSample> torque = fields.sampleTorque(level, probe);
```

Formula:

```text
m = N * I * A * n
tau = m x B
```

For a registered coil:

```java
Optional<FieldTorqueSample> torque = fields.sampleCoilTorque(level, coilId, currentAmps);
```

This uses the registered `CoilRegion` geometry and the caller-supplied current.

## Flux And Coil Sampling

`FluxProbe` is a one-shot read-only probe:

```java
FluxProbe probe = new FluxProbe(
        probeId,
        regionId,
        center,
        Direction.NORTH,
        areaSquareMeters,
        turns
);

Optional<FluxSample> sample = fields.sampleFlux(level, probe);
```

The current approximation is lumped:

```text
Phi = dot(B(center), normal) * area
linkage = N * Phi
```

`CoilRegion` is a registered coil with history for induction:

```java
CoilRegion coil = new CoilRegion(coilId, regionId, center, Direction.NORTH, area, turns);
fields.registerCoil(level, coil);

Optional<FluxSample> sample = fields.sampleCoil(level, coilId);
```

`sampleCoil(...)` computes induced voltage from flux-linkage history:

```text
V_induced = -d(N * Phi) / dt
```

The first sample has no previous history and reports `OptionalDouble.empty()`. Repeated sampling at the same world
time reuses the last computed induced voltage so a circuit solve and a debug read in the same tick see a stable
value.

## Circuit To Field Coupling

Use `CircuitDrivenFieldSource` when a circuit current should create field current density.

```java
CircuitDrivenFieldSource driven = CircuitDrivenFieldSource.currentDensityAlong(
        sourceId,
        regionId,
        currentPort,
        sourcePosition,
        radiusMeters,
        direction,
        crossSectionAreaSquareMeters
);

fields.registerCircuitDrivenSource(level, driven);
```

At world tick, EMcore reads `CircuitSample.currentAmps()` from `currentPort`, converts it into:

```text
J = I * currentDensityPerAmp
```

and registers/replaces an internal `FieldSource.currentDensity(...)` with the same id as the driven source.

The helper `currentDensityAlong(...)` creates:

```text
currentDensityPerAmp = unit(direction) / crossSectionArea
```

For best sign control, provide a stable `CircuitPort` whose reported current direction is the one your field source
expects. `CircuitSample.currentAmps()` is per exact port and accumulates stamped contributions for that port, so
dedicated ports are easier to reason about than shared multi-element ports.

## Field To Circuit Coupling

Use `FieldInducedVoltageSourceElement` from the circuit API when a registered coil should drive a circuit:

```java
CircuitElement inducedSource = new FieldInducedVoltageSourceElement(
        elementId,
        positivePort,
        negativePort,
        coilId
);

Electromagnetics.api().circuits().registerElement(level, inducedSource);
```

During circuit solve, EMcore samples the coil and stamps an ideal voltage source:

```text
V(positivePort) - V(negativePort) = voltageScale * V_induced
```

Diagnostics:

- `FIELD_COUPLING_NOT_AVAILABLE`: the coil cannot be sampled; EMcore stamps 0V for that source.
- `FIELD_COUPLING_STALE`: the coil sample exists but is based on a stale or missing solved field snapshot.

`FieldInducedVoltageSourceElement` is a coupling adapter only. Addon mods should add their own coil resistance,
load, rectifier, tuner, machine logic, UI, and ratings.

## Stale Semantics

`stale == true` means the sample was produced from an incomplete, dirty, missing, pending, or otherwise outdated
committed field snapshot. It does not mean the value is unusable; it means the value should be treated as last-known
or provisional.

Recommended handling:

1. Use stale samples for smooth visuals and low-risk UI.
2. Gate high-impact gameplay actions on fresh samples or explicit solve completion.
3. Surface stale diagnostics in developer tools.
4. Avoid forcing synchronous solves every tick for large regions.

## Current Limits

The current field layer is not a full Maxwell time-domain solver.

Implemented:

- electrostatic Poisson solve
- magnetostatic vector-potential solve
- magnetic flux and quasi-static induction
- circuit-current to current-density coupling
- field-induced voltage source coupling
- point/concentrated energy, force, and torque samples

Not yet implemented:

- full electromagnetic wave propagation
- reflection/interference from fields
- magnetic permeability materials
- conductivity and eddy currents
- field region integration over arbitrary bodies
- moving body projection API
- Create: Aeronautics compatibility

Use quasi-static or lumped models when the world/system size is much smaller than the signal wavelength. If a
future addon needs propagation delay, reflection, or antenna patterns, that should be designed as a separate wave
layer instead of overloading the current Poisson field backend.
