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

            // OJO:
            // - Aviso para DIRECTA: el tren viene hacia PK creciente, así que el aviso está ANTES,
            //   o sea hacia PK decreciente -> usamos lado inverso geométrico.
            // - Aviso para INVERSA: el tren viene hacia PK decreciente, así que el aviso está ANTES,
            //   o sea hacia PK creciente -> usamos lado directo geométrico.

            BrakingNoticeResult directNotice = findBrakingNoticeForDirect(
                    pkLtv, s.id, inversePath, byId, ltvSpeedKmh, aEff
            );

            BrakingNoticeResult inverseNotice = findBrakingNoticeForInverse(
                    pkLtv, s.id, directPath, byId, ltvSpeedKmh, aEff
            );

            System.out.println();
            if (directNotice != null && directNotice.exactNoticeFound) {
                System.out.println("Aviso LTV en DIRECTA:");
                System.out.println("  Segmento: " + directNotice.segmentId);
                System.out.println("  PK: " + directNotice.pk);
                System.out.printf("  Velocidad en aviso: %.2f km/h%n", directNotice.noticeSpeedKmh);
                System.out.printf("  Velocidad objetivo LTV: %.2f km/h%n", directNotice.targetSpeedKmh);
                System.out.printf("  Distancia total de frenado: %.2f m%n", directNotice.totalDistanceMeters);
            } else {
                System.out.println("Aviso LTV en DIRECTA:");
                System.out.println("  No se pudo calcular con la ruta disponible.");
            }

            if (directNotice != null && directNotice.exactNoticeFound) {
                System.out.println("Aviso LTV en INVERSA:");
                System.out.println("  Segmento: " + inverseNotice.segmentId);
                System.out.println("  PK: " + inverseNotice.pk);
                System.out.printf("  Velocidad en aviso: %.2f km/h%n", inverseNotice.noticeSpeedKmh);
                System.out.printf("  Velocidad objetivo LTV: %.2f km/h%n", inverseNotice.targetSpeedKmh);
                System.out.printf("  Distancia total de frenado: %.2f m%n", inverseNotice.totalDistanceMeters);
            } else {
                System.out.println("Aviso LTV en INVERSA:");
                System.out.println("  No se pudo calcular con la ruta disponible.");
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

    /**
     * DIRECTA = tren circula hacia PK creciente, usando perfil UP.
     * El aviso está antes de la LTV, es decir, hacia PK decreciente.
     * Por eso usamos inversePath como lado geométrico.
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
     * Por eso usamos directPath como lado geométrico.
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
     *
     * currentRequiredKmh = velocidad máxima admisible en el extremo más cercano a la LTV.
     * En cada intervalo anterior:
     * - si el límite del intervalo es <= currentRequired, no hace falta frenar antes ahí.
     * - si el límite del intervalo es > currentRequired, vemos si el frenado cabe dentro del intervalo.
     *   - si cabe, el aviso cae dentro del intervalo
     *   - si no cabe, propagamos hacia atrás la velocidad requerida al inicio del intervalo
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
     * Construye intervalos hacia atrás para el caso DIRECTA:
     * - movimiento real: PK creciente
     * - perfil a usar: UP
     * - lado geométrico de búsqueda: PK decreciente
     *
     * Los intervalos se devuelven ordenados desde el más cercano a la LTV hacia atrás.
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
            double maxPk = s.maxPK();

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
                // Segmento completo, recorrido hacia atrás desde maxPK a minPK
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
     * Construye intervalos hacia atrás para el caso INVERSA:
     * - movimiento real: PK decreciente
     * - perfil a usar: DOWN
     * - lado geométrico de búsqueda: PK creciente
     *
     * Los intervalos se devuelven ordenados desde el más cercano a la LTV hacia atrás.
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
                // Segmento completo, recorrido hacia atrás desde minPK a maxPK
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

            System.out.println("  " + s.id
                    + "  [minPK=" + s.minPK()
                    + ", maxPK=" + s.maxPK()
                    + ", len=" + s.length()
                    + ", vRestr=" + s.mostRestrictiveSpeed + "]");
        }
    }
    
    private static void printSpeedProfilesAlongPath(List<String> path, Map<String, Segment> byId, String dir) {
        if (path == null || path.isEmpty()) {
            System.out.println("  (ruta vacía)");
            return;
        }

        for (String id : path) {
            Segment s = byId.get(id);
            if (s == null) continue;

            System.out.println("  Segmento " + s.id
                    + " [minPK=" + s.minPK()
                    + ", maxPK=" + s.maxPK()
                    + ", len=" + s.length() + "]");

            List<Segment.SpeedInterval> profile =
                    "down".equalsIgnoreCase(dir) ? s.speedProfileDown : s.speedProfileUp;

            if (profile == null || profile.isEmpty()) {
                System.out.println("    (sin datos de velocidad para " + dir.toUpperCase() + ")");
            } else {
                for (Segment.SpeedInterval interval : profile) {
                    double absFrom = s.minPK() + interval.fromPos;
                    double absTo = s.minPK() + interval.toPos;

                    System.out.println(String.format(
                            "    [PK %.2f -> PK %.2f : %.2f km/h]",
                            absFrom, absTo, interval.vMax
                    ));
                }
            }
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
    
    private static void printUsedIntervals(BrakingNoticeResult result) {
        if (result == null) {
            System.out.println("  (sin resultado)");
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
    
    
    
    
}