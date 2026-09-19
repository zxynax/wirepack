package com.cdrware.wirepack.bench;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * Side-by-side wirepack vs REST comparison report (Markdown + CSV).
 */
final class BenchReport {
    private final BenchArgs args;
    private final Instant started = Instant.now();
    private final List<ScenarioRow> rows = new ArrayList<>();

    BenchReport(BenchArgs args) {
        this.args = args;
    }

    void add(
            Scenario scenario,
            int count,
            int size,
            int sessions,
            String transport,
            BenchResult result
    ) {
        rows.add(new ScenarioRow(scenario, count, size, sessions, transport, result));
    }

    void write(Path markdownPath) throws IOException {
        Path parent = markdownPath.getParent();
        if (parent != null) {
            Files.createDirectories(parent);
        }
        Files.writeString(markdownPath, toMarkdown(), StandardCharsets.UTF_8);
        Files.writeString(sibling(markdownPath, ".csv"), toCsv(), StandardCharsets.UTF_8);
    }

    private static Path sibling(Path markdownPath, String newExt) {
        String name = markdownPath.getFileName().toString();
        int dot = name.lastIndexOf('.');
        String base = dot > 0 ? name.substring(0, dot) : name;
        Path parent = markdownPath.getParent();
        Path csv = Path.of(base + newExt);
        return parent == null ? csv : parent.resolve(csv);
    }

    String toMarkdown() {
        DateTimeFormatter fmt = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss z")
                .withZone(ZoneId.systemDefault());
        StringBuilder sb = new StringBuilder();
        sb.append("# wirepack vs REST throughput report\n\n");
        sb.append("| | |\n|---|---|\n");
        sb.append("| Generated | ").append(fmt.format(started)).append(" |\n");
        sb.append("| Scenario | `").append(args.scenario().label()).append("` |\n");
        sb.append("| Transport | `").append(args.transport().name().toLowerCase(Locale.ROOT)).append("` |\n");
        sb.append("| Wirepack crypto | ").append(args.crypto() ? "ChaCha20-Poly1305 AEAD" : "cleartext").append(" |\n");
        sb.append("| DATA loss | ").append(String.format(Locale.ROOT, "%.1f%%", args.loss() * 100)).append(" |\n");
        sb.append("| Runs (averaged) | ").append(args.runs()).append(" |\n");
        sb.append("| Batch | ").append(args.batch()).append(" |\n");
        sb.append("| REST | plain HTTP loopback (not TLS) |\n\n");

        sb.append("## Results\n\n");
        sb.append("| Scenario | Sessions | Count | Size | Transport | Encoding | Time (ms) | Ent/s | MiB/s | Payload |\n");
        sb.append("|----------|--------:|------:|-----:|-----------|----------|----------:|------:|------:|--------:|\n");
        for (ScenarioRow row : rows) {
            BenchResult r = row.result;
            sb.append(String.format(
                    Locale.ROOT,
                    "| %s | %d | %d | %d | %s | %s | %.1f | %.0f | %.2f | %d |\n",
                    row.scenario.label(),
                    row.sessions,
                    row.count,
                    row.size,
                    row.transport,
                    encodingFor(row.scenario, row.transport),
                    r.elapsedMs(),
                    r.entitiesPerSec(),
                    r.mebibytesPerSec(),
                    r.payloadBytes()));
        }

        sb.append("\n## Comparison (wirepack vs REST)\n\n");
        sb.append("| Scenario | Workload | Wirepack (ms) | REST (ms) | Speedup (REST/wirepack) | Wirepack MiB/s | REST MiB/s |\n");
        sb.append("|----------|----------|-------------:|----------:|------------------------:|---------------:|-----------:|\n");
        for (Scenario s : List.of(Scenario.SMALL_MANY, Scenario.LARGE_FEW, Scenario.MULTI_SESSION)) {
            ScenarioRow wp = find(s, "wirepack");
            ScenarioRow rest = find(s, "rest");
            if (wp == null || rest == null) {
                continue;
            }
            double speedup = wp.result.elapsedMs() <= 0
                    ? Double.NaN
                    : rest.result.elapsedMs() / wp.result.elapsedMs();
            String workload = wp.sessions > 1
                    ? String.format(Locale.ROOT, "%d×%d × %d B", wp.sessions, wp.count, wp.size)
                    : String.format(Locale.ROOT, "%d × %d B", wp.count, wp.size);
            sb.append(String.format(
                    Locale.ROOT,
                    "| %s | %s | %.1f | %.1f | **%.2f×** | %.2f | %.2f |\n",
                    s.label(),
                    workload,
                    wp.result.elapsedMs(),
                    rest.result.elapsedMs(),
                    speedup,
                    wp.result.mebibytesPerSec(),
                    rest.result.mebibytesPerSec()));
        }

        sb.append("\n## How to read this\n\n");
        sb.append("- **Speedup** &gt; 1 means wirepack finished faster than REST on wall clock.\n");
        sb.append("- Wirepack crypto uses ChaCha20-Poly1305 (not AES-NI); REST here is plain HTTP.\n");
        sb.append("- **--loss** drops DATA frames only (HELLO/ACK/REQ preserved) to exercise retransmit.\n");
        sb.append("- **multi-session**: N independent client systems → one receiver. ");
        sb.append("Wirepack = N UDP connects (separate runtimes). REST = one HttpClient per client ");
        sb.append("(no shared pool across clients).\n");
        return sb.toString();
    }

    String toCsv() {
        StringBuilder sb = new StringBuilder();
        sb.append("scenario,sessions,count,size,transport,encoding,crypto,loss,elapsed_ms,entities_per_sec,mib_per_sec,payload_bytes\n");
        for (ScenarioRow row : rows) {
            BenchResult r = row.result;
            sb.append(String.format(
                    Locale.ROOT,
                    "%s,%d,%d,%d,%s,%s,%s,%.4f,%.3f,%.3f,%.6f,%d%n",
                    row.scenario.label(),
                    row.sessions,
                    row.count,
                    row.size,
                    row.transport,
                    encodingFor(row.scenario, row.transport),
                    args.crypto(),
                    args.loss(),
                    r.elapsedMs(),
                    r.entitiesPerSec(),
                    r.mebibytesPerSec(),
                    r.payloadBytes()));
        }
        return sb.toString();
    }

    private ScenarioRow find(Scenario scenario, String transport) {
        for (ScenarioRow row : rows) {
            if (row.scenario == scenario && row.transport.equals(transport)) {
                return row;
            }
        }
        return null;
    }

    private String encodingFor(Scenario scenario, String transport) {
        if ("wirepack".equals(transport)) {
            String base = scenario == Scenario.LARGE_FEW ? "ENTITY_FRAG" : "TLV entity";
            return args.crypto() ? base + "+AEAD" : base;
        }
        return scenario == Scenario.LARGE_FEW ? "octet-stream" : "JSON batch";
    }

    private record ScenarioRow(
            Scenario scenario,
            int count,
            int size,
            int sessions,
            String transport,
            BenchResult result
    ) {
    }
}
