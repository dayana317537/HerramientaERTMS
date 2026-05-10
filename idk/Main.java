package idk;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Scanner;
import java.util.Set;

public class Main {

    // Constantes de frenado
    private static final double G = 9.81;
    private static final double A_BRAKE = 0.4;      // m/s^2
    private static final double GRAD_PERMIL = 10.0; // 10 ‰
    private static final int SLOPE_DIR = -1;        // bajada
    
    //Margen de la LTV 
    private static final double LTV_MARGIN = 7.0; // metros, editable
    //Balizas
    private static final double MIN_BG_GAP_METERS = 15.0;
    private static final double BG_SEARCH_WINDOW_METERS = 20.0;
    private static final boolean  DEBUG_BG = true;        
   
    
    // Límite de exploración hacia atrás desde la LTV
    private static final double MAX_BACKWARD_SEARCH_METERS = 8000.0;

    public static void main(String[] args) throws Exception {
        Locale.setDefault(Locale.US);

        Path xml = Path.of("C:/Users/49204/Desktop/Herramienta/VIA_VI_RA_54_Extendido.xml");

        RailMLSegmentsStaxParser parser = new RailMLSegmentsStaxParser();
        List<Segment> segs = parser.parseSegments(xml);

        Map<String, Segment> byId = new HashMap<>();
        for (Segment s : segs) {
            byId.put(s.id, s);
        }

        for (Segment s : segs) {
            s.finalizeBaliseData(byId);
        }
        
        System.out.println("=== DEBUG BALIZAS RESUELTAS ===");
        for (Segment seg : segs) {
            if (!seg.getBaliseGroups().isEmpty()) {
                System.out.println("Segmento " + seg.id);
                for (BaliseData.BaliseGroup bg : seg.getBaliseGroups()) {
                    System.out.println("  BG " + bg.id
                            + " first=" + bg.getFirstPkByNdx(byId)
                            + " last=" + bg.getLastPkByNdx(byId));
                }
            }
        }
        System.out.println("Segmentos leídos: " + segs.size());

        Scanner sc = new Scanner(System.in);

        System.out.print("Introduce PK de la LTV (en metros, ej: 239000): ");
        double pkLtv = sc.nextDouble();

        System.out.print("Introduce velocidad objetivo de la LTV (km/h, ej: 60): ");
        double ltvSpeedKmh = sc.nextDouble();

        System.out.print("Introduce longitud de la LTV (en metros, ej: 100): ");
        double ltvLengthMeters = sc.nextDouble();

        double pkLtvEffectiveDirect  = pkLtv - LTV_MARGIN;
        double pkLtvEffectiveInverse = pkLtv + ltvLengthMeters + LTV_MARGIN;

        System.out.printf("Tramo LTV: [%.2f, %.2f]%n", pkLtv, pkLtv + ltvLengthMeters);
        System.out.printf("Con margen (%.2f m): [%.2f, %.2f]%n",
                LTV_MARGIN, pkLtvEffectiveDirect, pkLtvEffectiveInverse);
        System.out.printf("PK de trabajo DIRECTA:  %.2f m%n", pkLtvEffectiveDirect);
        System.out.printf("PK de trabajo INVERSA:  %.2f m%n", pkLtvEffectiveInverse);

        double aSlope = SLOPE_DIR * G * (GRAD_PERMIL / 1000.0);
        double aEff   = A_BRAKE + aSlope;

        System.out.println();
        System.out.println("=== DATOS DE FRENADO ===");
        System.out.printf("aBrake = %.4f m/s^2%n", A_BRAKE);
        System.out.printf("gradiente = %.2f ‰%n", GRAD_PERMIL);
        System.out.printf("dir pendiente = %d (bajada)%n", SLOPE_DIR);
        System.out.printf("aSlope = %.4f m/s^2%n", aSlope);
        System.out.printf("aEff = %.4f m/s^2%n", aEff);

        if (aEff <= 0) {
            System.out.println("ERROR: la desaceleración efectiva es <= 0.");
            return;
        }

        // Buscar todos los segmentos que cubren alguno de los dos PKs efectivos
        Set<String> allIds = new HashSet<>();
        List<Segment> containingAll = new ArrayList<>();
        for (Segment seg : segs) {
//            if (seg.mostRestrictiveSpeed == null || seg.mostRestrictiveSpeed < ltvSpeedKmh) continue;
//            if (seg.containsPK(pkLtvEffectiveDirect) || seg.containsPK(pkLtvEffectiveInverse)) {
//                if (allIds.add(seg.id)) containingAll.add(seg);
//            }
        	if (seg.mostRestrictiveSpeed != null && seg.mostRestrictiveSpeed < ltvSpeedKmh) continue;
            
            if (seg.containsPK(pkLtvEffectiveDirect) || seg.containsPK(pkLtvEffectiveInverse)) {
                if (allIds.add(seg.id)) containingAll.add(seg);
            }
        }

        System.out.println();
        if (containingAll.isEmpty()) {
            System.out.println("No encontré segmentos que cubran el tramo LTV.");
            return;
        }

        System.out.println("Segmentos del tramo LTV:");
        for (int i = 0; i < containingAll.size(); i++) {
            Segment seg = containingAll.get(i);
            System.out.println(" " + (i + 1) + ") " + seg.id
                    + " [min=" + seg.minPK() + ", max=" + seg.maxPK()
                    + ", vRestr=" + seg.mostRestrictiveSpeed + "]"
                    + (seg.containsPK(pkLtvEffectiveDirect)  ? " <- DIRECTA"  : "")
                    + (seg.containsPK(pkLtvEffectiveInverse) ? " <- INVERSA" : ""));
        }

        System.out.print("Elige el segmento con el que quieres trabajar (1-" + containingAll.size() + "): ");
        int choice = sc.nextInt() - 1;
        if (choice < 0 || choice >= containingAll.size()) {
            System.out.println("Opción inválida.");
            return;
        }

        Segment selectedSegment = containingAll.get(choice);
        System.out.println("\nTrabajando con el segmento: " + selectedSegment.id);

        // Determinar automáticamente el segmento de inicio para cada dirección
        // Primero buscar entre los candidatos, luego en todos los segmentos
//       

     // Primero intentar con el segmento elegido
        Segment segDirect  = selectedSegment.containsPK(pkLtvEffectiveDirect)  ? selectedSegment : null;
        Segment segInverse = selectedSegment.containsPK(pkLtvEffectiveInverse) ? selectedSegment : null;

        // Si no lo contiene, buscar en vecinos DIRECTAMENTE CONECTADOS al elegido
        if (segDirect == null) {
            // DIRECTA busca hacia PK decreciente → vecinos inversos
            for (String neighborId : selectedSegment.inverseNeighbors) {
                Segment neighbor = byId.get(neighborId);
                if (neighbor != null && neighbor.containsPK(pkLtvEffectiveDirect)) {
                    segDirect = neighbor;
                    break;
                }
            }
        }
        if (segInverse == null) {
            // INVERSA busca hacia PK creciente → vecinos directos
            for (String neighborId : selectedSegment.directNeighbors) {
                Segment neighbor = byId.get(neighborId);
                if (neighbor != null && neighbor.containsPK(pkLtvEffectiveInverse)) {
                    segInverse = neighbor;
                    break;
                }
            }
        }
        

        // Último recurso: buscar solo en containingAll (no en todo el mapa)
        if (segDirect == null) {
            for (Segment seg : containingAll) {
                if (seg.containsPK(pkLtvEffectiveDirect)) { segDirect = seg; break; }
            }
        }
        if (segInverse == null) {
            for (Segment seg : containingAll) {
                if (seg.containsPK(pkLtvEffectiveInverse)) { segInverse = seg; break; }
            }
        }
        System.out.println("Segmento de inicio DIRECTA:  " + (segDirect  != null ? segDirect.id  : "no encontrado"));
        System.out.println("Segmento de inicio INVERSA:  " + (segInverse != null ? segInverse.id : "no encontrado"));

        // Info del segmento seleccionado
        System.out.println();
        System.out.println("==================================================");
        System.out.println("SEGMENTO CANDIDATO: " + selectedSegment.id);
        System.out.println("==================================================");

        System.out.println("Balizas del segmento " + selectedSegment.id + ":");
        for (BaliseData.Balise b : selectedSegment.getBalises()) {
            System.out.println("  " + b + " absPk=" + b.absolutePk(byId));
        }
        System.out.println("BG del segmento " + selectedSegment.id + ":");
        for (BaliseData.BaliseGroup bg : selectedSegment.getBaliseGroups()) {
            System.out.println("  " + bg);
            System.out.println("    firstByNdx PK = " + bg.getFirstPkByNdx(byId));
            System.out.println("    lastByNdx  PK = " + bg.getLastPkByNdx(byId));
        }

        System.out.println();
        System.out.println("Detalle del segmento:");
        System.out.println("  beginAbsPos = " + selectedSegment.beginAbsPos);
        System.out.println("  endAbsPos   = " + selectedSegment.endAbsPos);
        System.out.println("  length      = " + selectedSegment.length());
        System.out.println("  directNeighbors  = " + selectedSegment.directNeighbors);
        System.out.println("  inverseNeighbors = " + selectedSegment.inverseNeighbors);
        System.out.println("  vRestr total = " + selectedSegment.mostRestrictiveSpeed);
        System.out.println("  vRestr up    = " + selectedSegment.mostRestrictiveSpeedUp);
        System.out.println("  vRestr down  = " + selectedSegment.mostRestrictiveSpeedDown);

        System.out.println();
        System.out.println("Perfil de velocidad UP:");
        printSpeedProfile(selectedSegment.speedProfileUp);

        System.out.println();
        System.out.println("Perfil de velocidad DOWN:");
        printSpeedProfile(selectedSegment.speedProfileDown);

        // Calcular ramas
        List<BranchSearchResult> directBranches  = new ArrayList<>();
        List<BranchSearchResult> inverseBranches = new ArrayList<>();

        // DIRECTA
        if (segDirect == null) {
            System.out.println("No hay segmento válido para DIRECTA.");
        } else {
            Double directSpeedAtLtv = getSpeedAtPk(segDirect, pkLtvEffectiveDirect, true , byId);
            if (directSpeedAtLtv != null && directSpeedAtLtv <= ltvSpeedKmh) {
                directBranches.add(new BranchSearchResult(
                        List.of(segDirect.id), 0.0, null, false,
                        String.format("No hace falta LTV en DIRECTA: velocidad %.2f <= %.2f km/h",
                                directSpeedAtLtv, ltvSpeedKmh)
                ));
            } else {
                directBranches = findAllNoticeBranches(
                        pkLtvEffectiveDirect, segDirect.id, byId, true,
                        ltvSpeedKmh, aEff, MAX_BACKWARD_SEARCH_METERS
                );
            }
        }

        // INVERSA
        if (segInverse == null) {
            System.out.println("No hay segmento válido para INVERSA.");
        } else {
            Double inverseSpeedAtLtv = getSpeedAtPk(segInverse, pkLtvEffectiveInverse, false, byId);
            if (inverseSpeedAtLtv != null && inverseSpeedAtLtv <= ltvSpeedKmh) {
                inverseBranches.add(new BranchSearchResult(
                        List.of(segInverse.id), 0.0, null, false,
                        String.format("No hace falta LTV en INVERSA: velocidad %.2f <= %.2f km/h",
                                inverseSpeedAtLtv, ltvSpeedKmh)
                ));
            } else {
                inverseBranches = findAllNoticeBranches(
                        pkLtvEffectiveInverse, segInverse.id, byId, false,
                        ltvSpeedKmh, aEff, MAX_BACKWARD_SEARCH_METERS
                );
            }
        }

     // Recoger avisos de señal
        List<SignalNoticeResolver.SignalNoticeResult> signalsDirect  = new ArrayList<>();
        List<SignalNoticeResolver.SignalNoticeResult> signalsInverse = new ArrayList<>();

        for (BranchSearchResult b : directBranches) {
            SignalNoticeResolver.SignalNoticeResult sig =
                SignalNoticeResolver.findSignalInPath(b.path, byId, true, pkLtvEffectiveDirect);
            if (sig.found) signalsDirect.add(sig);
        }
        for (BranchSearchResult b : inverseBranches) {
            SignalNoticeResolver.SignalNoticeResult sig =
                SignalNoticeResolver.findSignalInPath(b.path, byId, false, pkLtvEffectiveInverse);
            if (sig.found) signalsInverse.add(sig);
        }

        // Fusionar ramas
        List<BranchSearchResult> allBranches = new ArrayList<>();
        allBranches.addAll(directBranches);
        allBranches.addAll(inverseBranches);

        // Extraer NID_BG existentes
        Set<Integer> existingNidBgs = TelegramGenerator.extractExistingNidBgs(segs);

        // Generar telegramas
        TelegramGenerator.LtvTelegramSet telegramSet = TelegramGenerator.generate(
                allBranches,
                signalsDirect,
                signalsInverse,
                pkLtv,
                pkLtvEffectiveDirect,
                pkLtvEffectiveInverse,
                ltvSpeedKmh,
                ltvLengthMeters,
                LTV_MARGIN,
                existingNidBgs
        );

        // Exportar y mostrar tabla
        Path outputTxt = Path.of("telegramas_LTV_" + (int) pkLtv + ".txt");
        TelegramGenerator.exportToFile(telegramSet, outputTxt);
        TelegramGenerator.printSummaryTable(telegramSet, outputTxt); 
        
        System.out.println();
        System.out.println("RAMAS EN DIRECTA (tren hacia PK creciente, búsqueda hacia PK decreciente):");
        printBranchResults("DIRECTA", directBranches, byId, pkLtvEffectiveDirect);

        System.out.println();
        System.out.println("RAMAS EN INVERSA (tren hacia PK decreciente, búsqueda hacia PK creciente):");
        printBranchResults("INVERSA", inverseBranches, byId, pkLtvEffectiveInverse);
    }

    private static List<Segment> findSegmentsContainingPk(List<Segment> segs, double pk) {
        List<Segment> res = new ArrayList<>();
        for (Segment s : segs) {
            if (s.containsPK(pk)) {
                res.add(s);
            }
        }
        return res;
    }
    
    private static Double getSpeedAtPk(Segment s, double pk, boolean directMovement, Map<String, Segment> byId) {
        if (s == null) return null;
        if (!s.containsPK(pk)) return null;

        List<Segment.SpeedInterval> profile = getEffectiveProfile(s, directMovement, byId);;
        if (profile == null || profile.isEmpty()) return null;

        double relPos = pk - s.minPK();

        for (Segment.SpeedInterval interval : profile) {
            if (relPos >= interval.fromPos && relPos <= interval.toPos) {
                return interval.vMax;
            }
        }

        return null;
    }

    /**
     * FASE 1:
     * Explora ramas hacia atrás desde la LTV.
     * - sin repetir segmentos dentro de la misma rama
     * - sin fusionar todavía ramas confluyentes
     * - parando cuando ya se encuentra aviso exacto
     * - o cuando se llega al límite de distancia
     */
    private static List<BranchSearchResult> findAllNoticeBranches(
            double pkLtv,
            String startSegmentId,
            Map<String, Segment> byId,
            boolean directMovement,
            double targetSpeedKmh,
            double aEff,
            double maxBackwardDistance
    ) {
        List<BranchSearchResult> results = new ArrayList<>();

        Segment start = byId.get(startSegmentId);
        if (start == null) return results;

        List<String> initialPath = new ArrayList<>();
        initialPath.add(startSegmentId);

        Set<String> visited = new HashSet<>();
        visited.add(startSegmentId);

        double initialDistance = initialBackwardDistance(start, pkLtv, directMovement);

        dfsNoticeBranches(
                pkLtv,
                startSegmentId,
                byId,
                directMovement,
                targetSpeedKmh,
                aEff,
                maxBackwardDistance,
                initialPath,
                visited,
                initialDistance,
                results
        );

        return results;
    }

    private static void dfsNoticeBranches(
            double pkLtv,
            String startSegmentId,
            Map<String, Segment> byId,
            boolean directMovement,
            double targetSpeedKmh,
            double aEff,
            double maxBackwardDistance,
            List<String> currentPath,
            Set<String> visited,
            double currentBackwardDistance,
            List<BranchSearchResult> results
    ) {
        BrakingNoticeResult notice = directMovement
                ? findBrakingNoticeForDirect(pkLtv, startSegmentId, currentPath, byId, targetSpeedKmh, aEff)
                : findBrakingNoticeForInverse(pkLtv, startSegmentId, currentPath, byId, targetSpeedKmh, aEff);

        if (notice != null && notice.exactNoticeFound) {
            results.add(new BranchSearchResult(
                    new ArrayList<>(currentPath),
                    currentBackwardDistance,
                    notice,
                    true,
                    "Aviso exacto encontrado"
            ));
            return;
        }

        String currentSegmentId = currentPath.get(currentPath.size() - 1);
        Segment current = byId.get(currentSegmentId);

        if (current == null) {
            results.add(new BranchSearchResult(
                    new ArrayList<>(currentPath),
                    currentBackwardDistance,
                    notice,
                    false,
                    "Segmento no encontrado en el mapa"
            ));
            return;
        }

        List<String> predecessors = getBackwardNeighbors(current, directMovement);

        boolean expanded = false;
        boolean blockedByDistance = false;
        boolean blockedByVisited = false;

        for (String prevId : predecessors) {
            if (prevId == null) continue;

            if (visited.contains(prevId)) {
                blockedByVisited = true;
                continue;
            }

            Segment prev = byId.get(prevId);
            if (prev == null) continue;

            double nextDistance = currentBackwardDistance + prev.length();
            if (nextDistance > maxBackwardDistance) {
                blockedByDistance = true;
                continue;
            }

            expanded = true;

            currentPath.add(prevId);
            visited.add(prevId);

            dfsNoticeBranches(
                    pkLtv,
                    startSegmentId,
                    byId,
                    directMovement,
                    targetSpeedKmh,
                    aEff,
                    maxBackwardDistance,
                    currentPath,
                    visited,
                    nextDistance,
                    results
            );

            visited.remove(prevId);
            currentPath.remove(currentPath.size() - 1);
        }

        if (!expanded) {
            String reason;

            if (predecessors.isEmpty()) {
                reason = "Fin de ruta antes de encontrar aviso";
            } else if (blockedByDistance) {
                reason = "Se alcanzó el límite de " + maxBackwardDistance + " m sin encontrar aviso";
            } else if (blockedByVisited) {
                reason = "La rama se detuvo para evitar repetir segmentos";
            } else {
                reason = "No hay predecesores válidos para seguir";
            }

            results.add(new BranchSearchResult(
                    new ArrayList<>(currentPath),
                    currentBackwardDistance,
                    notice,
                    false,
                    reason
            ));
        }
    }

    private static List<String> getBackwardNeighbors(Segment current, boolean directMovement) {
        if (current == null) return new ArrayList<>();

        // DIRECTA:
        // tren hacia PK creciente, aviso hacia atrás en PK decreciente
        // segmentos anteriores = inverseNeighbors
        if (directMovement) {
            return current.inverseNeighbors;
        }

        // INVERSA:
        // tren hacia PK decreciente, aviso hacia atrás en PK creciente
        // segmentos anteriores = directNeighbors
        return current.directNeighbors;
    }

    private static double initialBackwardDistance(Segment start, double pkLtv, boolean directMovement) {
        if (start == null) return 0.0;

        if (directMovement) {
            // DIRECTA: buscamos el aviso geométricamente hacia PK decreciente
            return Math.max(0.0, pkLtv - start.minPK());
        } else {
            // INVERSA: buscamos el aviso geométricamente hacia PK creciente
            return Math.max(0.0, start.maxPK() - pkLtv);
        }
    }

    private static void printBranchResults(String title, List<BranchSearchResult> branches, Map<String, Segment> byId ,double pkLtvEffective ) {
        if (branches == null || branches.isEmpty()) {
            System.out.println("  (sin ramas)");
            return;
        }

        for (int i = 0; i < branches.size(); i++) {
            BranchSearchResult branch = branches.get(i);
            
            boolean directMovement = "DIRECTA".equalsIgnoreCase(title); 

            System.out.println("  Rama " + title + " " + (i + 1) + ":");
            System.out.println("    Ruta: " + String.join(" => ", branch.path));
            System.out.printf("    Distancia hacia atrás explorada: %.2f m%n", branch.backwardDistanceMeters);
            System.out.println("    Estado: " + branch.reason);

            System.out.println("    Segmentos de la rama:");
            printPathDetails(branch.path, byId);

//            if (branch.notice != null && branch.notice.exactNoticeFound) {
//                System.out.println("    Aviso exacto: SI");
//                System.out.println("    Segmento aviso: " + branch.notice.segmentId);
//                System.out.println("    PK aviso: " + branch.notice.pk);
//                System.out.printf("    Velocidad en aviso: %.2f km/h%n", branch.notice.noticeSpeedKmh);
//                System.out.printf("    Velocidad objetivo LTV: %.2f km/h%n", branch.notice.targetSpeedKmh);
//                System.out.printf("    Distancia total de frenado: %.2f m%n", branch.notice.totalDistanceMeters);
//
//                System.out.println("    Tramos realmente usados entre la LTV y el aviso:");
//                printUsedIntervals(branch.notice);
//            } else {
//                System.out.println("    Aviso exacto: NO");
//                System.out.println("    " + branch.reason);
//            }
            
            BrakingNoticeResult finalNotice = branch.notice;
            
            
			SignalNoticeResolver.SignalNoticeResult signalResult = SignalNoticeResolver.findSignalInPath( branch.path, byId, directMovement, pkLtvEffective);

            if (signalResult.found) {
            	System.out.println("    Señal en el path: " + signalResult.signalName
            	 + " en PK=" + signalResult.pk + " (seg=" + signalResult.segmentId + ")");
            	System.out.println("    → Aviso colocado en señal: PK=" + signalResult.pk);
            }

         // Ajustar la señal contra balizas
            signalResult = adjustSignalNoticeAgainstBalises(
                    signalResult,
                    branch.path,
                    byId,
                    directMovement,
                    MIN_BG_GAP_METERS,
                    BG_SEARCH_WINDOW_METERS
            );
            
            
            if (finalNotice != null && finalNotice.exactNoticeFound) {
               // boolean directMovement = "DIRECTA".equalsIgnoreCase(title);

                finalNotice = adjustNoticeAgainstBalises(
                        finalNotice,
                        branch.path,
                        byId,
                        directMovement,
                        MIN_BG_GAP_METERS,
                        BG_SEARCH_WINDOW_METERS
                );
            }

            if (finalNotice != null && finalNotice.exactNoticeFound) {
                System.out.println("    Aviso exacto: SI");
                System.out.println("    Segmento aviso: " + finalNotice.segmentId);
                System.out.println("    PK aviso: " + finalNotice.pk);
                System.out.printf("    Velocidad en aviso: %.2f km/h%n", finalNotice.noticeSpeedKmh);
                System.out.printf("    Velocidad objetivo LTV: %.2f km/h%n", finalNotice.targetSpeedKmh);
                System.out.printf("    Distancia total de frenado: %.2f m%n", finalNotice.totalDistanceMeters);

                System.out.println("    Tramos realmente usados entre la LTV y el aviso:");
                printUsedIntervals(finalNotice);
            } else {
                System.out.println("    Aviso exacto: NO");
                System.out.println("    " + branch.reason);
            }

            System.out.println();
        }
    }
    
    /**
     * DIRECTA = tren circula hacia PK creciente, usando perfil UP.
     * El aviso está antes de la LTV, es decir, hacia PK decreciente.
     */
    private static BrakingNoticeResult findBrakingNoticeForDirect(
            double pkLtv,
            String startSegmentId,
            List<String> backwardGeomPath,
            Map<String, Segment> byId,
            double targetSpeedKmh,
            double aEff
    ) {
        List<ApproachInterval> intervals = collectApproachIntervalsForDirect(
                pkLtv, startSegmentId, backwardGeomPath, byId
        );

        return solveBrakingNotice(intervals, targetSpeedKmh, aEff, true);
    }

    /**
     * INVERSA = tren circula hacia PK decreciente, usando perfil DOWN.
     * El aviso está antes de la LTV, es decir, hacia PK creciente.
     */
    private static BrakingNoticeResult findBrakingNoticeForInverse(
            double pkLtv,
            String startSegmentId,
            List<String> backwardGeomPath,
            Map<String, Segment> byId,
            double targetSpeedKmh,
            double aEff
    ) {
        List<ApproachInterval> intervals = collectApproachIntervalsForInverse(
                pkLtv, startSegmentId, backwardGeomPath, byId
        );

        return solveBrakingNotice(intervals, targetSpeedKmh, aEff, false);
    }

    /**
     * Resuelve el punto de aviso recorriendo intervalos desde la LTV hacia atrás.
     * Aquí "intervalo" = intervalo de velocidad, no segmento completo.
     */

    private static BrakingNoticeResult solveBrakingNotice(
            List<ApproachInterval> intervals,
            double targetSpeedKmh,
            double aEff,
            boolean directMovement
    ) {
        if (intervals == null || intervals.isEmpty()) {
            return new BrakingNoticeResult(
                    null,
                    Double.NaN,
                    Double.NaN,
                    targetSpeedKmh,
                    0.0,
                    new ArrayList<>(),
                    false
            );
        }

        double accumulatedDistance = 0.0;
        List<UsedInterval> usedIntervals = new ArrayList<>();

        for (ApproachInterval interval : intervals) {

            double v = interval.speedKmh;
            double len = interval.lengthMeters();

            double dNeed = brakingDistanceMeters(v, targetSpeedKmh, aEff);
            double totalAvailable = accumulatedDistance + len;

            if (v == targetSpeedKmh) {
                double noticePk;
                double usedFrom;
                double usedTo;
                if (directMovement) {
                    noticePk = interval.pkAwaySide;
                    usedFrom = interval.pkAwaySide;
                    usedTo   = interval.pkTargetSide;
                } else {
                    noticePk = interval.pkAwaySide;
                    usedFrom = interval.pkTargetSide;
                    usedTo   = interval.pkAwaySide;
                }
                usedIntervals.add(new UsedInterval(
                        interval.segmentId,
                        usedFrom,
                        usedTo,
                        v
                ));
                return new BrakingNoticeResult(
                        interval.segmentId,
                        noticePk,
                        v,
                        targetSpeedKmh,
                        dNeed,
                        new ArrayList<>(usedIntervals),
                        true
                );
            }
            
            if (v > targetSpeedKmh && dNeed <= totalAvailable) {

                double distInside = dNeed - accumulatedDistance;

                if (distInside < 0) distInside = 0;
                if (distInside > len) distInside = len;

                double noticePk;
                double usedFrom;
                double usedTo;

                if (directMovement) {
                    noticePk = interval.pkTargetSide - distInside;
                    usedFrom = noticePk;
                    usedTo = interval.pkTargetSide;
                } else {
                    noticePk = interval.pkTargetSide + distInside;
                    usedFrom = interval.pkTargetSide;
                    usedTo = noticePk;
                }

                usedIntervals.add(new UsedInterval(
                        interval.segmentId,
                        usedFrom,
                        usedTo,
                        v
                ));

                return new BrakingNoticeResult(
                        interval.segmentId,
                        noticePk,
                        v,
                        targetSpeedKmh,
                        dNeed,
                        new ArrayList<>(usedIntervals),
                        true
                );
            }

            accumulatedDistance += len;

            double fullFrom;
            double fullTo;

            if (directMovement) {
                fullFrom = interval.pkAwaySide;
                fullTo = interval.pkTargetSide;
            } else {
                fullFrom = interval.pkTargetSide;
                fullTo = interval.pkAwaySide;
            }

            usedIntervals.add(new UsedInterval(
                    interval.segmentId,
                    fullFrom,
                    fullTo,
                    v
            ));
        }

        return new BrakingNoticeResult(
                null,
                Double.NaN,
                Double.NaN,
                targetSpeedKmh,
                accumulatedDistance,
                new ArrayList<>(usedIntervals),
                false
        );
    }
    
    
    /**
     * DIRECTA:
     * - movimiento real: PK creciente
     * - perfil a usar: UP
     * - búsqueda geométrica hacia atrás: PK decreciente
     */
    private static List<ApproachInterval> collectApproachIntervalsForDirect(
            double pkLtv,
            String startSegmentId,
            List<String> backwardGeomPath,
            Map<String, Segment> byId
    ) {
        List<ApproachInterval> out = new ArrayList<>();

        for (int i = 0; i < backwardGeomPath.size(); i++) {
            String segId = backwardGeomPath.get(i);
            Segment s = byId.get(segId);
            if (s == null) continue;

            double minPk = s.minPK();
            List<Segment.SpeedInterval> profile = getEffectiveProfile (s ,true , byId);
            if (profile == null || profile.isEmpty()) continue;

            if (i == 0 && segId.equals(startSegmentId)) {
                // Solo la parte desde minPK hasta pkLtv
                double limitPk = pkLtv;

                for (int j = profile.size() - 1; j >= 0; j--) {
                    Segment.SpeedInterval si = profile.get(j);

                    double absFrom = minPk + si.fromPos;
                    double absTo = minPk + si.toPos;

//                    double overlapFrom = Math.max(absFrom, minPk);
//                    double overlapTo = Math.min(absTo, limitPk);
                    // CAMBIO: overlapTo puede ser el max del segmento si pkLtv < minPk
                    double overlapFrom = absFrom;
                    double overlapTo   = Math.min(absTo, Math.max(limitPk, minPk)); 


                    if (overlapTo <= overlapFrom) continue;

                    // Cerca de LTV = PK mayor
                    out.add(new ApproachInterval(
                            segId,
                            overlapFrom,
                            overlapTo,
                            overlapTo,
                            overlapFrom,
                            si.vMax
                    ));
                }
            } else {
                // Segmento completo hacia atrás desde PK mayor a PK menor
                for (int j = profile.size() - 1; j >= 0; j--) {
                    Segment.SpeedInterval si = profile.get(j);

                    double absFrom = minPk + si.fromPos;
                    double absTo = minPk + si.toPos;

                    if (absTo <= absFrom) continue;

                    out.add(new ApproachInterval(
                            segId,
                            absFrom,
                            absTo,
                            absTo,
                            absFrom,
                            si.vMax
                    ));
                }
            }
        }

        return out;
    }

    /**
     * INVERSA:
     * - movimiento real: PK decreciente
     * - perfil a usar: DOWN
     * - búsqueda geométrica hacia atrás: PK creciente
     */
    private static List<ApproachInterval> collectApproachIntervalsForInverse(
            double pkLtv,
            String startSegmentId,
            List<String> backwardGeomPath,
            Map<String, Segment> byId
    ) {
        List<ApproachInterval> out = new ArrayList<>();

        for (int i = 0; i < backwardGeomPath.size(); i++) {
            String segId = backwardGeomPath.get(i);
            Segment s = byId.get(segId);
            if (s == null) continue;

            double minPk = s.minPK();
            double maxPk = s.maxPK();

            List<Segment.SpeedInterval> profile = getEffectiveProfile (s, false, byId);
            if (profile == null || profile.isEmpty()) continue;

            if (i == 0 && segId.equals(startSegmentId)) {
                // Solo la parte desde pkLtv hasta maxPK
                double startPk = pkLtv;

                for (int j = 0; j < profile.size(); j++) {
                    Segment.SpeedInterval si = profile.get(j);

                    double absFrom = minPk + si.fromPos;
                    double absTo = minPk + si.toPos;

//                    double overlapFrom = Math.max(absFrom, startPk);
//                    double overlapTo = Math.min(absTo, maxPk);
                 // CAMBIO: overlapFrom puede ser el min del segmento si pkLtv > maxPk
                    double overlapFrom = Math.max(absFrom, Math.min(startPk, maxPk)); 
                    double overlapTo   = Math.min(absTo, maxPk);

                    if (overlapTo <= overlapFrom) continue;

                    // Cerca de LTV = PK menor
                    out.add(new ApproachInterval(
                            segId,
                            overlapFrom,
                            overlapTo,
                            overlapFrom,
                            overlapTo,
                            si.vMax
                    ));
                }
            } else {
                // Segmento completo hacia atrás desde PK menor a PK mayor
                for (int j = 0; j < profile.size(); j++) {
                    Segment.SpeedInterval si = profile.get(j);

                    double absFrom = minPk + si.fromPos;
                    double absTo = minPk + si.toPos;

                    if (absTo <= absFrom) continue;

                    out.add(new ApproachInterval(
                            segId,
                            absFrom,
                            absTo,
                            absFrom,
                            absTo,
                            si.vMax
                    ));
                }
            }
        }

        return out;
    }

    private static double brakingDistanceMeters(double v0Kmh, double vTKmh, double aEff) {
        double v0 = kmhToMs(v0Kmh);
        double vT = kmhToMs(vTKmh);

        if (v0 <= vT) return 0.0;
        return (v0 * v0 - vT * vT) / (2.0 * aEff);
    }

    private static double kmhToMs(double kmh) {
        return kmh / 3.6;
    }

    private static double msToKmh(double ms) {
        return ms * 3.6;
    }

    private static void printPathDetails(List<String> path, Map<String, Segment> byId) {
        for (String id : path) {
            Segment s = byId.get(id);
            if (s == null) continue;

            System.out.println("      " + s.id
                    + "  [minPK=" + s.minPK()
                    + ", maxPK=" + s.maxPK()
                    + ", len=" + s.length()
                    + ", vRestr=" + s.mostRestrictiveSpeed + "]");
        }
    }

    private static void printSpeedProfile(List<Segment.SpeedInterval> profile) {
        if (profile == null || profile.isEmpty()) {
            System.out.println("  (sin datos)");
            return;
        }

        for (Segment.SpeedInterval interval : profile) {
            System.out.println("  " + interval);
        }
    }

    private static void printUsedIntervals(BrakingNoticeResult result) {
        if (result == null) {
            System.out.println("      (sin resultado)");
            return;
        }

        if (!result.exactNoticeFound) {
            System.out.println("      No se encontró un punto exacto de aviso dentro de la ruta disponible.");
            return;
        }

        if (result.usedIntervals == null || result.usedIntervals.isEmpty()) {
            System.out.println("      (sin intervalos usados)");
            return;
        }

        String currentSegment = null;

        for (int i = result.usedIntervals.size() - 1; i >= 0; i--) {
            UsedInterval ui = result.usedIntervals.get(i);

            if (!ui.segmentId.equals(currentSegment)) {
                currentSegment = ui.segmentId;
                System.out.println("      Segmento " + currentSegment);
            }

            System.out.println(String.format(
                    "        [desde PK %.2f hasta PK %.2f : %.2f km/h] len=%.2f m",
                    ui.pkTo, ui.pkFrom, ui.speedKmh, ui.lengthMeters()
            ));
        }
    }



//    private static class BaliseReference {
//        String groupId;
//        String segmentId;
//        double pk;
//
//        BaliseReference(String groupId, String segmentId, double pk) {
//            this.groupId = groupId;
//            this.segmentId = segmentId;
//            this.pk = pk;
//        }
//    }



  
    
    private static List<Segment> filterSegmentsByPkAndSpeed(List<Segment> segs, double pk, double speed) {
        List<Segment> result = new ArrayList<>();

        for (Segment s : segs) {
            if (s.containsPK(pk)) { // primero: el PK
                // criterio: mostRestrictiveSpeed >= velocidad TLV
                if (s.mostRestrictiveSpeed != null && s.mostRestrictiveSpeed >= speed) {
                    result.add(s);
                }
            }
        }

        return result;
    }
    

    
    private static Segment findSegmentContainingPkInNeighbors(
            Map<String, Segment> byId,
            List<String> branchPath,
            double pk,
            boolean directMovement
    ) {
        if (branchPath == null || branchPath.isEmpty()) return null;

        // El último segmento de la rama es el más alejado de la LTV
        String lastSegId = branchPath.get(branchPath.size() - 1);
        Segment lastSeg = byId.get(lastSegId);
        if (lastSeg == null) return null;

        // En INVERSA: dirección away = directNeighbors (PK creciente)
        // En DIRECTA: dirección away = inverseNeighbors (PK decreciente)
        List<String> neighbors = directMovement
                ? lastSeg.inverseNeighbors
                : lastSeg.directNeighbors;

        for (String neighborId : neighbors) {
            if (neighborId == null) continue;
            Segment neighbor = byId.get(neighborId);
            if (neighbor != null && neighbor.containsPK(pk)) {
                if (DEBUG_BG) {
                    System.out.println("    [BG] Aviso extendido al segmento vecino: " + neighborId);
                }
                return neighbor;
            }
        }

        return null;
    }
    
    private static BrakingNoticeResult adjustNoticeAgainstBalises(
            BrakingNoticeResult rawNotice,
            List<String> branchPath,
            Map<String, Segment> byId,
            boolean directMovement,
            double minGapMeters,
            double searchWindowMeters
    ) {
        if (rawNotice == null || !rawNotice.exactNoticeFound) return rawNotice;

        double adjustedPk = rawNotice.pk;
        String adjustedSegmentId = rawNotice.segmentId;

        if (DEBUG_BG) {
            System.out.println("    [BG] Aviso teórico inicial:");
            System.out.println("         segmento=" + rawNotice.segmentId + " pk=" + rawNotice.pk);
            System.out.println("         sentido=" + (directMovement ? "DIRECTA" : "INVERSA"));
        }

        for (int guard = 0; guard < 50; guard++) {
            if (DEBUG_BG) {
                System.out.printf("    [BG] Iteración %d, ventana [%.2f, %.2f]%n",
                        guard + 1,
                        adjustedPk - searchWindowMeters,
                        adjustedPk + searchWindowMeters);
            }

            List<BaliseReference> nearby = findNearbyBalises(
                    adjustedPk, branchPath, byId, directMovement, searchWindowMeters
            );

            if (DEBUG_BG) {
                if (nearby.isEmpty()) {
                    System.out.println("    [BG] No hay BG cercanos.");
                } else {
                    System.out.println("    [BG] BG cercanos:");
                    for (BaliseReference ref : nearby) {
                        double delta = directMovement ? (adjustedPk - ref.pk) : (ref.pk - adjustedPk);
                        System.out.printf("         BG=%s seg=%s pk=%.2f delta=%.2f%n",
                                ref.groupId, ref.segmentId, ref.pk, delta);
                    }
                }
            }

            BaliseReference conflict = findNearestConflictingBalise(
                    adjustedPk,branchPath, byId, directMovement, minGapMeters, searchWindowMeters
            );

            if (conflict == null) {
                if (DEBUG_BG) {
                    System.out.println("    [BG] No hay conflicto. Aviso válido.");
                    System.out.println("    [BG] Aviso final: segmento=" + adjustedSegmentId + " pk=" + adjustedPk);
                }

                return new BrakingNoticeResult(
                        adjustedSegmentId,
                        adjustedPk,
                        rawNotice.noticeSpeedKmh,
                        rawNotice.targetSpeedKmh,
                        rawNotice.totalDistanceMeters,
                        rawNotice.usedIntervals,
                        true
                );
            }

            double delta = directMovement ? (adjustedPk - conflict.pk) : (conflict.pk - adjustedPk);

            if (DEBUG_BG) {
                System.out.printf("    [BG] CONFLICTO con BG=%s seg=%s pk=%.2f delta=%.2f < %.2f%n",
                        conflict.groupId, conflict.segmentId, conflict.pk, delta, minGapMeters);
            }

            if (directMovement) {
                adjustedPk = conflict.pk - minGapMeters;
            } else {
                adjustedPk = conflict.pk + minGapMeters;
            }

            Segment newSeg = findSegmentContainingPkInBranch(byId, branchPath, adjustedPk);

            if (newSeg == null) {
                // Intentar en vecinos del último segmento de la rama en dirección away
                newSeg = findSegmentContainingPkInNeighbors(byId, branchPath, adjustedPk, directMovement);
            }
            
            if (DEBUG_BG) {
                System.out.printf("    [BG] Aviso desplazado a pk=%.2f%n", adjustedPk);
            }

            if (newSeg == null) {
                if (DEBUG_BG) {
                    System.out.println("    [BG] ERROR: el aviso ajustado no cae en ningún segmento.");
                }

                return new BrakingNoticeResult(
                        rawNotice.segmentId,
                        rawNotice.pk,
                        rawNotice.noticeSpeedKmh,
                        rawNotice.targetSpeedKmh,
                        rawNotice.totalDistanceMeters,
                        rawNotice.usedIntervals,
                        false
                );
            }

            adjustedSegmentId = newSeg.id;

            if (DEBUG_BG) {
                System.out.println("    [BG] Nuevo segmento del aviso: " + adjustedSegmentId);
            }
        }

        if (DEBUG_BG) {
            System.out.println("    [BG] Se alcanzó el máximo de iteraciones de ajuste.");
        }

        return rawNotice;
    }
    
///////////////////////FUNCION CON DEBUGS ///////////////////////////
//    private static List<BaliseReference> findNearbyBalises(
//            double noticePk,
//            List<String> branchPath,
//            Map<String, Segment> byId,
//            boolean directMovement,
//            double searchWindowMeters
//    ) {
//        List<BaliseReference> refs = new ArrayList<>();
//
//        double windowMin = noticePk - searchWindowMeters;
//        double windowMax = noticePk + searchWindowMeters;
//
//        if (branchPath == null || branchPath.isEmpty()) return refs;
//
//        // Segmentos a inspeccionar: la rama + vecinos away del último segmento
//        List<String> segmentsToCheck = new ArrayList<>(branchPath);
//
//        String lastSegId = branchPath.get(branchPath.size() - 1);
//        Segment lastSeg = byId.get(lastSegId);
//        
//     // DEBUG temporal - añade aquí:
//        System.out.println("    [BG-DEBUG] Último seg de rama: " + lastSegId);
//        if (lastSeg != null) {
//            System.out.println("    [BG-DEBUG] directNeighbors: " + lastSeg.directNeighbors);
//            System.out.println("    [BG-DEBUG] inverseNeighbors: " + lastSeg.inverseNeighbors);
//            List<String> awayNeighbors = directMovement
//                    ? lastSeg.inverseNeighbors
//                    : lastSeg.directNeighbors;
//            System.out.println("    [BG-DEBUG] awayNeighbors a añadir: " + awayNeighbors);
//            
//            
//        }
//        
//        if (lastSeg != null) {
//            // INVERSA: away = directNeighbors | DIRECTA: away = inverseNeighbors
//            List<String> awayNeighbors = directMovement
//                    ? lastSeg.inverseNeighbors
//                    : lastSeg.directNeighbors;
//            for (String neighborId : awayNeighbors) {
//                if (neighborId != null && !segmentsToCheck.contains(neighborId)) {
//                    segmentsToCheck.add(neighborId);
//                }
//            }
//        }
//
//        // Buscar BGs en todos los segmentos candidatos
//        for (String segId : segmentsToCheck) {
//            Segment s = byId.get(segId);
//            if (s == null) { 
//                System.out.println("    [BG-DEBUG] segId=" + segId + " -> null en byId"); 
//                continue; 
//            }
//
//            System.out.println("    [BG-DEBUG] Revisando seg=" + segId 
//                + " minPK=" + s.minPK() + " maxPK=" + s.maxPK()
//                + " windowMin=" + windowMin + " windowMax=" + windowMax);
//
//            if (s.maxPK() < windowMin || s.minPK() > windowMax) {
//                System.out.println("    [BG-DEBUG] -> fuera de ventana, skip");
//                continue;
//            }
//
//            System.out.println("    [BG-DEBUG] -> BGs en este seg: " + s.getBaliseGroups().size());
//           for (BaliseData.BaliseGroup bg : s.getBaliseGroups()) {
////                Double refPk = directMovement
////                        ? bg.getLastPkByNdx(byId)
////                        : bg.getFirstPkByNdx(byId);
//        	   Double refPk = bg.getLastPkByNdx(byId); // siempre ndx mayor = primera baliza del grupo
//
//                System.out.println("    [BG-DEBUG]   BG=" + bg.id + " refPk=" + refPk);
//                
//                if (refPk == null) {
//                    System.out.println("    [BG-DEBUG]   -> refPk null, skip");
//                    continue;
//                }
//                if (refPk < windowMin || refPk > windowMax) {
//                    System.out.println("    [BG-DEBUG]   -> fuera de ventana (" + windowMin + "-" + windowMax + "), skip");
//                    continue;
//                }
//                refs.add(new BaliseReference(bg.id, s.id, refPk));
//            }
//        }
//
//        return refs;
//    }
    private static List<BaliseReference> findNearbyBalises(
            double noticePk,
            List<String> branchPath,
            Map<String, Segment> byId,
            boolean directMovement,
            double searchWindowMeters
    ) {
        List<BaliseReference> refs = new ArrayList<>();

        double windowMin = noticePk - searchWindowMeters;
        double windowMax = noticePk + searchWindowMeters;

        if (branchPath == null || branchPath.isEmpty()) return refs;

        // Segmentos a inspeccionar: la rama + vecinos away del último segmento
        List<String> segmentsToCheck = new ArrayList<>(branchPath);

        String lastSegId = branchPath.get(branchPath.size() - 1);
        Segment lastSeg = byId.get(lastSegId);
        if (lastSeg != null) {
            // INVERSA: away = directNeighbors | DIRECTA: away = inverseNeighbors
            List<String> awayNeighbors = directMovement
                    ? lastSeg.inverseNeighbors
                    : lastSeg.directNeighbors;
            for (String neighborId : awayNeighbors) {
                if (neighborId != null && !segmentsToCheck.contains(neighborId)) {
                    segmentsToCheck.add(neighborId);
                }
            }
        }

        // Buscar BGs en todos los segmentos candidatos
        for (String segId : segmentsToCheck) {
            Segment s = byId.get(segId);
            if (s == null) continue;

            if (s.maxPK() < windowMin || s.minPK() > windowMax) continue;

            for (BaliseData.BaliseGroup bg : s.getBaliseGroups()) {
                Double refPk = bg.getFirstPkByNdx(byId); // ndx=0, primera baliza del grupo
                     

                if (refPk == null) continue;
                if (refPk < windowMin || refPk > windowMax) continue;

                refs.add(new BaliseReference(bg.id, s.id, refPk));
            }
        }

        return refs;
    }

    /**
     * Ajusta un aviso de SEÑAL contra balizas existentes.
     * Similar a adjustNoticeAgainstBalises pero para SignalNoticeResult.
     */
    private static SignalNoticeResolver.SignalNoticeResult adjustSignalNoticeAgainstBalises(
            SignalNoticeResolver.SignalNoticeResult rawSignal,
            List<String> branchPath,
            Map<String, Segment> byId,
            boolean directMovement,
            double minGapMeters,
            double searchWindowMeters
    ){
        if (rawSignal == null || !rawSignal.found) return rawSignal;

        double adjustedPk = rawSignal.pk;
        String adjustedSegmentId = rawSignal.segmentId;

        if (DEBUG_BG) {
            System.out.println("    [BG-SIGNAL] Aviso de señal inicial:");
            System.out.println("         segmento=" + rawSignal.segmentId + " pk=" + rawSignal.pk);
            System.out.println("         sentido=" + (directMovement ? "DIRECTA" : "INVERSA"));
        }

        for (int guard = 0; guard < 50; guard++) {
            if (DEBUG_BG) {
                System.out.printf("    [BG-SIGNAL] Iteración %d, ventana [%.2f, %.2f]%n",
                        guard + 1,
                        adjustedPk - searchWindowMeters,
                        adjustedPk + searchWindowMeters);
            }

            List<BaliseReference> nearby = findNearbyBalises(
                    adjustedPk, branchPath, byId, directMovement, searchWindowMeters
            );

            if (DEBUG_BG) {
                if (nearby.isEmpty()) {
                    System.out.println("    [BG-SIGNAL] No hay BG cercanos.");
                } else {
                    System.out.println("    [BG-SIGNAL] BG cercanos:");
                    for (BaliseReference ref : nearby) {
                        double delta = directMovement ? (adjustedPk - ref.pk) : (ref.pk - adjustedPk);
                        System.out.printf("         BG=%s seg=%s pk=%.2f delta=%.2f%n",
                                ref.groupId, ref.segmentId, ref.pk, delta);
                    }
                }
            }

            BaliseReference conflict = findNearestConflictingBalise(
                    adjustedPk, branchPath, byId, directMovement, minGapMeters, searchWindowMeters
            );

            if (conflict == null) {
                if (DEBUG_BG) {
                    System.out.println("    [BG-SIGNAL] No hay conflicto. Aviso de señal válido.");
                    System.out.println("    [BG-SIGNAL] Aviso final: segmento=" + adjustedSegmentId + " pk=" + adjustedPk);
                }

                return new SignalNoticeResolver.SignalNoticeResult(
                        true,
                        adjustedSegmentId,
                        adjustedPk,
                        rawSignal.signalName,
                        rawSignal.signalId
                );
            }

            double delta = directMovement ? (adjustedPk - conflict.pk) : (conflict.pk - adjustedPk);

            if (DEBUG_BG) {
                System.out.printf("    [BG-SIGNAL] CONFLICTO con BG=%s seg=%s pk=%.2f delta=%.2f < %.2f%n",
                        conflict.groupId, conflict.segmentId, conflict.pk, delta, minGapMeters);
            }

            if (directMovement) { // NUEVO COMPORTAMIENTO: Empujar el aviso hacia ADELANTE de la señal
                adjustedPk = conflict.pk + minGapMeters;
            } else {
                adjustedPk = conflict.pk - minGapMeters;
            }

            Segment newSeg = findSegmentContainingPkInBranch(byId, branchPath, adjustedPk);

            if (newSeg == null) {
                // Intentar en vecinos del último segmento de la rama en dirección away
                newSeg = findSegmentContainingPkInNeighbors(byId, branchPath, adjustedPk, directMovement);
            }
            
            if (DEBUG_BG) {
                System.out.printf("    [BG-SIGNAL] Aviso desplazado a pk=%.2f%n", adjustedPk);
            }

            if (newSeg == null) {
                if (DEBUG_BG) {
                    System.out.println("    [BG-SIGNAL] ERROR: el aviso ajustado no cae en ningún segmento.");
                }

                return rawSignal;
            }

            adjustedSegmentId = newSeg.id;

            if (DEBUG_BG) {
                System.out.println("    [BG-SIGNAL] Nuevo segmento del aviso: " + adjustedSegmentId);
            }
        }

        if (DEBUG_BG) {
            System.out.println("    [BG-SIGNAL] Se alcanzó el máximo de iteraciones de ajuste.");
        }

        return rawSignal;
    }
    
private static Segment findSegmentContainingPk(Map<String, Segment> byId, double pk) {
    for (Segment s : byId.values()) {
        if (s.containsPK(pk)) return s;
    }
    return null;
}

private static BaliseReference findNearestConflictingBalise(
        double noticePk,
        List <String> branchPath,
        Map<String, Segment> byId,
        boolean directMovement,
        double minGapMeters,
        double searchWindowMeters
) {
    BaliseReference best = null;
    double bestAbsDelta = Double.POSITIVE_INFINITY;

    List<BaliseReference> nearby = findNearbyBalises(
            noticePk,branchPath, byId, directMovement, searchWindowMeters
    );

    for (BaliseReference ref : nearby) {
    	double absDelta = Math.abs(noticePk - ref.pk);

        if (absDelta < minGapMeters && absDelta < bestAbsDelta) {
            bestAbsDelta = absDelta;
            best = ref;
        }
    }

    return best;
}


private static Segment findSegmentContainingPkInBranch(
        Map<String, Segment> byId,
        List<String> branchPath,
        double pk
) {
    for (String segId : branchPath) {
        Segment s = byId.get(segId);
        if (s != null && s.containsPK(pk)) return s;
    }
    return null;
}


///VELOCIDADES DE LOS SEGMENTOS QUE NO TIENE UN SSP AL INICIO , HEREDAN DE LOS SEGMENTOS ANTERIORES 
private static Double findSpeedseg (Segment currentSeg , boolean direction , Map<String , Segment> byId , Set<String> visited ) {
	if (currentSeg == null) return null;
    visited.add(currentSeg.id); // Evitar bucles infinitos en grafos circulares

    // Buscar aguas arriba (de dónde viene el tren)
    List<String> prevNeighbors = direction ? currentSeg.inverseNeighbors : currentSeg.directNeighbors;
    
    for (String prevId : prevNeighbors) {
        if (visited.contains(prevId)) continue;
        Segment prevSeg = byId.get(prevId);
        if (prevSeg == null) continue;

        List<Segment.SpeedInterval> profile = direction ? prevSeg.speedProfileUp : prevSeg.speedProfileDown;
        
        // Si el segmento anterior SÍ tiene velocidad, cogemos el valor de su tramo final
        if (profile != null && !profile.isEmpty()) {
            return profile.get(profile.size() - 1).vMax;
        }

        // Si el anterior también está vacío, seguimos buscando hacia atrás recursivamente
        Double inheritedSpeed = findSpeedseg(prevSeg, direction, byId, visited);
        if (inheritedSpeed != null) return inheritedSpeed;
    }
    
    return null; // Si llegamos al principio del todo y no hay nada
}

/**
 * Wrapper: Devuelve el perfil real del XML o genera uno virtual heredado si está vacío.
 */
private static List<Segment.SpeedInterval> getEffectiveProfile (Segment seg, boolean directMovement, Map<String, Segment> byId) {
   
	List<Segment.SpeedInterval> profile = directMovement ? seg.speedProfileUp : seg.speedProfileDown;
    
    // Si el segmento tiene su propio perfil en el XML, lo usamos
    if (profile != null && !profile.isEmpty()) {
        return profile;
    }

    // Si está vacío, heredamos la velocidad topológicamente
    Double inheritedSpeed = findSpeedseg(seg, directMovement, byId, new HashSet<>());
    
    if (inheritedSpeed == null) {
        // Si no se encuentra velocidad por herencia (ej: primer segmento del mapa sin datos)
        // se puede devolver una lista vacía o asumir una velocidad por defecto segura.
        return new ArrayList<>(); 
    }

    // Creamos un perfil virtual que abarca todo el segmento (desde pos 0.0 hasta su longitud total)
    return List.of(new Segment.SpeedInterval(0.0, seg.length(), inheritedSpeed));
}

}