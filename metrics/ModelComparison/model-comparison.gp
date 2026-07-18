# Experiment E3: measured vs. predicted optimal pool size, per sizing method (readMostly).
# Input:  model-comparison.csv (pool, Measured peak size, Measured plateau start, USL (N*), Little's Law, Erlang-C, Heuristic)
# Output: model-comparison.svg / .pdf
set datafile separator ","
set title "Measured vs. predicted optimal pool size" font ",13"
set ylabel "Pool size (connections)"
set style data histograms
set style histogram clustered gap 1
set style fill solid 0.9 border -1
set boxwidth 0.9
set grid ytics lt 0 lw 0.5 lc rgb "#cccccc"
set key outside right top vertical
set xtics rotate by -20

set style line 1 lc rgb "#455A64"
set style line 2 lc rgb "#90A4AE"
set style line 3 lc rgb "#1565C0"
set style line 4 lc rgb "#2E7D32"
set style line 5 lc rgb "#F9A825"
set style line 6 lc rgb "#C62828"

set terminal svg enhanced font "arial,11" size 1000,560
set output 'model-comparison.svg'
plot for [i=2:7] 'model-comparison.csv' using i:xtic(1) ls (i-1) title columnheader

set terminal pdfcairo enhanced font "arial,13" size 5,2.8
set output 'model-comparison.pdf'
replot
set output
