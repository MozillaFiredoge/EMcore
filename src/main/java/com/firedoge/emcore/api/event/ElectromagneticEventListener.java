package com.firedoge.emcore.api.event;

public interface ElectromagneticEventListener {
    default void onArc(ArcEvent event) {
    }

    default void onOverload(OverloadEvent event) {
    }

    default void onSignalReceived(SignalReceivedEvent event) {
    }

    default void onEmpPulse(EmpPulseEvent event) {
    }
}
