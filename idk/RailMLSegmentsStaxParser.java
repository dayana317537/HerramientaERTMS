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

            Double currentTrackEndPos = null;
            Double currentSwitchPos = null;

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
                        currentTrackEndPos = null;
                        currentSwitchPos = null;
                        continue;
                    }

                    if (current == null) continue;

                    if ("trackTopology".equals(ln)) {
                        inTrackTopology = true;
                        continue;
                    }

                    if (!inTrackTopology) continue;

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

                        Double pos = readPos(reader);
                        if (pos != null) currentTrackEndPos = pos;

                        continue;
                    }

                    if ("connections".equals(ln)) {
                        inConnections = true;
                        continue;
                    }

                    if (inConnections && "switch".equals(ln)) {
                        inSwitch = true;
                        currentSwitchPos = readPos(reader);
                        continue;
                    }

                    if ("connection".equals(ln)) {
                        String ref = attr(reader, "ref");
                        String toId = Segment.neighborIdFromRef(ref);

                        if (toId == null) continue;

                        // 1) Conexión en trackBegin -> INVERSA
                        if (inTrackBegin) {
                            current.addInverseNeighbor(toId);
                            continue;
                        }

                        // 2) Conexión en trackEnd -> DIRECTA
                        if (inTrackEnd) {
                            current.addDirectNeighbor(toId);
                            continue;
                        }

                        // 3) Conexiones de switch
                        if (inSwitch) {
                            String orientation = attr(reader, "orientation");

                            // En tu XML:
                            // - incoming siempre en pos=0  -> lado begin -> INVERSA
                            // - outgoing siempre en endPos -> lado end   -> DIRECTA
                            if ("incoming".equalsIgnoreCase(orientation)) {
                                current.addInverseNeighbor(toId);
                            } else if ("outgoing".equalsIgnoreCase(orientation)) {
                                current.addDirectNeighbor(toId);
                            }
                        }
                    }

                } else if (ev == XMLStreamConstants.END_ELEMENT) {
                    String ln = reader.getLocalName();

                    if ("track".equals(ln)) {
                        if (current != null) segments.add(current);
                        current = null;
                        continue;
                    }

                    if ("trackTopology".equals(ln)) inTrackTopology = false;
                    if ("trackBegin".equals(ln)) inTrackBegin = false;
                    if ("trackEnd".equals(ln)) inTrackEnd = false;
                    if ("connections".equals(ln)) inConnections = false;

                    if ("switch".equals(ln)) {
                        inSwitch = false;
                        currentSwitchPos = null;
                    }
                }
            }

            reader.close();
        }

        return segments;
    }

    private static Double readAbsPos(XMLStreamReader r) {
        String v = attr(r, "absPos");
        if (v == null) return null;

        try {
            return Double.parseDouble(v);
        } catch (NumberFormatException ex) {
            return null;
        }
    }

    private static Double readPos(XMLStreamReader r) {
        String v = attr(r, "pos");
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
}