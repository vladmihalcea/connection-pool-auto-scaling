# Experiments K3/K5: throughput vs. time, plateau start (N_eps) vs. peak (N*) targeting, shift at window 60.
# Input:  plateau-tps.csv (window, static-nstar-phase1, static-plateau-phase1, probe, usl, usl-plateau)
# Output: plateau-tps.svg / .pdf
set datafile separator ","
set title "Throughput: plateau start vs. peak targeting" font ",13"
set xlabel "Observation window"
set ylabel "Throughput (tx/s)"
set grid xtics ytics lt 0 lw 0.5 lc rgb "#cccccc"
set key outside right bottom vertical
set arrow from 60, graph 0 to 60, graph 1 nohead dt 2 lc rgb "#888888"
set style line 1 lw 2 lc rgb "#2E7D32"
set style line 2 lw 2 dt 2 lc rgb "#2E7D32"
set style line 3 lw 2 lc rgb "#6A1B9A"
set style line 4 lw 2 lc rgb "#1565C0"
set style line 5 lw 2 lc rgb "#C62828"
set terminal svg enhanced font "arial,11" size 1000,560
set output 'plateau-tps.svg'
plot for [i=2:6] 'plateau-tps.csv' using 1:i with lines ls (i-1) title columnheader
set terminal pdfcairo enhanced font "arial,10" size 8,4.5
set output 'plateau-tps.pdf'
replot
set output
