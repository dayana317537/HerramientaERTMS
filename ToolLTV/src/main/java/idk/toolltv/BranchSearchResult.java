package idk.toolltv;

import java.util.List;

/**
 * Resultado de explorar una rama de segmentos hacia atrás desde la LTV.
 * Contiene el camino recorrido, la distancia explorada, el aviso calculado
 * y el motivo por el que se detuvo la búsqueda.
 */
public class BranchSearchResult {

    public final List<String>        path;
    public final double              backwardDistanceMeters;
    public final BrakingNoticeResult notice;
    public final boolean             exactNoticeFound;
    public final String              reason;

    public BranchSearchResult(
            List<String> path,
            double backwardDistanceMeters,
            BrakingNoticeResult notice,
            boolean exactNoticeFound,
            String reason
    ) {
        this.path                   = path;
        this.backwardDistanceMeters = backwardDistanceMeters;
        this.notice                 = notice;
        this.exactNoticeFound       = exactNoticeFound;
        this.reason                 = reason;
    }
}
