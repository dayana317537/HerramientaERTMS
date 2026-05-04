package idk.toolltv;

/**
 * Tramo de velocidad constante que el tren recorre entre el aviso y la LTV.
 * Se usa para mostrar el desglose del cálculo de frenado.
 */
public class UsedInterval {

    public final String segmentId;
    public final double pkFrom;
    public final double pkTo;
    public final double speedKmh;

    public UsedInterval(String segmentId, double pkFrom, double pkTo, double speedKmh) {
        this.segmentId = segmentId;
        this.pkFrom    = pkFrom;
        this.pkTo      = pkTo;
        this.speedKmh  = speedKmh;
    }

    public double lengthMeters() {
        return Math.abs(pkTo - pkFrom);
    }
}
