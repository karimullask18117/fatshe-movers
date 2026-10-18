package com.fatshe.movers;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.time.ZonedDateTime;
import java.time.format.DateTimeFormatter;
import java.util.List;

/**
 * Saves quote requests to data/quotes.csv, which opens directly in Excel.
 * Cells that start with a formula character are neutralised so a hostile
 * request cannot run a formula when the owner opens the file.
 */
final class QuoteStore {
    private static final String HEADER =
            "reference,received_at,name,mobile,email,route,goods,shifting_date,from,to,indicative_rate\n";
    private static final DateTimeFormatter DAY = DateTimeFormatter.ofPattern("yyyyMMdd");
    private static final DateTimeFormatter STAMP = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss");

    private final Path file;
    private final Object lock = new Object();
    private long seq;

    QuoteStore(Path dir) throws IOException {
        Files.createDirectories(dir);
        this.file = dir.resolve("quotes.csv");
        if (Files.exists(file)) {
            seq = Math.max(0, Files.readAllLines(file, StandardCharsets.UTF_8).size() - 1);
        } else {
            Files.writeString(file, HEADER, StandardCharsets.UTF_8, StandardOpenOption.CREATE_NEW);
        }
    }

    /** Appends one request and returns its reference number. */
    String append(ZonedDateTime now, List<String> fields) throws IOException {
        synchronized (lock) {
            String ref = "FQ-" + DAY.format(now) + "-" + String.format("%04d", seq + 1);
            StringBuilder row = new StringBuilder(csv(ref)).append(',').append(csv(STAMP.format(now)));
            for (String f : fields) row.append(',').append(csv(f));
            row.append('\n');
            Files.writeString(file, row.toString(), StandardCharsets.UTF_8, StandardOpenOption.APPEND);
            seq++;
            return ref;
        }
    }

    byte[] readAll() throws IOException {
        synchronized (lock) {
            return Files.readAllBytes(file);
        }
    }

    static String csv(String value) {
        String v = value == null ? "" : value;
        if (!v.isEmpty() && "=+-@\t\r".indexOf(v.charAt(0)) >= 0) v = "'" + v;
        return "\"" + v.replace("\"", "\"\"") + "\"";
    }
}
