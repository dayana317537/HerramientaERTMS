package idk.toolltv;

import idk.toolltv.ApproachInterval;
import idk.toolltv.BrakingNoticeResult;
import idk.toolltv.BranchSearchResult;
import idk.toolltv.UsedInterval;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * BrakingEngine
 *
 * Motor de cálculo del aviso de frenado. Contiene:
 * - Exploración DFS de ramas hacia atrás desde la LTV
 * - Recolección de intervalos de velocidad por sentido de marcha
 * - Resolución del PK exacto donde debe colocarse el aviso
 * - Utilidades de cálculo de distancia de frenado y velocidad en PK
 */
public class BrakingEngine {

    // -------------------------------------------------------------------------
    // Punto de entrada: exploración de todas las ramas desde la LTV
    // -------------------------------------------------------------------------

    /**
     * Explora todas las ramas de segmentos hacia atrás desde pkLtv
     * y calcula el aviso de frenado para cada una.
     */
    public static List<BranchSearchResult> findAllNoticeBranches(
            double pkLtv,
            String startSegmentId,
            Map<String, Segment> byId,
            boolean directMovement,
            double targetSpeedKmh,
            double aEff,
            double maxBackwardDistance
    ) {
        List<BranchSearchResult> results = new ArrayList<>();
        Segment start = byId.get(startSegmentId);
        if (start == null) return results;

        List<String> initialPath = new ArrayList<>();
        initialPath.add(startSegmentId);

        Set<String> visited = new HashSet<>();
        visited.add(startSegmentId);

        double initialDistance = directMovement
                ? Math.max(0.0, pkLtv - start.minPK())
                : Math.max(0.0, start.maxPK() - pkLtv);

        dfs(pkLtv, startSegmentId, byId, directMovement,
                targetSpeedKmh, aEff, maxBackwardDistance,
                initialPath, visited, initialDistance, results);

        return results;
    }

    // -------------------------------------------------------------------------
    // DFS: exploración recursiva de ramas
    // -------------------------------------------------------------------------

    private static void dfs(
            double pkLtv,
            String startSegmentId,
            Map<String, Segment> byId,
            boolean directMovement,
            double targetSpeedKmh,
            double aEff,
            double maxBackwardDistance,
            List<String> currentPath,
            Set<String> visited,
            double currentDistance,
            List<BranchSearchResult> results
    ) {
        // Calcular aviso con el path actual
        BrakingNoticeResult notice = directMovement
                ? calcNoticeForDirect(pkLtv, startSegmentId, currentPath, byId, targetSpeedKmh, aEff)
                : calcNoticeForInverse(pkLtv, startSegmentId, currentPath, byId, targetSpeedKmh, aEff);

        // Si encontramos aviso exacto, guardamos y paramos esta rama
        if (notice != null && notice.exactNoticeFound) {
            results.add(new BranchSearchResult(
                    new ArrayList<>(currentPath), currentDistance, notice, true, "Aviso exacto encontrado"));
            return;
        }

        // Seguir expandiendo hacia atrás
        String lastSegId = currentPath.get(currentPath.size() - 1);
        Segment lastSeg = byId.get(lastSegId);
        if (lastSeg == null) {
            results.add(new BranchSearchResult(
                    new ArrayList<>(currentPath), currentDistance, notice, false, "Segmento no encontrado"));
            return;
        }

        // En DIRECTA buscamos hacia PK decreciente → predecesores = inverseNeighbors
        // En INVERSA buscamos hacia PK creciente  → predecesores = directNeighbors
        List<String> predecessors = directMovement ? lastSeg.inverseNeighbors : lastSeg.directNeighbors;

        boolean expanded          = false;
        boolean blockedByDistance = false;
        boolean blockedByVisited  = false;

        for (String prevId : predecessors) {
            if (prevId == null) continue;
            if (visited.contains(prevId)) { blockedByVisited = true; continue; }

            Segment prev = byId.get(prevId);
            if (prev == null) continue;

            double nextDistance = currentDistance + prev.length();
            if (nextDistance > maxBackwardDistance) { blockedByDistance = true; continue; }

            expanded = true;
            currentPath.add(prevId);
            visited.add(prevId);

            dfs(pkLtv, startSegmentId, byId, directMovement,
                    targetSpeedKmh, aEff, maxBackwardDistance,
                    currentPath, visited, nextDistance, results);

            currentPath.remove(currentPath.size() - 1);
            visited.remove(prevId);
        }

        // Si no pudimos expandir, guardamos la rama como terminada sin aviso
        if (!expanded) {
            String reason;
            if      (predecessors.isEmpty()) reason = "Fin de ruta antes de encontrar aviso";
            else if (blockedByDistance)      reason = "Se alcanzó el límite de " + maxBackwardDistance + " m sin encontrar aviso";
            else if (blockedByVisited)       reason = "La rama se detuvo para evitar repetir segmentos";
            else                             reason = "No hay predecesores válidos para seguir";

            results.add(new BranchSearchResult(
                    new ArrayList<>(currentPath), currentDistance, notice, false, reason));
        }
    }

    // -------------------------------------------------------------------------
    // Cálculo del aviso por sentido de marcha
    // -------------------------------------------------------------------------

    /**
     * DIRECTA: tren circula hacia PK creciente, perfil UP, aviso hacia PK decreciente.
     */
    private static BrakingNoticeResult calcNoticeForDirect(
            double pkLtv, String startSegmentId,
            List<String> path, Map<String, Segment> byId,
            double targetSpeedKmh, double aEff
    ) {
        return solveNotice(
                collectIntervalsForDirect(pkLtv, startSegmentId, path, byId),
                targetSpeedKmh, aEff, true);
    }

    /**
     * INVERSA: tren circula hacia PK decreciente, perfil DOWN, aviso hacia PK creciente.
     */
    private static BrakingNoticeResult calcNoticeForInverse(
            double pkLtv, String startSegmentId,
            List<String> path, Map<String, Segment> byId,
            double targetSpeedKmh, double aEff
    ) {
        return solveNotice(
                collectIntervalsForInverse(pkLtv, startSegmentId, path, byId),
                targetSpeedKmh, aEff, false);
    }

    // -------------------------------------------------------------------------
    // Resolución del punto exacto de aviso
    // -------------------------------------------------------------------------

    /**
     * Recorre los intervalos de velocidad desde la LTV hacia atrás
     * y encuentra el PK exacto donde debe colocarse el aviso.
     */
    private static BrakingNoticeResult solveNotice(
            List<ApproachInterval> intervals,
            double targetSpeedKmh,
            double aEff,
            boolean directMovement
    ) {
        if (intervals == null || intervals.isEmpty()) {
            return new BrakingNoticeResult(null, Double.NaN, Double.NaN,
                    targetSpeedKmh, 0.0, new ArrayList<>(), false);
        }

        double accumulatedDistance = 0.0;
        List<UsedInterval> used = new ArrayList<>();

        for (ApproachInterval interval : intervals) {
            double v          = interval.speedKmh;
            double len        = interval.lengthMeters();
            double dNeed      = brakingDistance(v, targetSpeedKmh, aEff);
            double available  = accumulatedDistance + len;

            // El segmento ya tiene la velocidad objetivo → aviso al inicio del tramo
            if (v == targetSpeedKmh) {
                double noticePk = interval.pkAwaySide;
                double usedFrom = directMovement ? interval.pkAwaySide   : interval.pkTargetSide;
                double usedTo   = directMovement ? interval.pkTargetSide : interval.pkAwaySide;
                used.add(new UsedInterval(interval.segmentId, usedFrom, usedTo, v));
                return new BrakingNoticeResult(interval.segmentId, noticePk, v,
                        targetSpeedKmh, dNeed, new ArrayList<>(used), true);
            }

            // Hay distancia suficiente en este tramo → aviso dentro del intervalo
            if (v > targetSpeedKmh && dNeed <= available) {
                double distInside = Math.min(Math.max(dNeed - accumulatedDistance, 0), len);
                double noticePk, usedFrom, usedTo;
                if (directMovement) {
                    noticePk = interval.pkTargetSide - distInside;
                    usedFrom = noticePk;
                    usedTo   = interval.pkTargetSide;
                } else {
                    noticePk = interval.pkTargetSide + distInside;
                    usedFrom = interval.pkTargetSide;
                    usedTo   = noticePk;
                }
                used.add(new UsedInterval(interval.segmentId, usedFrom, usedTo, v));
                return new BrakingNoticeResult(interval.segmentId, noticePk, v,
                        targetSpeedKmh, dNeed, new ArrayList<>(used), true);
            }

            // Tramo completo consumido, seguimos buscando
            accumulatedDistance += len;
            double fullFrom = directMovement ? interval.pkAwaySide   : interval.pkTargetSide;
            double fullTo   = directMovement ? interval.pkTargetSide : interval.pkAwaySide;
            used.add(new UsedInterval(interval.segmentId, fullFrom, fullTo, v));
        }

        // No se encontró aviso en la ruta disponible
        return new BrakingNoticeResult(null, Double.NaN, Double.NaN,
                targetSpeedKmh, accumulatedDistance, new ArrayList<>(used), false);
    }

    // -------------------------------------------------------------------------
    // Recolección de intervalos de velocidad
    // -------------------------------------------------------------------------

    /**
     * DIRECTA: recoge intervalos de velocidad desde pkLtv hacia PK decreciente, perfil UP.
     */
    private static List<ApproachInterval> collectIntervalsForDirect(
            double pkLtv, String startSegmentId,
            List<String> path, Map<String, Segment> byId
    ) {
        List<ApproachInterval> out = new ArrayList<>();

        for (int i = 0; i < path.size(); i++) {
            String segId = path.get(i);
            Segment s = byId.get(segId);
            if (s == null) continue;

            double minPk = s.minPK();
            List<Segment.SpeedInterval> profile = s.speedProfileUp;
            if (profile == null || profile.isEmpty()) continue;

            if (i == 0 && segId.equals(startSegmentId)) {
                // Primer segmento: solo la parte hasta pkLtv
                for (int j = profile.size() - 1; j >= 0; j--) {
                    Segment.SpeedInterval si = profile.get(j);
                    double absFrom   = minPk + si.fromPos;
                    double absTo     = minPk + si.toPos;
                    double overlapTo = Math.min(absTo, Math.max(pkLtv, minPk));
                    if (overlapTo <= absFrom) continue;
                    out.add(new ApproachInterval(segId, absFrom, overlapTo, overlapTo, absFrom, si.vMax));
                }
            } else {
                // Segmentos siguientes: completos, de mayor a menor PK
                for (int j = profile.size() - 1; j >= 0; j--) {
                    Segment.SpeedInterval si = profile.get(j);
                    double absFrom = minPk + si.fromPos;
                    double absTo   = minPk + si.toPos;
                    if (absTo <= absFrom) continue;
                    out.add(new ApproachInterval(segId, absFrom, absTo, absTo, absFrom, si.vMax));
                }
            }
        }
        return out;
    }

    /**
     * INVERSA: recoge intervalos de velocidad desde pkLtv hacia PK creciente, perfil DOWN.
     */
    private static List<ApproachInterval> collectIntervalsForInverse(
            double pkLtv, String startSegmentId,
            List<String> path, Map<String, Segment> byId
    ) {
        List<ApproachInterval> out = new ArrayList<>();

        for (int i = 0; i < path.size(); i++) {
            String segId = path.get(i);
            Segment s = byId.get(segId);
            if (s == null) continue;

            double minPk = s.minPK();
            double maxPk = s.maxPK();
            List<Segment.SpeedInterval> profile = s.speedProfileDown;
            if (profile == null || profile.isEmpty()) continue;

            if (i == 0 && segId.equals(startSegmentId)) {
                // Primer segmento: solo la parte desde pkLtv
                for (int j = 0; j < profile.size(); j++) {
                    Segment.SpeedInterval si = profile.get(j);
                    double absFrom     = minPk + si.fromPos;
                    double absTo       = minPk + si.toPos;
                    double overlapFrom = Math.max(absFrom, Math.min(pkLtv, maxPk));
                    double overlapTo   = Math.min(absTo, maxPk);
                    if (overlapTo <= overlapFrom) continue;
                    out.add(new ApproachInterval(segId, overlapFrom, overlapTo, overlapFrom, overlapTo, si.vMax));
                }
            } else {
                // Segmentos siguientes: completos, de menor a mayor PK
                for (int j = 0; j < profile.size(); j++) {
                    Segment.SpeedInterval si = profile.get(j);
                    double absFrom = minPk + si.fromPos;
                    double absTo   = minPk + si.toPos;
                    if (absTo <= absFrom) continue;
                    out.add(new ApproachInterval(segId, absFrom, absTo, absFrom, absTo, si.vMax));
                }
            }
        }
        return out;
    }

    // -------------------------------------------------------------------------
    // Utilidades de cálculo
    // -------------------------------------------------------------------------

    /** Distancia de frenado en metros de v0 a vT con desaceleración aEff. */
    public static double brakingDistance(double v0Kmh, double vTKmh, double aEff) {
        double v0 = v0Kmh / 3.6;
        double vT = vTKmh / 3.6;
        if (v0 <= vT) return 0.0;
        return (v0 * v0 - vT * vT) / (2.0 * aEff);
    }

    /** Velocidad máxima en el PK dado, según el perfil del sentido indicado. */
    public static Double speedAtPk(Segment s, double pk, boolean directMovement) {
        if (s == null || !s.containsPK(pk)) return null;
        List<Segment.SpeedInterval> profile = directMovement ? s.speedProfileUp : s.speedProfileDown;
        if (profile == null || profile.isEmpty()) return null;
        double relPos = pk - s.minPK();
        for (Segment.SpeedInterval interval : profile) {
            if (relPos >= interval.fromPos && relPos <= interval.toPos) return interval.vMax;
        }
        return null;
    }
}
