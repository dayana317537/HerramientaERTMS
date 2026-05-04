package idk;

public class ApproachInterval {
    public String segmentId;
    public double pkFrom;
    public double pkTo;
    public double pkTargetSide;
    public double pkAwaySide;
    public double speedKmh;

    public ApproachInterval(String segmentId, double pkFrom, double pkTo,
                            double pkTargetSide, double pkAwaySide, double speedKmh) {
        this.segmentId = segmentId;
        this.pkFrom = pkFrom;
        this.pkTo = pkTo;
        this.pkTargetSide = pkTargetSide;
        this.pkAwaySide = pkAwaySide;
        this.speedKmh = speedKmh;
    }

    public double lengthMeters() {
        return Math.abs(pkTo - pkFrom);
    }
}