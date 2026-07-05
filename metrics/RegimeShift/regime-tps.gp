# Experiment E5: throughput vs. time across a regime shift at window 60.
# Input:  regime-tps.csv (window, static-oracle-phase1, reactive, probe, usl)
# Output: regime-tps.svg / .pdf
set datafile separator ","
set title "Throughput across a workload regime shift" font ",13"
set xlabel "Observation window"
set ylabel "Throughput (tx/s)"
set grid xtics ytics lt 0 lw 0.5 lc rgb "#cccccc"
set key outside right bottom vertical
set arrow from 60, graph 0 to 60, graph 1 nohead dt 2 lc rgb "#888888"
set style line 1 lw 2 lc rgb "#2E7D32"
set style line 2 lw 2 lc rgb "#F9A825"
set style line 3 lw 2 lc rgb "#6A1B9A"
set style line 4 lw 2 lc rgb "#1565C0"
set terminal svg enhanced font "arial,11" size 1000,560
set output 'regime-tps.svg'
plot for [i=2:5] 'regime-tps.csv' using 1:i with lines ls (i-1) title columnheader
set terminal pdfcairo enhanced font "arial,10" size 8,4.5
set output 'regime-tps.pdf'
replot
set output
