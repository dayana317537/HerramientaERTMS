package idk;

import javax.xml.stream.XMLInputFactory;
import javax.xml.stream.XMLStreamConstants;
import javax.xml.stream.XMLStreamReader;

import java.io.FileInputStream;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

import idk.Segment.Side;
import idk.Segment.Edge;

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
                        continue;
                    }

                    if (current == null) continue;

                    if ("trackTopology".equals(ln)) { inTrackTopology = true; continue; }
                    if (!inTrackTopology) continue;

                    if ("trackBegin".equals(ln)) {
                        inTrackBegin = true;
                        Double p = readPos(reader);
                        if (p != null) current.beginAbsPos = p;
                        continue;
                    }

                    if ("trackEnd".equals(ln)) {
                        inTrackEnd = true;
                        Double p = readPos(reader);
                        if (p != null) current.endAbsPos = p;
                        continue;
                    }

                    if ("connections".equals(ln)) { inConnections = true; continue; }
                    if (inConnections && "switch".equals(ln)) { inSwitch = true; continue; }

                    if ("connection".equals(ln)) {
                        String ref = attr(reader, "ref");
                        if (ref == null) continue;

                        String toId = Segment.neighborIdFromRef(ref);
                        if (toId == null) continue;

                        Side fromSide = Side.UNKNOWN;

                        if (inTrackBegin) fromSide = Side.BEGIN;
                        else if (inTrackEnd) fromSide = Side.END;

                        if (inSwitch) {
                            String orientation = attr(reader, "orientation");
                            if (!"outgoing".equalsIgnoreCase(orientation)) continue;
                            fromSide = Side.END; // aproximación razonable para tu railML
                        }

                        Side toSide = Segment.toSideFromRef(ref);

                        current.outEdges.add(new Edge(toId, fromSide, toSide));
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
                    if ("switch".equals(ln)) inSwitch = false;
                }
            }
            reader.close();
        }

        return segments;
    }

    private static Double readPos(XMLStreamReader r) {
        String v = attr(r, "absPos");
        if (v == null) v = attr(r, "pos");
        if (v == null) return null;
        try { return Double.parseDouble(v); }
        catch (NumberFormatException ex) { return null; }
    }

    private static String attr(XMLStreamReader r, String name) {
        String v = r.getAttributeValue(null, name);
        return (v == null || v.isBlank()) ? null : v;
    }
}