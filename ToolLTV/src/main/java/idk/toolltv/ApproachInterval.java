package idk.toolltv;

/**
 * Intervalo de velocidad constante en la vía, visto desde la LTV hacia atrás.
 * pkTargetSide es el extremo más cercano a la LTV.
 * pkAwaySide   es el extremo más alejado de la LTV.
 */
public class ApproachInterval {

    public final String segmentId;
    public final double pkFrom;
    public final double pkTo;
    public final double pkTargetSide;
    public final double pkAwaySide;
    public final double speedKmh;

    public ApproachInterval(
            String segmentId,
            double pkFrom,
            double pkTo,
            double pkTargetSide,
            double pkAwaySide,
            double speedKmh
    ) {
        this.segmentId    = segmentId;
        this.pkFrom       = pkFrom;
        this.pkTo         = pkTo;
        this.pkTargetSide = pkTargetSide;
        this.pkAwaySide   = pkAwaySide;
        this.speedKmh     = speedKmh;
    }

    public double lengthMeters() {
        return Math.abs(pkTo - pkFrom);
    }
}
