package idk.toolltv;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

public class LtvService {

    private static final String XML_PATH =
            "C:/Users/49204/Desktop/Herramienta/VIA_ATO_TOLUCA_DISERTMS (99).xml";

    private static final double G = 9.81;
    private static final double A_BRAKE = 0.4;
    private static final double GRAD_PERMIL = 10.0;
    private static final int SLOPE_DIR = -1;

    private static final double LTV_MARGIN = 7.0;

    private static final double MIN_BG_GAP_METERS = 15.0;
    private static final double BG_SEARCH_WINDOW_METERS = 20.0;

    private static final double MAX_BACKWARD_SEARCH_METERS = 4000.0;

    public List<SegmentOption> buscarCandidatos(double pkLtv, double ltvSpeedKmh, double ltvLengthMeters) throws Exception {
        Locale.setDefault(Locale.US);

        List<Segment> segs = loadSegments();

        double pkDirect = pkLtv - LTV_MARGIN;
        double pkInverse = pkLtv + ltvLengthMeters + LTV_MARGIN;

        List<Segment> candidates = findCandidateSegments(segs, pkDirect, pkInverse, ltvSpeedKmh);

        List<SegmentOption> result = new ArrayList<>();
        for (Segment seg : candidates) {
            boolean direct = seg.containsPK(pkDirect);
            boolean inverse = seg.containsPK(pkInverse);

            String label = String.format(
                    Locale.US,
                    "%s [min=%.1f, max=%.1f, vRestr=%.1f]%s%s",
                    seg.id,
                    seg.minPK(),
                    seg.maxPK(),
                    seg.mostRestrictiveSpeed,
                    direct ? " <- DIRECTA" : "",
                    inverse ? " <- INVERSA" : ""
            );

            result.add(new SegmentOption(seg.id, label));
        }
        return result;
    }

    public String calcular(double pkLtv, double ltvSpeedKmh, double ltvLengthMeters, String selectedSegmentId) throws Exception {
        Locale.setDefault(Locale.US);
        StringBuilder out = new StringBuilder();

        List<Segment> segs = loadSegments();
        Map<String, Segment> byId = buildMap(segs);

        double pkDirect = pkLtv - LTV_MARGIN;
        double pkInverse = pkLtv + ltvLengthMeters + LTV_MARGIN;

        printf(out, "Segmentos leídos: %d%n", segs.size());
        printf(out, "%nTramo LTV:      [%.2f, %.2f]%n", pkLtv, pkLtv + ltvLengthMeters);
        printf(out, "Con margen %.1fm: [%.2f, %.2f]%n", LTV_MARGIN, pkDirect, pkInverse);

        double aSlope = SLOPE_DIR * G * (GRAD_PERMIL / 1000.0);
        double aEff = A_BRAKE + aSlope;

        printf(out, "%n=== DATOS DE FRENADO ===%n");
        printf(out, "aBrake=%.4f  gradiente=%.1f‰  aSlope=%.4f  aEff=%.4f m/s²%n",
                A_BRAKE, GRAD_PERMIL, aSlope, aEff);

        if (aEff <= 0) {
            println(out, "ERROR: la desaceleración efectiva es <= 0. Revisa los parámetros.");
            return out.toString();
        }

        List<Segment> candidates = findCandidateSegments(segs, pkDirect, pkInverse, ltvSpeedKmh);

        if (candidates.isEmpty()) {
            println(out, "No encontré segmentos que cubran el tramo LTV con la velocidad indicada.");
            return out.toString();
        }

        Segment selected = byId.get(selectedSegmentId);
        if (selected == null) {
            println(out, "ERROR: el segmento seleccionado no existe.");
            return out.toString();
        }

        println(out, "\nSegmentos del tramo LTV:");
        for (int i = 0; i < candidates.size(); i++) {
            Segment seg = candidates.get(i);
            printf(out, " %d) %-8s [min=%.1f, max=%.1f, vRestr=%.1f]%s%s%n",
                    i + 1, seg.id, seg.minPK(), seg.maxPK(), seg.mostRestrictiveSpeed,
                    seg.containsPK(pkDirect) ? " <- DIRECTA" : "",
                    seg.containsPK(pkInverse) ? " <- INVERSA" : "");
        }

        println(out, "\nSegmento seleccionado: " + selected.id);

        Segment segDirect = resolveStartSegment(selected, pkDirect, byId, candidates);
        Segment segInverse = resolveStartSegment(selected, pkInverse, byId, candidates);

        println(out, "Segmento de inicio DIRECTA:  " + (segDirect != null ? segDirect.id : "no encontrado"));
        println(out, "Segmento de inicio INVERSA:  " + (segInverse != null ? segInverse.id : "no encontrado"));

        printSegmentInfo(out, selected, byId);

        List<BranchSearchResult> directBranches = new ArrayList<>();
        if (segDirect == null) {
            println(out, "No hay segmento válido para DIRECTA.");
        } else {
            Double vAtLtv = BrakingEngine.speedAtPk(segDirect, pkDirect, true);
            if (vAtLtv != null && vAtLtv <= ltvSpeedKmh) {
                directBranches.add(noticeBranchNotNeeded(segDirect.id, vAtLtv, ltvSpeedKmh, "DIRECTA"));
            } else {
                directBranches = BrakingEngine.findAllNoticeBranches(
                        pkDirect, segDirect.id, byId, true, ltvSpeedKmh, aEff, MAX_BACKWARD_SEARCH_METERS);
            }
        }

        List<BranchSearchResult> inverseBranches = new ArrayList<>();
        if (segInverse == null) {
            println(out, "No hay segmento válido para INVERSA.");
        } else {
            Double vAtLtv = BrakingEngine.speedAtPk(segInverse, pkInverse, false);
            if (vAtLtv != null && vAtLtv <= ltvSpeedKmh) {
                inverseBranches.add(noticeBranchNotNeeded(segInverse.id, vAtLtv, ltvSpeedKmh, "INVERSA"));
            } else {
                inverseBranches = BrakingEngine.findAllNoticeBranches(
                        pkInverse, segInverse.id, byId, false, ltvSpeedKmh, aEff, MAX_BACKWARD_SEARCH_METERS);
            }
        }

        println(out, "\nRAMAS EN DIRECTA (tren hacia PK creciente):");
        printBranchResults(out, directBranches, byId, true, pkDirect);

        println(out, "\nRAMAS EN INVERSA (tren hacia PK decreciente):");
        printBranchResults(out, inverseBranches, byId, false, pkInverse);

        return out.toString();
    }

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

    private static List<Segment> findCandidateSegments(
            List<Segment> segs, double pkDirect, double pkInverse, double ltvSpeedKmh
    ) {
        Set<String> seen = new HashSet<>();
        List<Segment> result = new ArrayList<>();
        for (Segment seg : segs) {
            if (seg.mostRestrictiveSpeed == null || seg.mostRestrictiveSpeed < ltvSpeedKmh) continue;
            if (!seg.containsPK(pkDirect) && !seg.containsPK(pkInverse)) continue;
            if (seen.add(seg.id)) result.add(seg);
        }
        return result;
    }

    private static Segment resolveStartSegment(
            Segment selected, double pkEffective,
            Map<String, Segment> byId, List<Segment> candidates
    ) {
        if (selected.containsPK(pkEffective)) return selected;

        for (String nId : selected.directNeighbors) {
            Segment n = byId.get(nId);
            if (n != null && n.containsPK(pkEffective)) return n;
        }
        for (String nId : selected.inverseNeighbors) {
            Segment n = byId.get(nId);
            if (n != null && n.containsPK(pkEffective)) return n;
        }

        for (Segment seg : candidates) {
            if (seg.containsPK(pkEffective)) return seg;
        }
        return null;
    }

    private static BranchSearchResult noticeBranchNotNeeded(
            String segId, double speed, double targetSpeed, String direction
    ) {
        return new BranchSearchResult(
                List.of(segId), 0.0, null, false,
                String.format("No hace falta LTV en %s: velocidad %.2f <= %.2f km/h",
                        direction, speed, targetSpeed));
    }

    private static void printBranchResults(
            StringBuilder out,
            List<BranchSearchResult> branches,
            Map<String, Segment> byId,
            boolean directMovement,
            double pkLtvEffective
    ) {
        if (branches == null || branches.isEmpty()) {
            println(out, "  (sin ramas)");
            return;
        }

        String title = directMovement ? "DIRECTA" : "INVERSA";

        for (int i = 0; i < branches.size(); i++) {
            BranchSearchResult branch = branches.get(i);

            printf(out, "%n  Rama %s %d:%n", title, i + 1);
            println(out, "    Ruta: " + String.join(" => ", branch.path));
            printf(out, "    Distancia explorada: %.2f m%n", branch.backwardDistanceMeters);
            println(out, "    Estado: " + branch.reason);

            println(out, "    Segmentos:");
            for (String segId : branch.path) {
                Segment s = byId.get(segId);
                if (s == null) continue;
                printf(out, "      %-8s [minPK=%.1f, maxPK=%.1f, len=%.1f, vRestr=%.1f]%n",
                        s.id, s.minPK(), s.maxPK(), s.length(), s.mostRestrictiveSpeed);
            }

            SignalNoticeResolver.SignalNoticeResult signal =
                    SignalNoticeResolver.findSignalInPath(branch.path, byId, directMovement, pkLtvEffective);
            if (signal.found) {
                printf(out, "    Señal: %s en PK=%.2f (seg=%s)%n",
                        signal.signalName, signal.pk, signal.segmentId);
            }

            BrakingNoticeResult notice = branch.notice;
            if (notice != null && notice.exactNoticeFound) {
                notice = BaliseNoticeResolver.adjust(
                        notice, branch.path, byId, directMovement,
                        MIN_BG_GAP_METERS, BG_SEARCH_WINDOW_METERS);
            }

            if (notice != null && notice.exactNoticeFound) {
                println(out, "    Aviso: SI");
                printf(out, "      Segmento: %s%n", notice.segmentId);
                printf(out, "      PK:       %.2f m%n", notice.pk);
                printf(out, "      Velocidad en aviso:  %.2f km/h%n", notice.noticeSpeedKmh);
                printf(out, "      Velocidad objetivo:  %.2f km/h%n", notice.targetSpeedKmh);
                printf(out, "      Distancia frenado:   %.2f m%n", notice.totalDistanceMeters);
                println(out, "      Tramos usados:");
                printUsedIntervals(out, notice);
            } else {
                println(out, "    Aviso: NO  →  " + branch.reason);
            }
        }
    }

    private static void printUsedIntervals(StringBuilder out, BrakingNoticeResult notice) {
        if (notice.usedIntervals == null || notice.usedIntervals.isEmpty()) {
            println(out, "        (sin intervalos)");
            return;
        }
        String currentSeg = null;
        for (int i = notice.usedIntervals.size() - 1; i >= 0; i--) {
            UsedInterval ui = notice.usedIntervals.get(i);
            if (!ui.segmentId.equals(currentSeg)) {
                currentSeg = ui.segmentId;
                println(out, "        Segmento " + currentSeg);
            }
            printf(out, "          [PK %.2f → %.2f : %.2f km/h  len=%.2f m]%n",
                    ui.pkTo, ui.pkFrom, ui.speedKmh, ui.lengthMeters());
        }
    }

    private static void printSegmentInfo(StringBuilder out, Segment s, Map<String, Segment> byId) {
        println(out, "\n==================================================");
        println(out, "SEGMENTO CANDIDATO: " + s.id);
        println(out, "==================================================");
        printf(out, "  Rango PK:    [%.2f, %.2f]  len=%.2f m%n", s.minPK(), s.maxPK(), s.length());
        printf(out, "  vRestr:      total=%.1f  up=%.1f  down=%.1f%n",
                s.mostRestrictiveSpeed, s.mostRestrictiveSpeedUp, s.mostRestrictiveSpeedDown);
        println(out, "  Vecinos directos:  " + s.directNeighbors);
        println(out, "  Vecinos inversos:  " + s.inverseNeighbors);

        println(out, "  Perfil UP:");
        for (Segment.SpeedInterval si : s.speedProfileUp) {
            println(out, "    " + si);
        }

        println(out, "  Perfil DOWN:");
        for (Segment.SpeedInterval si : s.speedProfileDown) {
            println(out, "    " + si);
        }

        println(out, "  Balizas:");
        for (BaliseData.Balise b : s.getBalises()) {
            printf(out, "    %-30s absPk=%.2f%n", b.name, b.absolutePk(byId));
        }

        println(out, "  Grupos de balizas:");
        for (BaliseData.BaliseGroup bg : s.getBaliseGroups()) {
            printf(out, "    %-40s firstPk=%.2f  lastPk=%.2f%n",
                    bg.name, bg.getFirstPkByNdx(byId), bg.getLastPkByNdx(byId));
        }
    }

    private static void println(StringBuilder out, String text) {
        out.append(text).append(System.lineSeparator());
    }

    private static void printf(StringBuilder out, String format, Object... args) {
        out.append(String.format(Locale.US, format, args));
    }

    public static class SegmentOption {
        private final String id;
        private final String label;

        public SegmentOption(String id, String label) {
            this.id = id;
            this.label = label;
        }

        public String getId() {
            return id;
        }

        @Override
        public String toString() {
            return label;
        }
    }
}