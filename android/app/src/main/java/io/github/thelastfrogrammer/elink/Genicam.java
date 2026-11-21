package io.github.thelastfrogrammer.elink;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.zip.ZipEntry;
import java.util.zip.ZipInputStream;
import javax.xml.parsers.DocumentBuilderFactory;
import org.w3c.dom.Element;
import org.w3c.dom.Node;
import org.w3c.dom.NodeList;

/**
 * A camera's GenICam description, read from the camera itself, as far as the app needs it: integer, boolean,
 * enumeration and command features whose value lives in one register (directly, or through pValue), with fixed or
 * pointed-to addresses and bit fields. Features computed by formulas are not supported, and are reported as such
 * rather than guessed. Independent of Android.
 */
final class Genicam {
    /** Register access: GigeVision.Control in use, a fake in tests. */
    interface Port {
        byte[] read(long address, int length) throws IOException;
        void write(long address, byte[] data) throws IOException;
    }

    private final Map<String, Element> nodes = new HashMap<>();
    private final Port port;

    Genicam(String xml, Port port) throws IOException {
        this.port = port;
        try {
            DocumentBuilderFactory f = DocumentBuilderFactory.newInstance(); f.setNamespaceAware(true);
            try { f.setFeature("http://apache.org/xml/features/disallow-doctype-decl", true); } catch (Exception unsupported) { }
            f.setExpandEntityReferences(false);
            Element root = f.newDocumentBuilder().parse(new ByteArrayInputStream(xml.getBytes(StandardCharsets.UTF_8))).getDocumentElement();
            collect(root);
        } catch (Exception unreadable) { throw new IOException("the camera's description could not be read"); }
    }
    private void collect(Element e) {
        if (e.hasAttribute("Name") && !"EnumEntry".equals(tag(e)) && !"StructEntry".equals(tag(e))) nodes.put(e.getAttribute("Name"), e);
        if ("StructEntry".equals(tag(e)) && e.hasAttribute("Name")) nodes.put(e.getAttribute("Name"), e);
        for (Node c = e.getFirstChild(); c != null; c = c.getNextSibling()) if (c instanceof Element) collect((Element) c);
    }
    private static String tag(Element e) { String n = e.getLocalName() != null ? e.getLocalName() : e.getTagName(); int colon = n.indexOf(':'); return colon < 0 ? n : n.substring(colon + 1); }
    private static Element child(Element e, String name) {
        for (Node c = e.getFirstChild(); c != null; c = c.getNextSibling()) if (c instanceof Element && tag((Element) c).equals(name)) return (Element) c;
        return null;
    }
    private static List<Element> children(Element e, String name) {
        List<Element> out = new ArrayList<>();
        for (Node c = e.getFirstChild(); c != null; c = c.getNextSibling()) if (c instanceof Element && tag((Element) c).equals(name)) out.add((Element) c);
        return out;
    }
    private static String text(Element e, String name) { Element c = child(e, name); return c == null ? null : c.getTextContent().trim(); }
    static long number(String s) {
        s = s.trim();
        return s.startsWith("0x") || s.startsWith("0X") ? Long.parseUnsignedLong(s.substring(2), 16) : Long.parseLong(s);
    }

    boolean has(String feature) { return nodes.containsKey(feature); }

    /** Whether a feature can be read and written as the app does it (a value in one register, no formula). */
    boolean usable(String feature) {
        Element e = nodes.get(feature);
        for (int depth = 0; e != null && depth < 8; depth++) {
            String t = tag(e);
            if (isRegister(t)) return true;
            if (child(e, "Value") != null && child(e, "pValue") == null) return true;
            String p = text(e, "pValue"); if (p == null) return false;
            e = nodes.get(p);
        }
        return false;
    }

    /** An enumeration's entries by name, with their values. */
    Map<String, Long> entries(String feature) {
        Map<String, Long> out = new LinkedHashMap<>(); Element e = nodes.get(feature);
        if (e == null) return out;
        for (Element entry : children(e, "EnumEntry")) {
            String v = text(entry, "Value"); if (v == null) continue;
            try { out.put(entry.getAttribute("Name"), number(v)); } catch (NumberFormatException odd) { }
        }
        return out;
    }

    /** A feature's value (an enumeration's as its number). */
    long get(String feature) throws IOException { return value(node(feature), 0); }
    /** Sets a feature's value (an enumeration's by number). */
    void set(String feature, long value) throws IOException { store(node(feature), value, 0); }
    /** Sets an enumeration by entry name; false when the camera does not offer that entry. */
    boolean choose(String feature, String entry) throws IOException {
        Long v = entries(feature).get(entry); if (v == null) return false;
        set(feature, v); return true;
    }
    /** The entry the enumeration holds now, or "" when its value is none of them. */
    String chosen(String feature) throws IOException {
        long v = get(feature);
        for (Map.Entry<String, Long> e : entries(feature).entrySet()) if (e.getValue() == v) return e.getKey();
        return "";
    }
    /** Runs a command feature (AcquisitionStart and the like). */
    void execute(String feature) throws IOException {
        Element e = node(feature);
        if (!"Command".equals(tag(e))) throw new IOException(feature + " is not a command");
        String value = text(e, "CommandValue"), pointer = text(e, "pCommandValue");
        long v = value != null ? number(value) : pointer != null ? value(node(pointer), 0) : 1;
        store(e, v, 0);
    }

    private Element node(String name) throws IOException {
        Element e = nodes.get(name); if (e == null) throw new IOException("the camera has no " + name);
        return e;
    }
    private static boolean isRegister(String t) { return t.equals("IntReg") || t.equals("MaskedIntReg") || t.equals("StructEntry") || t.equals("Register"); }

    private long value(Element e, int depth) throws IOException {
        if (depth > 8) throw new IOException("the camera's description loops");
        String t = tag(e);
        if (isRegister(t)) return readRegister(e);
        String p = text(e, "pValue");
        if (p != null) return value(node(p), depth + 1);
        String v = text(e, "Value");
        if (v != null) {
            if (t.equals("Boolean")) return number(v);
            return number(v);
        }
        throw new IOException(e.getAttribute("Name") + " is computed by the camera's description in a way the app does not read");
    }
    private void store(Element e, long v, int depth) throws IOException {
        if (depth > 8) throw new IOException("the camera's description loops");
        if (isRegister(tag(e))) { writeRegister(e, v); return; }
        String p = text(e, "pValue");
        if (p != null) { store(node(p), v, depth + 1); return; }
        throw new IOException(e.getAttribute("Name") + " cannot be set");
    }

    /** Where a register is, how long, its byte order, and the bits the feature uses. */
    private static final class Place { long address; int length; boolean little; int shift, bits; }
    private Place place(Element e) throws IOException {
        Element reg = e;
        Place p = new Place();
        if (tag(e).equals("StructEntry")) reg = (Element) e.getParentNode();
        long address = 0;
        for (Element a : children(reg, "Address")) address += number(a.getTextContent());
        for (Element a : children(reg, "pAddress")) address += value(node(a.getTextContent().trim()), 1);
        if (child(reg, "IntSwissKnife") != null || child(reg, "pIndex") != null) throw new IOException(reg.getAttribute("Name") + " has a computed address the app does not read");
        p.address = address;
        String length = text(reg, "Length"); p.length = length == null ? 4 : (int) number(length);
        p.little = "LittleEndian".equals(text(reg, "Endianess"));
        int width = p.length * 8; p.shift = 0; p.bits = width;
        String bit = text(e, "Bit"), lsb = text(e, "LSB"), msb = text(e, "MSB");
        if (bit != null) { lsb = bit; msb = bit; }
        if (lsb != null && msb != null) {
            int l = Integer.parseInt(lsb), m = Integer.parseInt(msb);
            if (p.little) { p.shift = l; p.bits = m - l + 1; }
            else { p.shift = width - 1 - l; p.bits = l - m + 1; }   // big-endian GenICam numbers bit 0 as the top bit
        }
        if (p.length != 4 && p.length != 8 || p.bits <= 0 || p.shift < 0 || p.shift + p.bits > width) throw new IOException(e.getAttribute("Name") + " has a register layout the app does not read");
        return p;
    }
    private long raw(Place p) throws IOException {
        byte[] b = port.read(p.address, p.length); long v = 0;
        for (int i = 0; i < p.length; i++) v |= (long) (b[p.little ? i : p.length - 1 - i] & 0xff) << (8 * i);
        return v;
    }
    private void put(Place p, long v) throws IOException {
        byte[] b = new byte[p.length];
        for (int i = 0; i < p.length; i++) b[p.little ? i : p.length - 1 - i] = (byte) (v >>> (8 * i));
        port.write(p.address, b);
    }
    private long readRegister(Element e) throws IOException {
        Place p = place(e); long v = raw(p) >>> p.shift;
        return p.bits >= 64 ? v : v & ((1L << p.bits) - 1);
    }
    private void writeRegister(Element e, long value) throws IOException {
        Place p = place(e);
        if (p.bits == p.length * 8) { put(p, value); return; }
        long mask = ((1L << p.bits) - 1) << p.shift;
        put(p, raw(p) & ~mask | (value << p.shift) & mask);
    }

    // ------------------------------------------------------------------ fetching it from the camera

    /** "Local:NAME.zip;ADDRESS;LENGTH" (hex): where the camera keeps its description. Null for any other kind of address. */
    static long[] localUrl(String url) {
        if (url == null || !url.regionMatches(true, 0, "Local:", 0, 6)) return null;
        String[] parts = url.substring(6).split(";");
        if (parts.length < 3) return null;
        try { return new long[] {Long.parseUnsignedLong(parts[1].trim().replaceFirst("(?i)^0x", ""), 16), Long.parseUnsignedLong(parts[2].trim().replaceFirst("(?i)^0x", ""), 16)}; }
        catch (NumberFormatException odd) { return null; }
    }
    /** The description's text: unzipped when it is a zip file, as is otherwise. */
    static String unpack(String url, byte[] data) throws IOException {
        String name = url.split(";")[0].toLowerCase(Locale.ROOT);
        if (name.endsWith(".zip") || data.length > 4 && data[0] == 'P' && data[1] == 'K') {
            try (ZipInputStream zip = new ZipInputStream(new ByteArrayInputStream(data))) {
                for (ZipEntry e; (e = zip.getNextEntry()) != null; ) if (e.getName().toLowerCase(Locale.ROOT).endsWith(".xml")) return new String(readAll(zip, 16 << 20), StandardCharsets.UTF_8);
            }
            throw new IOException("the camera's description file holds no XML");
        }
        int end = data.length; while (end > 0 && data[end - 1] == 0) end--;
        return new String(data, 0, end, StandardCharsets.UTF_8);
    }
    private static byte[] readAll(InputStream in, int limit) throws IOException {
        ByteArrayOutputStream out = new ByteArrayOutputStream(); byte[] buffer = new byte[16384]; int n, total = 0;
        while ((n = in.read(buffer)) > 0) { total += n; if (total > limit) throw new IOException("the camera's description is too large"); out.write(buffer, 0, n); }
        return out.toByteArray();
    }
}
