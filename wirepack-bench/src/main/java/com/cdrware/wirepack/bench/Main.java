package com.cdrware.wirepack.bench;

import java.nio.file.Path;

/**
 * Optional throughput comparison: wirepack UDP entities vs REST (JSON / octet-stream).
 *
 * <p>Not part of the wirepack runtime — production apps need not depend on this module.
 * Supports AEAD crypto, injected DATA loss, and multi-session concurrency.
 */
public final class Main {
    private Main() {
    }

    public static void main(String[] args) throws Exception {
        BenchArgs a = BenchArgs.parse(args);
        if (a.help()) {
            System.out.print(BenchArgs.usage());
            return;
        }

        System.out.println("wirepack-bench (loopback; REST = plain HTTP)");
        System.out.printf(
                "scenario=%s transport=%s crypto=%s loss=%.3f sessions=%d batch=%d runs=%d%n",
                a.scenario(), a.transport(), a.crypto(), a.loss(), a.sessions(), a.batch(), a.runs());

        BenchReport report = new BenchReport(a);

        for (Scenario scenario : a.scenarios()) {
            int count = a.countFor(scenario);
            int size = a.sizeFor(scenario);
            int sessions = a.sessionsFor(scenario);
            System.out.println();
            if (sessions > 1) {
                System.out.printf(
                        "=== %s (%d sessions × %d × %d bytes) ===%n",
                        scenario.label(), sessions, count, size);
            } else {
                System.out.printf("=== %s (%d × %d bytes) ===%n", scenario.label(), count, size);
            }

            if (a.transport().wirepack()) {
                BenchResult r = averageRuns(a.runs(), () -> WirepackRunner.run(scenario, a));
                print("wirepack", r);
                report.add(scenario, count, size, sessions, "wirepack", r);
            }
            if (a.transport().rest()) {
                BenchResult r = averageRuns(a.runs(), () -> RestRunner.run(scenario, a));
                print("rest    ", r);
                report.add(scenario, count, size, sessions, "rest", r);
            }
        }

        Path reportPath = Path.of(a.reportPath());
        report.write(reportPath);
        Path csv = reportPath.getParent() == null
                ? Path.of(stripExt(reportPath.getFileName().toString()) + ".csv")
                : reportPath.getParent().resolve(stripExt(reportPath.getFileName().toString()) + ".csv");
        System.out.println();
        System.out.println("Report: " + reportPath.toAbsolutePath());
        System.out.println("CSV:    " + csv.toAbsolutePath());
        // Netty event loops / HttpClient pools can keep non-daemon threads alive.
        System.exit(0);
    }

    private static String stripExt(String name) {
        int dot = name.lastIndexOf('.');
        return dot > 0 ? name.substring(0, dot) : name;
    }

    @FunctionalInterface
    private interface BenchCall {
        BenchResult run() throws Exception;
    }

    private static BenchResult averageRuns(int runs, BenchCall call) throws Exception {
        long nanos = 0;
        BenchResult last = null;
        for (int i = 0; i < runs; i++) {
            last = call.run();
            nanos += last.elapsedNanos();
        }
        return new BenchResult(nanos / runs, last.entityCount(), last.payloadBytes());
    }

    private static void print(String name, BenchResult r) {
        System.out.printf(
                "  %-8s  %8.1f ms  %10.0f ent/s  %10.1f MiB/s  (%d bytes payload)%n",
                name,
                r.elapsedMs(),
                r.entitiesPerSec(),
                r.mebibytesPerSec(),
                r.payloadBytes());
    }
}
