package idk;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Scanner;
import java.util.Set;

public class Main {

    // Constantes de frenado
    private static final double G = 9.81;
    private static final double A_BRAKE = 0.4;      // m/s^2
    private static final double GRAD_PERMIL = 10.0; // 10 ‰
    private static final int SLOPE_DIR = -1;        // bajada

    // Límite de exploración hacia atrás desde la LTV
    private static final double MAX_BACKWARD_SEARCH_METERS = 4000.0;

    public static void main(String[] args) throws Exception {
        Locale.setDefault(Locale.US);

        Path xml = Path.of("C:/Users/49204/Desktop/Herramienta/VIA_VI_RA_54_Extendido.xml");

        RailMLSegmentsStaxParser parser = new RailMLSegmentsStaxParser();
        List<Segment> segs = parser.parseSegments(xml);

        Map<String, Segment> byId = new HashMap<>();
        for (Segment s : segs) {
            byId.put(s.id, s);
        }

        System.out.println("Segmentos leídos: " + segs.size());

        Scanner sc = new Scanner(System.in);

        System.out.print("Introduce PK de la LTV (en metros, ej: 239000): ");
        double pkLtv = sc.nextDouble();

        System.out.print("Introduce velocidad objetivo de la LTV (km/h, ej: 60): ");
        double ltvSpeedKmh = sc.nextDouble();

        double aSlope = SLOPE_DIR * G * (GRAD_PERMIL / 1000.0);
        double aEff = A_BRAKE + aSlope;

        System.out.println();
        System.out.println("=== DATOS DE FRENADO ===");
        System.out.printf("aBrake = %.4f m/s^2%n", A_BRAKE);
        System.out.printf("gradiente = %.2f ‰%n", GRAD_PERMIL);
        System.out.printf("dir pendiente = %d (bajada)%n", SLOPE_DIR);
        System.out.printf("aSlope = %.4f m/s^2%n", aSlope);
        System.out.printf("aEff = %.4f m/s^2%n", aEff);

        if (aEff <= 0) {
            System.out.println("ERROR: la desaceleración efectiva es <= 0. No se puede garantizar frenado.");
            return;
        }

        List<Segment> containing = findSegmentsContainingPk(segs, pkLtv);

        System.out.println();

        if (containing.isEmpty()) {
            System.out.println("No encontré ningún segmento que contenga el PK " + pkLtv);
            return;
        }

        System.out.println("Segmentos que contienen el PK:");
        for (Segment s : containing) {
            System.out.println("  " + s.id + "  [min=" + s.minPK() + ", max=" + s.maxPK() + "]");
        }

        for (Segment s : containing) {
            System.out.println();
            System.out.println("==================================================");
            System.out.println("SEGMENTO CANDIDATO: " + s.id);
            System.out.println("==================================================");

            Double directSpeedAtLtv = getSpeedAtPk(s, pkLtv, true);
            Double inverseSpeedAtLtv = getSpeedAtPk(s, pkLtv, false);

            List<BranchSearchResult> directBranches = new ArrayList<>();
            List<BranchSearchResult> inverseBranches = new ArrayList<>();

            if (directSpeedAtLtv != null && directSpeedAtLtv <= ltvSpeedKmh) {
                directBranches.add(new BranchSearchResult(
                        List.of(s.id),
                        0.0,
                        null,
                        false,
                        String.format(
                                "No hace falta LTV en DIRECTA: en el PK %.2f ya existe velocidad %.2f km/h <= %.2f km/h",
                                pkLtv, directSpeedAtLtv, ltvSpeedKmh
                        )
                ));
            } else {
                directBranches = findAllNoticeBranches(
                        pkLtv, s.id, byId, true, ltvSpeedKmh, aEff, MAX_BACKWARD_SEARCH_METERS
                );
            }

            if (inverseSpeedAtLtv != null && inverseSpeedAtLtv <= ltvSpeedKmh) {
                inverseBranches.add(new BranchSearchResult(
                        List.of(s.id),
                        0.0,
                        null,
                        false,
                        String.format(
                                "No hace falta LTV en INVERSA: en el PK %.2f ya existe velocidad %.2f km/h <= %.2f km/h",
                                pkLtv, inverseSpeedAtLtv, ltvSpeedKmh
                        )
                ));
            } else {
                inverseBranches = findAllNoticeBranches(
                        pkLtv, s.id, byId, false, ltvSpeedKmh, aEff, MAX_BACKWARD_SEARCH_METERS
                );
            }

            System.out.println();
            System.out.println("Detalle del segmento:");
            System.out.println("  beginAbsPos = " + s.beginAbsPos);
            System.out.println("  endAbsPos   = " + s.endAbsPos);
            System.out.println("  length      = " + s.length());
            System.out.println("  directNeighbors  = " + s.directNeighbors);
            System.out.println("  inverseNeighbors = " + s.inverseNeighbors);
            System.out.println("  vRestr total = " + s.mostRestrictiveSpeed);
            System.out.println("  vRestr up    = " + s.mostRestrictiveSpeedUp);
            System.out.println("  vRestr down  = " + s.mostRestrictiveSpeedDown);

            System.out.println();
            System.out.println("Perfil de velocidad UP:");
            printSpeedProfile(s.speedProfileUp);

            System.out.println();
            System.out.println("Perfil de velocidad DOWN:");
            printSpeedProfile(s.speedProfileDown);

            System.out.println();
            System.out.println("RAMAS EN DIRECTA (tren hacia PK creciente, búsqueda hacia PK decreciente):");
            printBranchResults("DIRECTA", directBranches, byId);

            System.out.println();
            System.out.println("RAMAS EN INVERSA (tren hacia PK decreciente, búsqueda hacia PK creciente):");
            printBranchResults("INVERSA", inverseBranches, byId);
        }
    }

    private static List<Segment> findSegmentsContainingPk(List<Segment> segs, double pk) {
        List<Segment> res = new ArrayList<>();
        for (Segment s : segs) {
            if (s.containsPK(pk)) {
                res.add(s);
            }
        }
        return res;
    }
    
    private static Double getSpeedAtPk(Segment s, double pk, boolean directMovement) {
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

    /**
     * FASE 1:
     * Explora ramas hacia atrás desde la LTV.
     * - sin repetir segmentos dentro de la misma rama
     * - sin fusionar todavía ramas confluyentes
     * - parando cuando ya se encuentra aviso exacto
     * - o cuando se llega al límite de distancia
     */
    private static List<BranchSearchResult> findAllNoticeBranches(
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

        double initialDistance = initialBackwardDistance(start, pkLtv, directMovement);

        dfsNoticeBranches(
                pkLtv,
                startSegmentId,
                byId,
                directMovement,
                targetSpeedKmh,
                aEff,
                maxBackwardDistance,
                initialPath,
                visited,
                initialDistance,
                results
        );

        return results;
    }

    private static void dfsNoticeBranches(
            double pkLtv,
            String startSegmentId,
            Map<String, Segment> byId,
            boolean directMovement,
            double targetSpeedKmh,
            double aEff,
            double maxBackwardDistance,
            List<String> currentPath,
            Set<String> visited,
            double currentBackwardDistance,
            List<BranchSearchResult> results
    ) {
        BrakingNoticeResult notice = directMovement
                ? findBrakingNoticeForDirect(pkLtv, startSegmentId, currentPath, byId, targetSpeedKmh, aEff)
                : findBrakingNoticeForInverse(pkLtv, startSegmentId, currentPath, byId, targetSpeedKmh, aEff);

        if (notice != null && notice.exactNoticeFound) {
            results.add(new BranchSearchResult(
                    new ArrayList<>(currentPath),
                    currentBackwardDistance,
                    notice,
                    true,
                    "Aviso exacto encontrado"
            ));
            return;
        }

        String currentSegmentId = currentPath.get(currentPath.size() - 1);
        Segment current = byId.get(currentSegmentId);

        if (current == null) {
            results.add(new BranchSearchResult(
                    new ArrayList<>(currentPath),
                    currentBackwardDistance,
                    notice,
                    false,
                    "Segmento no encontrado en el mapa"
            ));
            return;
        }

        List<String> predecessors = getBackwardNeighbors(current, directMovement);

        boolean expanded = false;
        boolean blockedByDistance = false;
        boolean blockedByVisited = false;

        for (String prevId : predecessors) {
            if (prevId == null) continue;

            if (visited.contains(prevId)) {
                blockedByVisited = true;
                continue;
            }

            Segment prev = byId.get(prevId);
            if (prev == null) continue;

            double nextDistance = currentBackwardDistance + prev.length();
            if (nextDistance > maxBackwardDistance) {
                blockedByDistance = true;
                continue;
            }

            expanded = true;

            currentPath.add(prevId);
            visited.add(prevId);

            dfsNoticeBranches(
                    pkLtv,
                    startSegmentId,
                    byId,
                    directMovement,
                    targetSpeedKmh,
                    aEff,
                    maxBackwardDistance,
                    currentPath,
                    visited,
                    nextDistance,
                    results
            );

            visited.remove(prevId);
            currentPath.remove(currentPath.size() - 1);
        }

        if (!expanded) {
            String reason;

            if (predecessors.isEmpty()) {
                reason = "Fin de ruta antes de encontrar aviso";
            } else if (blockedByDistance) {
                reason = "Se alcanzó el límite de " + maxBackwardDistance + " m sin encontrar aviso";
            } else if (blockedByVisited) {
                reason = "La rama se detuvo para evitar repetir segmentos";
            } else {
                reason = "No hay predecesores válidos para seguir";
            }

            results.add(new BranchSearchResult(
                    new ArrayList<>(currentPath),
                    currentBackwardDistance,
                    notice,
                    false,
                    reason
            ));
        }
    }

    private static List<String> getBackwardNeighbors(Segment current, boolean directMovement) {
        if (current == null) return new ArrayList<>();

        // DIRECTA:
        // tren hacia PK creciente, aviso hacia atrás en PK decreciente
        // segmentos anteriores = inverseNeighbors
        if (directMovement) {
            return current.inverseNeighbors;
        }

        // INVERSA:
        // tren hacia PK decreciente, aviso hacia atrás en PK creciente
        // segmentos anteriores = directNeighbors
        return current.directNeighbors;
    }

    private static double initialBackwardDistance(Segment start, double pkLtv, boolean directMovement) {
        if (start == null) return 0.0;

        if (directMovement) {
            // DIRECTA: buscamos el aviso geométricamente hacia PK decreciente
            return Math.max(0.0, pkLtv - start.minPK());
        } else {
            // INVERSA: buscamos el aviso geométricamente hacia PK creciente
            return Math.max(0.0, start.maxPK() - pkLtv);
        }
    }

    private static void printBranchResults(String title, List<BranchSearchResult> branches, Map<String, Segment> byId) {
        if (branches == null || branches.isEmpty()) {
            System.out.println("  (sin ramas)");
            return;
        }

        for (int i = 0; i < branches.size(); i++) {
            BranchSearchResult branch = branches.get(i);

            System.out.println("  Rama " + title + " " + (i + 1) + ":");
            System.out.println("    Ruta: " + String.join(" => ", branch.path));
            System.out.printf("    Distancia hacia atrás explorada: %.2f m%n", branch.backwardDistanceMeters);
            System.out.println("    Estado: " + branch.reason);

            System.out.println("    Segmentos de la rama:");
            printPathDetails(branch.path, byId);

            if (branch.notice != null && branch.notice.exactNoticeFound) {
                System.out.println("    Aviso exacto: SI");
                System.out.println("    Segmento aviso: " + branch.notice.segmentId);
                System.out.println("    PK aviso: " + branch.notice.pk);
                System.out.printf("    Velocidad en aviso: %.2f km/h%n", branch.notice.noticeSpeedKmh);
                System.out.printf("    Velocidad objetivo LTV: %.2f km/h%n", branch.notice.targetSpeedKmh);
                System.out.printf("    Distancia total de frenado: %.2f m%n", branch.notice.totalDistanceMeters);

                System.out.println("    Tramos realmente usados entre la LTV y el aviso:");
                printUsedIntervals(branch.notice);
            } else {
                System.out.println("    Aviso exacto: NO");
                System.out.println("    " + branch.reason);
            }

            System.out.println();
        }
    }

    /**
     * DIRECTA = tren circula hacia PK creciente, usando perfil UP.
     * El aviso está antes de la LTV, es decir, hacia PK decreciente.
     */
    private static BrakingNoticeResult findBrakingNoticeForDirect(
            double pkLtv,
            String startSegmentId,
            List<String> backwardGeomPath,
            Map<String, Segment> byId,
            double targetSpeedKmh,
            double aEff
    ) {
        List<ApproachInterval> intervals = collectApproachIntervalsForDirect(
                pkLtv, startSegmentId, backwardGeomPath, byId
        );

        return solveBrakingNotice(intervals, targetSpeedKmh, aEff, true);
    }

    /**
     * INVERSA = tren circula hacia PK decreciente, usando perfil DOWN.
     * El aviso está antes de la LTV, es decir, hacia PK creciente.
     */
    private static BrakingNoticeResult findBrakingNoticeForInverse(
            double pkLtv,
            String startSegmentId,
            List<String> backwardGeomPath,
            Map<String, Segment> byId,
            double targetSpeedKmh,
            double aEff
    ) {
        List<ApproachInterval> intervals = collectApproachIntervalsForInverse(
                pkLtv, startSegmentId, backwardGeomPath, byId
        );

        return solveBrakingNotice(intervals, targetSpeedKmh, aEff, false);
    }

    /**
     * Resuelve el punto de aviso recorriendo intervalos desde la LTV hacia atrás.
     * Aquí "intervalo" = intervalo de velocidad, no segmento completo.
     */
    private static BrakingNoticeResult solveBrakingNotice(
            List<ApproachInterval> intervals,
            double targetSpeedKmh,
            double aEff,
            boolean directMovement
    ) {
        if (intervals == null || intervals.isEmpty()) return null;

        double currentRequiredKmh = targetSpeedKmh;
        double accumulatedDistance = 0.0;
        List<UsedInterval> usedIntervals = new ArrayList<>();

        for (ApproachInterval interval : intervals) {
            double vLimit = interval.speedKmh;

            // Si el tramo ya es <= velocidad requerida, se usa entero
            if (vLimit <= currentRequiredKmh + 1e-9) {
                accumulatedDistance += interval.lengthMeters();

                usedIntervals.add(new UsedInterval(
                        interval.segmentId,
                        interval.pkFrom,
                        interval.pkTo,
                        interval.speedKmh
                ));

                currentRequiredKmh = Math.min(currentRequiredKmh, vLimit);
                continue;
            }

            double dNeed = brakingDistanceMeters(vLimit, currentRequiredKmh, aEff);

            if (dNeed <= interval.lengthMeters() + 1e-9) {
                double noticePk;
                double usedFrom;
                double usedTo;

                if (directMovement) {
                    // Cerca de LTV = pkTargetSide grande; el aviso cae hacia PK menor
                    noticePk = interval.pkTargetSide - dNeed;
                    usedFrom = noticePk;
                    usedTo = interval.pkTargetSide;
                } else {
                    // Cerca de LTV = pkTargetSide pequeño; el aviso cae hacia PK mayor
                    noticePk = interval.pkTargetSide + dNeed;
                    usedFrom = interval.pkTargetSide;
                    usedTo = noticePk;
                }

                usedIntervals.add(new UsedInterval(
                        interval.segmentId,
                        usedFrom,
                        usedTo,
                        interval.speedKmh
                ));

                return new BrakingNoticeResult(
                        interval.segmentId,
                        noticePk,
                        vLimit,
                        targetSpeedKmh,
                        accumulatedDistance + dNeed,
                        usedIntervals,
                        true
                );
            } else {
                // Se usa entero y propagamos hacia atrás
                usedIntervals.add(new UsedInterval(
                        interval.segmentId,
                        interval.pkFrom,
                        interval.pkTo,
                        interval.speedKmh
                ));

                double vStartRequiredMs = Math.sqrt(
                        kmhToMs(currentRequiredKmh) * kmhToMs(currentRequiredKmh)
                                + 2.0 * aEff * interval.lengthMeters()
                );
                double vStartRequiredKmh = msToKmh(vStartRequiredMs);

                currentRequiredKmh = Math.min(vLimit, vStartRequiredKmh);
                accumulatedDistance += interval.lengthMeters();
            }
        }

        return new BrakingNoticeResult(
                null,
                Double.NaN,
                Double.NaN,
                targetSpeedKmh,
                accumulatedDistance,
                usedIntervals,
                false
        );
    }

    /**
     * DIRECTA:
     * - movimiento real: PK creciente
     * - perfil a usar: UP
     * - búsqueda geométrica hacia atrás: PK decreciente
     */
    private static List<ApproachInterval> collectApproachIntervalsForDirect(
            double pkLtv,
            String startSegmentId,
            List<String> backwardGeomPath,
            Map<String, Segment> byId
    ) {
        List<ApproachInterval> out = new ArrayList<>();

        for (int i = 0; i < backwardGeomPath.size(); i++) {
            String segId = backwardGeomPath.get(i);
            Segment s = byId.get(segId);
            if (s == null) continue;

            double minPk = s.minPK();
            List<Segment.SpeedInterval> profile = s.speedProfileUp;
            if (profile == null || profile.isEmpty()) continue;

            if (i == 0 && segId.equals(startSegmentId)) {
                // Solo la parte desde minPK hasta pkLtv
                double limitPk = pkLtv;

                for (int j = profile.size() - 1; j >= 0; j--) {
                    Segment.SpeedInterval si = profile.get(j);

                    double absFrom = minPk + si.fromPos;
                    double absTo = minPk + si.toPos;

                    double overlapFrom = Math.max(absFrom, minPk);
                    double overlapTo = Math.min(absTo, limitPk);

                    if (overlapTo <= overlapFrom) continue;

                    // Cerca de LTV = PK mayor
                    out.add(new ApproachInterval(
                            segId,
                            overlapFrom,
                            overlapTo,
                            overlapTo,
                            overlapFrom,
                            si.vMax
                    ));
                }
            } else {
                // Segmento completo hacia atrás desde PK mayor a PK menor
                for (int j = profile.size() - 1; j >= 0; j--) {
                    Segment.SpeedInterval si = profile.get(j);

                    double absFrom = minPk + si.fromPos;
                    double absTo = minPk + si.toPos;

                    if (absTo <= absFrom) continue;

                    out.add(new ApproachInterval(
                            segId,
                            absFrom,
                            absTo,
                            absTo,
                            absFrom,
                            si.vMax
                    ));
                }
            }
        }

        return out;
    }

    /**
     * INVERSA:
     * - movimiento real: PK decreciente
     * - perfil a usar: DOWN
     * - búsqueda geométrica hacia atrás: PK creciente
     */
    private static List<ApproachInterval> collectApproachIntervalsForInverse(
            double pkLtv,
            String startSegmentId,
            List<String> backwardGeomPath,
            Map<String, Segment> byId
    ) {
        List<ApproachInterval> out = new ArrayList<>();

        for (int i = 0; i < backwardGeomPath.size(); i++) {
            String segId = backwardGeomPath.get(i);
            Segment s = byId.get(segId);
            if (s == null) continue;

            double minPk = s.minPK();
            double maxPk = s.maxPK();

            List<Segment.SpeedInterval> profile = s.speedProfileDown;
            if (profile == null || profile.isEmpty()) continue;

            if (i == 0 && segId.equals(startSegmentId)) {
                // Solo la parte desde pkLtv hasta maxPK
                double startPk = pkLtv;

                for (int j = 0; j < profile.size(); j++) {
                    Segment.SpeedInterval si = profile.get(j);

                    double absFrom = minPk + si.fromPos;
                    double absTo = minPk + si.toPos;

                    double overlapFrom = Math.max(absFrom, startPk);
                    double overlapTo = Math.min(absTo, maxPk);

                    if (overlapTo <= overlapFrom) continue;

                    // Cerca de LTV = PK menor
                    out.add(new ApproachInterval(
                            segId,
                            overlapFrom,
                            overlapTo,
                            overlapFrom,
                            overlapTo,
                            si.vMax
                    ));
                }
            } else {
                // Segmento completo hacia atrás desde PK menor a PK mayor
                for (int j = 0; j < profile.size(); j++) {
                    Segment.SpeedInterval si = profile.get(j);

                    double absFrom = minPk + si.fromPos;
                    double absTo = minPk + si.toPos;

                    if (absTo <= absFrom) continue;

                    out.add(new ApproachInterval(
                            segId,
                            absFrom,
                            absTo,
                            absFrom,
                            absTo,
                            si.vMax
                    ));
                }
            }
        }

        return out;
    }

    private static double brakingDistanceMeters(double v0Kmh, double vTKmh, double aEff) {
        double v0 = kmhToMs(v0Kmh);
        double vT = kmhToMs(vTKmh);

        if (v0 <= vT) return 0.0;
        return (v0 * v0 - vT * vT) / (2.0 * aEff);
    }

    private static double kmhToMs(double kmh) {
        return kmh / 3.6;
    }

    private static double msToKmh(double ms) {
        return ms * 3.6;
    }

    private static void printPathDetails(List<String> path, Map<String, Segment> byId) {
        for (String id : path) {
            Segment s = byId.get(id);
            if (s == null) continue;

            System.out.println("      " + s.id
                    + "  [minPK=" + s.minPK()
                    + ", maxPK=" + s.maxPK()
                    + ", len=" + s.length()
                    + ", vRestr=" + s.mostRestrictiveSpeed + "]");
        }
    }

    private static void printSpeedProfile(List<Segment.SpeedInterval> profile) {
        if (profile == null || profile.isEmpty()) {
            System.out.println("  (sin datos)");
            return;
        }

        for (Segment.SpeedInterval interval : profile) {
            System.out.println("  " + interval);
        }
    }

    private static void printUsedIntervals(BrakingNoticeResult result) {
        if (result == null) {
            System.out.println("      (sin resultado)");
            return;
        }

        if (!result.exactNoticeFound) {
            System.out.println("      No se encontró un punto exacto de aviso dentro de la ruta disponible.");
            return;
        }

        if (result.usedIntervals == null || result.usedIntervals.isEmpty()) {
            System.out.println("      (sin intervalos usados)");
            return;
        }

        String currentSegment = null;

        for (int i = result.usedIntervals.size() - 1; i >= 0; i--) {
            UsedInterval ui = result.usedIntervals.get(i);

            if (!ui.segmentId.equals(currentSegment)) {
                currentSegment = ui.segmentId;
                System.out.println("      Segmento " + currentSegment);
            }

            System.out.println(String.format(
                    "        [desde PK %.2f hasta PK %.2f : %.2f km/h] len=%.2f m",
                    ui.pkTo, ui.pkFrom, ui.speedKmh, ui.lengthMeters()
            ));
        }
    }

    private static class BranchSearchResult {
        List<String> path;
        double backwardDistanceMeters;
        BrakingNoticeResult notice;
        boolean exactNoticeFound;
        String reason;

        BranchSearchResult(List<String> path,
                           double backwardDistanceMeters,
                           BrakingNoticeResult notice,
                           boolean exactNoticeFound,
                           String reason) {
            this.path = path;
            this.backwardDistanceMeters = backwardDistanceMeters;
            this.notice = notice;
            this.exactNoticeFound = exactNoticeFound;
            this.reason = reason;
        }
    }

    private static class ApproachInterval {
        String segmentId;
        double pkFrom;
        double pkTo;
        double pkTargetSide; // extremo más cercano a la LTV
        double pkAwaySide;   // extremo más alejado de la LTV
        double speedKmh;

        ApproachInterval(String segmentId, double pkFrom, double pkTo,
                         double pkTargetSide, double pkAwaySide, double speedKmh) {
            this.segmentId = segmentId;
            this.pkFrom = pkFrom;
            this.pkTo = pkTo;
            this.pkTargetSide = pkTargetSide;
            this.pkAwaySide = pkAwaySide;
            this.speedKmh = speedKmh;
        }

        double lengthMeters() {
            return Math.abs(pkTo - pkFrom);
        }
    }

    private static class UsedInterval {
        String segmentId;
        double pkFrom;
        double pkTo;
        double speedKmh;

        UsedInterval(String segmentId, double pkFrom, double pkTo, double speedKmh) {
            this.segmentId = segmentId;
            this.pkFrom = pkFrom;
            this.pkTo = pkTo;
            this.speedKmh = speedKmh;
        }

        double lengthMeters() {
            return Math.abs(pkTo - pkFrom);
        }
    }

    private static class BrakingNoticeResult {
        String segmentId;
        double pk;
        double noticeSpeedKmh;
        double targetSpeedKmh;
        double totalDistanceMeters;
        List<UsedInterval> usedIntervals;
        boolean exactNoticeFound;

        BrakingNoticeResult(String segmentId, double pk,
                            double noticeSpeedKmh, double targetSpeedKmh,
                            double totalDistanceMeters,
                            List<UsedInterval> usedIntervals,
                            boolean exactNoticeFound) {
            this.segmentId = segmentId;
            this.pk = pk;
            this.noticeSpeedKmh = noticeSpeedKmh;
            this.targetSpeedKmh = targetSpeedKmh;
            this.totalDistanceMeters = totalDistanceMeters;
            this.usedIntervals = usedIntervals;
            this.exactNoticeFound = exactNoticeFound;
        }
    }
}