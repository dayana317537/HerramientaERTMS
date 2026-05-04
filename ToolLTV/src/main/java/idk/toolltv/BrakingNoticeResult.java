package idk.toolltv;

import idk.toolltv.UsedInterval;

import java.util.List;

/**
 * Resultado del cálculo del punto de aviso de frenado.
 * Contiene el PK donde debe colocarse el aviso, la velocidad en ese punto,
 * la distancia de frenado necesaria, y los tramos usados para el cálculo.
 */
public class BrakingNoticeResult {

    public final String            segmentId;
    public final double            pk;
    public final double            noticeSpeedKmh;
    public final double            targetSpeedKmh;
    public final double            totalDistanceMeters;
    public final List<UsedInterval> usedIntervals;
    public final boolean           exactNoticeFound;

    public BrakingNoticeResult(
            String segmentId,
            double pk,
            double noticeSpeedKmh,
            double targetSpeedKmh,
            double totalDistanceMeters,
            List<UsedInterval> usedIntervals,
            boolean exactNoticeFound
    ) {
        this.segmentId           = segmentId;
        this.pk                  = pk;
        this.noticeSpeedKmh      = noticeSpeedKmh;
        this.targetSpeedKmh      = targetSpeedKmh;
        this.totalDistanceMeters = totalDistanceMeters;
        this.usedIntervals       = usedIntervals;
        this.exactNoticeFound    = exactNoticeFound;
    }
}
