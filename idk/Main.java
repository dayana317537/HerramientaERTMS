package idk;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Scanner;
import java.util.Set;

public class Main {

    public static void main(String[] args) throws Exception {

        Path xml = Path.of("C:/Users/49204/Desktop/Herramienta/VIA_ATO_TOLUCA_DISERTMSPruebaLTVs.xml");

        RailMLSegmentsStaxParser parser = new RailMLSegmentsStaxParser();
        List<Segment> segs = parser.parseSegments(xml);

        Map<String, Segment> byId = new HashMap<>();
        for (Segment s : segs) {
            byId.put(s.id, s);
        }

        System.out.println("Segmentos leídos: " + segs.size());

        Scanner sc = new Scanner(System.in);
        System.out.print("Introduce PK (en metros, ej: 239000): ");
        double pk = sc.nextDouble();

        List<Segment> containing = findSegmentsContainingPk(segs, pk);

        System.out.println();

        if (containing.isEmpty()) {
            System.out.println("No encontré ningún segmento que contenga el PK " + pk);
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

            List<String> directPath = buildSinglePath(s.id, byId, true);
            List<String> inversePath = buildSinglePath(s.id, byId, false);

            System.out.println("Directa (PK creciente):");
            System.out.println("  " + String.join(" => ", directPath));

            System.out.println("Inversa (PK decreciente):");
            System.out.println("  " + String.join(" => ", inversePath));

            FlagPosition directFlag = findObjectAtDistance(pk, s.id, directPath, byId, true, 1000.0);
            FlagPosition inverseFlag = findObjectAtDistance(pk, s.id, inversePath, byId, false, 1000.0);

            System.out.println();
            if (directFlag != null) {
                System.out.println("Objeto a 1 km en DIRECTA:");
                System.out.println("  Segmento: " + directFlag.segmentId);
                System.out.println("  PK: " + directFlag.pk);
            } else {
                System.out.println("Objeto a 1 km en DIRECTA:");
                System.out.println("  No se alcanza 1 km con la ruta calculada.");
            }

            if (inverseFlag != null) {
                System.out.println("Objeto a 1 km en INVERSA:");
                System.out.println("  Segmento: " + inverseFlag.segmentId);
                System.out.println("  PK: " + inverseFlag.pk);
            } else {
                System.out.println("Objeto a 1 km en INVERSA:");
                System.out.println("  No se alcanza 1 km con la ruta calculada.");
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

    private static FlagPosition findObjectAtDistance(
            double startPk,
            String startSegmentId,
            List<String> path,
            Map<String, Segment> byId,
            boolean direct,
            double distanceMeters
    ) {
        if (path == null || path.isEmpty()) return null;

        double remaining = distanceMeters;

        for (int i = 0; i < path.size(); i++) {
            String segId = path.get(i);
            Segment s = byId.get(segId);
            if (s == null) return null;

            double min = s.minPK();
            double max = s.maxPK();

            if (i == 0 && segId.equals(startSegmentId)) {
                double available;
                if (direct) {
                    available = max - startPk;
                    if (remaining <= available) {
                        return new FlagPosition(segId, startPk + remaining);
                    } else {
                        remaining -= available;
                    }
                } else {
                    available = startPk - min;
                    if (remaining <= available) {
                        return new FlagPosition(segId, startPk - remaining);
                    } else {
                        remaining -= available;
                    }
                }
            } else {
                double len = s.length();

                if (remaining <= len) {
                    if (direct) {
                        return new FlagPosition(segId, min + remaining);
                    } else {
                        return new FlagPosition(segId, max - remaining);
                    }
                } else {
                    remaining -= len;
                }
            }
        }

        return null;
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

    private static class FlagPosition {
        String segmentId;
        double pk;

        FlagPosition(String segmentId, double pk) {
            this.segmentId = segmentId;
            this.pk = pk;
        }
    }
}