import com.vladmihalcea.phd.pool.usl.UslFit;
import com.vladmihalcea.phd.pool.usl.UslFitter;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.*;

/** Fits the repo's UslFitter to every (pool, workload) curve in a tidy JMH CSV. Usage: java Fit.java <csv> */
public class Fit {
    public static void main(String[] args) throws Exception {
        List<String> lines = Files.readAllLines(Path.of(args[0]));
        List<String> header = Arrays.asList(lines.get(0).split(","));
        int iPool = header.indexOf("pool"), iSize = header.indexOf("poolSize"), iWl = header.indexOf("workload"),
                iScore = header.indexOf("score");
        Map<String, TreeMap<Double, Double>> curves = new TreeMap<>();
        for (String line : lines.subList(1, lines.size())) {
            String[] c = line.split(",");
            curves.computeIfAbsent(c[iWl] + " " + c[iPool], k -> new TreeMap<>())
                    .put(Double.parseDouble(c[iSize]), Double.parseDouble(c[iScore]));
        }
        System.out.printf("%-24s %8s %16s %16s %4s %6s %8s %4s%n", "curve", "lambda", "sigma(e-2)", "kappa(e-4)", "N*", "R2", "peakX", "@N");
        for (var e : curves.entrySet()) {
            double[] n = e.getValue().keySet().stream().mapToDouble(Double::doubleValue).toArray();
            double[] x = e.getValue().values().stream().mapToDouble(Double::doubleValue).toArray();
            UslFit f = UslFitter.fit(n, x);
            var peak = e.getValue().entrySet().stream().max(Map.Entry.comparingByValue()).get();
            System.out.printf("%-24s %8.0f %7.2f +- %5.2f %7.2f +- %5.2f %4d %6.3f %8.0f %4.0f%n", e.getKey(),
                    f.model().lambda(), f.model().sigma() * 100, f.sigmaCi95() * 100,
                    f.model().kappa() * 1e4, f.kappaCi95() * 1e4, f.optimalPoolSize(), f.rSquared(),
                    peak.getValue(), peak.getKey());
        }
    }
}
