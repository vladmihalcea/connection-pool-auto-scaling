# TPC-C-derived load: throughput vs. pool size per database, measured (points) and USL fit (lines).
# Input:  tpcc-sweep-<database>.csv (pool_size, tps, new_order_per_min, failures_per_s)
# Output: tpcc-sweep.svg / .pdf
set datafile separator ","
set title "TPC-C throughput vs. pool size (2 warehouses, 64 terminals)" font ",13"
set xlabel "Pool size (connections)"
set ylabel "Throughput (tx/s)"
set grid xtics ytics lt 0 lw 0.5 lc rgb "#cccccc"
set key outside right top vertical
set xrange [0:48]
set yrange [0:*]
set samples 400
X_postgresql(n) = 574.930366 * n / (1 + 0.17320022 * (n - 1) + 0.0018567734 * n * (n - 1))
X_mysql(n) = 140.358386 * n / (1 + 0.07998399 * (n - 1) + 0.0034876748 * n * (n - 1))
set terminal svg enhanced font "arial,11" size 1000,560
set output 'tpcc-sweep.svg'
plot 'tpcc-sweep-postgresql.csv' using 1:2 with points pt 7 ps 0.8 lc rgb "#1565C0" title 'postgresql measured', X_postgresql(x) with lines lw 2 lc rgb "#1565C0" title 'postgresql USL fit', \
     'tpcc-sweep-mysql.csv' using 1:2 with points pt 7 ps 0.8 lc rgb "#C62828" title 'mysql measured', X_mysql(x) with lines lw 2 lc rgb "#C62828" title 'mysql USL fit'
set terminal pdfcairo enhanced font "arial,10" size 8,4.5
set output 'tpcc-sweep.pdf'
replot
set output
