package idk;

import java.util.ArrayList;
import java.util.List;

public class Segment {

    public final String id;
    public final String name;

    public Double beginAbsPos = null;
    public Double endAbsPos = null;

    // PK creciente
    public final List<String> directNeighbors = new ArrayList<>();

    // PK decreciente
    public final List<String> inverseNeighbors = new ArrayList<>();

    public Segment(String id, String name) {
        this.id = id;
        this.name = name;
    }

    public double minPK() {
        if (beginAbsPos == null || endAbsPos == null) return Double.NaN;
        return Math.min(beginAbsPos, endAbsPos);
    }

    public double maxPK() {
        if (beginAbsPos == null || endAbsPos == null) return Double.NaN;
        return Math.max(beginAbsPos, endAbsPos);
    }

    public double length() {
        if (beginAbsPos == null || endAbsPos == null) return 0.0;
        return Math.abs(endAbsPos - beginAbsPos);
    }

    public boolean containsPK(double pk) {
        if (beginAbsPos == null || endAbsPos == null) return false;
        return pk >= minPK() && pk <= maxPK();
    }

    public void addDirectNeighbor(String id) {
        if (id == null) return;
        if (!directNeighbors.contains(id)) directNeighbors.add(id);
    }

    public void addInverseNeighbor(String id) {
        if (id == null) return;
        if (!inverseNeighbors.contains(id)) inverseNeighbors.add(id);
    }

    public static String neighborIdFromRef(String ref) {
        if (ref == null) return null;
        int idx = ref.indexOf("_connection_");
        if (idx <= 0) return null;
        return ref.substring(0, idx);
    }

    @Override
    public String toString() {
        return "Seg{id='" + id + "', name='" + name + "', begin=" + beginAbsPos
                + ", end=" + endAbsPos + ", len=" + length()
                + ", direct=" + directNeighbors.size()
                + ", inverse=" + inverseNeighbors.size() + "}";
    }
}