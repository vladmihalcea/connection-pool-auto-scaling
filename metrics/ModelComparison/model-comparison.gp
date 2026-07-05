# Experiment E3: predicted vs. measured optimal pool size, per sizing method.
# Input:  model-comparison.csv (pool, measured, USL, Little, ErlangC, Heuristic)
# Output: model-comparison.svg / .pdf
set datafile separator ","
set title "Predicted vs. measured optimal pool size" font ",13"
set ylabel "Pool size (connections)"
set style data histograms
set style histogram clustered gap 1
set style fill solid 0.9 border -1
set boxwidth 0.9
set grid ytics lt 0 lw 0.5 lc rgb "#cccccc"
set key outside right top vertical
set xtics rotate by -20

set style line 1 lc rgb "#455A64"
set style line 2 lc rgb "#1565C0"
set style line 3 lc rgb "#2E7D32"
set style line 4 lc rgb "#F9A825"
set style line 5 lc rgb "#C62828"

set terminal svg enhanced font "arial,11" size 1000,560
set output 'model-comparison.svg'
plot for [i=2:6] 'model-comparison.csv' using i:xtic(1) ls (i-1) title columnheader

set terminal pdfcairo enhanced font "arial,10" size 8,4.5
set output 'model-comparison.pdf'
replot
set output
