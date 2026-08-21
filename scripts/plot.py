import pandas as pd
import matplotlib.pyplot as plt
import seaborn as sns
import numpy as np
from scipy.stats import kurtosis, skew
from matplotlib.lines import Line2D
import matplotlib.ticker as ticker
import os

# ==========================================
# 0. CONSTANTS & CONFIGURATION
# ==========================================
DEFAULT_CSV_PATH = "data/raw/random_trajectories.csv"
OUTPUT_DIR = 'figures/distributions'

# --- SELECTION MODE ---
# Set to None to plot ALL files found in CSV
# Set to ['filename.scm'] to plot specific files
TARGET_FILES = None 
# TARGET_FILES = ['four-in-a-row.scm', 'SICP-compiler.scm']

# --- FILTERING ---
MIN_ITERATIONS_CUTOFF = 50

# Visual Configuration
FIG_SIZE = (12, 8)
DPI = 200
KDE_ALPHA = 0.3

# Colors
COLOR_RANDOM = "#5DADE2"
COLOR_FIFO = "#858585" 
COLOR_MEAN = "#1B2631"
COLOR_ORACLE = "#E74C3C"
COLOR_ML = "#27AE60"
AXIS_COLOR = "#424949"

# Z-Index
Z_KDE = 1
Z_AXVLINE = 10

# ==========================================
# 1. Helper Functions
# ==========================================
def setup_plot_style():
    sns.set_theme(style="white")
    plt.rcParams['font.sans-serif'] = ['Arial', 'DejaVu Sans', 'sans-serif']
    plt.rcParams['font.family'] = 'sans-serif'
    os.makedirs(OUTPUT_DIR, exist_ok=True)

def generate_plot(df, filename, ml_df=None):
    subset = df[df['filename'] == filename]
    
    if subset.empty:
        print(f"[Skipped] {filename}: No data found.")
        return

    random_runs = subset[subset['strategy'] == 'Random']['iterations']
    
    if len(random_runs) < 2:
        print(f"[Skipped] {filename}: Not enough samples.")
        return

    mu = random_runs.mean()
    if mu < MIN_ITERATIONS_CUTOFF:
        print(f"[Skipped] {filename}: Trivial (Mean {mu:.1f}).")
        return

    print(f"Generating plot for: {filename}...")

    # Handle FIFO
    fifo_subset = subset[subset['strategy'] == 'FIFO']['iterations']
    if fifo_subset.empty:
        fifo_val = None
    else:
        fifo_val = fifo_subset.mean()

    # Statistics
    std = random_runs.std()
    oracle_threshold = mu - (3 * std)
    
    # --- Plotting ---
    fig, ax = plt.subplots(figsize=FIG_SIZE, dpi=DPI)

    # Histogram & KDE (Gap Fix Logic)
    # If range is small (<100), force discrete bins to avoid gaps
    data_range = random_runs.max() - random_runs.min()
    is_discrete = data_range < 100  
    
    if is_discrete:
        sns.histplot(random_runs, discrete=True, kde=True, stat="density", 
                     color=COLOR_RANDOM, edgecolor='white', alpha=KDE_ALPHA, zorder=Z_KDE)
    else:
        sns.histplot(random_runs, bins=40, kde=True, stat="density", 
                     color=COLOR_RANDOM, edgecolor='white', alpha=KDE_ALPHA, zorder=Z_KDE)

    # Vertical Lines
    lines = []
    
    # FIFO
    if fifo_val is not None:
        plt.axvline(fifo_val, color=COLOR_FIFO, linestyle='--', linewidth=2.5, 
                    zorder=Z_AXVLINE, clip_on=False)
        lines.append(Line2D([0], [0], color=COLOR_FIFO, lw=1, ls='--', label='FIFO Baseline'))

    # Mean
    plt.axvline(mu, color=COLOR_MEAN, linestyle='-', linewidth=2.5, 
                zorder=Z_AXVLINE, clip_on=False)
    lines.append(Line2D([0], [0], color=COLOR_MEAN, lw=1, label=r'Mean ($\mu$)'))

    # Legend Logic (Inserted at top)
    lines.insert(0, Line2D([0], [0], color=COLOR_RANDOM, lw=4, alpha=0.6, label='Random Strategy'))

    # --- Formatting ---
    # Centering
    data_min, data_max = random_runs.min(), random_runs.max()
    max_dist = max(abs(data_min - mu), abs(data_max - mu), abs(oracle_threshold - mu)) * 1.1
    ax.set_xlim(mu - max_dist, mu + max_dist)

    # 1. LEGEND -> UPPER RIGHT
    ax.legend(handles=lines, loc='upper right', frameon=False, 
              fontsize=10, bbox_to_anchor=(0.99, 0.98))

    stats_text = (
        f"N = {len(random_runs)}\n"
        f"Min = {data_min:,.0f}\n"
        f"Max = {data_max:,.0f}\n"
        f"μ = {mu:,.1f}\n"
        f"σ = {std:,.1f}\n"
        f"Skew = {skew(random_runs):.2f}\n"
        f"Kurt = {kurtosis(random_runs):.2f}"
    )

    # Stats box
    ax.text(0.818, 0.75, stats_text, transform=ax.transAxes, fontsize=10.5,
            verticalalignment='top', horizontalalignment='left', 
            family='monospace', color='#2C3E50',
            linespacing=1.6, bbox=dict(facecolor='white', alpha=0.2, edgecolor='none'))

    # Titles
    ax.text(0.5, 1.12, f"Efficiency Distribution: {filename}", transform=ax.transAxes, 
            fontsize=24, fontweight='bold', ha='center', va='bottom', color='#212F3D')
    ax.text(0.5, 1.07, 'Comparative analysis of Random vs. FIFO worklist strategies', 
            transform=ax.transAxes, fontsize=14, ha='center', va='bottom', 
            color='#566573', style='italic')

    # Axis Cleanup
    ax.set_xlabel('Iterations', fontsize=12, fontweight='regular', color=AXIS_COLOR, labelpad=5)
    ax.tick_params(axis='x', which='major', bottom=True, 
                   size=6, width=1.2, color=AXIS_COLOR, 
                   labelcolor=AXIS_COLOR, labelsize=12, pad=10)

    ax.set_ylabel('')
    ax.set_yticks([])
    ax.xaxis.set_major_formatter(ticker.StrMethodFormatter('{x:,.0f}'))
    
    sns.despine(left=True, bottom=False)
    ax.spines['bottom'].set_color(AXIS_COLOR)
    ax.spines['bottom'].set_linewidth(1.2)

    plt.subplots_adjust(bottom=0.2, top=0.8)

    # Save
    safe_name = filename.replace('.', '_')
    save_path = f"{OUTPUT_DIR}/{safe_name}_dist.png"
    plt.savefig(save_path, bbox_inches='tight', dpi=DPI, pad_inches=1)
    plt.close() 
    print(f"  -> Saved to {save_path}")

# ==========================================
# 2. Main Execution
# ==========================================
import argparse

if __name__ == "__main__":
    parser = argparse.ArgumentParser(description="Plot trajectory distributions.")
    parser.add_argument("--csv", type=str, default=DEFAULT_CSV_PATH, help="Path to random_trajectories.csv")
    parser.add_argument("--eval-csv", type=str, default=None, help="Path to evaluation_results.csv for ML overlay")
    args = parser.parse_args()

    setup_plot_style()
    
    try:
        df = pd.read_csv(args.csv)
    except FileNotFoundError:
        print(f"Error: {args.csv} not found.")
        exit(1)
        
    ml_df = None
    if args.eval_csv:
        try:
            ml_df = pd.read_csv(args.eval_csv)
        except FileNotFoundError:
            print(f"Warning: {args.eval_csv} not found. Skipping ML overlay.")

    if TARGET_FILES is None:
        targets = df['filename'].unique()
    else:
        targets = TARGET_FILES

    print(f"Processing {len(targets)} benchmark candidates...")
    
    for fname in targets:
        generate_plot(df, fname, ml_df)
        
    print(f"\nBatch processing complete.")