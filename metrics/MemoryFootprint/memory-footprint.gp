# Experiment E7: retained client-side memory per component (JOL total size).
# Input:  memory-footprint.csv (component, size, retained_bytes)
# Output: memory-footprint.svg / .pdf
set datafile separator ","
set title "Retained client-side memory (JOL)" font ",13"
set ylabel "Retained bytes (log scale)"
set logscale y
set format y "10^{%L}"
set style data histograms
set style fill solid 0.9 border -1
set boxwidth 0.7
set grid ytics lt 0 lw 0.5 lc rgb "#cccccc"
set xtics rotate by -40 font ",8"
unset key
set terminal svg enhanced font "arial,11" size 1000,600
set output 'memory-footprint.svg'
plot 'memory-footprint.csv' using 3:xtic(sprintf("%s@%s", stringcolumn(1), stringcolumn(2))) lc rgb "#1565C0" notitle
set terminal pdfcairo enhanced font "arial,9" size 8,5
set output 'memory-footprint.pdf'
replot
set output
