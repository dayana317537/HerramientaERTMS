package idk;
import java.util.List;

public class BrakingNoticeResult {
    public String segmentId;
    public double pk;
    public double noticeSpeedKmh;
    public double targetSpeedKmh;
    public double totalDistanceMeters;
    public List<UsedInterval> usedIntervals;
    public boolean exactNoticeFound;

    public BrakingNoticeResult(String segmentId, double pk, double noticeSpeedKmh,
                               double targetSpeedKmh, double totalDistanceMeters,
                               List<UsedInterval> usedIntervals, boolean exactNoticeFound) {
        this.segmentId = segmentId;
        this.pk = pk;
        this.noticeSpeedKmh = noticeSpeedKmh;
        this.targetSpeedKmh = targetSpeedKmh;
        this.totalDistanceMeters = totalDistanceMeters;
        this.usedIntervals = usedIntervals;
        this.exactNoticeFound = exactNoticeFound;
    }
}