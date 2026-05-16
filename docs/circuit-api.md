# Circuit API Guide

EMcore's circuit layer is a linear modified nodal analysis (MNA) core. It owns topology collection, equation
assembly, solving, samples, and diagnostics. Addon mods own gameplay semantics such as ratings, overload
behavior, damage, recipes, machine state, UI, and block behavior.

Use the public API under `com.firedoge.emcore.api.circuit`. Treat `com.firedoge.emcore.internal.*` as private
implementation detail.

## Integration Flow

Circuit elements are registered through `CircuitAccess`.

Typical block entity flow:

1. Build stable `CircuitPort` values for the block's terminals.
2. Build one or more `CircuitElement` values that use those ports.
3. Register ports, terminals, and elements when the block entity loads or becomes electrically active.
4. Unregister the same ports, terminals, and elements when the block entity unloads or changes identity.
5. Query `CircuitSnapshot`, `CircuitSample`, `AcCircuitSnapshot`, or `AcCircuitSample` when you need readings.

`CircuitElementProvider` is a convenience interface for simple providers:

- `circuitPorts()` returns every port owned by the provider.
- `circuitTerminals()` optionally maps ports to world node positions.
- `circuitElements()` returns the elements currently contributed by the provider.
- `registerCircuitElements(level)` and `unregisterCircuitElements(level)` call `CircuitAccess` in the expected order.

Every `CircuitElement#ports()` must return every port the element may use while building topology or stamping
equations. If a port is stamped but not returned from `ports()`, EMcore reports an element stamp failure.

## Element Types

Elements can contribute topology, equations, or both.

- `CircuitTopologyElement` declares ideal zero-impedance connections through `CircuitTopologyBuilder`.
- `LinearCircuitElement` stamps real-valued DC terms through `CircuitEquationBuilder`.
- `NonlinearCircuitElement` stamps nonlinear Newton-Raphson terms through `NonlinearCircuitEquationBuilder`.
- `AcLinearCircuitElement` stamps complex-valued AC phasor terms through `AcCircuitEquationBuilder`.
- `TransientCircuitElement` stamps fixed-step transient companion-model terms through `TransientCircuitEquationBuilder`.

One element may implement more than one interface. For example, a closed relay can connect two contacts through
topology and also stamp coil-side equations.

`WireElement`, `ResistorElement`, and `VoltageSourceElement` are convenience API records. The solver does not
special-case them; it consumes the generic topology and equation builder interfaces.

`DiodeElement` is a convenience nonlinear DC element using the Shockley diode equation. `CapacitorElement` and
`InductorElement` are convenience ideal reactive elements for AC phasor and fixed-step transient solves.

Compiled example elements live under:

- `src/test/java/com/firedoge/emcore/examples/circuit/ExampleDcCurrentSourceElement.java`
- `src/test/java/com/firedoge/emcore/examples/circuit/ExampleClosedSwitchElement.java`
- `src/test/java/com/firedoge/emcore/examples/circuit/ExampleDcVccsElement.java`
- `src/test/java/com/firedoge/emcore/examples/circuit/ExampleAcVoltageSourceElement.java`
- `src/test/java/com/firedoge/emcore/examples/circuit/ExampleAcCapacitorElement.java`
- `src/test/java/com/firedoge/emcore/examples/circuit/ExampleAcInductorElement.java`
- `src/test/java/com/firedoge/emcore/examples/circuit/ExampleAcVcvsElement.java`

They are in test scope so they do not become gameplay content, but `compileTestJava` verifies that they remain
valid against the public API.

## Port Direction

Two-terminal stamps use `(positivePort, negativePort)`.

- Voltage is `V(positivePort) - V(negativePort)`.
- Positive current flows from `positivePort` to `negativePort`.
- DC sampled power is `voltage * current` for that stamp direction.
- AC complex power is currently reported as `V * conjugate(I)` for the same direction.

If an addon wants the opposite sign, swap the ports or negate the stamped value.

Use distinct ports for distinct device terminals even if they occupy the same block position. Connect ports that
should be the same circuit node through topology (`connectIdeal`) or by registering terminals at the same node
position.

## Topology Stamps

`CircuitTopologyBuilder#connectIdeal(a, b)` collapses two ports into the same circuit node. Use it for wires,
closed switches, relay contacts, busbars, or any other ideal conductive state.

Open switches should simply avoid declaring an ideal connection for that tick. See `ExampleClosedSwitchElement`
for the minimal pattern.

Topology is collected before DC or AC equation stamping. That means both DC and AC solvers see the same ideal
connectivity.

## DC Equation Stamps

`LinearCircuitElement#stamp(CircuitEquationBuilder builder)` supports:

- conductance: `I = G * (Vpositive - Vnegative)`
- independent current source: fixed current from positive to negative
- independent voltage source: `Vpositive - Vnegative = V`
- VCCS: output current controlled by a port voltage
- VCVS: output voltage controlled by a port voltage
- CCCS: output current controlled by a branch current
- CCVS: output voltage controlled by a branch current
- current probe: a 0V ideal voltage source that exposes branch current

Units:

- conductance: siemens
- current: amps
- voltage: volts
- VCCS transconductance: siemens
- VCVS gain: unitless
- CCCS gain: unitless
- CCVS transresistance: ohms

Basic DC examples:

```java
builder.addConductance(positivePort, negativePort, 1.0 / resistanceOhms);
builder.addCurrentSource(positivePort, negativePort, currentAmps);
builder.addVoltageSource(positivePort, negativePort, voltageVolts);
```

For a current-controlled source, use a branch current returned by a voltage-like stamp:

```java
CircuitBranchCurrent control = builder.addCurrentProbe(probePositivePort, probeNegativePort);
builder.addCurrentControlledCurrentSource(outputPositivePort, outputNegativePort, control, gain);
```

The probe is an ideal 0V voltage source, not a passive observer. It changes the MNA system and must be placed
where an ideal 0V series element is acceptable.

## Nonlinear DC Stamps

`NonlinearCircuitElement#stamp(NonlinearCircuitEquationBuilder builder)` supports Newton-Raphson nonlinear solves.
The first nonlinear API shape is intentionally small: elements read their current Newton estimate voltage, then stamp
a current and differential conductance at that point.

For a two-terminal current `I = f(V)` flowing from positive to negative:

```java
double voltage = builder.voltage(positivePort, negativePort);
double current = f(voltage);
double conductance = derivativeAt(voltage);
builder.addLinearizedCurrent(positivePort, negativePort, current, conductance);
```

Internally EMcore linearizes this as:

```text
I(V) ~= G * V + (I0 - G * V0)
```

where `V0` and `I0` are the current Newton estimate and `G = dI/dV`.

The built-in `DiodeElement` stamps the Shockley model:

```text
I = Is * (exp(V / (n * Vt)) - 1)
G = dI/dV
```

AC solves use the latest DC operating point as their small-signal bias point. When `acSnapshot(...)` or
`sampleAcPort(...)` is requested, EMcore first refreshes the DC solve if needed. Nonlinear elements are then stamped
into the AC system as their differential conductance `G = dI/dV` at that solved bias point. The DC equivalent current
offset is not stamped into AC.

The same nonlinear stamps are also included in transient steps, where they solve against the current time step's
linear companion-model terms. This is still a circuit-level time-domain solve, not a field propagation or RF channel
model.

## AC Phasor Stamps

`AcLinearCircuitElement#stamp(AcCircuitEquationBuilder builder)` supports the same MNA shape as DC, but values
are `CircuitPhasor` instead of `double`.

The builder is scoped to one requested frequency:

- `frequencyHertz()`
- `angularFrequencyRadiansPerSecond()`

AC supported stamps:

- admittance: `I = Y * (Vpositive - Vnegative)`
- independent current source: fixed phasor current from positive to negative
- independent voltage source: phasor `Vpositive - Vnegative`
- VCCS, VCVS, CCCS, and CCVS with complex gains or transimpedance
- current probe: a 0V ideal voltage source that exposes phasor branch current
- nonlinear DC elements as real small-signal admittance at the current DC bias point

Basic AC examples:

```java
builder.addAdmittance(positivePort, negativePort, CircuitPhasor.real(1.0 / resistanceOhms));
builder.addVoltageSource(positivePort, negativePort, CircuitPhasor.of(10.0, 0.0));
builder.addVoltageControlledVoltageSource(outP, outN, ctrlP, ctrlN, CircuitPhasor.of(0.0, 2.0));
```

Frequency-dependent elements should stamp admittance for the requested frequency:

```java
double omega = builder.angularFrequencyRadiansPerSecond();
CircuitPhasor capacitorY = CircuitPhasor.of(0.0, omega * capacitanceFarads);
CircuitPhasor inductorY = CircuitPhasor.of(0.0, -1.0 / (omega * inductanceHenries));
```

For an ideal inductor, `omega == 0` is singular. Do not stamp an infinite admittance. Either reject zero-frequency
AC solves for that element, model a non-ideal inductor with resistance, or represent DC behavior through a
separate DC/topology state.

AC solves are requested separately from DC:

```java
AcCircuitSnapshot snapshot = Electromagnetics.api().circuits().acSnapshot(level, 60.0);
Optional<AcCircuitSample> sample = Electromagnetics.api().circuits().sampleAcPort(level, port, 60.0);
```

## Transient Stamps

`TransientCircuitElement#stamp(TransientCircuitEquationBuilder builder)` supports fixed-step transient companion
models. The first transient API surface covers ideal capacitors and inductors:

```java
double t = builder.timeSeconds();
builder.addCapacitance(positivePort, negativePort, capacitanceFarads);
builder.addInductance(positivePort, negativePort, inductanceHenries);
builder.addVoltageSource(positivePort, negativePort, voltageAt(t));
builder.addCurrentSource(positivePort, negativePort, currentAt(t));
```

The capacitor companion model uses backward Euler:

```text
G = C / dt
Ieq = -G * Vprevious
I(t) = G * V(t) + Ieq
```

The inductor companion model also uses backward Euler, stamped in Norton form:

```text
G = dt / L
Ieq = Iprevious
I(t) = G * V(t) + Ieq
```

`CapacitorElement` and `InductorElement` stamp these models automatically. Previous capacitor voltage and inductor
current are stored internally by element id and stamp index, so re-registering an element with a different internal
dynamic-element order should be treated as new dynamic state.

Transient solves are requested explicitly:

```java
CircuitSnapshot snapshot = Electromagnetics.api().circuits().stepTransient(level, 1.0e-4);
```

`stepTransient(...)` returns the time-domain snapshot for that step. It does not replace `snapshot(...)`, which
continues to mean DC operating point. Nonlinear elements participate in the same Newton-Raphson loop per time step;
successful transient steps keep dynamic element state and node-voltage estimates for the next step. The current MVP
uses fixed caller-supplied time steps and does not yet provide adaptive stepping.

For in-game debugging, operators can advance transient solves manually:

```text
/emcore circuit transient step <dtSeconds> [steps]
/emcore circuit transient probe <x y z> <dtSeconds> [steps]
```

The transient debug output reports the requested step size, number of steps, accumulated transient time, diagnostics,
and per-port `V`, `I`, `P`, and nonzero stored energy `E`.

## Batch Transient Solves

For audio-rate or offline workflows, use the batch transient API instead of stepping a `ServerLevel` circuit:

```java
CircuitNetlist netlist = new CircuitNetlist(List.of(source, resistor, capacitor, wires));
BatchTransientRequest request = new BatchTransientRequest(
        netlist,
        1.0 / 96_000.0,
        960,
        List.of(audioProbePort)
);
BatchTransientResult result = Electromagnetics.api().circuits().solveTransient(request);
```

`CircuitNetlist` is a copied list of ports, terminals, and elements, so the batch solver does not need a
`ServerLevel`. `TransientVoltageSourceElement` and `TransientCurrentSourceElement` are convenience sources for
`v(t)` and `i(t)` inputs such as sampled AM/audio waveforms.

Each `BatchTransientStep` reports the step index, time, solved nodes, requested probe samples, and diagnostics. If
the request has no probes, each step returns all port samples.

The first implementation is intentionally a correctness-oriented fixed-step backend. It prepares the stable port
index, topology, node mapping, static linear stamps, nonlinear element list, component partition, branch-current
columns, matrix shape, and static matrix template once per request. Each step restamps transient
sources/companion terms, copies the prepared matrix template, then fills dynamic RHS/dynamic conductance and
nonlinear Newton terms before running the current dense solve. Future backends can go deeper by reusing
factorizations for purely linear fixed-layout blocks.

For the prepared path, transient elements must keep the same stamp layout every step: same stamp count, same
ports, same order. Stamp zero-valued sources/conductances when a time-varying effect is inactive instead of
omitting that stamp for the frame.

## Branch Current

`CircuitBranchCurrent` identifies a current unknown introduced by a voltage-like branch:

- independent voltage source
- VCVS
- CCVS
- 0V current probe

The branch current direction is from that branch's `positivePort` to `negativePort`.

Current-controlled sources must reference a `CircuitBranchCurrent` returned by one of those stamps. If the
referenced branch current is missing or duplicated, EMcore reports a diagnostic and does not solve that component.

## Samples

`CircuitSample` is reported per exact port for DC:

- `voltageVolts`: solved node voltage for that port
- `currentAmps`: sum of stamped current contributions associated with that exact port
- `powerWatts`: sum of stamped power contributions associated with that exact port
- `storedEnergyJoules`: transient capacitor or inductor energy when reported by `stepTransient(...)`
- `overloaded`: reserved for API compatibility; gameplay mods own ratings and failure behavior

`AcCircuitSample` is reported per exact port for AC:

- `voltageVolts`: voltage phasor
- `currentAmps`: current phasor
- `complexPowerVoltAmps`: complex power phasor-derived reading

If multiple element terminals intentionally share one `CircuitPort`, their contributions accumulate. For clearer
per-device readings, use distinct ports and connect them through topology when they should share a circuit node.

## Diagnostics

Circuit snapshots may include diagnostics for invalid or unsupported linear systems:

- element topology/stamp failures
- duplicate or missing branch current references
- dense solver scale warnings for large components
- ideal voltage source shorted by ideal conductors
- conflicting ideal voltage source constraints
- linear solve failure

Diagnostics are solver/API feedback. They are not gameplay overload events. Addon mods can translate diagnostics
into UI, logs, machine shutdown, damage, or other gameplay behavior if desired.

Recommended diagnostic handling for addon mods:

1. Show diagnostics in development/debug builds.
2. Treat `ERROR` diagnostics as "reading unavailable" for affected devices.
3. Avoid converting solver diagnostics directly into explosions or damage inside EMcore-facing code.
4. Keep gameplay fault logic in your own mod layer.

## DC And AC Boundary

The DC circuit API is real-valued steady-state DC. AC phasor solving reuses the same topology model and MNA
shape, but it has a separate complex-valued stamp/result layer.

Do not encode AC phase into `CircuitSample`. Use `AcCircuitSnapshot` and `AcCircuitSample` for AC. If an element
has both DC and AC behavior, implement both `LinearCircuitElement` and `AcLinearCircuitElement`, and stamp the
appropriate model in each method.
