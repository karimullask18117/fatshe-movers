package com.fatshe.movers;

import com.sun.net.httpserver.Headers;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;

import java.io.IOException;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.net.URLDecoder;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.InvalidPathException;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.time.LocalDate;
import java.time.ZoneId;
import java.time.ZonedDateTime;
import java.time.format.DateTimeParseException;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.Executors;
import java.util.regex.Pattern;

/**
 * Fatshe Packers and Movers: web server for the site and the quote API.
 * Uses only the JDK (Java 17 or newer).
 *
 *   GET  /               the page, filled from config.properties
 *   GET  /api/site       rates for the live estimate
 *   POST /api/quotes     save a quote request (form encoded)
 *   GET  /api/quotes     download all requests as CSV (needs ADMIN_TOKEN)
 *   GET  /healthz        liveness check
 */
public final class Main {
    private static final ZoneId IST = ZoneId.of("Asia/Kolkata");
    private static final int MAX_BODY = 8 * 1024;
    private static final Pattern EMAIL = Pattern.compile("^[^@\\s]{1,64}@[^@\\s]{1,200}\\.[^@\\s]{2,}$");
    private static final Map<String, String> TYPES = Map.of(
            "html", "text/html; charset=utf-8",
            "css", "text/css; charset=utf-8",
            "js", "text/javascript; charset=utf-8",
            "svg", "image/svg+xml",
            "png", "image/png",
            "webp", "image/webp",
            "ico", "image/x-icon",
            "txt", "text/plain; charset=utf-8",
            "json", "application/json; charset=utf-8",
            "woff2", "font/woff2");
    private static final String CSP = "default-src 'self'; style-src 'self' https://fonts.googleapis.com; "
            + "font-src https://fonts.gstatic.com; img-src 'self' data:; connect-src 'self'; "
            + "frame-ancestors 'none'; base-uri 'none'; form-action 'self'";

    private final Site site;
    private final QuoteStore store;
    private final Path publicDir;
    private final String adminToken;
    private final boolean trustProxy;
    private final RateLimiter perIp = new RateLimiter(8, 10 * 60_000L);
    private final RateLimiter overall = new RateLimiter(300, 60 * 60_000L);

    private Main(Site site, QuoteStore store, Path publicDir, String adminToken, boolean trustProxy) {
        this.site = site;
        this.store = store;
        this.publicDir = publicDir;
        this.adminToken = adminToken;
        this.trustProxy = trustProxy;
    }

    public static void main(String[] args) throws Exception {
        int port = Integer.parseInt(env("PORT", "8080"));
        String host = env("BIND", "0.0.0.0");
        Path config = Path.of(env("CONFIG", "config.properties"));
        Path publicDir = Path.of(env("PUBLIC_DIR", "public")).toAbsolutePath().normalize();
        Path dataDir = Path.of(env("DATA_DIR", "data"));
        String token = System.getenv("ADMIN_TOKEN");
        if (token != null && token.isBlank()) token = null;
        if (token != null && token.length() < 16) {
            System.err.println("ADMIN_TOKEN must be at least 16 characters.");
            System.exit(1);
        }

        Site site;
        try {
            site = Site.load(config);
        } catch (IOException | IllegalStateException e) {
            System.err.println("Could not load " + config + ": " + e.getMessage());
            System.exit(1);
            return;
        }
        if (!Files.isRegularFile(publicDir.resolve("index.html"))) {
            System.err.println("Missing " + publicDir.resolve("index.html"));
            System.exit(1);
        }
        for (String key : List.of("address", "email", "gst", "insurance")) {
            if (site.get(key).isEmpty()) log("NOTE " + key + " is empty in " + config + ", so that part of the page is hidden.");
        }
        if (token == null) log("NOTE ADMIN_TOKEN is not set, so the CSV download is switched off.");

        Main app = new Main(site, new QuoteStore(dataDir), publicDir, token, "true".equalsIgnoreCase(env("TRUST_PROXY", "false")));
        HttpServer server = HttpServer.create(new InetSocketAddress(host, port), 0);
        server.createContext("/", app::handle);
        server.setExecutor(Executors.newVirtualThreadPerTaskExecutor());
        server.start();
        log("Listening on http://" + (host.equals("0.0.0.0") ? "localhost" : host) + ":" + port);
    }

    // ---------------------------------------------------------------- routing

    private void handle(HttpExchange ex) {
        int status = 500;
        String line = ex.getRequestMethod() + " " + ex.getRequestURI().getPath();
        try {
            status = dispatch(ex);
        } catch (Exception e) {
            log("ERROR " + line + " " + e);
            try {
                status = json(ex, 500, error("Something went wrong on our side. Please call us and we will take your details."));
            } catch (Exception ignored) {
                // response may already be partly sent
            }
        } finally {
            ex.close();
            log(status + " " + line);
        }
    }

    private int dispatch(HttpExchange ex) throws IOException {
        String path = ex.getRequestURI().getPath();
        String method = ex.getRequestMethod();
        boolean read = method.equals("GET") || method.equals("HEAD");

        if (path.equals("/healthz")) {
            return read ? send(ex, 200, "text/plain; charset=utf-8", "ok".getBytes(StandardCharsets.UTF_8), "no-store") : notAllowed(ex, "GET, HEAD");
        }
        if (path.equals("/api/site")) {
            if (!read) return notAllowed(ex, "GET, HEAD");
            return send(ex, 200, TYPES.get("json"), site.apiJson().getBytes(StandardCharsets.UTF_8), "public, max-age=60");
        }
        if (path.equals("/api/quotes")) {
            if (method.equals("POST")) return createQuote(ex);
            if (method.equals("GET")) return downloadQuotes(ex);
            return notAllowed(ex, "GET, POST");
        }
        if (path.startsWith("/api/")) return json(ex, 404, error("Not found."));
        if (!read) return notAllowed(ex, "GET, HEAD");
        return serveStatic(ex, path);
    }

    // ---------------------------------------------------------------- quote API

    private int createQuote(HttpExchange ex) throws IOException {
        String ip = clientIp(ex);
        if (!overall.allow("all") || !perIp.allow(ip)) {
            ex.getResponseHeaders().set("Retry-After", String.valueOf(perIp.retryAfterSeconds(ip)));
            return json(ex, 429, error("Too many requests. Please try again in a few minutes, or call us."));
        }
        String type = ex.getRequestHeaders().getFirst("Content-Type");
        if (type == null || !type.toLowerCase(Locale.ROOT).startsWith("application/x-www-form-urlencoded")) {
            return json(ex, 415, error("Unsupported content type."));
        }
        byte[] raw = ex.getRequestBody().readNBytes(MAX_BODY + 1);
        if (raw.length > MAX_BODY) return json(ex, 413, error("Request is too large."));

        Map<String, String> form;
        try {
            form = parseForm(new String(raw, StandardCharsets.UTF_8));
        } catch (IllegalArgumentException e) {
            return json(ex, 400, error("Could not read the form."));
        }

        // Hidden field that people never fill. Bots do, so they get a fake success and nothing is saved.
        if (!clean(form.get("company")).isEmpty()) {
            return json(ex, 200, "{\"ok\":true,\"reference\":\"FQ-0000\",\"rate\":null,\"whatsappUrl\":null}");
        }

        String name = clean(form.get("name"));
        String mobile = clean(form.get("mobile")).replaceAll("\\D", "");
        String email = clean(form.get("email"));
        String date = clean(form.get("date"));
        String from = clean(form.get("from"));
        String to = clean(form.get("to"));
        Site.Route route = site.route(clean(form.get("route")));
        Site.Goods goods = site.goods(clean(form.get("goods")));

        if (mobile.length() == 12 && mobile.startsWith("91")) mobile = mobile.substring(2);
        else if (mobile.length() == 11 && mobile.startsWith("0")) mobile = mobile.substring(1);

        Map<String, String> errors = new LinkedHashMap<>();
        if (name.length() < 2 || name.length() > 80) errors.put("name", "Enter your name, 2 to 80 characters.");
        if (!mobile.matches("[6-9]\\d{9}")) errors.put("mobile", "Enter a 10-digit mobile number.");
        if (!email.isEmpty() && (email.length() > 120 || !EMAIL.matcher(email).matches())) errors.put("email", "Enter a valid email address, or leave it empty.");
        if (route == null) errors.put("route", "Choose a route.");
        if (goods == null) errors.put("goods", "Choose what we are moving.");
        if (!date.isEmpty()) {
            try {
                LocalDate d = LocalDate.parse(date);
                LocalDate today = LocalDate.now(IST);
                if (d.isBefore(today)) errors.put("date", "Choose today or a later date.");
                else if (d.isAfter(today.plusYears(2))) errors.put("date", "Choose a date within the next two years.");
            } catch (DateTimeParseException e) {
                errors.put("date", "Enter a valid date.");
            }
        }
        if (from.length() > 200) errors.put("from", "Keep this under 200 characters.");
        if (to.length() > 200) errors.put("to", "Keep this under 200 characters.");
        if (!errors.isEmpty()) return json(ex, 422, errorsJson(errors));

        String rate = site.rateText(route, goods.id());
        String ref = store.append(ZonedDateTime.now(IST), List.of(
                name, mobile, email, route.label(), goods.label(), date, from, to, rate == null ? "Quote on survey" : rate));
        log("QUOTE " + ref + " " + route.id() + " " + goods.id());

        String waNumber = site.get("whatsapp");
        String waUrl = null;
        if (!waNumber.isEmpty()) {
            List<String> lines = new ArrayList<>();
            lines.add("Hello, I would like a quote for shifting. Reference " + ref + ".");
            lines.add("Name: " + name);
            lines.add("Mobile: " + mobile);
            lines.add("Route: " + route.label());
            lines.add("Moving: " + goods.label());
            if (!date.isEmpty()) lines.add("Date of shifting: " + date);
            if (!from.isEmpty()) lines.add("From: " + from);
            if (!to.isEmpty()) lines.add("To: " + to);
            if (rate != null) lines.add("Indicative rate on your page: " + rate);
            waUrl = "https://wa.me/" + waNumber + "?text="
                    + URLEncoder.encode(String.join("\n", lines), StandardCharsets.UTF_8).replace("+", "%20");
        }
        return json(ex, 200, "{\"ok\":true,\"reference\":" + Json.str(ref) + ",\"rate\":" + Json.str(rate)
                + ",\"whatsappUrl\":" + Json.str(waUrl) + "}");
    }

    private int downloadQuotes(HttpExchange ex) throws IOException {
        if (adminToken == null) return json(ex, 404, error("Not found."));
        String auth = ex.getRequestHeaders().getFirst("Authorization");
        byte[] given = (auth == null ? "" : auth).getBytes(StandardCharsets.UTF_8);
        byte[] want = ("Bearer " + adminToken).getBytes(StandardCharsets.UTF_8);
        if (!MessageDigest.isEqual(given, want)) {
            ex.getResponseHeaders().set("WWW-Authenticate", "Bearer");
            return json(ex, 401, error("Unauthorized."));
        }
        ex.getResponseHeaders().set("Content-Disposition", "attachment; filename=\"quotes.csv\"");
        return send(ex, 200, "text/csv; charset=utf-8", store.readAll(), "no-store");
    }

    // ---------------------------------------------------------------- static files

    private int serveStatic(HttpExchange ex, String path) throws IOException {
        String rel = path.equals("/") ? "index.html" : path.substring(1);
        Path file;
        try {
            file = publicDir.resolve(rel).normalize();
        } catch (InvalidPathException e) {
            return text404(ex);
        }
        if (!file.startsWith(publicDir) || !Files.isRegularFile(file)) return text404(ex);
        String name = file.getFileName().toString();
        int dot = name.lastIndexOf('.');
        String type = dot < 0 ? null : TYPES.get(name.substring(dot + 1).toLowerCase(Locale.ROOT));
        if (type == null) return text404(ex);

        boolean page = file.equals(publicDir.resolve("index.html"));
        byte[] body = page
                ? Page.render(Files.readString(file, StandardCharsets.UTF_8), site).getBytes(StandardCharsets.UTF_8)
                : Files.readAllBytes(file);
        return send(ex, 200, type, body, page ? "no-cache" : "public, max-age=300");
    }

    private int text404(HttpExchange ex) throws IOException {
        return send(ex, 404, "text/plain; charset=utf-8", "Not found".getBytes(StandardCharsets.UTF_8), "no-store");
    }

    // ---------------------------------------------------------------- helpers

    private int json(HttpExchange ex, int status, String body) throws IOException {
        return send(ex, status, TYPES.get("json"), body.getBytes(StandardCharsets.UTF_8), "no-store");
    }

    private int notAllowed(HttpExchange ex, String allow) throws IOException {
        ex.getResponseHeaders().set("Allow", allow);
        return json(ex, 405, error("Method not allowed."));
    }

    private int send(HttpExchange ex, int status, String type, byte[] body, String cache) throws IOException {
        Headers h = ex.getResponseHeaders();
        h.set("Content-Type", type);
        h.set("Cache-Control", cache);
        h.set("Content-Security-Policy", CSP);
        h.set("X-Content-Type-Options", "nosniff");
        h.set("X-Frame-Options", "DENY");
        h.set("Referrer-Policy", "strict-origin-when-cross-origin");
        if (ex.getRequestMethod().equals("HEAD")) {
            h.set("Content-Length", String.valueOf(body.length));
            ex.sendResponseHeaders(status, -1);
        } else if (body.length == 0) {
            ex.sendResponseHeaders(status, -1);
        } else {
            ex.sendResponseHeaders(status, body.length);
            try (OutputStream os = ex.getResponseBody()) {
                os.write(body);
            }
        }
        return status;
    }

    private String clientIp(HttpExchange ex) {
        if (trustProxy) {
            String xff = ex.getRequestHeaders().getFirst("X-Forwarded-For");
            if (xff != null && !xff.isBlank()) return xff.split(",")[0].trim();
        }
        return ex.getRemoteAddress().getAddress().getHostAddress();
    }

    private static Map<String, String> parseForm(String body) {
        Map<String, String> out = new LinkedHashMap<>();
        int count = 0;
        for (String pair : body.split("&")) {
            if (pair.isEmpty()) continue;
            if (++count > 30) throw new IllegalArgumentException("too many fields");
            int eq = pair.indexOf('=');
            String k = URLDecoder.decode(eq < 0 ? pair : pair.substring(0, eq), StandardCharsets.UTF_8);
            String v = eq < 0 ? "" : URLDecoder.decode(pair.substring(eq + 1), StandardCharsets.UTF_8);
            out.putIfAbsent(k, v);
        }
        return out;
    }

    /** Trims, turns control characters and runs of spaces into one space. */
    private static String clean(String s) {
        if (s == null) return "";
        return s.replaceAll("[\\p{Cntrl}\\u2028\\u2029]+", " ").replaceAll("\\s+", " ").trim();
    }

    private static String error(String message) {
        return "{\"ok\":false,\"error\":" + Json.str(message) + "}";
    }

    private static String errorsJson(Map<String, String> errors) {
        StringBuilder b = new StringBuilder("{\"ok\":false,\"errors\":{");
        boolean first = true;
        for (Map.Entry<String, String> e : errors.entrySet()) {
            if (!first) b.append(',');
            first = false;
            b.append(Json.str(e.getKey())).append(':').append(Json.str(e.getValue()));
        }
        return b.append("}}").toString();
    }

    private static String env(String key, String fallback) {
        String v = System.getenv(key);
        return v == null || v.isBlank() ? fallback : v.trim();
    }

    private static void log(String message) {
        System.out.println(ZonedDateTime.now(IST).toLocalDateTime().withNano(0) + " " + message);
    }
}
