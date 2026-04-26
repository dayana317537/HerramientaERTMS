package idk;

import java.io.IOException;
import java.io.PrintWriter;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/**
 * TelegramGenerator
 *
 * Genera los telegramas ETCS (paquete 65 - TSR) para cada aviso de LTV calculado.
 *
 * Por cada aviso se crea un grupo de balizas (BG) con dos balizas (N_PIG=0 y N_PIG=1).
 * Todos los avisos de la misma LTV comparten el mismo NID_TSR.
 *
 * Parámetros configurables:
 *   - M_VERSION : versión ERTMS (ej: 33 = Baseline 3 / ERTMS 2.3.0)
 *   - NID_C     : identificador de país/operador extraído del proyecto
 */
public class TelegramGenerator {

    // -------------------------------------------------------------------------
    // Configuración global (editable)
    // -------------------------------------------------------------------------

    /** Versión ERTMS del telegrama */
    public static double M_VERSION = 2.3;

    /** NID_C del proyecto (identificador de país/operador) */
    public static int NID_C = 920;

    /** Contador interno de NID_TSR (no puede ser 255) */
    private static int nextNidTsr = 1;

    // -------------------------------------------------------------------------
    // Modelos de salida
    // -------------------------------------------------------------------------

    /** Aviso individual con su telegrama generado. */
    public static class NoticeEntry {
        public final String  baliseName;
        public final double  distanceMeters;
        public final double  pk;
        public final String  via;
        public final String  telegramText;
        public final boolean isSignalNotice;

        public NoticeEntry(String baliseName, double distanceMeters, double pk,
                           String telegramText, boolean isSignalNotice) {
            this.baliseName     = baliseName;
            this.distanceMeters = distanceMeters;
            this.pk             = pk;
            this.via            = "";
            this.telegramText   = telegramText;
            this.isSignalNotice = isSignalNotice;
        }
    }

    /** Conjunto de todos los avisos de una LTV con su NID_TSR común. */
    public static class LtvTelegramSet {
        public final int               nidTsr;
        public final double            ltvPk;
        public final double            ltvSpeedKmh;
        public final double            ltvLengthMeters;
        public final double            ltvMargin;
        public final List<NoticeEntry> entries = new ArrayList<>();

        public LtvTelegramSet(int nidTsr, double ltvPk, double ltvSpeedKmh,
                              double ltvLengthMeters, double ltvMargin) {
            this.nidTsr          = nidTsr;
            this.ltvPk           = ltvPk;
            this.ltvSpeedKmh     = ltvSpeedKmh;
            this.ltvLengthMeters = ltvLengthMeters;
            this.ltvMargin       = ltvMargin;
        }
    }

    // -------------------------------------------------------------------------
    // Punto de entrada principal
    // -------------------------------------------------------------------------

    /**
     * Genera todos los telegramas para una LTV.
     *
     * @param brakingBranches       Ramas calculadas (DIRECTA + INVERSA ya ajustadas)
     * @param signalsDirect         Avisos de señal en DIRECTA
     * @param signalsInverse        Avisos de señal en INVERSA
     * @param pkLtv                 PK de inicio de la LTV
     * @param pkDirect              PK efectivo DIRECTA (pkLtv - margen)
     * @param pkInverse             PK efectivo INVERSA (pkLtv + longitud + margen)
     * @param ltvSpeedKmh           Velocidad objetivo
     * @param ltvLengthMeters       Longitud de la LTV
     * @param ltvMargin             Margen aplicado a cada extremo
     * @param existingNidBgs        NID_BG ya usados en el proyecto
     */
    public static LtvTelegramSet generate(
            List<BranchSearchResult> brakingBranches,
            List<SignalNoticeResolver.SignalNoticeResult> signalsDirect,
            List<SignalNoticeResolver.SignalNoticeResult> signalsInverse,
            double pkLtv,
            double pkDirect,
            double pkInverse,
            double ltvSpeedKmh,
            double ltvLengthMeters,
            double ltvMargin,
            Set<Integer> existingNidBgs
    ) {
        int    nidTsr = assignNidTsr();
        double lTsr   = ltvLengthMeters + ltvMargin * 2;

        LtvTelegramSet set     = new LtvTelegramSet(nidTsr, pkLtv, ltvSpeedKmh, ltvLengthMeters, ltvMargin);
        Set<Integer>   usedBgs = new HashSet<>(existingNidBgs);

        // Avisos de frenado
        for (BranchSearchResult branch : brakingBranches) {
            if (branch.notice == null || !branch.notice.exactNoticeFound) continue;
            BrakingNoticeResult notice = branch.notice;

            boolean isDirectBranch = notice.pk < pkLtv;
            double  pkRef          = isDirectBranch ? pkDirect : pkInverse;
            double  dTsr           = Math.abs(notice.pk - pkRef);

            addEntry(set, usedBgs, nidTsr, notice.pk, notice.totalDistanceMeters,
                    dTsr, lTsr, ltvSpeedKmh, isDirectBranch, false);
        }

        // Avisos de señal DIRECTA
        for (SignalNoticeResolver.SignalNoticeResult sig : signalsDirect) {
            if (!sig.found) continue;
            double dTsr = Math.abs(sig.pk - pkDirect);
            addEntry(set, usedBgs, nidTsr, sig.pk, dTsr, dTsr, lTsr, ltvSpeedKmh, true, true);
        }

        // Avisos de señal INVERSA
        for (SignalNoticeResolver.SignalNoticeResult sig : signalsInverse) {
            if (!sig.found) continue;
            double dTsr = Math.abs(sig.pk - pkInverse);
            addEntry(set, usedBgs, nidTsr, sig.pk, dTsr, dTsr, lTsr, ltvSpeedKmh, false, true);
        }

        return set;
    }

    /** Crea una NoticeEntry y la añade al set. */
    private static void addEntry(
            LtvTelegramSet set, Set<Integer> usedBgs,
            int nidTsr, double pk, double distanceMeters,
            double dTsr, double lTsr, double vTsr,
            boolean directMovement, boolean isSignalNotice
    ) {
        int    nidBg    = nextAvailableNidBg(usedBgs);
        usedBgs.add(nidBg);
        String bgName   = "BG" + NID_C + "-" + nidBg;
        String telegram = buildTelegram(nidBg, nidTsr, dTsr, lTsr, vTsr, directMovement);
        set.entries.add(new NoticeEntry(bgName, distanceMeters, pk, telegram, isSignalNotice));
    }

    // -------------------------------------------------------------------------
    // Construcción del telegrama
    // -------------------------------------------------------------------------

    /** Telegrama completo: 2 balizas (N_PIG=0 y N_PIG=1). */
    private static String buildTelegram(int nidBg, int nidTsr,
                                        double dTsr, double lTsr, double vTsr,
                                        boolean directMovement) {
        return buildBalise(nidBg, 0, nidTsr, dTsr, lTsr, vTsr) +
               buildBalise(nidBg, 1, nidTsr, dTsr, lTsr, vTsr);
    }

    /** Telegrama de una baliza individual. */
    private static String buildBalise(int nidBg, int nPig, int nidTsr,
                                      double dTsr, double lTsr, double vTsr) {
        int vTsrInt = (int) Math.round(vTsr / 5.0); // múltiplos de 5 km/h
        int dTsrInt = (int) Math.round(dTsr);
        int lTsrInt = (int) Math.round(lTsr);

        StringBuilder sb = new StringBuilder();
        sb.append("=== BALIZA N_PIG=").append(nPig).append(" ===\n\n");

        // Cabecera
        sb.append("-- CABECERA --\n");
        sb.append("Q_UPDOWN   = 1\n");
        sb.append("M_VERSION  = ").append(M_VERSION).append("\n");
        sb.append("Q_MEDIA    = 0\n");
        sb.append("N_PIG      = ").append(nPig).append("\n");
        sb.append("N_TOTAL    = 010\n");
        sb.append("M_DUP      = 0\n");
        sb.append("M_MCOUNT   = 255\n");
        sb.append("NID_C      = ").append(NID_C).append("\n");
        sb.append("NID_BG     = ").append(nidBg).append("\n");
        sb.append("Q_LINK     = 0\n\n");

        // Paquete 65 - TSR
        sb.append("-- PAQUETE 65 (TSR) --\n");
        sb.append("NID_PACKET = 65\n");
        sb.append("Q_DIR      = 1\n");
        sb.append("L_PACKET   = 71\n");
        sb.append("Q_SCALE    = 1\n");
        sb.append("NID_TSR    = ").append(nidTsr).append("\n");
        sb.append("D_TSR      = ").append(dTsrInt).append("\n");
        sb.append("L_TSR      = ").append(lTsrInt).append("\n");
        sb.append("Q_FRONT    = 0\n");
        sb.append("V_TSR      = ").append(vTsrInt).append("\n\n");

        // Paquete 255 - Cierre
        sb.append("-- PAQUETE 255 (CIERRE) --\n");
        sb.append("NID_PACKET = 255\n\n");

        return sb.toString();
    }

    // -------------------------------------------------------------------------
    // Exportación a .txt
    // -------------------------------------------------------------------------

    /** Escribe todos los telegramas en un fichero .txt. */
    public static void exportToFile(LtvTelegramSet set, Path outputPath) throws IOException {
        try (PrintWriter pw = new PrintWriter(outputPath.toFile())) {
            pw.println("========================================");
            pw.println("  TELEGRAMAS LTV - ETCS");
            pw.printf("  PK LTV:      %.2f m%n",  set.ltvPk);
            pw.printf("  Velocidad:   %.1f km/h%n", set.ltvSpeedKmh);
            pw.printf("  Longitud:    %.1f m%n",   set.ltvLengthMeters);
            pw.printf("  Margen:      %.1f m%n",   set.ltvMargin);
            pw.printf("  NID_TSR:     %d%n",       set.nidTsr);
            pw.printf("  NID_C:       %d%n",       NID_C);
            pw.printf("  M_VERSION:   %.1f%n",       M_VERSION);
            pw.println("========================================\n");

            for (NoticeEntry entry : set.entries) {
                pw.println("----------------------------------------");
                pw.printf("BG:          %s%n", entry.baliseName);
                pw.printf("PK aviso:    %.2f m%n", entry.pk);
                pw.printf("Distancia:   %.2f m%n", entry.distanceMeters);
                pw.printf("Tipo:        %s%n", entry.isSignalNotice ? "Señal" : "Frenado");
                pw.println("----------------------------------------");
                pw.println(entry.telegramText);
            }
        }
    }

    // -------------------------------------------------------------------------
    // Tabla resumen por consola
    // -------------------------------------------------------------------------

    /** Imprime la tabla resumen: Baliza | Distancia | PK | Vía | Fichero */
    public static void printSummaryTable(LtvTelegramSet set, Path outputPath) {
        System.out.println("\n=== TABLA DE AVISOS Y TELEGRAMAS ===");
        System.out.printf("NID_TSR: %d  |  NID_C: %d%n%n", set.nidTsr, NID_C);
        System.out.printf("%-38s | %-13s | %-12s | %-6s | %s%n",
                "Baliza", "Distancia (m)", "PK (m)", "Vía", "Datos telegrama");
        System.out.println("-".repeat(95));

        for (NoticeEntry entry : set.entries) {
            System.out.printf("%-38s | %-13.2f | %-12.2f | %-6s | %s%s%n",
                    entry.baliseName,
                    entry.distanceMeters,
                    entry.pk,
                    entry.via,
                    outputPath.getFileName(),
                    entry.isSignalNotice ? "  [SEÑAL]" : "");
        }
        System.out.println("-".repeat(95));
        System.out.println("Fichero: " + outputPath.toAbsolutePath());
    }

    // -------------------------------------------------------------------------
    // Extracción de NID_BG existentes del proyecto
    // -------------------------------------------------------------------------

    /**
     * Lee los NID_BG existentes de los BaliseGroups del proyecto.
     * Formato esperado del nombre: "BG{NID_C}-{NID_BG}" o "BG{NID_C},{NID_BG}".
     */
    public static Set<Integer> extractExistingNidBgs(List<Segment> segs) {
        Set<Integer> existing = new HashSet<>();
        for (Segment seg : segs) {
            for (BaliseData.BaliseGroup bg : seg.getBaliseGroups()) {
                Integer nidBg = parseNidBg(bg.name);
                if (nidBg != null) existing.add(nidBg);
            }
        }
        return existing;
    }

    /** Parsea el NID_BG del nombre del BG. Ej: "BG920-101" → 101, "BG920,102" → 102 */
    private static Integer parseNidBg(String bgName) {
        if (bgName == null) return null;
        String cleaned = bgName.replaceFirst("^BG\\d+[-,]", "");
        try {
            return Integer.parseInt(cleaned);
        } catch (NumberFormatException e) {
            return null;
        }
    }

    // -------------------------------------------------------------------------
    // Asignación de identificadores
    // -------------------------------------------------------------------------

    /** Devuelve el siguiente NID_BG >= 6000 que no esté en uso. */
    private static int nextAvailableNidBg(Set<Integer> usedBgs) {
        int candidate = 6000;
        while (usedBgs.contains(candidate)) candidate++;
        return candidate;
    }

    /** Asigna un NID_TSR único por LTV. Nunca devuelve 255 (reservado). */
    private static int assignNidTsr() {
        int tsr = nextNidTsr++;
        if (nextNidTsr == 255) nextNidTsr++;
        return tsr;
    }

    /** Reinicia el contador de NID_TSR al inicio de una nueva sesión. */
    public static void resetNidTsrCounter() {
        nextNidTsr = 1;
    }
}

