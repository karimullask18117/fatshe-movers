package com.fatshe.movers;

import java.io.IOException;
import java.io.Reader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Properties;

/** Business details, routes, goods and rates, all read from config.properties. */
final class Site {
    record Goods(String id, String label) {}

    /** {@code from} true means the rate starts at {@code min} and rises with the load. */
    record Rate(long min, long max, boolean from) {}

    record Route(String id, String label, String city, boolean inTable, Map<String, Rate> rates) {}

    private final Properties props;
    final List<Goods> goods = new ArrayList<>();
    final List<Route> routes = new ArrayList<>();
    private final Map<String, Goods> goodsIndex = new HashMap<>();
    private final Map<String, Route> routeIndex = new HashMap<>();

    static Site load(Path file) throws IOException {
        Properties p = new Properties();
        try (Reader r = Files.newBufferedReader(file, StandardCharsets.UTF_8)) {
            p.load(r);
        }
        return new Site(p);
    }

    private Site(Properties p) {
        this.props = p;
        need("business.name");
        need("phone.jio");
        need("whatsapp");

        for (String id : list("goods")) {
            Goods g = new Goods(id, need("goods." + id));
            goods.add(g);
            goodsIndex.put(id, g);
        }
        if (goods.isEmpty()) throw new IllegalStateException("Config: 'goods' must list at least one item");

        for (String id : list("routes")) {
            Map<String, Rate> rates = new LinkedHashMap<>();
            for (Goods g : goods) {
                String key = "rate." + id + "." + g.id();
                String v = get(key);
                if (!v.isEmpty()) rates.put(g.id(), parseRate(key, v));
            }
            boolean inTable = !"false".equalsIgnoreCase(get("route." + id + ".table"));
            Route r = new Route(id, need("route." + id + ".label"), get("route." + id + ".city"), inTable, rates);
            routes.add(r);
            routeIndex.put(id, r);
        }
        if (routes.isEmpty()) throw new IllegalStateException("Config: 'routes' must list at least one route");
    }

    String get(String key) {
        String v = props.getProperty(key);
        return v == null ? "" : v.trim();
    }

    Goods goods(String id) { return id == null ? null : goodsIndex.get(id); }

    Route route(String id) { return id == null ? null : routeIndex.get(id); }

    /** Text such as "from Rs 22,000" or "Rs 28,000 to 32,000", or null when the route has no listed rate. */
    String rateText(Route route, String goodsId) {
        Rate r = route.rates().get(goodsId);
        if (r == null) return null;
        return r.from() ? "from Rs " + inr(r.min()) : "Rs " + inr(r.min()) + " to " + inr(r.max());
    }

    /** Indian digit grouping: 28,000 and 1,10,000. */
    static String inr(long n) {
        String s = Long.toString(n);
        if (s.length() <= 3) return s;
        String rest = s.substring(0, s.length() - 3);
        StringBuilder b = new StringBuilder();
        for (int i = 0; i < rest.length(); i++) {
            if (i > 0 && (rest.length() - i) % 2 == 0) b.append(',');
            b.append(rest.charAt(i));
        }
        return b.append(',').append(s, s.length() - 3, s.length()).toString();
    }

    /** Public data the page needs for its live estimate. */
    String apiJson() {
        StringBuilder b = new StringBuilder("{\"routes\":[");
        for (int i = 0; i < routes.size(); i++) {
            Route r = routes.get(i);
            if (i > 0) b.append(',');
            b.append("{\"id\":").append(Json.str(r.id())).append(",\"city\":").append(Json.str(r.city())).append('}');
        }
        b.append("],\"rates\":{");
        boolean firstRoute = true;
        for (Route r : routes) {
            if (!firstRoute) b.append(',');
            firstRoute = false;
            b.append(Json.str(r.id())).append(":{");
            boolean firstGoods = true;
            for (Goods g : goods) {
                String t = rateText(r, g.id());
                if (t == null) continue;
                if (!firstGoods) b.append(',');
                firstGoods = false;
                b.append(Json.str(g.id())).append(':').append(Json.str(t));
            }
            b.append('}');
        }
        return b.append("}}").toString();
    }

    private List<String> list(String key) {
        List<String> out = new ArrayList<>();
        for (String s : get(key).split(",")) {
            String t = s.trim();
            if (!t.isEmpty()) out.add(t);
        }
        return out;
    }

    private String need(String key) {
        String v = get(key);
        if (v.isEmpty()) throw new IllegalStateException("Config: '" + key + "' is required");
        return v;
    }

    private static Rate parseRate(String key, String v) {
        try {
            if (v.endsWith("+")) {
                return new Rate(Long.parseLong(v.substring(0, v.length() - 1).trim()), 0, true);
            }
            int dash = v.indexOf('-');
            if (dash > 0) {
                long min = Long.parseLong(v.substring(0, dash).trim());
                long max = Long.parseLong(v.substring(dash + 1).trim());
                if (min > max) throw new IllegalStateException("Config: '" + key + "' has min above max");
                return new Rate(min, max, false);
            }
        } catch (NumberFormatException e) {
            // falls through to the error below
        }
        throw new IllegalStateException("Config: '" + key + "' must look like 22000+ or 28000-32000, got '" + v + "'");
    }
}
