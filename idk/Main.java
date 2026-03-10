package idk;

import idk.Segment.Edge;
import idk.Segment.Side;

public class Main {

    public static void main(String[] args) throws Exception {

        Path xml = Path.of("C:/Users/49204/Desktop/Herramienta/VIA_ATO_TOLUCA_DISERTMSPruebaLTVs.xml");

        RailMLSegmentsStaxParser parser = new RailMLSegmentsStaxParser();
        List<Segment> segs = parser.parseSegments(xml);

        System.out.println("Segmentos leídos: " + segs.size());

        Map<String, Segment> byId = new HashMap<>();
        for (Segment s : segs) byId.put(s.id, s);

        Map<String, List<Edge>> graph = buildGraph(segs, byId);
        Map<String, List<Edge>> reverseGraph = buildReverseGraph(graph);

        Scanner sc = new Scanner(System.in);
        System.out.print("Introduce PK (en metros, ej: 239000): ");
        double pk = sc.nextDouble();

        double radius = 1000.0;

        List<Segment> startSegments = findSegmentsContainingPk(segs, pk);

        System.out.println();
        if (startSegments.isEmpty()) {
            System.out.println("No encontré ningún segmento que contenga el PK " + pk);
            return;
        }

        System.out.println("Segmentos que contienen el PK:");
        for (Segment s : startSegments) {
            System.out.println("  " + s.id + "  [min=" + s.minPos() + ", max=" + s.maxPos() + "]");
        }

        // DIRECTA
        ReachResult direct = reachableWithinRadius(graph, byId, startSegments, pk, radius);
        System.out.println();
        System.out.println("RUTAS (DIRECTA) dentro de " + (int)radius + " m:");
        printRoutes(direct, startSegments);

        FlagInfo fwd = computeFlagAtRadius(direct, byId, radius);
        if (fwd != null) {
            System.out.println("FLAG DIRECTA (radio 1 km desde PK=" + (int)pk + "):");
            System.out.println("  Termina en " + fwd.segmentId + " en PK ~ " + String.format("%.1f", fwd.pkFlag));
        }

        System.out.println();
        System.out.println("Información (DIRECTA):");
        printReachableTable(direct.dist, byId);

        // INVERSA
        ReachResult inv = reachableWithinRadius(reverseGraph, byId, startSegments, pk, radius);
        System.out.println();
        System.out.println("RUTAS (INVERSA) dentro de " + (int)radius + " m:");
        printRoutes(inv, startSegments);

        FlagInfo back = computeFlagAtRadius(inv, byId, radius);
        if (back != null) {
            System.out.println("FLAG INVERSA (radio 1 km desde PK=" + (int)pk + "):");
            System.out.println("  Termina en " + back.segmentId + " en PK ~ " + String.format("%.1f", back.pkFlag));
        }

        System.out.println();
        System.out.println("Información (INVERSA):");
        printReachableTable(inv.dist, byId);
    }

    // -------------------- Estructuras --------------------

    static class ReachResult {
        Map<String, Double> dist = new HashMap<>();
        Map<String, String> parent = new HashMap<>();
        Map<String, Side> entrySide = new HashMap<>(); // por qué lado “entras” al segmento (BEGIN/END)
    }

    static class NodeDist {
        String nodeId;
        double dist;
        NodeDist(String nodeId, double dist) { this.nodeId = nodeId; this.dist = dist; }
    }

    static class FlagInfo {
        String segmentId;
        double pkFlag;
        FlagInfo(String segmentId, double pkFlag) { this.segmentId = segmentId; this.pkFlag = pkFlag; }
    }

    // -------------------- Construcción de grafos --------------------

    private static Map<String, List<Edge>> buildGraph(List<Segment> segs, Map<String, Segment> byId) {
        Map<String, List<Edge>> g = new HashMap<>();
        for (Segment s : segs) {
            List<Edge> outs = new ArrayList<>();
            for (Edge e : s.outEdges) {
                if (byId.containsKey(e.toId)) outs.add(e);
            }
            g.put(s.id, outs);
        }
        return g;
    }

    private static Map<String, List<Edge>> buildReverseGraph(Map<String, List<Edge>> graph) {
        Map<String, List<Edge>> rev = new HashMap<>();
        for (String id : graph.keySet()) rev.put(id, new ArrayList<>());

        for (Map.Entry<String, List<Edge>> entry : graph.entrySet()) {
            String from = entry.getKey();
            for (Edge e : entry.getValue()) {
                // reversa: (from -> to) se convierte en (to -> from)
                // Al ir “hacia atrás”, sales del 'to' por el lado por donde ENTRABAS (toSide)
                // y entras al 'from' por el lado por donde SALÍAS (fromSide).
                rev.computeIfAbsent(e.toId, k -> new ArrayList<>())
                   .add(new Edge(from, e.toSide, e.fromSide));
            }
        }
        return rev;
    }

    // -------------------- PK -> segmento(s) --------------------

    private static List<Segment> findSegmentsContainingPk(List<Segment> segs, double pk) {
        List<Segment> res = new ArrayList<>();
        for (Segment s : segs) {
            if (s.containsPk(pk)) res.add(s);
        }
        return res;
    }

    // -------------------- Alcanzables en radio (Dijkstra truncado) --------------------
    private static ReachResult reachableWithinRadius(
            Map<String, List<Edge>> graph,
            Map<String, Segment> byId,
            List<Segment> starts,
            double pk,
            double radius
    ) {
        ReachResult rr = new ReachResult();
        PriorityQueue<NodeDist> pq = new PriorityQueue<>(Comparator.comparingDouble(a -> a.dist));

        for (Segment s : starts) {
            rr.dist.put(s.id, 0.0);
            rr.parent.put(s.id, null);
            rr.entrySide.put(s.id, Side.UNKNOWN);
            pq.add(new NodeDist(s.id, 0.0));
        }

        while (!pq.isEmpty()) {
            NodeDist cur = pq.poll();
            double bestHere = rr.dist.getOrDefault(cur.nodeId, Double.POSITIVE_INFINITY);
            if (cur.dist != bestHere) continue;
            if (cur.dist > radius) continue;

            Segment seg = byId.get(cur.nodeId);
            if (seg == null) continue;

            for (Edge e : graph.getOrDefault(seg.id, List.of())) {

                double stepCost;

                // Caso especial: estoy en un segmento que contiene el PK y aún dist=0
                if (cur.dist == 0.0 && seg.containsPk(pk)) {
                    if (e.fromSide == Side.BEGIN) stepCost = seg.distancePkToBegin(pk);
                    else if (e.fromSide == Side.END) stepCost = seg.distancePkToEnd(pk);
                    else stepCost = Math.min(seg.distancePkToBegin(pk), seg.distancePkToEnd(pk));
                } else {
                    // aproximación simple: “atravesar” el segmento completo
                    stepCost = seg.length();
                }

                double nd = cur.dist + stepCost;
                if (nd > radius) continue;

                double old = rr.dist.getOrDefault(e.toId, Double.POSITIVE_INFINITY);
                if (nd < old) {
                    rr.dist.put(e.toId, nd);
                    rr.parent.put(e.toId, seg.id);
                    rr.entrySide.put(e.toId, e.toSide); // guardamos por qué lado entramos al destino
                    pq.add(new NodeDist(e.toId, nd));
                }
            }
        }
        return rr;
    }

    // -------------------- Impresión bonita de rutas --------------------

    private static void printRoutes(ReachResult rr, List<Segment> starts) {
        Set<String> startIds = new HashSet<>();
        for (Segment s : starts) startIds.add(s.id);

        List<Map.Entry<String, Double>> list = new ArrayList<>(rr.dist.entrySet());
        list.sort(Comparator.comparingDouble(Map.Entry::getValue));

        // imprime todas excepto los starts (si quieres, puedes limitar)
        for (Map.Entry<String, Double> e : list) {
            String node = e.getKey();
            if (startIds.contains(node)) continue; // no imprimas “ruta trivial”

            String path = buildPathString(rr.parent, node);
            System.out.println("  " + path + "   (dist=" + String.format("%.1f", e.getValue()) + " m)");
        }
    }

    private static String buildPathString(Map<String, String> parent, String goal) {
        List<String> rev = new ArrayList<>();
        String x = goal;
        while (x != null) {
            rev.add(x);
            x = parent.get(x);
        }
        Collections.reverse(rev);
        return String.join("=>", rev);
    }

    // -------------------- Cálculo del PK del FLAG en el borde del radio --------------------

    private static FlagInfo computeFlagAtRadius(ReachResult rr, Map<String, Segment> byId, double radius) {
        // Elegimos el nodo alcanzable MÁS LEJANO (dist máxima) para colocar el flag ahí.
        // (Si quieres otro criterio, se cambia fácil.)
        String bestNode = null;
        double bestDist = -1.0;

        for (Map.Entry<String, Double> e : rr.dist.entrySet()) {
            if (e.getValue() > bestDist) {
                bestDist = e.getValue();
                bestNode = e.getKey();
            }
        }

        if (bestNode == null) return null;

        Segment s = byId.get(bestNode);
        if (s == null || s.beginAbsPos == null || s.endAbsPos == null) return null;

        double remaining = radius - bestDist; // lo que “queda” dentro del último segmento
        if (remaining < 0) remaining = 0;

        Side entry = rr.entrySide.getOrDefault(bestNode, Side.UNKNOWN);

        // Si no sabemos el lado de entrada, ponemos el flag en el punto medio (aprox.)
        if (entry == Side.UNKNOWN) {
            double pkMid = (s.beginAbsPos + s.endAbsPos) / 2.0;
            return new FlagInfo(bestNode, pkMid);
        }

        // Movimiento desde el lado de entrada hacia el otro extremo, en dirección lineal del segmento
        double len = s.length();
        if (len <= 0.0) return null;

        // unit vector en “dirección BEGIN->END”
        double dir = (s.endAbsPos - s.beginAbsPos) / len;

        double pkFlag;
        if (entry == Side.BEGIN) {
            pkFlag = s.beginAbsPos + dir * remaining;
        } else {
            // entry END: nos movemos hacia BEGIN
            pkFlag = s.endAbsPos - dir * remaining;
        }

        // clamp por seguridad dentro del rango [min,max]
        pkFlag = Math.max(s.minPos(), Math.min(s.maxPos(), pkFlag));
        return new FlagInfo(bestNode, pkFlag);
    }

    // -------------------- Tabla “como antes” pero más limpia --------------------

    private static void printReachableTable(Map<String, Double> dist, Map<String, Segment> byId) {
        List<Map.Entry<String, Double>> list = new ArrayList<>(dist.entrySet());
        list.sort(Comparator.comparingDouble(Map.Entry::getValue));

        for (Map.Entry<String, Double> e : list) {
            Segment s = byId.get(e.getKey());
            if (s == null) continue;
            System.out.printf("  %-8s dist=%7.1f m  range=[%.1f, %.1f]  len=%.1f%n",
                    s.id, e.getValue(), s.minPos(), s.maxPos(), s.length());
        }
    }
}