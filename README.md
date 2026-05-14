# ElectromagneticsCore

ElectromagneticsCore is a NeoForge mod library for electromagnetic simulation and integration APIs.
It is intended to provide stable field, circuit, signal, and event interfaces that other mods can build on.

The first development target is a minimal server-side core:

- public API records and interfaces for field sampling, circuit readings, signal sampling, and EM events
- per-world service state for simulation ownership
- a circuit-network MVP before higher-cost field or Maxwell-grid simulation work

Other mods should enter through:

```java
Electromagnetics.api()
```

The current implementation installs a server-side API with per-world state and a minimal DC circuit network.
It supports registered ports, resistors, ideal voltage sources, and ideal wires. Field and signal solvers
still return empty samples.
Custom elements can implement `CircuitTopologyElement`, `LinearCircuitElement`, or both. Topology elements
declare ideal node connections through `CircuitTopologyBuilder`; linear elements stamp conductance,
current-source, voltage-source, voltage-controlled source, and current-controlled source terms through
`CircuitEquationBuilder`. Current-controlled sources reference a `CircuitBranchCurrent`, usually returned
by a voltage-source-like stamp or a 0V current probe. EMcore only solves and reports electrical state;
gameplay rules such as ratings, overload behavior, damage, or shutdown remain the responsibility of the mod
that owns the element.
Detailed sign, current, power, branch-current, and DC/AC boundary conventions are documented in
[`docs/circuit-api.md`](docs/circuit-api.md).

Debug blocks:

- Debug Voltage Source: 12V between this block and the block in front of it
- Debug Resistor: 6 ohm between this block and the block behind it
- Debug Wire: ideal wire between the blocks behind and in front of it
- Debug Junction Wire: ideal wire between this block and all six adjacent blocks

Debug blocks face the horizontal direction you are looking when placed. For the simplest in-game circuit,
place a Debug Voltage Source, then place a Debug Resistor directly in front of it with the same facing.
The expected current is 2A.

Circuit terminals connect automatically when they declare the same world node position. Debug blocks use this
terminal layer instead of manually wiring every block together.
Disconnected circuit components are solved independently, so an isolated test block will not invalidate other
circuits in the same dimension.
Circuit snapshots also expose diagnostics for invalid ideal constraints, such as a voltage source shorted by
ideal conductors, conflicting ideal voltage sources, or a linear solve failure.

Debug commands:

```text
/emcore circuit list
/emcore circuit probe <pos>
/emcore circuit test create <pos>
/emcore circuit test short <pos>
/emcore circuit test conflict <pos>
/emcore circuit test clear
```

## Development

Build the project with:

```bash
./gradlew build
```

Run client and server configurations through Gradle or an IDE after the NeoForge project is imported.
