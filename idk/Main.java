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

    public static void main(String[] args) throws Exception {
        Locale.setDefault(Locale.US);

        Path xml = Path.of("C:/Users/49204/Desktop/Herramienta/VIA_ATO_TOLUCA_DISERTMSPruebaLTVs.xml");

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

            List<String> directPath = buildSinglePath(s.id, byId, true);   // PK creciente
            List<String> inversePath = buildSinglePath(s.id, byId, false); // PK decreciente

            System.out.println("Directa (PK creciente):");
            System.out.println("  " + String.join(" => ", directPath));

            System.out.println("Inversa (PK decreciente):");
            System.out.println("  " + String.join(" => ", inversePath));

            BrakingNoticeResult directNotice;
            BrakingNoticeResult inverseNotice;

            // DIRECTA usa perfil UP en el propio PK
            Double speedAtLtvDirect = getSpeedAtPk(s, pkLtv, true);
            if (speedAtLtvDirect != null && speedAtLtvDirect <= ltvSpeedKmh) {
                directNotice = BrakingNoticeResult.notNeeded(
                        ltvSpeedKmh,
                        String.format(
                                "No hace falta LTV en DIRECTA: en el PK %.2f ya existe velocidad %.2f km/h <= %.2f km/h",
                                pkLtv, speedAtLtvDirect, ltvSpeedKmh
                        )
                );
            } else {
                // Aviso para DIRECTA: se busca hacia PK decreciente
                directNotice = findBrakingNoticeForDirect(
                        pkLtv, s.id, inversePath, byId, ltvSpeedKmh, aEff
                );
            }

            // INVERSA usa perfil DOWN en el propio PK
            Double speedAtLtvInverse = getSpeedAtPk(s, pkLtv, false);
            if (speedAtLtvInverse != null && speedAtLtvInverse <= ltvSpeedKmh) {
                inverseNotice = BrakingNoticeResult.notNeeded(
                        ltvSpeedKmh,
                        String.format(
                                "No hace falta LTV en INVERSA: en el PK %.2f ya existe velocidad %.2f km/h <= %.2f km/h",
                                pkLtv, speedAtLtvInverse, ltvSpeedKmh
                        )
                );
            } else {
                // Aviso para INVERSA: se busca hacia PK creciente
                inverseNotice = findBrakingNoticeForInverse(
                        pkLtv, s.id, directPath, byId, ltvSpeedKmh, aEff
                );
            }

            System.out.println();
            System.out.println("Aviso LTV en DIRECTA:");
            printNoticeResult(directNotice);

            System.out.println();
            System.out.println("Aviso LTV en INVERSA:");
            printNoticeResult(inverseNotice);

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
            System.out.println("Tramos realmente usados para calcular el aviso LTV en DIRECTA:");
            printUsedIntervals(directNotice);

            System.out.println();
            System.out.println("Tramos realmente usados para calcular el aviso LTV en INVERSA:");
            printUsedIntervals(inverseNotice);

            System.out.println();
            System.out.println("Detalle de la ruta directa:");
            printPathDetails(directPath, byId);

            System.out.println();
            System.out.println("Detalle de la ruta inversa:");
            printPathDetails(inversePath, byId);
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

    private static List<String> buildSinglePath(String startId, Map<String, Segment> byId, boolean direct) {
        List<String> path = new ArrayList<>();
        Set<String> visited = new HashSet<>();

        String currentId = startId;

        while (currentId != null) {
            if (visited.contains(currentId)) break;

            visited.add(currentId);
            path.add(currentId);

            Segment current = byId.get(currentId);
            if (current == null) break;

            List<String> nexts = direct ? current.directNeighbors : current.inverseNeighbors;
            if (nexts.isEmpty()) break;

            String nextId = null;
            for (String n : nexts) {
                if (!visited.contains(n)) {
                    nextId = n;
                    break;
                }
            }

            if (nextId == null) break;
            currentId = nextId;
        }

        return path;
    }

    private static Double getSpeedAtPk(Segment s, double pk, boolean directMovement) {
        if (s == null || !s.containsPK(pk)) return null;

        List<Segment.SpeedInterval> profile = directMovement ? s.speedProfileUp : s.speedProfileDown;
        if (profile == null || profile.isEmpty()) return null;

        double relPos = pk - s.minPK();

        for (Segment.SpeedInterval interval : profile) {
            boolean inside = relPos >= interval.fromPos && relPos <= interval.toPos;
            if (inside) return interval.vMax;
        }

        return null;
    }

    /**
     * DIRECTA = tren circula hacia PK creciente, usando perfil UP.
     * El aviso está antes de la LTV, o sea hacia PK decreciente.
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
     * El aviso está antes de la LTV, o sea hacia PK creciente.
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
     * Criterio correcto:
     * - para cada tramo hacia atrás:
     *   vInicial = velocidad del tramo
     *   vFinal   = velocidad LTV
     * - se compara la distancia necesaria con la distancia acumulada desde
     *   el INICIO de ese tramo hasta la LTV
     * - el aviso cae en el primer tramo donde eso ya cabe
     */
    private static BrakingNoticeResult solveBrakingNotice(
            List<ApproachInterval> intervals,
            double targetSpeedKmh,
            double aEff,
            boolean directMovement
    ) {
        if (intervals == null || intervals.isEmpty()) {
            return BrakingNoticeResult.noSolution(
                    targetSpeedKmh,
                    "No hay intervalos de velocidad disponibles para evaluar"
            );
        }

        double accumulatedDistance = 0.0;
        List<UsedInterval> usedIntervals = new ArrayList<>();

        for (ApproachInterval interval : intervals) {
            double vLimit = interval.speedKmh;
            double intervalLen = interval.lengthMeters();

            double accumulatedAfterThisInterval = accumulatedDistance + intervalLen;
            double dNeed = brakingDistanceMeters(vLimit, targetSpeedKmh, aEff);

            if (vLimit > targetSpeedKmh + 1e-9 && dNeed <= accumulatedAfterThisInterval + 1e-9) {
                double offsetInsideInterval = dNeed - accumulatedDistance;

                if (offsetInsideInterval < 0.0) {
                    offsetInsideInterval = 0.0;
                }
                if (offsetInsideInterval > intervalLen) {
                    offsetInsideInterval = intervalLen;
                }

                double noticePk;
                double usedFrom;
                double usedTo;

                if (directMovement) {
                    // En DIRECTA el lado cercano a la LTV es PK mayor
                    noticePk = interval.pkTargetSide - offsetInsideInterval;
                    usedFrom = noticePk;
                    usedTo = interval.pkTargetSide;
                } else {
                    // En INVERSA el lado cercano a la LTV es PK menor
                    noticePk = interval.pkTargetSide + offsetInsideInterval;
                    usedFrom = interval.pkTargetSide;
                    usedTo = noticePk;
                }

                usedIntervals.add(new UsedInterval(
                        interval.segmentId,
                        usedFrom,
                        usedTo,
                        interval.speedKmh
                ));

                return BrakingNoticeResult.exact(
                        interval.segmentId,
                        noticePk,
                        vLimit,
                        targetSpeedKmh,
                        dNeed,
                        usedIntervals
                );
            }

            accumulatedDistance = accumulatedAfterThisInterval;

            usedIntervals.add(new UsedInterval(
                    interval.segmentId,
                    interval.pkFrom,
                    interval.pkTo,
                    interval.speedKmh
            ));
        }

        return BrakingNoticeResult.noSolution(
                targetSpeedKmh,
                "No se encontró un punto exacto de aviso dentro de la ruta disponible",
                usedIntervals,
                accumulatedDistance
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
                double limitPk = pkLtv;

                for (int j = profile.size() - 1; j >= 0; j--) {
                    Segment.SpeedInterval si = profile.get(j);

                    double absFrom = minPk + si.fromPos;
                    double absTo = minPk + si.toPos;

                    double overlapFrom = Math.max(absFrom, minPk);
                    double overlapTo = Math.min(absTo, limitPk);

                    if (overlapTo <= overlapFrom) continue;

                    out.add(new ApproachInterval(
                            segId,
                            overlapFrom,
                            overlapTo,
                            overlapTo,   // lado cercano a la LTV
                            overlapFrom, // lado lejano
                            si.vMax
                    ));
                }
            } else {
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
                double startPk = pkLtv;

                for (int j = 0; j < profile.size(); j++) {
                    Segment.SpeedInterval si = profile.get(j);

                    double absFrom = minPk + si.fromPos;
                    double absTo = minPk + si.toPos;

                    double overlapFrom = Math.max(absFrom, startPk);
                    double overlapTo = Math.min(absTo, maxPk);

                    if (overlapTo <= overlapFrom) continue;

                    out.add(new ApproachInterval(
                            segId,
                            overlapFrom,
                            overlapTo,
                            overlapFrom, // lado cercano a la LTV
                            overlapTo,   // lado lejano
                            si.vMax
                    ));
                }
            } else {
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

    private static void printPathDetails(List<String> path, Map<String, Segment> byId) {
        for (String id : path) {
            Segment s = byId.get(id);
            if (s == null) continue;

            System.out.println("  " + s.id
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
            System.out.println("  (sin resultado)");
            return;
        }

        if (result.ltvNotNeeded) {
            System.out.println("  " + result.statusMessage);
            return;
        }

        if (!result.exactNoticeFound) {
            System.out.println("  No se encontró un punto exacto de aviso dentro de la ruta disponible.");
            return;
        }

        if (result.usedIntervals == null || result.usedIntervals.isEmpty()) {
            System.out.println("  (sin intervalos usados)");
            return;
        }

        String currentSegment = null;

        for (int i = result.usedIntervals.size() - 1; i >= 0; i--) {
            UsedInterval ui = result.usedIntervals.get(i);

            if (!ui.segmentId.equals(currentSegment)) {
                currentSegment = ui.segmentId;
                System.out.println("  Segmento " + currentSegment);
            }

            System.out.println(String.format(
                    "    [desde PK %.2f hasta PK %.2f : %.2f km/h] len=%.2f m",
                    ui.pkTo, ui.pkFrom, ui.speedKmh, ui.lengthMeters()
            ));
        }
    }

    private static void printNoticeResult(BrakingNoticeResult result) {
        if (result == null) {
            System.out.println("  (sin resultado)");
            return;
        }

        if (result.ltvNotNeeded) {
            System.out.println("  Estado: NO HACE FALTA LTV");
            System.out.println("  " + result.statusMessage);
            return;
        }

        if (!result.exactNoticeFound) {
            System.out.println("  Estado: NO SE PUDO DETERMINAR");
            System.out.println("  " + result.statusMessage);
            return;
        }

        System.out.println("  Estado: AVISO EXACTO");
        System.out.println("  Segmento: " + result.segmentId);
        System.out.println("  PK: " + result.pk);
        System.out.printf("  Velocidad en aviso: %.2f km/h%n", result.noticeSpeedKmh);
        System.out.printf("  Velocidad objetivo LTV: %.2f km/h%n", result.targetSpeedKmh);
        System.out.printf("  Distancia total de frenado: %.2f m%n", result.totalDistanceMeters);
    }

    private static double kmhToMs(double kmh, boolean unused) {
        return kmh / 3.6;
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
        boolean ltvNotNeeded;
        String statusMessage;

        static BrakingNoticeResult exact(
                String segmentId,
                double pk,
                double noticeSpeedKmh,
                double targetSpeedKmh,
                double totalDistanceMeters,
                List<UsedInterval> usedIntervals
        ) {
            BrakingNoticeResult r = new BrakingNoticeResult();
            r.segmentId = segmentId;
            r.pk = pk;
            r.noticeSpeedKmh = noticeSpeedKmh;
            r.targetSpeedKmh = targetSpeedKmh;
            r.totalDistanceMeters = totalDistanceMeters;
            r.usedIntervals = usedIntervals;
            r.exactNoticeFound = true;
            r.ltvNotNeeded = false;
            r.statusMessage = "Aviso exacto encontrado";
            return r;
        }

        static BrakingNoticeResult notNeeded(double targetSpeedKmh, String msg) {
            BrakingNoticeResult r = new BrakingNoticeResult();
            r.segmentId = null;
            r.pk = Double.NaN;
            r.noticeSpeedKmh = Double.NaN;
            r.targetSpeedKmh = targetSpeedKmh;
            r.totalDistanceMeters = 0.0;
            r.usedIntervals = new ArrayList<>();
            r.exactNoticeFound = false;
            r.ltvNotNeeded = true;
            r.statusMessage = msg;
            return r;
        }

        static BrakingNoticeResult noSolution(double targetSpeedKmh, String msg) {
            return noSolution(targetSpeedKmh, msg, new ArrayList<>(), 0.0);
        }

        static BrakingNoticeResult noSolution(
                double targetSpeedKmh,
                String msg,
                List<UsedInterval> usedIntervals,
                double totalDistanceMeters
        ) {
            BrakingNoticeResult r = new BrakingNoticeResult();
            r.segmentId = null;
            r.pk = Double.NaN;
            r.noticeSpeedKmh = Double.NaN;
            r.targetSpeedKmh = targetSpeedKmh;
            r.totalDistanceMeters = totalDistanceMeters;
            r.usedIntervals = usedIntervals;
            r.exactNoticeFound = false;
            r.ltvNotNeeded = false;
            r.statusMessage = msg;
            return r;
        }
    }
}