package com.firedoge.emcore.internal.field;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.OptionalDouble;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

import com.firedoge.emcore.api.field.CoilRegion;
import com.firedoge.emcore.api.field.ElectricFieldSample;
import com.firedoge.emcore.api.field.FieldDiagnostic;
import com.firedoge.emcore.api.field.FieldDiagnosticSeverity;
import com.firedoge.emcore.api.field.FieldDiagnosticType;
import com.firedoge.emcore.api.field.FieldRegion;
import com.firedoge.emcore.api.field.FieldSample;
import com.firedoge.emcore.api.field.FieldSnapshot;
import com.firedoge.emcore.api.field.FieldSolveResult;
import com.firedoge.emcore.api.field.FieldSource;
import com.firedoge.emcore.api.field.FieldSourceType;
import com.firedoge.emcore.api.field.FluxProbe;
import com.firedoge.emcore.api.field.FluxSample;
import com.firedoge.emcore.api.field.MagneticFieldSample;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;

public final class FieldNetwork {
    private static final int DEFAULT_POISSON_MAX_ITERATIONS = 512;
    private static final double DEFAULT_POISSON_TOLERANCE_VOLTS = 1.0e-4;
    private static final double DEFAULT_POISSON_RELAXATION_OMEGA = 1.85;
    private static final int DEFAULT_AUTO_SOLVE_MAX_REGIONS_PER_TICK = 1;
    private static final int DEFAULT_AUTO_SOLVE_MAX_PENDING = 1;
    private static final int DEFAULT_AUTO_SOLVE_MAX_CELLS_PER_TICK = 64 * 64 * 64;
    private static final ExecutorService SOLVER_EXECUTOR = Executors.newSingleThreadExecutor(task -> {
        Thread thread = new Thread(task, "EMcore Field Solver");
        thread.setDaemon(true);
        return thread;
    });

    private final Map<ResourceLocation, FieldRegion> regions = new LinkedHashMap<>();
    private final Map<ResourceLocation, FieldSource> sources = new LinkedHashMap<>();
    private final Map<ResourceLocation, CoilRegion> coils = new LinkedHashMap<>();
    private final Map<ResourceLocation, FluxHistory> coilFluxHistory = new LinkedHashMap<>();
    private final Map<ResourceLocation, SolvedPoissonRegion> solvedRegions = new LinkedHashMap<>();
    private final Map<ResourceLocation, SolvedMagnetostaticRegion> solvedMagneticRegions = new LinkedHashMap<>();
    private final Map<ResourceLocation, FieldSolveResult> solveResults = new LinkedHashMap<>();
    private final Map<ResourceLocation, CompletableFuture<PoissonSolveOutput>> pendingSolves = new LinkedHashMap<>();
    private final Map<ResourceLocation, Long> pendingSolveRevisions = new LinkedHashMap<>();
    private final Map<ResourceLocation, String> solveFailures = new LinkedHashMap<>();
    private final Map<ResourceLocation, Long> regionRevisions = new LinkedHashMap<>();
    private final Set<ResourceLocation> dirtyRegionIds = new LinkedHashSet<>();
    private long version;

    public void tick() {
        commitCompletedSolves();
        scheduleDirtySolves();
    }

    public void registerRegion(FieldRegion region) {
        Objects.requireNonNull(region, "region");
        regions.put(region.id(), region);
        solvedRegions.remove(region.id());
        solvedMagneticRegions.remove(region.id());
        solveResults.remove(region.id());
        solveFailures.remove(region.id());
        bumpRegionRevision(region.id());
        markDirty(region.id());
        version++;
    }

    public void unregisterRegion(ResourceLocation regionId) {
        Objects.requireNonNull(regionId, "regionId");
        regions.remove(regionId);
        solvedRegions.remove(regionId);
        solvedMagneticRegions.remove(regionId);
        solveResults.remove(regionId);
        solveFailures.remove(regionId);
        CompletableFuture<PoissonSolveOutput> pendingSolve = pendingSolves.remove(regionId);
        if (pendingSolve != null) {
            pendingSolve.cancel(false);
        }
        pendingSolveRevisions.remove(regionId);
        regionRevisions.remove(regionId);
        dirtyRegionIds.remove(regionId);
        unregisterCoilsInRegion(regionId);
        version++;
    }

    public void registerSource(FieldSource source) {
        Objects.requireNonNull(source, "source");
        FieldSource previous = sources.put(source.id(), source);
        if (previous != null) {
            bumpRegionRevision(previous.regionId());
            markDirty(previous.regionId());
        }
        bumpRegionRevision(source.regionId());
        markDirty(source.regionId());
        version++;
    }

    public void unregisterSource(ResourceLocation sourceId) {
        Objects.requireNonNull(sourceId, "sourceId");
        FieldSource removed = sources.remove(sourceId);
        if (removed != null) {
            bumpRegionRevision(removed.regionId());
            markDirty(removed.regionId());
            version++;
        }
    }

    public void registerCoil(CoilRegion coil) {
        Objects.requireNonNull(coil, "coil");
        coils.put(coil.id(), coil);
        coilFluxHistory.remove(coil.id());
        version++;
    }

    public void unregisterCoil(ResourceLocation coilId) {
        Objects.requireNonNull(coilId, "coilId");
        if (coils.remove(coilId) != null) {
            coilFluxHistory.remove(coilId);
            version++;
        }
    }

    public List<FieldRegion> regions() {
        return List.copyOf(regions.values());
    }

    public List<FieldSource> sources() {
        return List.copyOf(sources.values());
    }

    public List<CoilRegion> coils() {
        return List.copyOf(coils.values());
    }

    public FieldSnapshot snapshot() {
        return new FieldSnapshot(
                regions(),
                sources(),
                List.copyOf(dirtyRegionIds),
                diagnostics(),
                version,
                !dirtyRegionIds.isEmpty()
        );
    }

    public Optional<FieldSample> sample(Vec3 position) {
        Objects.requireNonNull(position, "position");
        List<ResourceLocation> containingRegions = new ArrayList<>();
        boolean stale = false;
        Vec3 electricField = Vec3.ZERO;
        double potentialVolts = 0.0;
        double chargeDensity = 0.0;
        double energyDensity = 0.0;
        boolean sampledSolvedRegion = false;

        for (FieldRegion region : regions.values()) {
            if (!region.contains(position)) {
                continue;
            }

            containingRegions.add(region.id());
            stale |= dirtyRegionIds.contains(region.id());
            SolvedPoissonRegion solvedRegion = solvedRegions.get(region.id());
            if (solvedRegion == null) {
                stale = true;
                continue;
            }

            FieldSample sample = solvedRegion.sample(position, dirtyRegionIds.contains(region.id()));
            electricField = electricField.add(sample.electricFieldVoltsPerMeter());
            potentialVolts += sample.potentialVolts();
            chargeDensity += sample.chargeDensityCoulombsPerCubicMeter();
            energyDensity += sample.energyDensityJoulesPerCubicMeter();
            sampledSolvedRegion = true;
        }

        if (containingRegions.isEmpty()) {
            return Optional.empty();
        }

        return Optional.of(new FieldSample(
                position,
                sampledSolvedRegion ? electricField : Vec3.ZERO,
                sampledSolvedRegion ? potentialVolts : 0.0,
                sampledSolvedRegion ? chargeDensity : 0.0,
                sampledSolvedRegion ? energyDensity : 0.0,
                stale || !sampledSolvedRegion,
                containingRegions
        ));
    }

    public ElectricFieldSample sampleElectricField(Vec3 position) {
        Objects.requireNonNull(position, "position");
        return sample(position)
                .map(FieldSample::toElectricFieldSample)
                .orElseGet(() -> new ElectricFieldSample(Vec3.ZERO, 0.0, 0.0, 0.0));
    }

    public MagneticFieldSample sampleMagneticField(Vec3 position) {
        Objects.requireNonNull(position, "position");
        Vec3 fluxDensity = Vec3.ZERO;
        boolean sampledSolvedRegion = false;
        for (FieldRegion region : regions.values()) {
            if (!region.contains(position)) {
                continue;
            }

            SolvedMagnetostaticRegion solvedRegion = solvedMagneticRegions.get(region.id());
            if (solvedRegion == null) {
                continue;
            }

            fluxDensity = fluxDensity.add(solvedRegion.fluxDensityAt(position));
            sampledSolvedRegion = true;
        }

        return new MagneticFieldSample(
                sampledSolvedRegion ? fluxDensity : Vec3.ZERO,
                0.0,
                Vec3.ZERO,
                0.0
        );
    }

    public double samplePotential(Vec3 position) {
        Objects.requireNonNull(position, "position");
        return sample(position)
                .map(FieldSample::potentialVolts)
                .orElse(0.0);
    }

    public double sampleMagneticFlux(BlockPos position, Direction normal) {
        Objects.requireNonNull(position, "position");
        Objects.requireNonNull(normal, "normal");
        Vec3 fluxDensity = sampleMagneticField(Vec3.atCenterOf(position)).fluxDensityTesla();
        return fluxDensity.x * normal.getStepX()
                + fluxDensity.y * normal.getStepY()
                + fluxDensity.z * normal.getStepZ();
    }

    public Optional<FluxSample> sampleFlux(FluxProbe probe) {
        Objects.requireNonNull(probe, "probe");
        return sampleFlux(probe, OptionalDouble.empty());
    }

    public Optional<FluxSample> sampleCoil(ResourceLocation coilId, double timeSeconds) {
        Objects.requireNonNull(coilId, "coilId");
        CoilRegion coil = coils.get(coilId);
        if (coil == null) {
            return Optional.empty();
        }

        Optional<FluxSample> rawSample = sampleFlux(coil.asFluxProbe());
        if (rawSample.isEmpty()) {
            return Optional.empty();
        }

        FluxSample sample = rawSample.orElseThrow();
        OptionalDouble inducedVoltage = OptionalDouble.empty();
        if (Double.isFinite(timeSeconds)) {
            FluxHistory previous = coilFluxHistory.get(coilId);
            if (previous != null) {
                double timeStepSeconds = timeSeconds - previous.timeSeconds();
                if (timeStepSeconds > 0.0) {
                    inducedVoltage = OptionalDouble.of(
                            -(sample.fluxLinkageWebers() - previous.fluxLinkageWebers()) / timeStepSeconds
                    );
                }
            }
            coilFluxHistory.put(coilId, new FluxHistory(sample.fluxLinkageWebers(), timeSeconds));
        }

        return Optional.of(sample.withInducedVoltage(inducedVoltage));
    }

    private Optional<FluxSample> sampleFlux(FluxProbe probe, OptionalDouble inducedVoltage) {
        FieldRegion region = regions.get(probe.regionId());
        if (region == null || !region.contains(probe.center())) {
            return Optional.empty();
        }

        MagneticFieldSample magneticSample = sampleMagneticField(probe.center());
        Vec3 fluxDensity = magneticSample.fluxDensityTesla();
        Vec3 normal = probe.normalVector();
        double fluxWebers = (fluxDensity.x * normal.x
                + fluxDensity.y * normal.y
                + fluxDensity.z * normal.z)
                * probe.areaSquareMeters();
        boolean stale = dirtyRegionIds.contains(probe.regionId())
                || pendingSolves.containsKey(probe.regionId())
                || !solvedMagneticRegions.containsKey(probe.regionId());

        return Optional.of(new FluxSample(
                probe.id(),
                probe.regionId(),
                probe.center(),
                probe.normal(),
                probe.areaSquareMeters(),
                probe.turns(),
                fluxDensity,
                fluxWebers,
                fluxWebers * probe.turns(),
                inducedVoltage,
                stale
        ));
    }

    public boolean requestSolve(ResourceLocation regionId) {
        Objects.requireNonNull(regionId, "regionId");
        PoissonSolveInput input = prepareSolveInput(regionId);
        if (input == null) {
            return false;
        }
        Long pendingRevision = pendingSolveRevisions.get(regionId);
        if (pendingRevision != null && pendingRevision == input.revision()) {
            return true;
        }
        CompletableFuture<PoissonSolveOutput> previousSolve = pendingSolves.remove(regionId);
        if (previousSolve != null) {
            previousSolve.cancel(false);
        }

        solveFailures.remove(regionId);
        pendingSolves.put(regionId, CompletableFuture.supplyAsync(() -> solvePoisson(input), SOLVER_EXECUTOR));
        pendingSolveRevisions.put(regionId, input.revision());
        version++;
        return true;
    }

    public Optional<FieldSolveResult> solve(ResourceLocation regionId) {
        Objects.requireNonNull(regionId, "regionId");
        PoissonSolveInput input = prepareSolveInput(regionId);
        if (input == null) {
            return Optional.empty();
        }

        PoissonSolveOutput output = solvePoisson(input);
        commitSolveOutput(output, true);
        return Optional.of(output.result());
    }

    private void markDirty(ResourceLocation regionId) {
        if (regions.containsKey(regionId)) {
            dirtyRegionIds.add(regionId);
        }
    }

    private void bumpRegionRevision(ResourceLocation regionId) {
        if (regions.containsKey(regionId)) {
            regionRevisions.merge(regionId, 1L, Long::sum);
        }
    }

    private void unregisterCoilsInRegion(ResourceLocation regionId) {
        List<ResourceLocation> removedCoilIds = new ArrayList<>();
        for (CoilRegion coil : coils.values()) {
            if (coil.regionId().equals(regionId)) {
                removedCoilIds.add(coil.id());
            }
        }
        for (ResourceLocation coilId : removedCoilIds) {
            coils.remove(coilId);
            coilFluxHistory.remove(coilId);
        }
    }

    private List<FieldDiagnostic> diagnostics() {
        List<FieldDiagnostic> diagnostics = new ArrayList<>();
        for (ResourceLocation regionId : dirtyRegionIds) {
            if (!regions.containsKey(regionId)) {
                continue;
            }

            diagnostics.add(new FieldDiagnostic(
                    FieldDiagnosticType.STALE_REGION,
                    FieldDiagnosticSeverity.INFO,
                    List.of(regionId),
                    "Field region has pending changes and no fresh solved snapshot"
            ));
        }
        for (ResourceLocation regionId : pendingSolves.keySet()) {
            if (!regions.containsKey(regionId)) {
                continue;
            }

            diagnostics.add(new FieldDiagnostic(
                    FieldDiagnosticType.POISSON_SOLVE_IN_PROGRESS,
                    FieldDiagnosticSeverity.INFO,
                    List.of(regionId),
                    "Poisson solve is running on a background worker"
            ));
        }
        for (ResourceLocation regionId : dirtyRegionIds) {
            FieldRegion region = regions.get(regionId);
            if (region == null || pendingSolves.containsKey(regionId)) {
                continue;
            }

            int cellCount = poissonCellCount(region);
            if (cellCount <= DEFAULT_AUTO_SOLVE_MAX_CELLS_PER_TICK) {
                continue;
            }

            diagnostics.add(new FieldDiagnostic(
                    FieldDiagnosticType.POISSON_SOLVE_DEFERRED,
                    FieldDiagnosticSeverity.WARNING,
                    List.of(regionId),
                    "Poisson region needs " + cellCount + " cells, above automatic per-tick budget "
                            + DEFAULT_AUTO_SOLVE_MAX_CELLS_PER_TICK
            ));
        }

        for (FieldSource source : sources.values()) {
            if (regions.containsKey(source.regionId())) {
                continue;
            }

            diagnostics.add(new FieldDiagnostic(
                    FieldDiagnosticType.SOURCE_REGION_NOT_FOUND,
                    FieldDiagnosticSeverity.WARNING,
                    List.of(source.regionId()),
                    "Field source " + source.id() + " references missing region " + source.regionId()
            ));
        }
        for (FieldSolveResult result : solveResults.values()) {
            if (result.converged() || dirtyRegionIds.contains(result.regionId()) || !regions.containsKey(result.regionId())) {
                continue;
            }

            diagnostics.add(new FieldDiagnostic(
                    FieldDiagnosticType.POISSON_SOLVE_DID_NOT_CONVERGE,
                    FieldDiagnosticSeverity.WARNING,
                    List.of(result.regionId()),
                    "Poisson solve reached " + result.iterations() + " iterations without reaching tolerance "
                            + result.toleranceVolts() + "V"
            ));
        }
        for (Map.Entry<ResourceLocation, String> entry : solveFailures.entrySet()) {
            if (!regions.containsKey(entry.getKey())) {
                continue;
            }

            diagnostics.add(new FieldDiagnostic(
                    FieldDiagnosticType.POISSON_SOLVE_FAILED,
                    FieldDiagnosticSeverity.ERROR,
                    List.of(entry.getKey()),
                    entry.getValue()
            ));
        }
        return List.copyOf(diagnostics);
    }

    private void commitCompletedSolves() {
        List<ResourceLocation> completedRegionIds = new ArrayList<>();
        for (Map.Entry<ResourceLocation, CompletableFuture<PoissonSolveOutput>> entry : pendingSolves.entrySet()) {
            CompletableFuture<PoissonSolveOutput> future = entry.getValue();
            if (!future.isDone()) {
                continue;
            }

            ResourceLocation regionId = entry.getKey();
            completedRegionIds.add(regionId);
            try {
                commitSolveOutput(future.join(), false);
            } catch (RuntimeException exception) {
                solveFailures.put(regionId, "Poisson solve failed: " + exception.getMessage());
                version++;
            }
        }

        completedRegionIds.forEach(pendingSolves::remove);
        completedRegionIds.forEach(pendingSolveRevisions::remove);
    }

    private void scheduleDirtySolves() {
        int submittedRegions = 0;
        int remainingCells = DEFAULT_AUTO_SOLVE_MAX_CELLS_PER_TICK;
        for (ResourceLocation regionId : List.copyOf(dirtyRegionIds)) {
            if (submittedRegions >= DEFAULT_AUTO_SOLVE_MAX_REGIONS_PER_TICK
                    || pendingSolves.size() >= DEFAULT_AUTO_SOLVE_MAX_PENDING) {
                return;
            }
            if (pendingSolves.containsKey(regionId)) {
                continue;
            }

            FieldRegion region = regions.get(regionId);
            if (region == null) {
                continue;
            }

            int cellCount = poissonCellCount(region);
            if (cellCount > DEFAULT_AUTO_SOLVE_MAX_CELLS_PER_TICK || cellCount > remainingCells) {
                continue;
            }

            if (requestSolve(regionId)) {
                submittedRegions++;
                remainingCells -= cellCount;
            }
        }
    }

    private PoissonSolveInput prepareSolveInput(ResourceLocation regionId) {
        FieldRegion region = regions.get(regionId);
        if (region == null) {
            return null;
        }

        List<FieldSource> regionSources = new ArrayList<>();
        for (FieldSource source : sources.values()) {
            if (source.regionId().equals(regionId)) {
                regionSources.add(source);
            }
        }
        return new PoissonSolveInput(
                regionId,
                regionRevisions.getOrDefault(regionId, 0L),
                region,
                List.copyOf(regionSources)
        );
    }

    private static PoissonSolveOutput solvePoisson(PoissonSolveInput input) {
        PreparedPoissonRegion electricRegion = preparePoissonRegion(input.region());
        stampElectricSources(input.region(), input.sources(), electricRegion);

        long startNanos = System.nanoTime();
        PreparedPoissonRegion.SolveStats electricStats = electricRegion.solveRedBlackGaussSeidel(
                DEFAULT_POISSON_MAX_ITERATIONS,
                DEFAULT_POISSON_TOLERANCE_VOLTS,
                DEFAULT_POISSON_RELAXATION_OMEGA
        );
        PreparedMagnetostaticSolve magneticSolve = solveMagnetostatic(input.region(), input.sources());
        long elapsedNanos = System.nanoTime() - startNanos;
        int iterations = Math.max(electricStats.iterations(), magneticSolve.iterations());
        boolean converged = electricStats.converged() && magneticSolve.converged();
        double maxDelta = Math.max(electricStats.maxDeltaVolts(), magneticSolve.maxDelta());
        double maxResidual = Math.max(electricStats.maxResidual(), magneticSolve.maxResidual());

        FieldSolveResult result = new FieldSolveResult(
                input.regionId(),
                electricRegion.xSize(),
                electricRegion.ySize(),
                electricRegion.zSize(),
                electricRegion.cellCount(),
                input.sources().size(),
                iterations,
                converged,
                DEFAULT_POISSON_TOLERANCE_VOLTS,
                maxDelta,
                maxResidual,
                elapsedNanos
        );
        return new PoissonSolveOutput(
                input.regionId(),
                input.revision(),
                result,
                SolvedPoissonRegion.copyOf(input.region(), electricRegion),
                magneticSolve.solvedRegion()
        );
    }

    private void commitSolveOutput(PoissonSolveOutput output, boolean force) {
        if (!force && regionRevisions.getOrDefault(output.regionId(), 0L) != output.revision()) {
            return;
        }
        if (!regions.containsKey(output.regionId())) {
            return;
        }

        solvedRegions.put(output.regionId(), output.solvedRegion());
        solvedMagneticRegions.put(output.regionId(), output.solvedMagneticRegion());
        solveResults.put(output.regionId(), output.result());
        solveFailures.remove(output.regionId());
        dirtyRegionIds.remove(output.regionId());
        version++;
    }

    private static PreparedPoissonRegion preparePoissonRegion(FieldRegion region) {
        AABB bounds = region.bounds();
        PreparedPoissonRegion preparedRegion = new PreparedPoissonRegion(
                gridSize(bounds.maxX - bounds.minX, region.cellSizeMeters()),
                gridSize(bounds.maxY - bounds.minY, region.cellSizeMeters()),
                gridSize(bounds.maxZ - bounds.minZ, region.cellSizeMeters()),
                region.cellSizeMeters()
        );
        preparedRegion.setOuterBoundary(0.0);
        return preparedRegion;
    }

    private static void stampElectricSources(
            FieldRegion region,
            List<FieldSource> sources,
            PreparedPoissonRegion preparedRegion
    ) {
        for (FieldSource source : sources) {
            if (source.type() != FieldSourceType.CURRENT_DENSITY) {
                stampSourceCells(region, preparedRegion, source, null);
            }
        }
    }

    private static PreparedMagnetostaticSolve solveMagnetostatic(FieldRegion region, List<FieldSource> sources) {
        PreparedPoissonRegion vectorX = preparePoissonRegion(region);
        PreparedPoissonRegion vectorY = preparePoissonRegion(region);
        PreparedPoissonRegion vectorZ = preparePoissonRegion(region);
        for (FieldSource source : sources) {
            if (source.type() == FieldSourceType.CURRENT_DENSITY) {
                stampSourceCells(region, vectorX, source, source.vectorValue().x);
                stampSourceCells(region, vectorY, source, source.vectorValue().y);
                stampSourceCells(region, vectorZ, source, source.vectorValue().z);
            }
        }

        PreparedPoissonRegion.SolveStats xStats = vectorX.solveRedBlackGaussSeidel(
                DEFAULT_POISSON_MAX_ITERATIONS,
                DEFAULT_POISSON_TOLERANCE_VOLTS,
                DEFAULT_POISSON_RELAXATION_OMEGA,
                PreparedPoissonRegion.VACUUM_PERMEABILITY_HENRYS_PER_METER
        );
        PreparedPoissonRegion.SolveStats yStats = vectorY.solveRedBlackGaussSeidel(
                DEFAULT_POISSON_MAX_ITERATIONS,
                DEFAULT_POISSON_TOLERANCE_VOLTS,
                DEFAULT_POISSON_RELAXATION_OMEGA,
                PreparedPoissonRegion.VACUUM_PERMEABILITY_HENRYS_PER_METER
        );
        PreparedPoissonRegion.SolveStats zStats = vectorZ.solveRedBlackGaussSeidel(
                DEFAULT_POISSON_MAX_ITERATIONS,
                DEFAULT_POISSON_TOLERANCE_VOLTS,
                DEFAULT_POISSON_RELAXATION_OMEGA,
                PreparedPoissonRegion.VACUUM_PERMEABILITY_HENRYS_PER_METER
        );

        return new PreparedMagnetostaticSolve(
                SolvedMagnetostaticRegion.copyOf(region, vectorX, vectorY, vectorZ),
                Math.max(xStats.iterations(), Math.max(yStats.iterations(), zStats.iterations())),
                xStats.converged() && yStats.converged() && zStats.converged(),
                Math.max(xStats.maxDeltaVolts(), Math.max(yStats.maxDeltaVolts(), zStats.maxDeltaVolts())),
                Math.max(xStats.maxResidual(), Math.max(yStats.maxResidual(), zStats.maxResidual()))
        );
    }

    private static void stampSourceCells(
            FieldRegion region,
            PreparedPoissonRegion preparedRegion,
            FieldSource source,
            Double overrideValue
    ) {
        int centerX = gridCoordinate(region.bounds().minX, region.cellSizeMeters(), source.position().x,
                preparedRegion.xSize());
        int centerY = gridCoordinate(region.bounds().minY, region.cellSizeMeters(), source.position().y,
                preparedRegion.ySize());
        int centerZ = gridCoordinate(region.bounds().minZ, region.cellSizeMeters(), source.position().z,
                preparedRegion.zSize());
        int cellRadius = Math.max(0, (int) Math.ceil(source.radiusMeters() / region.cellSizeMeters()));

        if (cellRadius == 0) {
            stampSourceCell(preparedRegion, source, centerX, centerY, centerZ, 1, overrideValue);
            return;
        }

        int stampedCells = countCellsInRadius(preparedRegion, centerX, centerY, centerZ, cellRadius);
        for (int z = Math.max(0, centerZ - cellRadius); z <= Math.min(preparedRegion.zSize() - 1, centerZ + cellRadius); z++) {
            for (int y = Math.max(0, centerY - cellRadius); y <= Math.min(preparedRegion.ySize() - 1, centerY + cellRadius); y++) {
                for (int x = Math.max(0, centerX - cellRadius); x <= Math.min(preparedRegion.xSize() - 1, centerX + cellRadius); x++) {
                    int dx = x - centerX;
                    int dy = y - centerY;
                    int dz = z - centerZ;
                    if (dx * dx + dy * dy + dz * dz <= cellRadius * cellRadius) {
                        stampSourceCell(preparedRegion, source, x, y, z, stampedCells, overrideValue);
                    }
                }
            }
        }
    }

    private static int countCellsInRadius(
            PreparedPoissonRegion preparedRegion,
            int centerX,
            int centerY,
            int centerZ,
            int cellRadius
    ) {
        int cells = 0;
        for (int z = Math.max(0, centerZ - cellRadius); z <= Math.min(preparedRegion.zSize() - 1, centerZ + cellRadius); z++) {
            for (int y = Math.max(0, centerY - cellRadius); y <= Math.min(preparedRegion.ySize() - 1, centerY + cellRadius); y++) {
                for (int x = Math.max(0, centerX - cellRadius); x <= Math.min(preparedRegion.xSize() - 1, centerX + cellRadius); x++) {
                    int dx = x - centerX;
                    int dy = y - centerY;
                    int dz = z - centerZ;
                    if (dx * dx + dy * dy + dz * dz <= cellRadius * cellRadius) {
                        cells++;
                    }
                }
            }
        }
        return Math.max(1, cells);
    }

    private static void stampSourceCell(
            PreparedPoissonRegion preparedRegion,
            FieldSource source,
            int x,
            int y,
            int z,
            int stampedCells,
            Double overrideValue
    ) {
        double value = overrideValue == null ? source.value() : overrideValue;
        if (source.type() == FieldSourceType.POINT_CHARGE) {
            double cellVolume = preparedRegion.cellSizeMeters()
                    * preparedRegion.cellSizeMeters()
                    * preparedRegion.cellSizeMeters();
            preparedRegion.addChargeDensity(x, y, z, value / (cellVolume * stampedCells));
        } else if (source.type() == FieldSourceType.CHARGE_DENSITY || source.type() == FieldSourceType.CURRENT_DENSITY) {
            preparedRegion.addChargeDensity(x, y, z, value);
        } else if (source.type() == FieldSourceType.POTENTIAL_BOUNDARY) {
            preparedRegion.setDirichletBoundary(x, y, z, value);
        } else if (source.type() == FieldSourceType.RELATIVE_PERMITTIVITY) {
            preparedRegion.setRelativePermittivity(x, y, z, value);
        }
    }

    private static int gridSize(double axisSizeMeters, double cellSizeMeters) {
        return Math.max(3, (int) Math.ceil(axisSizeMeters / cellSizeMeters) + 1);
    }

    private static int poissonCellCount(FieldRegion region) {
        AABB bounds = region.bounds();
        return gridSize(bounds.maxX - bounds.minX, region.cellSizeMeters())
                * gridSize(bounds.maxY - bounds.minY, region.cellSizeMeters())
                * gridSize(bounds.maxZ - bounds.minZ, region.cellSizeMeters());
    }

    private static int gridCoordinate(double min, double cellSizeMeters, double coordinate, int axisSize) {
        return clamp((int) Math.round((coordinate - min) / cellSizeMeters), 0, axisSize - 1);
    }

    private static int clamp(int value, int min, int max) {
        return Math.max(min, Math.min(max, value));
    }

    private static double clamp(double value, double min, double max) {
        return Math.max(min, Math.min(max, value));
    }

    private record FluxHistory(double fluxLinkageWebers, double timeSeconds) {
    }

    private record PoissonSolveInput(
            ResourceLocation regionId,
            long revision,
            FieldRegion region,
            List<FieldSource> sources
    ) {
        private PoissonSolveInput {
            Objects.requireNonNull(regionId, "regionId");
            Objects.requireNonNull(region, "region");
            sources = List.copyOf(sources);
        }
    }

    private record PoissonSolveOutput(
            ResourceLocation regionId,
            long revision,
            FieldSolveResult result,
            SolvedPoissonRegion solvedRegion,
            SolvedMagnetostaticRegion solvedMagneticRegion
    ) {
        private PoissonSolveOutput {
            Objects.requireNonNull(regionId, "regionId");
            Objects.requireNonNull(result, "result");
            Objects.requireNonNull(solvedRegion, "solvedRegion");
            Objects.requireNonNull(solvedMagneticRegion, "solvedMagneticRegion");
        }
    }

    private record PreparedMagnetostaticSolve(
            SolvedMagnetostaticRegion solvedRegion,
            int iterations,
            boolean converged,
            double maxDelta,
            double maxResidual
    ) {
        private PreparedMagnetostaticSolve {
            Objects.requireNonNull(solvedRegion, "solvedRegion");
        }
    }

    private record SolvedPoissonRegion(
            FieldRegion region,
            int xSize,
            int ySize,
            int zSize,
            int xySize,
            double cellSizeMeters,
            double[] potentialVolts,
            double[] chargeDensityCoulombsPerCubicMeter,
            double[] relativePermittivity
    ) {
        private SolvedPoissonRegion {
            potentialVolts = potentialVolts.clone();
            chargeDensityCoulombsPerCubicMeter = chargeDensityCoulombsPerCubicMeter.clone();
            relativePermittivity = relativePermittivity.clone();
        }

        private static SolvedPoissonRegion copyOf(FieldRegion region, PreparedPoissonRegion preparedRegion) {
            return new SolvedPoissonRegion(
                    region,
                    preparedRegion.xSize(),
                    preparedRegion.ySize(),
                    preparedRegion.zSize(),
                    preparedRegion.xSize() * preparedRegion.ySize(),
                    preparedRegion.cellSizeMeters(),
                    preparedRegion.copyPotentialVolts(),
                    preparedRegion.copyChargeDensityCoulombsPerCubicMeter(),
                    preparedRegion.copyRelativePermittivity()
            );
        }

        private FieldSample sample(Vec3 position, boolean stale) {
            AABB bounds = region.bounds();
            double localX = (position.x - bounds.minX) / cellSizeMeters;
            double localY = (position.y - bounds.minY) / cellSizeMeters;
            double localZ = (position.z - bounds.minZ) / cellSizeMeters;
            int nearestX = clamp((int) Math.round(localX), 0, xSize - 1);
            int nearestY = clamp((int) Math.round(localY), 0, ySize - 1);
            int nearestZ = clamp((int) Math.round(localZ), 0, zSize - 1);
            Vec3 electricField = electricFieldAt(nearestX, nearestY, nearestZ);
            double epsilonRelative = relativePermittivity[index(nearestX, nearestY, nearestZ)];
            double fieldMagnitudeSquared = electricField.x * electricField.x
                    + electricField.y * electricField.y
                    + electricField.z * electricField.z;

            return new FieldSample(
                    position,
                    electricField,
                    interpolatePotential(localX, localY, localZ),
                    chargeDensityCoulombsPerCubicMeter[index(nearestX, nearestY, nearestZ)],
                    0.5 * PreparedPoissonRegion.VACUUM_PERMITTIVITY_FARADS_PER_METER
                            * epsilonRelative
                            * fieldMagnitudeSquared,
                    stale,
                    List.of(region.id())
            );
        }

        private double interpolatePotential(double localX, double localY, double localZ) {
            int x0 = clamp((int) Math.floor(localX), 0, xSize - 2);
            int y0 = clamp((int) Math.floor(localY), 0, ySize - 2);
            int z0 = clamp((int) Math.floor(localZ), 0, zSize - 2);
            int x1 = x0 + 1;
            int y1 = y0 + 1;
            int z1 = z0 + 1;
            double tx = clamp(localX - x0, 0.0, 1.0);
            double ty = clamp(localY - y0, 0.0, 1.0);
            double tz = clamp(localZ - z0, 0.0, 1.0);

            double c00 = lerp(potentialVolts[index(x0, y0, z0)], potentialVolts[index(x1, y0, z0)], tx);
            double c10 = lerp(potentialVolts[index(x0, y1, z0)], potentialVolts[index(x1, y1, z0)], tx);
            double c01 = lerp(potentialVolts[index(x0, y0, z1)], potentialVolts[index(x1, y0, z1)], tx);
            double c11 = lerp(potentialVolts[index(x0, y1, z1)], potentialVolts[index(x1, y1, z1)], tx);
            double c0 = lerp(c00, c10, ty);
            double c1 = lerp(c01, c11, ty);
            return lerp(c0, c1, tz);
        }

        private Vec3 electricFieldAt(int x, int y, int z) {
            return new Vec3(
                    -gradientX(x, y, z),
                    -gradientY(x, y, z),
                    -gradientZ(x, y, z)
            );
        }

        private double gradientX(int x, int y, int z) {
            int lower = x == 0 ? x : x - 1;
            int upper = x == xSize - 1 ? x : x + 1;
            return gradient(index(lower, y, z), index(upper, y, z), upper - lower);
        }

        private double gradientY(int x, int y, int z) {
            int lower = y == 0 ? y : y - 1;
            int upper = y == ySize - 1 ? y : y + 1;
            return gradient(index(x, lower, z), index(x, upper, z), upper - lower);
        }

        private double gradientZ(int x, int y, int z) {
            int lower = z == 0 ? z : z - 1;
            int upper = z == zSize - 1 ? z : z + 1;
            return gradient(index(x, y, lower), index(x, y, upper), upper - lower);
        }

        private double gradient(int lowerIndex, int upperIndex, int cellDistance) {
            if (cellDistance == 0) {
                return 0.0;
            }
            return (potentialVolts[upperIndex] - potentialVolts[lowerIndex]) / (cellDistance * cellSizeMeters);
        }

        private int index(int x, int y, int z) {
            return z * xySize + y * xSize + x;
        }

        private static double lerp(double first, double second, double t) {
            return first + (second - first) * t;
        }
    }

    private record SolvedMagnetostaticRegion(
            FieldRegion region,
            int xSize,
            int ySize,
            int zSize,
            int xySize,
            double cellSizeMeters,
            double[] vectorPotentialX,
            double[] vectorPotentialY,
            double[] vectorPotentialZ
    ) {
        private SolvedMagnetostaticRegion {
            vectorPotentialX = vectorPotentialX.clone();
            vectorPotentialY = vectorPotentialY.clone();
            vectorPotentialZ = vectorPotentialZ.clone();
        }

        private static SolvedMagnetostaticRegion copyOf(
                FieldRegion region,
                PreparedPoissonRegion vectorX,
                PreparedPoissonRegion vectorY,
                PreparedPoissonRegion vectorZ
        ) {
            return new SolvedMagnetostaticRegion(
                    region,
                    vectorX.xSize(),
                    vectorX.ySize(),
                    vectorX.zSize(),
                    vectorX.xSize() * vectorX.ySize(),
                    vectorX.cellSizeMeters(),
                    vectorX.copyPotentialVolts(),
                    vectorY.copyPotentialVolts(),
                    vectorZ.copyPotentialVolts()
            );
        }

        private Vec3 fluxDensityAt(Vec3 position) {
            AABB bounds = region.bounds();
            double localX = (position.x - bounds.minX) / cellSizeMeters;
            double localY = (position.y - bounds.minY) / cellSizeMeters;
            double localZ = (position.z - bounds.minZ) / cellSizeMeters;
            int x = clamp((int) Math.round(localX), 0, xSize - 1);
            int y = clamp((int) Math.round(localY), 0, ySize - 1);
            int z = clamp((int) Math.round(localZ), 0, zSize - 1);

            double bx = gradientY(vectorPotentialZ, x, y, z) - gradientZ(vectorPotentialY, x, y, z);
            double by = gradientZ(vectorPotentialX, x, y, z) - gradientX(vectorPotentialZ, x, y, z);
            double bz = gradientX(vectorPotentialY, x, y, z) - gradientY(vectorPotentialX, x, y, z);
            return new Vec3(bx, by, bz);
        }

        private double gradientX(double[] values, int x, int y, int z) {
            int lower = x == 0 ? x : x - 1;
            int upper = x == xSize - 1 ? x : x + 1;
            return gradient(values, index(lower, y, z), index(upper, y, z), upper - lower);
        }

        private double gradientY(double[] values, int x, int y, int z) {
            int lower = y == 0 ? y : y - 1;
            int upper = y == ySize - 1 ? y : y + 1;
            return gradient(values, index(x, lower, z), index(x, upper, z), upper - lower);
        }

        private double gradientZ(double[] values, int x, int y, int z) {
            int lower = z == 0 ? z : z - 1;
            int upper = z == zSize - 1 ? z : z + 1;
            return gradient(values, index(x, y, lower), index(x, y, upper), upper - lower);
        }

        private double gradient(double[] values, int lowerIndex, int upperIndex, int cellDistance) {
            if (cellDistance == 0) {
                return 0.0;
            }
            return (values[upperIndex] - values[lowerIndex]) / (cellDistance * cellSizeMeters);
        }

        private int index(int x, int y, int z) {
            return z * xySize + y * xSize + x;
        }
    }
}
