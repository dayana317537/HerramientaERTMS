package idk;

import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
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

    // Resumen de velocidades
    public Double mostRestrictiveSpeed = null;
    public Double mostRestrictiveSpeedUp = null;
    public Double mostRestrictiveSpeedDown = null;

    // Perfil completo por sentido
    public final List<SpeedInterval> speedProfileUp = new ArrayList<>();
    public final List<SpeedInterval> speedProfileDown = new ArrayList<>();

    // Puntos raw leídos del XML
    private final List<SpeedPoint> rawSpeedPointsUp = new ArrayList<>();
    private final List<SpeedPoint> rawSpeedPointsDown = new ArrayList<>();

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

    public void addSpeedChange(String dir, Double pos, Double vMax) {
        if (dir == null || pos == null || vMax == null) return;

        SpeedPoint p = new SpeedPoint(pos, vMax);

        if ("up".equalsIgnoreCase(dir)) {
            rawSpeedPointsUp.add(p);
        } else if ("down".equalsIgnoreCase(dir)) {
            rawSpeedPointsDown.add(p);
        }
    }

    public void finalizeSpeedProfile() {
        speedProfileUp.clear();
        speedProfileDown.clear();

        buildProfile(rawSpeedPointsUp, speedProfileUp);
        buildProfile(rawSpeedPointsDown, speedProfileDown);

        mostRestrictiveSpeedUp = calcMostRestrictive(speedProfileUp);
        mostRestrictiveSpeedDown = calcMostRestrictive(speedProfileDown);
        mostRestrictiveSpeed = minNullable(mostRestrictiveSpeedUp, mostRestrictiveSpeedDown);
    }

    private void buildProfile(List<SpeedPoint> rawPoints, List<SpeedInterval> out) {
        double len = length();
        if (len <= 0.0 || rawPoints.isEmpty()) return;

        List<SpeedPoint> pts = new ArrayList<>(rawPoints);
        pts.sort(Comparator.comparingDouble(a -> a.pos));

        List<SpeedPoint> cleaned = new ArrayList<>();
        for (SpeedPoint p : pts) {
            if (p.pos < 0) continue;
            if (p.pos > len) continue;

            if (!cleaned.isEmpty() && Math.abs(cleaned.get(cleaned.size() - 1).pos - p.pos) < 1e-9) {
                cleaned.set(cleaned.size() - 1, p);
            } else {
                cleaned.add(p);
            }
        }

        if (cleaned.isEmpty()) return;

        if (cleaned.get(0).pos > 0.0) {
            cleaned.add(0, new SpeedPoint(0.0, cleaned.get(0).vMax));
        }

        for (int i = 0; i < cleaned.size(); i++) {
            SpeedPoint current = cleaned.get(i);
            double from = current.pos;
            double to = (i + 1 < cleaned.size()) ? cleaned.get(i + 1).pos : len;

            if (to < from) continue;
            if (Math.abs(to - from) < 1e-9) continue;

            out.add(new SpeedInterval(from, to, current.vMax));
        }
    }

    private Double calcMostRestrictive(List<SpeedInterval> profile) {
        Double min = null;
        for (SpeedInterval s : profile) {
            if (min == null || s.vMax < min) {
                min = s.vMax;
            }
        }
        return min;
    }

    private Double minNullable(Double a, Double b) {
        if (a == null) return b;
        if (b == null) return a;
        return Math.min(a, b);
    }

    public static String neighborIdFromRef(String ref) {
        if (ref == null) return null;
        int idx = ref.indexOf("_connection_");
        if (idx <= 0) return null;
        return ref.substring(0, idx);
    }

    public List<SpeedInterval> getSpeedProfile(String dir) {
        if ("down".equalsIgnoreCase(dir)) return Collections.unmodifiableList(speedProfileDown);
        return Collections.unmodifiableList(speedProfileUp);
    }

    @Override
    public String toString() {
        return "Seg{id='" + id + "', name='" + name + "', begin=" + beginAbsPos
                + ", end=" + endAbsPos + ", len=" + length()
                + ", vRestr=" + mostRestrictiveSpeed
                + ", direct=" + directNeighbors.size()
                + ", inverse=" + inverseNeighbors.size() + "}";
    }

    public static class SpeedPoint {
        public final double pos;
        public final double vMax;

        public SpeedPoint(double pos, double vMax) {
            this.pos = pos;
            this.vMax = vMax;
        }

        @Override
        public String toString() {
            return String.format("[pos=%.2f, vMax=%.2f]", pos, vMax);
        }
    }

    public static class SpeedInterval {
        public final double fromPos;
        public final double toPos;
        public final double vMax;

        public SpeedInterval(double fromPos, double toPos, double vMax) {
            this.fromPos = fromPos;
            this.toPos = toPos;
            this.vMax = vMax;
        }

        public double length() {
            return Math.max(0.0, toPos - fromPos);
        }

        @Override
        public String toString() {
            return String.format("[%.2f -> %.2f : %.2f km/h]", fromPos, toPos, vMax);
        }
    }
}