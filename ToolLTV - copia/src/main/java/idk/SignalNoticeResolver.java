package idk;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * SignalNoticeResolver
 *
 * Dado un path de segmentos (rama de búsqueda) y una dirección de movimiento,
 * busca si existe alguna señal en ese path que aplique a esa dirección.
 * Si la hay, devuelve el PK absoluto de esa señal como punto de aviso.
 *
 * Regla:
 *   - Se recorre el path desde el segmento más cercano a la LTV hacia atrás.
 *   - Se toma la primera señal encontrada que aplique a la dirección del tren.
 *   - El aviso se coloca exactamente en el PK de esa señal.
 */
public class SignalNoticeResolver {

    /**
     * Resultado de la búsqueda de señal en el path.
     */
    public static class SignalNoticeResult {
        public final boolean found;
        public final String  segmentId;
        public final double  pk;
        public final String  signalName;
        public final String  signalId;

        public SignalNoticeResult(boolean found, String segmentId,
                                  double pk, String signalName, String signalId) {
            this.found      = found;
            this.segmentId  = segmentId;
            this.pk         = pk;
            this.signalName = signalName;
            this.signalId   = signalId;
        }

        /** Sin señal encontrada. */
        public static SignalNoticeResult notFound() {
            return new SignalNoticeResult(false, null, Double.NaN, null, null);
        }
    }

    /**
     * Busca la señal más cercana a la LTV en el path que aplique a la dirección dada.
     *
     * @param path            Lista de segmentIds ordenada desde la LTV hacia atrás
     *                        (igual que branch.path en Main).
     * @param byId            Mapa global de segmentos.
     * @param directMovement  true = tren va hacia PK creciente (dir "up"),
     *                        false = tren va hacia PK decreciente (dir "down").
     * @param pkLtv           PK de la LTV (para filtrar señales que estén entre
     *                        el inicio del path y la LTV).
     * @return SignalNoticeResult con el PK de la señal o notFound().
     */
    public static SignalNoticeResult findSignalInPath(
            List<String> path,
            Map<String, Segment> byId,
            boolean directMovement,
            double pkLtv
    ) {
        if (path == null || path.isEmpty()) return SignalNoticeResult.notFound();

        // Recorremos el path desde el segmento más cercano a la LTV (índice 0)
        // hacia el más alejado (último índice).
        // Dentro de cada segmento, buscamos la señal más cercana a la LTV
        // en dirección away (la primera que encontraría el tren al aproximarse).

        for (int i = 0; i < path.size(); i++) {
            String segId = path.get(i);
            Segment seg  = byId.get(segId);
            if (seg == null) continue;

            List<SignalData.Signal> candidates = new ArrayList<>();

            for (SignalData.Signal sig : seg.getSignals()) {
                if (!sig.appliesToDirection(directMovement)) continue;

                Double absPk = sig.absolutePk(byId);
                if (absPk == null) continue;

                // En el primer segmento (el de la LTV), solo señales que estén
                // en la parte del segmento recorrida antes de la LTV.
                if (i == 0) {
                    if (directMovement && absPk >= pkLtv) continue; // ya pasó la LTV
                    if (!directMovement && absPk <= pkLtv) continue;
                }

                candidates.add(sig);
            }

            if (candidates.isEmpty()) continue;

            // De los candidatos, elegimos la señal MÁS CERCANA a la LTV
            // (la que el tren encuentra primero al aproximarse).
            SignalData.Signal best = null;
            double bestPk = Double.NaN;

            for (SignalData.Signal sig : candidates) {
                Double absPk = sig.absolutePk(byId);
                if (absPk == null) continue;

                if (best == null) {
                    best   = sig;
                    bestPk = absPk;
                } else {
                    // DIRECTA: tren viene de PK menor → señal más cercana = mayor PK
                    // INVERSA: tren viene de PK mayor → señal más cercana = menor PK
                    if (directMovement  && absPk > bestPk) { best = sig; bestPk = absPk; }
                    if (!directMovement && absPk < bestPk) { best = sig; bestPk = absPk; }
                }
            }

            if (best != null) {
                return new SignalNoticeResult(true, segId, bestPk, best.name, best.id);
            }
        }

        return SignalNoticeResult.notFound();
    }
}
