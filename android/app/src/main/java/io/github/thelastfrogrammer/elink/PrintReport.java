package io.github.thelastfrogrammer.elink;

import java.util.List;
import java.util.Locale;

/**
 * One print as a single self-contained web page to share (a forum post, a message): what was printed and how it went, the
 * charts and a handful of pictures, all inside the file. Holds nothing that names the printer or the network: no serial,
 * address or access code; the notes are the person's own.
 */
final class PrintReport {
    /** A picture or chart for the page: its caption and its bytes (PNG for charts, JPEG for pictures). */
    static final class Image {
        final String caption, type; final byte[] bytes;
        Image(String caption, String type, byte[] bytes) { this.caption = caption; this.type = type; this.bytes = bytes; }
    }

    /** The page. Every text is escaped; images are embedded as data. Pure, for tests. */
    static String html(String title, String subtitle, List<String> facts, List<String> notes, List<String> checks, List<Image> charts, List<Image> pictures, String made) {
        StringBuilder out = new StringBuilder(8192);
        out.append("<!doctype html><html lang=\"").append(Locale.getDefault().getLanguage()).append("\"><head><meta charset=\"utf-8\"><meta name=\"viewport\" content=\"width=device-width,initial-scale=1\">")
            .append("<title>").append(escape(title)).append("</title><style>")
            .append(":root{--ink:#17252c;--muted:#5a6d76;--bg:#f2f5f6;--card:#fff}")
            .append("@media (prefers-color-scheme:dark){:root{--ink:#e6eef1;--muted:#9fb3bb;--bg:#0e1417;--card:#182227}}")
            .append("body{margin:0;background:var(--bg);color:var(--ink);font:15px/1.45 system-ui,-apple-system,Roboto,sans-serif}")
            .append("main{max-width:860px;margin:0 auto;padding:16px}h1{font-size:22px;margin:8px 0 2px}h2{font-size:16px;margin:0 0 8px}")
            .append(".sub{color:var(--muted);margin:0 0 12px}section{background:var(--card);border-radius:16px;padding:14px 16px;margin:12px 0}")
            .append("ul{margin:0;padding-left:18px}li{margin:3px 0}figure{margin:10px 0}img{max-width:100%;height:auto;border-radius:8px;display:block}")
            .append("figcaption{color:var(--muted);font-size:13px;margin-top:4px}.grid{display:grid;grid-template-columns:repeat(auto-fill,minmax(220px,1fr));gap:10px}")
            .append("footer{color:var(--muted);font-size:12px;text-align:center;margin:16px 0}")
            .append("</style></head><body><main>");
        out.append("<h1>").append(escape(title)).append("</h1><p class=\"sub\">").append(escape(subtitle)).append("</p>");
        list(out, "How it went", facts);
        list(out, "Notes", notes);
        list(out, "Measured during the print", checks);
        if (!charts.isEmpty()) {
            out.append("<section><h2>Charts</h2>");
            for (Image c : charts) figure(out, c);
            out.append("</section>");
        }
        if (!pictures.isEmpty()) {
            out.append("<section><h2>Pictures</h2><div class=\"grid\">");
            for (Image p : pictures) figure(out, p);
            out.append("</div></section>");
        }
        out.append("<footer>").append(escape(made)).append("</footer></main></body></html>");
        return out.toString();
    }
    private static void list(StringBuilder out, String heading, List<String> lines) {
        if (lines.isEmpty()) return;
        out.append("<section><h2>").append(escape(heading)).append("</h2><ul>");
        for (String line : lines) out.append("<li>").append(escape(line)).append("</li>");
        out.append("</ul></section>");
    }
    private static void figure(StringBuilder out, Image image) {
        out.append("<figure><img alt=\"").append(escape(image.caption)).append("\" src=\"data:").append(image.type).append(";base64,")
            .append(java.util.Base64.getEncoder().encodeToString(image.bytes)).append("\"><figcaption>").append(escape(image.caption)).append("</figcaption></figure>");
    }
    static String escape(String text) {
        StringBuilder out = new StringBuilder(text.length());
        for (char c : text.toCharArray()) switch (c) {
            case '<': out.append("&lt;"); break; case '>': out.append("&gt;"); break; case '&': out.append("&amp;"); break;
            case '"': out.append("&quot;"); break; case '\'': out.append("&#39;"); break; default: out.append(c);
        }
        return out.toString();
    }
    /** Up to `count` of the pictures, evenly spread from first to last. Pure. */
    static int[] spread(int available, int count) {
        if (available <= 0 || count <= 0) return new int[0];
        if (available <= count) { int[] all = new int[available]; for (int i = 0; i < available; i++) all[i] = i; return all; }
        int[] out = new int[count];
        for (int i = 0; i < count; i++) out[i] = (int) Math.round(i * (available - 1) / (double) (count - 1));
        return out;
    }

    private PrintReport() { }
}
