package idk;

import idk.TelegramGenerator; // Ajusta este import si TelegramGenerator está en otro paquete
import idk.TelegramGenerator.LtvTelegramSet; // Asumiendo que comparten paquete, ajusta según tu estructura

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

public class LtvService {

    private static final String XML_PATH = "C:/Users/49204/Desktop/Herramienta/VIA_ATO_TOLUCA_DISERTMS (99).xml";

    // Constantes de frenado
    private static final double G = 9.81;
    private static final double A_BRAKE = 0.4;      // m/s^2
    private static final double GRAD_PERMIL = 10.0; // 10 ‰
    private static final int SLOPE_DIR = -1;        // bajada

    // Margen de la LTV
    private static final double LTV_MARGIN = 7.0; // metros, editable

    // Balizas
    private static final double MIN_BG_GAP_METERS = 15.0;
    private static final double BG_SEARCH_WINDOW_METERS = 20.0;
    private static final boolean DEBUG_BG = true;

    // Límite de exploración hacia atrás desde la LTV
    private static final double MAX_BACKWARD_SEARCH_METERS = 4000.0;

    // =========================================================================
    // MÉTODOS PARA LA INTERFAZ GRÁFICA (JAVAFX)
    // =========================================================================

    public List<SegmentOption> buscarCandidatos(double pkLtv, double ltvSpeedKmh, double ltvLengthMeters) throws Exception {
        Locale.setDefault(Locale.US);
        List<Segment> segs = loadSegments();

        double pkLtvEffectiveDirect = pkLtv - LTV_MARGIN;
        double pkLtvEffectiveInverse = pkLtv + ltvLengthMeters + LTV_MARGIN;

        List<SegmentOption> result = new ArrayList<>();
        Set<String> allIds = new HashSet<>();

        for (Segment seg : segs) {
            if (seg.mostRestrictiveSpeed == null || seg.mostRestrictiveSpeed < ltvSpeedKmh) continue;
            if (seg.containsPK(pkLtvEffectiveDirect) || seg.containsPK(pkLtvEffectiveInverse)) {
                if (allIds.add(seg.id)) {
                    boolean direct = seg.containsPK(pkLtvEffectiveDirect);
                    boolean inverse = seg.containsPK(pkLtvEffectiveInverse);

                    String label = String.format(Locale.US, "%s [min=%.1f, max=%.1f, vRestr=%.1f]%s%s",
                            seg.id, seg.minPK(), seg.maxPK(), seg.mostRestrictiveSpeed,
                            direct ? " <- DIRECTA" : "",
                            inverse ? " <- INVERSA" : ""
                    );
                    result.add(new SegmentOption(seg.id, label));
                }
            }
        }
        return result;
    }

    public CalcResult calcularCompleto(double pkLtv, double ltvSpeedKmh, double ltvLengthMeters, String selectedSegmentId) throws Exception {
        Locale.setDefault(Locale.US);
        StringBuilder out = new StringBuilder();

        List<Segment> segs = loadSegments();
        Map<String, Segment> byId = buildMap(segs);

        double pkLtvEffectiveDirect  = pkLtv - LTV_MARGIN;
        double pkLtvEffectiveInverse = pkLtv + ltvLengthMeters + LTV_MARGIN;

        double aSlope = SLOPE_DIR * G * (GRAD_PERMIL / 1000.0);
        double aEff   = A_BRAKE + aSlope;

        printf(out, "=== DATOS DE FRENADO ===%n");
        printf(out, "aBrake = %.4f m/s^2 | gradiente = %.2f ‰ | aSlope = %.4f m/s^2 | aEff = %.4f m/s^2%n%n",
                A_BRAKE, GRAD_PERMIL, aSlope, aEff);

        if (aEff <= 0) {
            println(out, "ERROR: la desaceleración efectiva es <= 0. Revisa los parámetros.");
            // CORRECCIÓN: Devolvemos un CalcResult con el error
            return new CalcResult(pkLtv, ltvSpeedKmh, ltvLengthMeters, new ArrayList<>(), out.toString(), "");
        }

        Segment selectedSegment = byId.get(selectedSegmentId);
        if (selectedSegment == null) {
            println(out, "ERROR: el segmento seleccionado no existe en el mapa.");
            // CORRECCIÓN: Devolvemos un CalcResult con el error
            return new CalcResult(pkLtv, ltvSpeedKmh, ltvLengthMeters, new ArrayList<>(), out.toString(), "");
        }

        // Lógica de vecinos
        Segment segDirect  = selectedSegment.containsPK(pkLtvEffectiveDirect)  ? selectedSegment : null;
        Segment segInverse = selectedSegment.containsPK(pkLtvEffectiveInverse) ? selectedSegment : null;

        if (segDirect == null) {
            for (String neighborId : selectedSegment.inverseNeighbors) {
                Segment neighbor = byId.get(neighborId);
                if (neighbor != null && neighbor.containsPK(pkLtvEffectiveDirect)) {
                    segDirect = neighbor; break;
                }
            }
        }
        if (segInverse == null) {
            for (String neighborId : selectedSegment.directNeighbors) {
                Segment neighbor = byId.get(neighborId);
                if (neighbor != null && neighbor.containsPK(pkLtvEffectiveInverse)) {
                    segInverse = neighbor; break;
                }
            }
        }

        // Último recurso global
        if (segDirect == null) segDirect = findSegmentContainingPk(byId, pkLtvEffectiveDirect);
        if (segInverse == null) segInverse = findSegmentContainingPk(byId, pkLtvEffectiveInverse);

        printf(out, "Segmento seleccionado: %s%n", selectedSegment.id);
        printf(out, "Segmento de inicio DIRECTA:  %s%n", (segDirect  != null ? segDirect.id  : "no encontrado"));
        printf(out, "Segmento de inicio INVERSA:  %s%n%n", (segInverse != null ? segInverse.id : "no encontrado"));

        List<BranchSearchResult> directBranches  = new ArrayList<>();
        List<BranchSearchResult> inverseBranches = new ArrayList<>();

        // DIRECTA
        if (segDirect == null) {
            println(out, "No hay segmento válido para DIRECTA.");
        } else {
            Double directSpeedAtLtv = getSpeedAtPk(segDirect, pkLtvEffectiveDirect, true);
            if (directSpeedAtLtv != null && directSpeedAtLtv <= ltvSpeedKmh) {
                directBranches.add(new BranchSearchResult(
                        List.of(segDirect.id), 0.0, null, false,
                        String.format("No hace falta LTV en DIRECTA: velocidad %.2f <= %.2f km/h", directSpeedAtLtv, ltvSpeedKmh)
                ));
            } else {
                directBranches = findAllNoticeBranches(
                        pkLtvEffectiveDirect, segDirect.id, byId, true,
                        ltvSpeedKmh, aEff, MAX_BACKWARD_SEARCH_METERS
                );
            }
        }

        // INVERSA
        if (segInverse == null) {
            println(out, "No hay segmento válido para INVERSA.");
        } else {
            Double inverseSpeedAtLtv = getSpeedAtPk(segInverse, pkLtvEffectiveInverse, false);
            if (inverseSpeedAtLtv != null && inverseSpeedAtLtv <= ltvSpeedKmh) {
                inverseBranches.add(new BranchSearchResult(
                        List.of(segInverse.id), 0.0, null, false,
                        String.format("No hace falta LTV en INVERSA: velocidad %.2f <= %.2f km/h", inverseSpeedAtLtv, ltvSpeedKmh)
                ));
            } else {
                inverseBranches = findAllNoticeBranches(
                        pkLtvEffectiveInverse, segInverse.id, byId, false,
                        ltvSpeedKmh, aEff, MAX_BACKWARD_SEARCH_METERS
                );
            }
        }

        // SEÑALES Y TELEGRAMAS
        List<SignalNoticeResolver.SignalNoticeResult> signalsDirect  = new ArrayList<>();
        List<SignalNoticeResolver.SignalNoticeResult> signalsInverse = new ArrayList<>();

        for (BranchSearchResult b : directBranches) {
            SignalNoticeResolver.SignalNoticeResult sig = SignalNoticeResolver.findSignalInPath(b.path, byId, true, pkLtvEffectiveDirect);
            if (sig.found) signalsDirect.add(sig);
        }
        for (BranchSearchResult b : inverseBranches) {
            SignalNoticeResolver.SignalNoticeResult sig = SignalNoticeResolver.findSignalInPath(b.path, byId, false, pkLtvEffectiveInverse);
            if (sig.found) signalsInverse.add(sig);
        }

        List<BranchSearchResult> allBranches = new ArrayList<>();
        allBranches.addAll(directBranches);
        allBranches.addAll(inverseBranches);

        Set<Integer> existingNidBgs = TelegramGenerator.extractExistingNidBgs(segs);

        // CORRECCIÓN: Aquí generamos el telegramSet una única vez
        TelegramGenerator.LtvTelegramSet telegramSet = TelegramGenerator.generate(
                allBranches, signalsDirect, signalsInverse, pkLtv,
                pkLtvEffectiveDirect, pkLtvEffectiveInverse, ltvSpeedKmh,
                ltvLengthMeters, LTV_MARGIN, existingNidBgs
        );

        // 1. Crear las filas para la tabla de JavaFX
        List<TelegramRow> filas = new ArrayList<>();
        for (TelegramGenerator.NoticeEntry entry : telegramSet.entries) {
            filas.add(new TelegramRow(
                    entry.baliseName,
                    entry.distanceMeters,
                    entry.pk,
                    entry.via != null && !entry.via.isEmpty() ? entry.via : "-",
                    entry.isSignalNotice
            ));
        }

        // 2. Generar el texto para el TextArea técnico (el log de la consola)
        appendTelegramSummaryToOut(out, telegramSet, Path.of("telegramas_LTV.txt"));
        println(out, "\nRAMAS EN DIRECTA (tren hacia PK creciente, búsqueda hacia PK decreciente):");
        printBranchResults(out, "DIRECTA", directBranches, byId, pkLtvEffectiveDirect, true);
        println(out, "\nRAMAS EN INVERSA (tren hacia PK decreciente, búsqueda hacia PK creciente):");
        printBranchResults(out, "INVERSA", inverseBranches, byId, pkLtvEffectiveInverse, false);

        // 3. Generar el texto interno para el archivo .txt descargable
        java.io.StringWriter sw = new java.io.StringWriter();
        try (java.io.PrintWriter pw = new java.io.PrintWriter(sw)) {
            pw.println("========================================");
            pw.println("  TELEGRAMAS LTV - ETCS");
            pw.printf("  PK LTV:      %.2f m%n", telegramSet.ltvPk);
            pw.printf("  Velocidad:   %.1f km/h%n", telegramSet.ltvSpeedKmh);
            pw.printf("  Longitud:    %.1f m%n", telegramSet.ltvLengthMeters);
            pw.printf("  NID_TSR:     %d%n", telegramSet.nidTsr);
            pw.println("========================================\n");
            for (TelegramGenerator.NoticeEntry entry : telegramSet.entries) {
                pw.println("----------------------------------------");
                pw.printf("BG:          %s%n", entry.baliseName);
                pw.printf("PK aviso:    %.2f m%n", entry.pk);
                pw.println("----------------------------------------");
                pw.println(entry.telegramText);
            }
        }
        String contenidoTxt = sw.toString();

        // 4. Devolver el objeto empaquetado para ResultsController
        return new CalcResult(pkLtv, ltvSpeedKmh, ltvLengthMeters, filas, out.toString(), contenidoTxt);
    }

    // =========================================================================
    // MÉTODOS PRIVADOS NATIVOS (MATEMÁTICA Y DFS)
    // =========================================================================

    private List<Segment> loadSegments() throws Exception {
        RailMLSegmentsStaxParser parser = new RailMLSegmentsStaxParser();
        List<Segment> segs = parser.parseSegments(Path.of(XML_PATH));
        Map<String, Segment> byId = buildMap(segs);
        for (Segment s : segs) {
            s.finalizeBaliseData(byId);
        }
        return segs;
    }

    private Map<String, Segment> buildMap(List<Segment> segs) {
        Map<String, Segment> byId = new HashMap<>();
        for (Segment s : segs) {
            byId.put(s.id, s);
        }
        return byId;
    }

    private Double getSpeedAtPk(Segment s, double pk, boolean directMovement) {
        if (s == null) return null;
        if (!s.containsPK(pk)) return null;

        List<Segment.SpeedInterval> profile = directMovement ? s.speedProfileUp : s.speedProfileDown;
        if (profile == null || profile.isEmpty()) return null;

        double relPos = pk - s.minPK();
        for (Segment.SpeedInterval interval : profile) {
            if (relPos >= interval.fromPos && relPos <= interval.toPos) {
                return interval.vMax;
            }
        }
        return null;
    }

    private List<BranchSearchResult> findAllNoticeBranches(
            double pkLtv, String startSegmentId, Map<String, Segment> byId,
            boolean directMovement, double targetSpeedKmh, double aEff, double maxBackwardDistance
    ) {
        List<BranchSearchResult> results = new ArrayList<>();
        Segment start = byId.get(startSegmentId);
        if (start == null) return results;

        List<String> initialPath = new ArrayList<>();
        initialPath.add(startSegmentId);
        Set<String> visited = new HashSet<>();
        visited.add(startSegmentId);

        double initialDistance = initialBackwardDistance(start, pkLtv, directMovement);

        dfsNoticeBranches(pkLtv, startSegmentId, byId, directMovement, targetSpeedKmh, aEff,
                maxBackwardDistance, initialPath, visited, initialDistance, results);

        return results;
    }

    private void dfsNoticeBranches(
            double pkLtv, String startSegmentId, Map<String, Segment> byId,
            boolean directMovement, double targetSpeedKmh, double aEff,
            double maxBackwardDistance, List<String> currentPath, Set<String> visited,
            double currentBackwardDistance, List<BranchSearchResult> results
    ) {
        BrakingNoticeResult notice = directMovement
                ? findBrakingNoticeForDirect(pkLtv, startSegmentId, currentPath, byId, targetSpeedKmh, aEff)
                : findBrakingNoticeForInverse(pkLtv, startSegmentId, currentPath, byId, targetSpeedKmh, aEff);

        if (notice != null && notice.exactNoticeFound) {
            results.add(new BranchSearchResult(new ArrayList<>(currentPath), currentBackwardDistance, notice, true, "Aviso exacto encontrado"));
            return;
        }

        String currentSegmentId = currentPath.get(currentPath.size() - 1);
        Segment current = byId.get(currentSegmentId);

        if (current == null) {
            results.add(new BranchSearchResult(new ArrayList<>(currentPath), currentBackwardDistance, notice, false, "Segmento no encontrado en el mapa"));
            return;
        }

        List<String> predecessors = getBackwardNeighbors(current, directMovement);
        boolean expanded = false;
        boolean blockedByDistance = false;
        boolean blockedByVisited = false;

        for (String prevId : predecessors) {
            if (prevId == null) continue;
            if (visited.contains(prevId)) { blockedByVisited = true; continue; }

            Segment prev = byId.get(prevId);
            if (prev == null) continue;

            double nextDistance = currentBackwardDistance + prev.length();
            if (nextDistance > maxBackwardDistance) { blockedByDistance = true; continue; }

            expanded = true;
            currentPath.add(prevId);
            visited.add(prevId);

            dfsNoticeBranches(pkLtv, startSegmentId, byId, directMovement, targetSpeedKmh, aEff,
                    maxBackwardDistance, currentPath, visited, nextDistance, results);

            visited.remove(prevId);
            currentPath.remove(currentPath.size() - 1);
        }

        if (!expanded) {
            String reason = predecessors.isEmpty() ? "Fin de ruta antes de encontrar aviso" :
                    (blockedByDistance ? "Se alcanzó el límite de " + maxBackwardDistance + " m" :
                            (blockedByVisited ? "La rama se detuvo para evitar repetir segmentos" : "No hay predecesores válidos"));
            results.add(new BranchSearchResult(new ArrayList<>(currentPath), currentBackwardDistance, notice, false, reason));
        }
    }

    private List<String> getBackwardNeighbors(Segment current, boolean directMovement) {
        if (current == null) return new ArrayList<>();
        return directMovement ? current.inverseNeighbors : current.directNeighbors;
    }

    private double initialBackwardDistance(Segment start, double pkLtv, boolean directMovement) {
        if (start == null) return 0.0;
        return directMovement ? Math.max(0.0, pkLtv - start.minPK()) : Math.max(0.0, start.maxPK() - pkLtv);
    }

    private BrakingNoticeResult findBrakingNoticeForDirect(double pkLtv, String startSegmentId, List<String> backwardGeomPath, Map<String, Segment> byId, double targetSpeedKmh, double aEff) {
        List<ApproachInterval> intervals = collectApproachIntervalsForDirect(pkLtv, startSegmentId, backwardGeomPath, byId);
        return solveBrakingNotice(intervals, targetSpeedKmh, aEff, true);
    }

    private BrakingNoticeResult findBrakingNoticeForInverse(double pkLtv, String startSegmentId, List<String> backwardGeomPath, Map<String, Segment> byId, double targetSpeedKmh, double aEff) {
        List<ApproachInterval> intervals = collectApproachIntervalsForInverse(pkLtv, startSegmentId, backwardGeomPath, byId);
        return solveBrakingNotice(intervals, targetSpeedKmh, aEff, false);
    }

    private BrakingNoticeResult solveBrakingNotice(List<ApproachInterval> intervals, double targetSpeedKmh, double aEff, boolean directMovement) {
        if (intervals == null || intervals.isEmpty()) return new BrakingNoticeResult(null, Double.NaN, Double.NaN, targetSpeedKmh, 0.0, new ArrayList<>(), false);

        double accumulatedDistance = 0.0;
        List<UsedInterval> usedIntervals = new ArrayList<>();

        for (ApproachInterval interval : intervals) {
            double v = interval.speedKmh;
            double len = interval.lengthMeters();
            double dNeed = brakingDistanceMeters(v, targetSpeedKmh, aEff);
            double totalAvailable = accumulatedDistance + len;

            if (v == targetSpeedKmh) {
                double noticePk = interval.pkAwaySide;
                double usedFrom = directMovement ? interval.pkAwaySide : interval.pkTargetSide;
                double usedTo = directMovement ? interval.pkTargetSide : interval.pkAwaySide;
                usedIntervals.add(new UsedInterval(interval.segmentId, usedFrom, usedTo, v));
                return new BrakingNoticeResult(interval.segmentId, noticePk, v, targetSpeedKmh, dNeed, new ArrayList<>(usedIntervals), true);
            }

            if (v > targetSpeedKmh && dNeed <= totalAvailable) {
                double distInside = Math.min(len, Math.max(0, dNeed - accumulatedDistance));
                double noticePk = directMovement ? (interval.pkTargetSide - distInside) : (interval.pkTargetSide + distInside);
                double usedFrom = directMovement ? noticePk : interval.pkTargetSide;
                double usedTo = directMovement ? interval.pkTargetSide : noticePk;
                usedIntervals.add(new UsedInterval(interval.segmentId, usedFrom, usedTo, v));
                return new BrakingNoticeResult(interval.segmentId, noticePk, v, targetSpeedKmh, dNeed, new ArrayList<>(usedIntervals), true);
            }

            accumulatedDistance += len;
            double fullFrom = directMovement ? interval.pkAwaySide : interval.pkTargetSide;
            double fullTo = directMovement ? interval.pkTargetSide : interval.pkAwaySide;
            usedIntervals.add(new UsedInterval(interval.segmentId, fullFrom, fullTo, v));
        }

        return new BrakingNoticeResult(null, Double.NaN, Double.NaN, targetSpeedKmh, accumulatedDistance, new ArrayList<>(usedIntervals), false);
    }

    private List<ApproachInterval> collectApproachIntervalsForDirect(double pkLtv, String startSegmentId, List<String> backwardGeomPath, Map<String, Segment> byId) {
        List<ApproachInterval> out = new ArrayList<>();
        for (int i = 0; i < backwardGeomPath.size(); i++) {
            String segId = backwardGeomPath.get(i);
            Segment s = byId.get(segId);
            if (s == null || s.speedProfileUp == null || s.speedProfileUp.isEmpty()) continue;

            double minPk = s.minPK();
            for (int j = s.speedProfileUp.size() - 1; j >= 0; j--) {
                Segment.SpeedInterval si = s.speedProfileUp.get(j);
                double absFrom = minPk + si.fromPos;
                double absTo = minPk + si.toPos;

                if (i == 0 && segId.equals(startSegmentId)) {
                    double overlapTo = Math.min(absTo, Math.max(pkLtv, minPk));
                    if (overlapTo <= absFrom) continue;
                    out.add(new ApproachInterval(segId, absFrom, overlapTo, overlapTo, absFrom, si.vMax));
                } else {
                    if (absTo <= absFrom) continue;
                    out.add(new ApproachInterval(segId, absFrom, absTo, absTo, absFrom, si.vMax));
                }
            }
        }
        return out;
    }

    private List<ApproachInterval> collectApproachIntervalsForInverse(double pkLtv, String startSegmentId, List<String> backwardGeomPath, Map<String, Segment> byId) {
        List<ApproachInterval> out = new ArrayList<>();
        for (int i = 0; i < backwardGeomPath.size(); i++) {
            String segId = backwardGeomPath.get(i);
            Segment s = byId.get(segId);
            if (s == null || s.speedProfileDown == null || s.speedProfileDown.isEmpty()) continue;

            double minPk = s.minPK();
            double maxPk = s.maxPK();
            for (int j = 0; j < s.speedProfileDown.size(); j++) {
                Segment.SpeedInterval si = s.speedProfileDown.get(j);
                double absFrom = minPk + si.fromPos;
                double absTo = minPk + si.toPos;

                if (i == 0 && segId.equals(startSegmentId)) {
                    double overlapFrom = Math.max(absFrom, Math.min(pkLtv, maxPk));
                    double overlapTo = Math.min(absTo, maxPk);
                    if (overlapTo <= overlapFrom) continue;
                    out.add(new ApproachInterval(segId, overlapFrom, overlapTo, overlapFrom, overlapTo, si.vMax));
                } else {
                    if (absTo <= absFrom) continue;
                    out.add(new ApproachInterval(segId, absFrom, absTo, absFrom, absTo, si.vMax));
                }
            }
        }
        return out;
    }

    private double brakingDistanceMeters(double v0Kmh, double vTKmh, double aEff) {
        double v0 = v0Kmh / 3.6;
        double vT = vTKmh / 3.6;
        if (v0 <= vT) return 0.0;
        return (v0 * v0 - vT * vT) / (2.0 * aEff);
    }

    private BrakingNoticeResult adjustNoticeAgainstBalises(
            BrakingNoticeResult rawNotice, List<String> branchPath, Map<String, Segment> byId,
            boolean directMovement, double minGapMeters, double searchWindowMeters
    ) {
        if (rawNotice == null || !rawNotice.exactNoticeFound) return rawNotice;

        double adjustedPk = rawNotice.pk;
        String adjustedSegmentId = rawNotice.segmentId;

        for (int guard = 0; guard < 50; guard++) {
            BaliseReference conflict = findNearestConflictingBalise(adjustedPk, branchPath, byId, directMovement, minGapMeters, searchWindowMeters);
            if (conflict == null) {
                return new BrakingNoticeResult(adjustedSegmentId, adjustedPk, rawNotice.noticeSpeedKmh, rawNotice.targetSpeedKmh, rawNotice.totalDistanceMeters, rawNotice.usedIntervals, true);
            }

            adjustedPk = directMovement ? (conflict.pk - minGapMeters) : (conflict.pk + minGapMeters);
            Segment newSeg = findSegmentContainingPkInBranch(byId, branchPath, adjustedPk);

            if (newSeg == null) {
                newSeg = findSegmentContainingPkInNeighbors(byId, branchPath, adjustedPk, directMovement);
            }
            if (newSeg == null) {
                return new BrakingNoticeResult(rawNotice.segmentId, rawNotice.pk, rawNotice.noticeSpeedKmh, rawNotice.targetSpeedKmh, rawNotice.totalDistanceMeters, rawNotice.usedIntervals, false);
            }
            adjustedSegmentId = newSeg.id;
        }
        return rawNotice;
    }

    private Segment findSegmentContainingPkInNeighbors(Map<String, Segment> byId, List<String> branchPath, double pk, boolean directMovement) {
        if (branchPath == null || branchPath.isEmpty()) return null;
        String lastSegId = branchPath.get(branchPath.size() - 1);
        Segment lastSeg = byId.get(lastSegId);
        if (lastSeg == null) return null;

        List<String> neighbors = directMovement ? lastSeg.inverseNeighbors : lastSeg.directNeighbors;
        for (String neighborId : neighbors) {
            if (neighborId == null) continue;
            Segment neighbor = byId.get(neighborId);
            if (neighbor != null && neighbor.containsPK(pk)) return neighbor;
        }
        return null;
    }

    private List<BaliseReference> findNearbyBalises(double noticePk, List<String> branchPath, Map<String, Segment> byId, boolean directMovement, double searchWindowMeters) {
        List<BaliseReference> refs = new ArrayList<>();
        double windowMin = noticePk - searchWindowMeters;
        double windowMax = noticePk + searchWindowMeters;
        if (branchPath == null || branchPath.isEmpty()) return refs;

        List<String> segmentsToCheck = new ArrayList<>(branchPath);
        String lastSegId = branchPath.get(branchPath.size() - 1);
        Segment lastSeg = byId.get(lastSegId);

        if (lastSeg != null) {
            List<String> awayNeighbors = directMovement ? lastSeg.inverseNeighbors : lastSeg.directNeighbors;
            for (String neighborId : awayNeighbors) {
                if (neighborId != null && !segmentsToCheck.contains(neighborId)) segmentsToCheck.add(neighborId);
            }
        }

        for (String segId : segmentsToCheck) {
            Segment s = byId.get(segId);
            if (s == null || s.maxPK() < windowMin || s.minPK() > windowMax) continue;
            for (BaliseData.BaliseGroup bg : s.getBaliseGroups()) {
                Double refPk = bg.getLastPkByNdx(byId);
                if (refPk != null && refPk >= windowMin && refPk <= windowMax) {
                    refs.add(new BaliseReference(bg.id, s.id, refPk));
                }
            }
        }
        return refs;
    }

    private BaliseReference findNearestConflictingBalise(double noticePk, List<String> branchPath, Map<String, Segment> byId, boolean directMovement, double minGapMeters, double searchWindowMeters) {
        BaliseReference best = null;
        double bestAbsDelta = Double.POSITIVE_INFINITY;
        for (BaliseReference ref : findNearbyBalises(noticePk, branchPath, byId, directMovement, searchWindowMeters)) {
            double absDelta = Math.abs(noticePk - ref.pk);
            if (absDelta < minGapMeters && absDelta < bestAbsDelta) {
                bestAbsDelta = absDelta;
                best = ref;
            }
        }
        return best;
    }

    private Segment findSegmentContainingPkInBranch(Map<String, Segment> byId, List<String> branchPath, double pk) {
        for (String segId : branchPath) {
            Segment s = byId.get(segId);
            if (s != null && s.containsPK(pk)) return s;
        }
        return null;
    }

    private Segment findSegmentContainingPk(Map<String, Segment> byId, double pk) {
        for (Segment s : byId.values()) {
            if (s.containsPK(pk)) return s;
        }
        return null;
    }

    // =========================================================================
    // MÉTODOS DE IMPRESIÓN (Adaptados a StringBuilder)
    // =========================================================================

    private void printBranchResults(StringBuilder out, String title, List<BranchSearchResult> branches, Map<String, Segment> byId, double pkLtvEffective, boolean directMovement) {
        if (branches == null || branches.isEmpty()) {
            println(out, "  (sin ramas)"); return;
        }
        for (int i = 0; i < branches.size(); i++) {
            BranchSearchResult branch = branches.get(i);
            printf(out, "  Rama %s %d:%n", title, i + 1);
            printf(out, "    Ruta: %s%n", String.join(" => ", branch.path));
            printf(out, "    Distancia hacia atrás explorada: %.2f m%n", branch.backwardDistanceMeters);
            printf(out, "    Estado: %s%n", branch.reason);

            SignalNoticeResolver.SignalNoticeResult signalResult = SignalNoticeResolver.findSignalInPath(branch.path, byId, directMovement, pkLtvEffective);
            if (signalResult.found) {
                printf(out, "    Señal en el path: %s en PK=%.2f (seg=%s)%n", signalResult.signalName, signalResult.pk, signalResult.segmentId);
                printf(out, "    → Aviso colocado en señal: PK=%.2f%n", signalResult.pk);
            }

            BrakingNoticeResult finalNotice = branch.notice;
            if (finalNotice != null && finalNotice.exactNoticeFound) {
                finalNotice = adjustNoticeAgainstBalises(finalNotice, branch.path, byId, directMovement, MIN_BG_GAP_METERS, BG_SEARCH_WINDOW_METERS);
            }

            if (finalNotice != null && finalNotice.exactNoticeFound) {
                println(out, "    Aviso exacto: SI");
                printf(out, "    Segmento aviso: %s%n", finalNotice.segmentId);
                printf(out, "    PK aviso: %.2f%n", finalNotice.pk);
                printf(out, "    Velocidad en aviso: %.2f km/h%n", finalNotice.noticeSpeedKmh);
                printf(out, "    Velocidad objetivo LTV: %.2f km/h%n", finalNotice.targetSpeedKmh);
                printf(out, "    Distancia total de frenado: %.2f m%n", finalNotice.totalDistanceMeters);
            } else {
                println(out, "    Aviso exacto: NO");
            }
            println(out, "");
        }
    }

    private void appendTelegramSummaryToOut(StringBuilder out, TelegramGenerator.LtvTelegramSet set, Path outputPath) {
        println(out, "\n=== TABLA DE AVISOS Y TELEGRAMAS ===");
        printf(out, "NID_TSR: %d  |  NID_C: %d%n%n", set.nidTsr, TelegramGenerator.NID_C);
        printf(out, "%-38s | %-13s | %-12s | %-6s | %s%n", "Baliza", "Distancia (m)", "PK (m)", "Vía", "Datos telegrama");
        println(out, "-".repeat(95));
        for (TelegramGenerator.NoticeEntry entry : set.entries) {
            printf(out, "%-38s | %-13.2f | %-12.2f | %-6s | %s%s%n",
                    entry.baliseName, entry.distanceMeters, entry.pk, entry.via,
                    outputPath.getFileName(), entry.isSignalNotice ? "  [SEÑAL]" : "");
        }
        println(out, "-".repeat(95));
        printf(out, "Fichero exportado: %s%n", outputPath.toAbsolutePath());
    }

    private void println(StringBuilder out, String text) {
        out.append(text).append("\n");
    }

    private void printf(StringBuilder out, String format, Object... args) {
        out.append(String.format(Locale.US, format, args));
    }

    // =========================================================================
    // CLASES INTERNAS DE SOPORTE
    // =========================================================================

    public static class SegmentOption {
        private final String id;
        private final String label;
        public SegmentOption(String id, String label) { this.id = id; this.label = label; }
        public String getId() { return id; }
        @Override public String toString() { return label; }
    }

    private static class BaliseReference {
        String groupId;
        String segmentId;
        double pk;
        BaliseReference(String groupId, String segmentId, double pk) {
            this.groupId = groupId; this.segmentId = segmentId; this.pk = pk;
        }
    }

    // =========================================================================
    // CLASES DE SOPORTE PARA LA INTERFAZ GRÁFICA NUEVA
    // =========================================================================

    public static class TelegramRow {
        private final String baliseName;
        private final double distanceMeters;
        private final double pk;
        private final String via;
        private final boolean isSignal;

        public TelegramRow(String baliseName, double distanceMeters, double pk, String via, boolean isSignal) {
            this.baliseName = baliseName;
            this.distanceMeters = distanceMeters;
            this.pk = pk;
            this.via = via;
            this.isSignal = isSignal;
        }

        public String getBaliseName() { return baliseName; }
        public double getDistanceMeters() { return distanceMeters; }
        public double getPk() { return pk; }
        public String getVia() { return via; }
        public boolean getIsSignal() { return isSignal; }
    }

    public static class CalcResult {
        public final double pkLtv;
        public final double ltvSpeedKmh;
        public final double ltvLengthMeters;
        public final List<TelegramRow> filasTelegramas;
        public final String detalleTexto; // El texto técnico que antes iba a consola
        public final String contenidoTxt; // El texto que irá al fichero descargable

        public CalcResult(double pkLtv, double ltvSpeedKmh, double ltvLengthMeters,
                          List<TelegramRow> filasTelegramas, String detalleTexto, String contenidoTxt) {
            this.pkLtv = pkLtv;
            this.ltvSpeedKmh = ltvSpeedKmh;
            this.ltvLengthMeters = ltvLengthMeters;
            this.filasTelegramas = filasTelegramas;
            this.detalleTexto = detalleTexto;
            this.contenidoTxt = contenidoTxt;
        }
    }
}