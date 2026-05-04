package idk.toolltv;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

public class SignalData {

    private final Map<String, Signal> signalsById = new LinkedHashMap<>();

    public void addSignal(Signal signal) {
        if (signal == null || signal.id == null) return;
        signalsById.put(signal.id, signal);
    }

    public Map<String, Signal> getSignalsById() {
        return signalsById;
    }

    public List<Signal> getAllSignals() {
        return new ArrayList<>(signalsById.values());
    }

    // -------------------------------------------------------
    // Clase interna Signal
    // -------------------------------------------------------
    public static class Signal {
        public final String id;
        public final String name;
        public final String dir;       // "up" o "down"
        public final Double pos;       // posición relativa al segmento
        public final String segmentId;

        public Signal(String id, String name, String dir, Double pos, String segmentId) {
            this.id        = id;
            this.name      = name;
            this.dir       = dir;
            this.pos       = pos;
            this.segmentId = segmentId;
        }

        /**
         * PK absoluto de la señal = minPK del segmento + pos relativa
         */
        public Double absolutePk(Map<String, Segment> byId) {
            if (pos == null || segmentId == null || byId == null) return null;
            Segment s = byId.get(segmentId);
            if (s == null) return null;
            return s.minPK() + pos;
        }

        /**
         * ¿Esta señal aplica al movimiento indicado?
         * dir="up"   → directMovement=true  (tren hacia PK creciente)
         * dir="down" → directMovement=false (tren hacia PK decreciente)
         */
        public boolean appliesToDirection(boolean directMovement) {
            if (dir == null) return false;
            return directMovement ? "up".equalsIgnoreCase(dir)
                                  : "down".equalsIgnoreCase(dir);
        }

        @Override
        public String toString() {
            return "Signal{id='" + id + "', name='" + name + "', dir='" + dir
                    + "', pos=" + pos + ", segmentId='" + segmentId + "'}";
        }
    }
}
