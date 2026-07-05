# Experiment E2: measured throughput points vs. fitted USL curves (readMostly workload).
# Input:  fit-readMostly.csv (poolSize, <pool>_measured, <pool>_fitted per pool)
# Output: fit-readMostly.svg / .pdf
set datafile separator ","
set title "Throughput vs. pool size: measured points and fitted USL" font ",13"
set xlabel "Pool size (connections)"
set ylabel "Throughput (tx/s)"
set grid xtics ytics lt 0 lw 0.5 lc rgb "#cccccc"
set key outside right top vertical

# measured = points, fitted = smooth line, one colour per pool
set style line 1 lc rgb "#1565C0" pt 7 ps 0.8
set style line 2 lc rgb "#1565C0" lw 2
set style line 3 lc rgb "#2E7D32" pt 5 ps 0.8
set style line 4 lc rgb "#2E7D32" lw 2
set style line 5 lc rgb "#F9A825" pt 9 ps 0.8
set style line 6 lc rgb "#F9A825" lw 2

set terminal svg enhanced font "arial,11" size 1000,600
set output 'fit-readMostly.svg'
plot 'fit-readMostly.csv' using 1:2 with points ls 1 title 'hikari (measured)', \
     '' using 1:3 with lines ls 2 title 'hikari (USL)', \
     '' using 1:4 with points ls 3 title 'dbcp2 (measured)', \
     '' using 1:5 with lines ls 4 title 'dbcp2 (USL)', \
     '' using 1:6 with points ls 5 title 'agroal (measured)', \
     '' using 1:7 with lines ls 6 title 'agroal (USL)'

set terminal pdfcairo enhanced font "arial,10" size 8,4.8
set output 'fit-readMostly.pdf'
replot
set output
