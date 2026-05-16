# Signal API Guide

EMcore's signal layer is a lightweight channel sampler for addon-defined transmissions. It owns source
registration, distance attenuation, strongest-source selection, same-channel interference accounting, delay, and
phase sampling. Addon mods own radios, antennas, speakers, packets, codecs, UI, recipes, permissions, and gameplay
rules for whether a signal is usable.

Use the public API under `com.firedoge.emcore.api.signal`. Treat `com.firedoge.emcore.internal.*` as private
implementation detail.

## Integration Flow

Signal sources are registered through `SignalAccess`.

Typical block entity flow:

1. Build a stable `SignalSource` id for the transmitter.
2. Pick a `channelId`. This is the logical tuning channel receivers sample.
3. Register the source when the transmitter is active.
4. Re-register the same source id when position, power, frequency, or payload changes.
5. Unregister the source when the transmitter unloads or stops transmitting.
6. Receivers call `sample(level, channelId, receiverPosition)` and decide how to use the returned `SignalSample`.

`SignalSourceProvider` is a convenience interface for simple providers:

- `signalSources()` returns every transmitter currently contributed by the provider.
- `registerSignalSources(level)` and `unregisterSignalSources(level)` call `SignalAccess` in the expected order.

Example:

```java
SignalSource source = new SignalSource(
        transmitterId,
        channelId,
        Vec3.atCenterOf(worldPosition),
        1_000_000.0,
        100.0,
        SignalPayload.text("station id")
);

Electromagnetics.api().signals().registerSource(level, source);
```

## Source Model

`SignalSource` is intentionally generic:

- `id` uniquely identifies one transmitter in a level.
- `channelId` is the receiver tuning key.
- `position` is the source position in world coordinates.
- `frequencyHz` is sampled into phase and delay calculations.
- `transmitPowerWatts` is the source strength used by the attenuation model.
- `payload` is addon-defined content.

`SignalPayload.text(value)` is a convenience payload for simple radio-style tests. Addons can define their own
payload types by using a custom `ResourceLocation` type and string value.

## Sampling Model

The current MVP sampler uses inverse-square distance attenuation:

```text
receivedPower = transmitPower / max(1 block, distance)^2
```

Sampling a channel returns the source with the strongest received power. Other non-zero sources on the same channel
are reported as `interferenceWatts`. The sample also reports:

- `snrDecibels`, computed against ambient noise plus same-channel interference
- `phaseRadians`, based on source frequency and propagation delay
- `delaySeconds`, using one block as one meter for propagation speed
- `shielded`, currently always `false`

The API does not decide whether a signal is readable. Receiver mods should apply their own thresholds, antenna
gain, shielding, demodulation, and gameplay rules.

## AC Circuit Coupling

For radio-style receivers, sample the signal first, then convert that sample into an AC phasor source for the
circuit solver.

`SignalSample` provides convenience conversions:

```java
CircuitPhasor antennaVoltage = signal.voltagePhasor(50.0);
CircuitPhasor antennaCurrent = signal.currentPhasor(50.0);
```

The conversion treats `receivedPowerWatts` as RMS power into the supplied reference resistance:

```text
Vrms = sqrt(P * R)
Irms = sqrt(P / R)
```

`SignalVoltageSourceElement` is a small adapter for Thevenin-style antenna models. It stamps the sampled signal as
an ideal AC voltage source only when the AC solve frequency matches `signal.frequencyHz()` within the configured
tolerance.

```java
SignalSample signal = Electromagnetics.api().signals()
        .sample(level, channelId, receiverPosition)
        .orElse(null);

if (signal != null) {
    CircuitElement antennaSource = new SignalVoltageSourceElement(
            antennaSourceId,
            antennaPositivePort,
            antennaNegativePort,
            signal,
            50.0
    );

    Electromagnetics.api().circuits().registerElement(level, antennaSource);
}
```

The adapter only represents the induced source. Addon mods should add their own antenna impedance, tuner, detector,
speaker/load, and update policy. Re-registering the same element id replaces the previous sampled value.

## Receiver Example

```java
Optional<SignalSample> sample = Electromagnetics.api().signals().sample(level, channelId, receiverPosition);

sample.ifPresent(signal -> {
    if (signal.snrDecibels() >= 10.0 && signal.payload().type().equals(SignalPayload.TEXT_TYPE)) {
        String message = signal.payload().value();
        // Receiver gameplay owns what to do with the decoded message.
    }
});
```
