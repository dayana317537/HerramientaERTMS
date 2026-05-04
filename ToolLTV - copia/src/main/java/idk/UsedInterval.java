package idk;

public class UsedInterval {
    public String segmentId;
    public double pkFrom;
    public double pkTo;
    public double speedKmh;

    public UsedInterval(String segmentId, double pkFrom, double pkTo, double speedKmh) {
        this.segmentId = segmentId;
        this.pkFrom = pkFrom;
        this.pkTo = pkTo;
        this.speedKmh = speedKmh;
    }

    public double lengthMeters() {
        return Math.abs(pkTo - pkFrom);
    }
}