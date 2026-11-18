"""Plot the CSV emitted by the actual Java diesel mix checks (no duplicate mixer implementation)."""
import csv, pathlib, sys
sys.path.insert(0, str(pathlib.Path('.tools/plot-python').resolve()))
import matplotlib
matplotlib.use('Agg')
import matplotlib.pyplot as plt

root = pathlib.Path('build/reports/ztz99a-gain')
with (root/'sweep.csv').open() as file:
    rows = [{k: float(v) for k, v in r.items()} for r in csv.DictReader(file)]
fig, ax = plt.subplots(1, 3, figsize=(15, 4.3), constrained_layout=True)
load = [r for r in rows if r['rpm_percent'] == 0]
ax[0].plot([r['su_load'] for r in load], [r['idle_automation_db'] for r in load], label='layer00 / idle')
ax[0].plot([r['su_load'] for r in load], [r['loaded_automation_db'] for r in load], label='layer06 / loaded')
ax[0].set(xlabel='Actual SU load ratio', ylabel='Automation (dB)', title='Native FMOD response / XML endpoints')
for value in [0, .25, .5, .75, 1]:
    curve = [r for r in rows if abs(r['su_load']-value) < .00001]
    ax[1].plot([r['rpm_percent'] for r in curve], [r['rms'] for r in curve], label=f'Load {value:.2f}')
ax[1].set(xlabel='Normalized Create RPM', ylabel='Calibrated RMS at GUI 100%', title='Continuous RPM overlaps')
curve = [r for r in rows if r['su_load'] == 1]
ax[2].plot([r['rpm_percent'] for r in curve], [r['raw_peak_with_2_shots'] for r in curve], label='Conservative unbounded peak')
ax[2].plot([r['rpm_percent'] for r in curve], [r['limited_peak'] for r in curve], label='With headroom control')
ax[2].axhline(.8, color='gray', linestyle=':', label='Per-engine ceiling 0.8')
ax[2].set(xlabel='Normalized Create RPM', ylabel='Worst aligned source peak', title='GUI 300%, continuous loops + two shots')
for plot in ax:
    plot.grid(alpha=.25); plot.legend(fontsize=8)
fig.suptitle('ZTZ99A Gain Automation — native sampled load curves, calibrated original recordings', fontsize=12)
dest=root/'gain_validation.png'; fig.savefig(dest, dpi=170)
print(dest.resolve())
