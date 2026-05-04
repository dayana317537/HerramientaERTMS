package idk;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

public class BaliseData {

    private final Map<String, Balise> balisesById = new LinkedHashMap<>();
    private final Map<String, BaliseGroup> groupsById = new LinkedHashMap<>();

    public void addBalise(Balise balise) {
        if (balise == null || balise.id == null) return;
        balisesById.put(balise.id, balise);
    }

    public void addGroup(BaliseGroup group) {
        if (group == null || group.id == null) return;
        groupsById.put(group.id, group);
    }

    public Map<String, Balise> getBalisesById() {
        return balisesById;
    }

    public Map<String, BaliseGroup> getGroupsById() {
        return groupsById;
    }

    public List<Balise> getAllBalises() {
        return new ArrayList<>(balisesById.values());
    }

    public List<BaliseGroup> getAllGroups() {
        return new ArrayList<>(groupsById.values());
    }

//    public void resolveGroups(double segmentMinPk) {
//        for (BaliseGroup group : groupsById.values()) {
//            group.resolveFromRefs(balisesById, segmentMinPk);
//        }
//    }
    public void resolveGroups(Map<String, Segment> byId) {
        for (BaliseGroup group : groupsById.values()) {
            group.resolveFromRefs(balisesById, byId);
        }
    }

    public static class Balise {
        public final String id;
        public final String name;
        public final String dir;
        public final Integer ndx;
        public final Double pos;       // relativa al segmento
        public final String segmentId; // nuevo

        public Balise(String id, String name, String dir, Integer ndx, Double pos, String segmentId) {
            this.id = id;
            this.name = name;
            this.dir = dir;
            this.ndx = ndx;
            this.pos = pos;
            this.segmentId = segmentId;
        }

        public Double absolutePk(Map<String, Segment> byId) {
            if (pos == null || segmentId == null || byId == null) return null;
            Segment s = byId.get(segmentId);
            if (s == null) return null;
            return s.minPK() + pos;
        }

        @Override
        public String toString() {
            return "Balise{id='" + id + "', name='" + name + "', dir='" + dir
                    + "', ndx=" + ndx + ", pos=" + pos + ", segmentId='" + segmentId + "'}";
        }
    }


    public static class BaliseGroup {
        public final String id;
        public final String name;
        public final List<String> baliseRefs = new ArrayList<>();

        // Resuelto después
        public final List<Balise> balises = new ArrayList<>();

        public Double minPos = null;
        public Double maxPos = null;
        public Double minPk = null;
        public Double maxPk = null;

        // Según lo que me has dicho:
        // última baliza = la de ndx mayor
        // primera baliza = la de ndx menor
        public Balise firstByNdx = null;
        public Balise lastByNdx = null;

        public BaliseGroup(String id, String name) {
            this.id = id;
            this.name = name;
        }

        public void addBaliseRef(String ref) {
            if (ref == null || ref.isBlank()) return;
            baliseRefs.add(ref);
        }

        public void resolveFromRefs(Map<String, Balise> balisesById, Map<String, Segment> byId) {
            balises.clear();
            minPos = null;
            maxPos = null;
            minPk = null;
            maxPk = null;
            firstByNdx = null;
            lastByNdx = null;

            for (String ref : baliseRefs) {
                Balise b = balisesById.get(ref);
                if (b == null) continue;
                balises.add(b);

                if (b.pos != null) {
                    if (minPos == null || b.pos < minPos) minPos = b.pos;
                    if (maxPos == null || b.pos > maxPos) maxPos = b.pos;

                    Double absPk = b.absolutePk(byId);
                    if (absPk != null) {
                        if (minPk == null || absPk < minPk) minPk = absPk;
                        if (maxPk == null || absPk > maxPk) maxPk = absPk;
                    }
                }

                if (b.ndx != null) {
                    if (firstByNdx == null || b.ndx < firstByNdx.ndx) {
                        firstByNdx = b;
                    }
                    if (lastByNdx == null || b.ndx > lastByNdx.ndx) {
                        lastByNdx = b;
                    }
                }
            }
        }

        public Double getFirstPkByNdx(Map<String, Segment> byId) {
            return firstByNdx == null ? null : firstByNdx.absolutePk(byId);
        }

        public Double getLastPkByNdx(Map<String, Segment> byId) {
            return lastByNdx == null ? null : lastByNdx.absolutePk(byId);
        }

        @Override
        public String toString() {
            return "BaliseGroup{id='" + id + "', name='" + name
                    + "', refs=" + baliseRefs.size()
                    + ", balises=" + balises.size()
                    + ", minPos=" + minPos
                    + ", maxPos=" + maxPos
                    + ", minPk=" + minPk
                    + ", maxPk=" + maxPk
                    + ", firstByNdx=" + (firstByNdx != null ? firstByNdx.name : null)
                    + ", lastByNdx=" + (lastByNdx != null ? lastByNdx.name : null)
                    + "}";
        }
    }
}