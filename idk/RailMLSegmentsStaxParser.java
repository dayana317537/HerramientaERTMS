package idk;

import javax.xml.stream.XMLInputFactory;
import javax.xml.stream.XMLStreamConstants;
import javax.xml.stream.XMLStreamReader;

import java.io.FileInputStream;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

public class RailMLSegmentsStaxParser {

    public List<Segment> parseSegments(Path xmlPath) throws Exception {
    	
    	boolean inOcsElements = false;
    	boolean inBalises = false;
    	boolean inBaliseGroup = false;
    	boolean inSignals = false; //Señales
    	
    	BaliseData.BaliseGroup currentBaliseGroup = null;
        List<Segment> segments = new ArrayList<>();

        XMLInputFactory factory = XMLInputFactory.newFactory();
        factory.setProperty(XMLInputFactory.IS_NAMESPACE_AWARE, true);
        factory.setProperty(XMLInputFactory.SUPPORT_DTD, false);

        try (FileInputStream file = new FileInputStream(xmlPath.toFile())) {
            XMLStreamReader reader = factory.createXMLStreamReader(file);

            Segment current = null;

            boolean inTrackTopology = false;
            boolean inTrackBegin = false;
            boolean inTrackEnd = false;
            boolean inConnections = false;
            boolean inSwitch = false;

            boolean inTrackElements = false;
            boolean inSpeedChanges = false;

            while (reader.hasNext()) {
                int ev = reader.next();

                if (ev == XMLStreamConstants.START_ELEMENT) {
                    String ln = reader.getLocalName();
                    	
                    if ("track".equals(ln)) {
                        String id = attr(reader, "id");
                        String name = attr(reader, "name");
                        current = new Segment(id, name);

                        inTrackTopology = false;
                        inTrackBegin = false;
                        inTrackEnd = false;
                        inConnections = false;
                        inSwitch = false;
                        inTrackElements = false;
                        inSpeedChanges = false;
                        continue;
                    }

                    if (current == null) continue;

                    if ("trackTopology".equals(ln)) {
                        inTrackTopology = true;
                        continue;
                    }

                    if ("trackElements".equals(ln)) {
                        inTrackElements = true;
                        continue;
                    }

                    if (inTrackElements && "speedChanges".equals(ln)) {
                        inSpeedChanges = true;
                        continue;
                    }

                    if (inSpeedChanges && "speedChange".equals(ln)) {
                        String dir = attr(reader, "dir");
                        Double pos = readPos(reader);
                        Double vMax = readDoubleAttr(reader, "vMax");
                        current.addSpeedChange(dir, pos, vMax);
                        continue;
                    }

                    if (inTrackTopology) {
                        if ("trackBegin".equals(ln)) {
                            inTrackBegin = true;

                            Double abs = readAbsPos(reader);
                            if (abs != null) current.beginAbsPos = abs;
                            continue;
                        }

                        if ("trackEnd".equals(ln)) {
                            inTrackEnd = true;

                            Double abs = readAbsPos(reader);
                            if (abs != null) current.endAbsPos = abs;
                            continue;
                        }

                        if ("connections".equals(ln)) {
                            inConnections = true;
                            continue;
                        }

                        if (inConnections && "switch".equals(ln)) {
                            inSwitch = true;
                            continue;
                        }

                        if ("connection".equals(ln)) {
                            String ref = attr(reader, "ref");
                            String toId = Segment.neighborIdFromRef(ref);

                            if (toId == null) continue;

                            if (inTrackBegin) {
                                current.addInverseNeighbor(toId);
                                //current.addDirectNeighbor(toId);
                                continue;
                            }

                            if (inTrackEnd) {
                            	//current.addInverseNeighbor(toId);
                               current.addDirectNeighbor(toId);
                                continue;
                            }

                            if (inSwitch) {
                                String orientation = attr(reader, "orientation");

                                if ("incoming".equalsIgnoreCase(orientation)) {
                                    current.addInverseNeighbor(toId);
                                } else if ("outgoing".equalsIgnoreCase(orientation)) {
                                    current.addDirectNeighbor(toId);
                                }
                            }
                        }
                    }
                    if ("ocsElements".equals(ln)) {
                        inOcsElements = true;
                        continue;
                    }

                    
                    //Señales 
                    if (inOcsElements && "signals".equals(ln)) {
                        inSignals = true;
                        continue;
                    }
                
                    if (inSignals && "signal".equals(ln)) {
                        String id   = attr(reader, "id");
                        String name = attr(reader, "name");
                        String dir  = attr(reader, "dir");
                        Double pos  = readPos(reader);
                        current.signalData.addSignal(
                            new SignalData.Signal(id, name, dir, pos, current.id)
                        );
                        continue;
                    }
                    
                    if (inOcsElements && "balises".equals(ln)) {
                        inBalises = true;
                        continue;
                    }

                    if (inBalises && "balise".equals(ln)) {
                        String id = attr(reader, "id");
                        String name = attr(reader, "name");
                        String dir = attr(reader, "dir");
                        Integer ndx = readIntAttr(reader, "ndx");
                        Double pos = readPos(reader);

                        current.baliseData.addBalise(
                                new BaliseData.Balise(id, name, dir, ndx, pos , current.id)
                        );
                        continue;
                    }

                    if (inBalises && "baliseGroup".equals(ln)) {
                        String id = attr(reader, "id");
                        String name = attr(reader, "name");

                        currentBaliseGroup = new BaliseData.BaliseGroup(id, name);
                        current.baliseData.addGroup(currentBaliseGroup);
                        inBaliseGroup = true;
                        continue;
                    }

                    if (inBaliseGroup && "baliseRef".equals(ln)) {
                        String ref = attr(reader, "ref");
                        if (currentBaliseGroup != null) {
                            currentBaliseGroup.addBaliseRef(ref);
                        }
                        continue;
                    }
                    
                    

                } else if (ev == XMLStreamConstants.END_ELEMENT) {
                    String ln = reader.getLocalName();

                    if ("track".equals(ln)) {
                        if (current != null) {
                            current.finalizeSpeedProfile();
                           // current.finalizeBaliseData();
                            segments.add(current);
                        }
                        current = null;
                        continue;
                    }

                    if ("trackTopology".equals(ln)) inTrackTopology = false;
                    if ("trackBegin".equals(ln)) inTrackBegin = false;
                    if ("trackEnd".equals(ln)) inTrackEnd = false;
                    if ("connections".equals(ln)) inConnections = false;
                    if ("switch".equals(ln)) inSwitch = false;
                    if ("speedChanges".equals(ln)) inSpeedChanges = false;
                    if ("trackElements".equals(ln)) inTrackElements = false;
                    if ("signals".equals(ln)) inSignals = false; //Señales
                    if ("baliseGroup".equals(ln)) {
                        inBaliseGroup = false;
                        currentBaliseGroup = null;
                    }

                    if ("balises".equals(ln)) inBalises = false;
                    if ("ocsElements".equals(ln)) inOcsElements = false;
                }
            }

            reader.close();
        }

        return segments;
    }

    private static Double readAbsPos(XMLStreamReader r) {
        return readDoubleAttr(r, "absPos");
    }

    private static Double readPos(XMLStreamReader r) {
        return readDoubleAttr(r, "pos");
    }

    private static Double readDoubleAttr(XMLStreamReader r, String attrName) {
        String v = attr(r, attrName);
        if (v == null) return null;

        try {
            return Double.parseDouble(v);
        } catch (NumberFormatException ex) {
            return null;
        }
    }

    private static String attr(XMLStreamReader r, String name) {
        String v = r.getAttributeValue(null, name);
        return (v == null || v.isBlank()) ? null : v;
    }
    
    private static Integer readIntAttr (XMLStreamReader r , String attrName) {
    	String v = attr(r , attrName);
    	if (v == null ) return null;
    	try {
    		return Integer.parseInt(v);
    	}catch(NumberFormatException ex) {
    		return null; 
    	}
     
    }
}