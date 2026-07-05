# Experiment E4: throughput vs. time per policy (stationary workload).
# Input:  convergence-tps.csv (window, static-small, static-oracle, static-large, reactive, probe, usl)
# Output: convergence-tps.svg / .pdf
set datafile separator ","
set title "Throughput convergence per policy" font ",13"
set xlabel "Observation window"
set ylabel "Throughput (tx/s)"
set grid xtics ytics lt 0 lw 0.5 lc rgb "#cccccc"
set key outside right bottom vertical
set style line 1 lw 2 lc rgb "#90A4AE"
set style line 2 lw 2 lc rgb "#2E7D32"
set style line 3 lw 2 lc rgb "#C62828"
set style line 4 lw 2 lc rgb "#F9A825"
set style line 5 lw 2 lc rgb "#6A1B9A"
set style line 6 lw 2 lc rgb "#1565C0"
set terminal svg enhanced font "arial,11" size 1000,560
set output 'convergence-tps.svg'
plot for [i=2:7] 'convergence-tps.csv' using 1:i with lines ls (i-1) title columnheader
set terminal pdfcairo enhanced font "arial,10" size 8,4.5
set output 'convergence-tps.pdf'
replot
set output
