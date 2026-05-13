# ElectromagneticsCore

ElectromagneticsCore is a NeoForge mod library for electromagnetic simulation and integration APIs.
It is intended to provide stable field, circuit, signal, and event interfaces that other mods can build on.

The first development target is a minimal server-side core:

- public API records and interfaces for field sampling, circuit readings, signal sampling, and EM events
- per-world service state for simulation ownership
- a circuit-network MVP before higher-cost field or Maxwell-grid simulation work

## Development

Build the project with:

```bash
./gradlew build
```

Run client and server configurations through Gradle or an IDE after the NeoForge project is imported.
