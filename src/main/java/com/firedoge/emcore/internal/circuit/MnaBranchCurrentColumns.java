package com.firedoge.emcore.internal.circuit;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

import com.firedoge.emcore.api.circuit.CircuitBranchCurrent;
import com.firedoge.emcore.api.circuit.CircuitDiagnostic;
import com.firedoge.emcore.api.circuit.CircuitDiagnosticSeverity;
import com.firedoge.emcore.api.circuit.CircuitDiagnosticType;

final class MnaBranchCurrentColumns {
    private MnaBranchCurrentColumns() {
    }

    static Map<CircuitBranchCurrent, Integer> assign(
            List<? extends MnaTopologySupport.VoltageLikeBranch> voltageSources,
            int voltageSourceOffset,
            List<? extends MnaTopologySupport.VoltageLikeBranch> voltageControlledVoltageSources,
            int controlledVoltageSourceOffset,
            List<? extends MnaTopologySupport.VoltageLikeBranch> currentControlledVoltageSources,
            int currentControlledVoltageSourceOffset,
            List<CircuitDiagnostic> diagnostics,
            String branchCurrentLabel
    ) {
        Map<CircuitBranchCurrent, Integer> columns = new HashMap<>();

        for (int index = 0; index < voltageSources.size(); index++) {
            if (!put(
                    columns,
                    voltageSources.get(index).branchCurrent(),
                    voltageSourceOffset + index,
                    diagnostics,
                    branchCurrentLabel
            )) {
                return null;
            }
        }
        for (int index = 0; index < voltageControlledVoltageSources.size(); index++) {
            if (!put(
                    columns,
                    voltageControlledVoltageSources.get(index).branchCurrent(),
                    controlledVoltageSourceOffset + index,
                    diagnostics,
                    branchCurrentLabel
            )) {
                return null;
            }
        }
        for (int index = 0; index < currentControlledVoltageSources.size(); index++) {
            if (!put(
                    columns,
                    currentControlledVoltageSources.get(index).branchCurrent(),
                    currentControlledVoltageSourceOffset + index,
                    diagnostics,
                    branchCurrentLabel
            )) {
                return null;
            }
        }

        return columns;
    }

    static void addMissingDiagnostic(
            List<CircuitDiagnostic> diagnostics,
            CircuitBranchCurrent branchCurrent,
            String branchCurrentLabel
    ) {
        diagnostics.add(new CircuitDiagnostic(
                CircuitDiagnosticType.BRANCH_CURRENT_NOT_FOUND,
                CircuitDiagnosticSeverity.ERROR,
                List.of(),
                branchCurrentLabel + " " + branchCurrent.id() + " is not defined by a voltage-like branch"
        ));
    }

    private static boolean put(
            Map<CircuitBranchCurrent, Integer> columns,
            CircuitBranchCurrent branchCurrent,
            int column,
            List<CircuitDiagnostic> diagnostics,
            String branchCurrentLabel
    ) {
        Integer previous = columns.putIfAbsent(branchCurrent, column);
        if (previous == null) {
            return true;
        }

        diagnostics.add(new CircuitDiagnostic(
                CircuitDiagnosticType.BRANCH_CURRENT_CONFLICT,
                CircuitDiagnosticSeverity.ERROR,
                List.of(),
                branchCurrentLabel + " " + branchCurrent.id()
                        + " is defined by multiple voltage-like branches"
        ));
        return false;
    }
}
