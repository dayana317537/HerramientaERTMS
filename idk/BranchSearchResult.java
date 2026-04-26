package idk;
import java.util.List;

public class BranchSearchResult {
    public List<String> path;
    public double backwardDistanceMeters;
    public BrakingNoticeResult notice;
    public boolean exactNoticeFound;
    public String reason;

    public BranchSearchResult(List<String> path, double backwardDistanceMeters,
                              BrakingNoticeResult notice, boolean exactNoticeFound, String reason) {
        this.path = path;
        this.backwardDistanceMeters = backwardDistanceMeters;
        this.notice = notice;
        this.exactNoticeFound = exactNoticeFound;
        this.reason = reason;
    }
}
