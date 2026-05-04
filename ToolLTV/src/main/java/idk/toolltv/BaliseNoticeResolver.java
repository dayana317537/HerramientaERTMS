package idk.toolltv;

import idk.toolltv.BrakingNoticeResult;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * BaliseNoticeResolver
 *
 * Ajusta el PK del aviso de frenado para que respete la distancia mínima
 * con los grupos de balizas (BG) existentes en la vía.
 *
 * Regla: si el aviso cae a menos de minGapMeters de un BG,
 * se desplaza alejándose de la LTV hasta que no haya conflictos.
 */
public class BaliseNoticeResolver {

    // Activar/desactivar logs de depuración
    public static boolean debugEnabled = true;

    // -------------------------------------------------------------------------
    // Punto de entrada principal
    // -------------------------------------------------------------------------

    /**
     * Ajusta el aviso teórico para que respete el gap mínimo con los BGs.
     */
    public static BrakingNoticeResult adjust(
            BrakingNoticeResult rawNotice,
            List<String> branchPath,
            Map<String, Segment> byId,
            boolean directMovement,
            double minGapMeters,
            double searchWindowMeters
    ) {
        if (rawNotice == null || !rawNotice.exactNoticeFound) return rawNotice;

        double adjustedPk        = rawNotice.pk;
        String adjustedSegmentId = rawNotice.segmentId;

        log("Aviso teórico inicial: seg=" + rawNotice.segmentId
                + " pk=" + rawNotice.pk
                + " sentido=" + (directMovement ? "DIRECTA" : "INVERSA"));

        for (int guard = 0; guard < 50; guard++) {
            log(String.format("Iteración %d, ventana [%.2f, %.2f]",
                    guard + 1, adjustedPk - searchWindowMeters, adjustedPk + searchWindowMeters));

            // Buscar BGs cercanos
            List<BaliseRef> nearby = findNearbyBalises(adjustedPk, branchPath, byId, searchWindowMeters);
            logNearby(nearby, adjustedPk, directMovement);

            // Buscar conflicto más cercano
            BaliseRef conflict = findNearestConflict(nearby, adjustedPk, minGapMeters);

            if (conflict == null) {
                log("No hay conflicto. Aviso válido en seg=" + adjustedSegmentId + " pk=" + adjustedPk);
                return new BrakingNoticeResult(adjustedSegmentId, adjustedPk,
                        rawNotice.noticeSpeedKmh, rawNotice.targetSpeedKmh,
                        rawNotice.totalDistanceMeters, rawNotice.usedIntervals, true);
            }

            log(String.format("CONFLICTO con BG=%s pk=%.2f distancia=%.2f < %.2f",
                    conflict.groupId, conflict.pk, Math.abs(adjustedPk - conflict.pk), minGapMeters));

            // Desplazar alejándose de la LTV
            adjustedPk = directMovement
                    ? conflict.pk - minGapMeters
                    : conflict.pk + minGapMeters;

            log(String.format("Aviso desplazado a pk=%.2f", adjustedPk));

            // Buscar segmento que contenga el nuevo PK
            Segment newSeg = findSegmentInBranchOrNeighbor(byId, branchPath, adjustedPk, directMovement);

            if (newSeg == null) {
                log("ERROR: el aviso ajustado no cae en ningún segmento accesible.");
                return new BrakingNoticeResult(rawNotice.segmentId, rawNotice.pk,
                        rawNotice.noticeSpeedKmh, rawNotice.targetSpeedKmh,
                        rawNotice.totalDistanceMeters, rawNotice.usedIntervals, false);
            }

            adjustedSegmentId = newSeg.id;
            log("Nuevo segmento del aviso: " + adjustedSegmentId);
        }

        log("Se alcanzó el máximo de iteraciones de ajuste.");
        return rawNotice;
    }

    // -------------------------------------------------------------------------
    // Búsqueda de balizas
    // -------------------------------------------------------------------------

    /**
     * Devuelve todos los BGs dentro de la ventana de búsqueda.
     * Incluye los segmentos de la rama y los vecinos del último segmento
     * en dirección away (para detectar BGs justo después del tramo).
     */
    private static List<BaliseRef> findNearbyBalises(
            double noticePk,
            List<String> branchPath,
            Map<String, Segment> byId,
            double searchWindowMeters
    ) {
        List<BaliseRef> refs = new ArrayList<>();
        if (branchPath == null || branchPath.isEmpty()) return refs;

        double windowMin = noticePk - searchWindowMeters;
        double windowMax = noticePk + searchWindowMeters;

        // Segmentos a inspeccionar: la rama + vecinos away del último segmento
        List<String> toCheck = new ArrayList<>(branchPath);
        String lastSegId = branchPath.get(branchPath.size() - 1);
        Segment lastSeg  = byId.get(lastSegId);
        if (lastSeg != null) {
            // DIRECTA: away = PK decreciente = inverseNeighbors
            // INVERSA: away = PK creciente   = directNeighbors
            // Añadimos ambos para no depender del sentido aquí
            for (String nId : lastSeg.directNeighbors)  { if (nId != null && !toCheck.contains(nId)) toCheck.add(nId); }
            for (String nId : lastSeg.inverseNeighbors) { if (nId != null && !toCheck.contains(nId)) toCheck.add(nId); }
        }

        for (String segId : toCheck) {
            Segment s = byId.get(segId);
            if (s == null || s.maxPK() < windowMin || s.minPK() > windowMax) continue;

            for (BaliseData.BaliseGroup bg : s.getBaliseGroups()) {
                // Usamos getLastPkByNdx: ndx mayor = primera baliza que ve el tren
                Double refPk = bg.getLastPkByNdx(byId);
                if (refPk == null || refPk < windowMin || refPk > windowMax) continue;
                refs.add(new BaliseRef(bg.id, s.id, refPk));
            }
        }
        return refs;
    }

    /** De los BGs cercanos, devuelve el que está a menos de minGapMeters del aviso. */
    private static BaliseRef findNearestConflict(
            List<BaliseRef> nearby, double noticePk, double minGapMeters
    ) {
        BaliseRef best     = null;
        double bestDelta   = Double.POSITIVE_INFINITY;

        for (BaliseRef ref : nearby) {
            double delta = Math.abs(noticePk - ref.pk);
            if (delta < minGapMeters && delta < bestDelta) {
                bestDelta = delta;
                best = ref;
            }
        }
        return best;
    }

    // -------------------------------------------------------------------------
    // Búsqueda de segmento por PK
    // -------------------------------------------------------------------------

    /**
     * Busca el segmento que contiene el PK dado, primero en la rama
     * y luego en los vecinos del último segmento en dirección away.
     */
    private static Segment findSegmentInBranchOrNeighbor(
            Map<String, Segment> byId,
            List<String> branchPath,
            double pk,
            boolean directMovement
    ) {
        // Buscar en la rama
        for (String segId : branchPath) {
            Segment s = byId.get(segId);
            if (s != null && s.containsPK(pk)) return s;
        }

        // Buscar en vecinos del último segmento en dirección away
        if (branchPath.isEmpty()) return null;
        Segment lastSeg = byId.get(branchPath.get(branchPath.size() - 1));
        if (lastSeg == null) return null;

        // DIRECTA: away = PK decreciente = inverseNeighbors
        // INVERSA: away = PK creciente   = directNeighbors
        List<String> awayNeighbors = directMovement ? lastSeg.inverseNeighbors : lastSeg.directNeighbors;
        for (String nId : awayNeighbors) {
            if (nId == null) continue;
            Segment neighbor = byId.get(nId);
            if (neighbor != null && neighbor.containsPK(pk)) {
                log("Aviso extendido al segmento vecino: " + nId);
                return neighbor;
            }
        }
        return null;
    }

    // -------------------------------------------------------------------------
    // Logging
    // -------------------------------------------------------------------------

    private static void log(String msg) {
        if (debugEnabled) System.out.println("    [BG] " + msg);
    }

    private static void logNearby(List<BaliseRef> nearby, double adjustedPk, boolean directMovement) {
        if (!debugEnabled) return;
        if (nearby.isEmpty()) {
            log("No hay BG cercanos.");
        } else {
            log("BG cercanos:");
            for (BaliseRef ref : nearby) {
                double delta = directMovement ? (adjustedPk - ref.pk) : (ref.pk - adjustedPk);
                System.out.printf("         BG=%s seg=%s pk=%.2f delta=%.2f%n",
                        ref.groupId, ref.segmentId, ref.pk, delta);
            }
        }
    }

    // -------------------------------------------------------------------------
    // Clase interna: referencia a un grupo de balizas
    // -------------------------------------------------------------------------

    /** Referencia a un grupo de balizas con su PK de referencia. */
    public static class BaliseRef {
        public final String groupId;
        public final String segmentId;
        public final double pk;

        public BaliseRef(String groupId, String segmentId, double pk) {
            this.groupId   = groupId;
            this.segmentId = segmentId;
            this.pk        = pk;
        }
    }
}
