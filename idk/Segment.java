package idk;

import java.util.ArrayList;
import java.util.List;

public class Segment {
    public final String id;
    public final String name;

    public Double beginAbsPos = null;
    public Double endAbsPos = null;

    public final List<Edge> outEdges = new ArrayList<>();

    public Segment(String id, String name) {
        this.id = id;
        this.name = name;
    }

    public double length() {
        if (beginAbsPos == null || endAbsPos == null) return 0.0;
        return Math.abs(endAbsPos - beginAbsPos);
    }

    public double minPos() {
        if (beginAbsPos == null || endAbsPos == null) return Double.NaN;
        return Math.min(beginAbsPos, endAbsPos);
    }

    public double maxPos() {
        if (beginAbsPos == null || endAbsPos == null) return Double.NaN;
        return Math.max(beginAbsPos, endAbsPos);
    }

    public boolean containsPk(double pk) {
        if (beginAbsPos == null || endAbsPos == null) return false;
        return pk >= minPos() && pk <= maxPos();
    }

    public double distancePkToBegin(double pk) {
        if (beginAbsPos == null) return Double.POSITIVE_INFINITY;
        return Math.abs(pk - beginAbsPos);
    }

    public double distancePkToEnd(double pk) {
        if (endAbsPos == null) return Double.POSITIVE_INFINITY;
        return Math.abs(pk - endAbsPos);
    }

    /** Extrae "SegX" desde "SegX_connection_connectionBegin/End..." */
    public static String neighborIdFromRef(String ref) {
        if (ref == null) return null;
        int idx = ref.indexOf("_connection_");
        if (idx <= 0) return null;
        return ref.substring(0, idx);
    }

    /** Del ref sacamos si el destino es Begin o End */
    public static Side toSideFromRef(String ref) {
        if (ref == null) return Side.UNKNOWN;
        String low = ref.toLowerCase();
        if (low.endsWith("connectionbegin")) return Side.BEGIN;
        if (low.endsWith("connectionend")) return Side.END;
        return Side.UNKNOWN;
    }

    @Override
    public String toString() {
        return "Seg{id='" + id + "', begin=" + beginAbsPos + ", end=" + endAbsPos +
                ", len=" + length() + ", out=" + outEdges.size() + "}";
    }

    public enum Side { BEGIN, END, UNKNOWN }

    public static class Edge {
        public final String toId;
        public final Side fromSide; // por qué lado sales del segmento actual
        public final Side toSide;   // por qué lado entras al segmento destino

        public Edge(String toId, Side fromSide, Side toSide) {
            this.toId = toId;
            this.fromSide = fromSide;
            this.toSide = toSide;
        }

        @Override
        public String toString() {
            return toId + "(" + fromSide + "->" + toSide + ")";
        }
    }
}