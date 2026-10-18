package com.fatshe.movers;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Fills public/index.html on the server, so phone numbers, rates and the table are
 * in the HTML itself (search engines and no-JavaScript visitors see them).
 *
 * <pre>
 *   {{key}}                    HTML-escaped value from config
 *   {{{key}}}                  raw HTML generated here (options, rates table)
 *   &lt;!--if:key-->...&lt;!--/if:key-->   kept only when the config value is not empty
 * </pre>
 */
final class Page {
    private static final Pattern IF = Pattern.compile("<!--if:([A-Za-z0-9]+)-->(.*?)<!--/if:\\1-->", Pattern.DOTALL);
    private static final Pattern RAW = Pattern.compile("\\{\\{\\{([A-Za-z0-9]+)\\}\\}\\}");
    private static final Pattern ESC = Pattern.compile("\\{\\{([A-Za-z0-9]+)\\}\\}");

    private Page() {}

    static String render(String template, Site site) {
        Map<String, String> values = new LinkedHashMap<>();
        values.put("name", site.get("business.name"));
        values.put("phoneJio", site.get("phone.jio"));
        values.put("phoneAirtel", site.get("phone.airtel"));
        values.put("whatsapp", site.get("whatsapp"));
        values.put("hours1", site.get("hours.line1"));
        values.put("hours2", site.get("hours.line2"));
        values.put("address", site.get("address"));
        values.put("email", site.get("email"));
        values.put("gst", site.get("gst"));
        values.put("insurance", site.get("insurance"));

        Map<String, String> raw = new LinkedHashMap<>();
        raw.put("routeOptions", routeOptions(site));
        raw.put("goodsOptions", goodsOptions(site));
        raw.put("ratesTable", ratesTable(site));

        String out = IF.matcher(template).replaceAll(m -> {
            String v = values.get(m.group(1));
            return Matcher.quoteReplacement(v != null && !v.isEmpty() ? m.group(2) : "");
        });
        out = RAW.matcher(out).replaceAll(m -> Matcher.quoteReplacement(raw.getOrDefault(m.group(1), "")));
        out = ESC.matcher(out).replaceAll(m -> Matcher.quoteReplacement(esc(values.getOrDefault(m.group(1), ""))));
        return out;
    }

    static String esc(String s) {
        StringBuilder b = new StringBuilder(s.length() + 8);
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            switch (c) {
                case '&' -> b.append("&amp;");
                case '<' -> b.append("&lt;");
                case '>' -> b.append("&gt;");
                case '"' -> b.append("&quot;");
                case '\'' -> b.append("&#39;");
                default -> b.append(c);
            }
        }
        return b.toString();
    }

    private static String routeOptions(Site site) {
        StringBuilder b = new StringBuilder("<option value=\"\" selected disabled>Choose a route</option>");
        for (Site.Route r : site.routes) {
            b.append("<option value=\"").append(esc(r.id())).append("\">").append(esc(r.label())).append("</option>");
        }
        return b.toString();
    }

    private static String goodsOptions(Site site) {
        StringBuilder b = new StringBuilder("<option value=\"\" selected disabled>Choose what we move</option>");
        for (Site.Goods g : site.goods) {
            b.append("<option value=\"").append(esc(g.id())).append("\">").append(esc(g.label())).append("</option>");
        }
        return b.toString();
    }

    private static String ratesTable(Site site) {
        StringBuilder b = new StringBuilder("<thead><tr><th scope=\"col\">Route</th>");
        for (Site.Goods g : site.goods) b.append("<th scope=\"col\">").append(esc(g.label())).append("</th>");
        b.append("</tr></thead><tbody>");
        for (Site.Route r : site.routes) {
            if (!r.inTable()) continue;
            b.append("<tr><th scope=\"row\">").append(esc(r.label())).append("</th>");
            for (Site.Goods g : site.goods) {
                String t = site.rateText(r, g.id());
                if (t == null) b.append("<td class=\"q\">Quote on survey</td>");
                else b.append("<td>").append(esc(t)).append("</td>");
            }
            b.append("</tr>");
        }
        return b.append("</tbody>").toString();
    }
}
