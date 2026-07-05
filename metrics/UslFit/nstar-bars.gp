# Experiment E2: optimal pool size N* per pool implementation, grouped by workload.
# Input:  nstar-bars.csv  (workload, hikari, dbcp2, agroal)
# Output: nstar-bars.svg / .pdf
set datafile separator ","
set title "USL-optimal pool size N* per pool" font ",13"
set ylabel "N* (connections)"
set style data histograms
set style histogram clustered gap 1
set style fill solid 0.9 border -1
set boxwidth 0.9
set grid ytics lt 0 lw 0.5 lc rgb "#cccccc"
set key outside right top vertical
set xtics rotate by -20

set style line 1 lc rgb "#1565C0"
set style line 2 lc rgb "#2E7D32"
set style line 3 lc rgb "#F9A825"

set terminal svg enhanced font "arial,11" size 900,520
set output 'nstar-bars.svg'
plot for [i=2:4] 'nstar-bars.csv' using i:xtic(1) ls (i-1) title columnheader

set terminal pdfcairo enhanced font "arial,10" size 8,4.5
set output 'nstar-bars.pdf'
replot
set output
