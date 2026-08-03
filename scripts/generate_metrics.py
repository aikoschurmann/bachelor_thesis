import os
import glob
import argparse
import pandas as pd
import numpy as np

PROJECT_ROOT = os.path.abspath(os.path.join(os.path.dirname(__file__), ".."))
DATA_BASE_DIR = os.path.join(PROJECT_ROOT, "data")

def geomean(x):
    return np.exp(np.log(x[x > 0]).mean())

def analyze_all_configs():
    all_files = glob.glob(os.path.join(DATA_BASE_DIR, "experiments", "lattice_*", "models", "*", "evaluation_results.csv"))
    
    dfs = []
    for f in all_files:
        df = pd.read_csv(f)
        # Infer config from path
        parts = f.split(os.sep)
        exp_name = parts[-3]
        model_name = parts[-2]
        df['config'] = f"{exp_name}/{model_name}"
        dfs.append(df)
        
    if not dfs:
        print("No evaluation data found.")
        return
        
    combined_df = pd.concat(dfs, ignore_index=True)
    combined_df = combined_df[combined_df['fifo_steps'] >= 50]
    
    summary = []
    for config, subset in combined_df.groupby('config'):
        if not subset.empty:
            win_rate = (subset['ratio'] < 1.0).mean() * 100
            time_win_rate = (subset['ml_time_ms'] / subset['fifo_time_ms'] < 1.0).mean() * 100 if 'ml_time_ms' in subset else 0
            avg_ratio = subset['ratio'].mean()
            geo_ratio = geomean(subset['ratio'])
            avg_overhead = subset['overhead_factor'].mean()
            
            summary.append({
                'Config': config,
                'Step Win Rate (%)': win_rate,
                'Time Win Rate (%)': time_win_rate,
                'Arithmetic Step Ratio': avg_ratio,
                'GeoMean Step Ratio': geo_ratio,
                'Avg TPI Overhead': avg_overhead
            })
            
    summary_df = pd.DataFrame(summary).sort_values('Config')
    print("## Model Configuration Summary")
    print(summary_df.to_markdown(index=False, floatfmt=".3f"))

def analyze_single_config(exp_name, model_name):
    csv_path = os.path.join(DATA_BASE_DIR, "experiments", exp_name, "models", model_name, "evaluation_results.csv")
    if not os.path.exists(csv_path):
        print(f"CSV not found at: {csv_path}")
        return
        
    df = pd.read_csv(csv_path)
    df = df.drop_duplicates(subset=['program'], keep='last')
    df = df[df['fifo_steps'] >= 50].copy()
    
    df['time_ratio'] = df['ml_time_ms'] / df['fifo_time_ms']
    df['time_win'] = df['time_ratio'] < 1.0
    
    print("\n" + "="*60)
    print(f"### Detailed Results for {exp_name}/{model_name} (Programs >= 50 FIFO Steps) ###")
    print("="*60)
    print(f"Programs Evaluated     : {len(df)}")
    print(f"Step Reduction Win Rate: {(df['ratio'] < 1.0).mean() * 100:.1f}%")
    print(f"Avg Step Ratio         : {df['ratio'].mean():.3f}x")
    print(f"GeoMean Step Ratio     : {geomean(df['ratio']):.3f}x")
    print(f"Avg TPI Overhead       : {df['overhead_factor'].mean():.2f}x")
    print(f"GeoMean TPI Overh.     : {geomean(df['overhead_factor']):.2f}x")
    print("-" * 60)
    print(f"Wall-Clock Time WinRate: {df['time_win'].mean() * 100:.1f}%")
    print(f"Avg Time Ratio         : {df['time_ratio'].mean():.3f}x")
    print(f"GeoMean Time Ratio     : {geomean(df['time_ratio']):.3f}x")
    print("="*60 + "\n")
    
    wins = df[df['time_win']].sort_values('time_ratio').head(5)
    print("Top 5 Best Wall-Clock Time Speedups:")
    for _, row in wins.iterrows():
        print(f" - {row['program']}: {row['fifo_time_ms']:.1f}ms -> {row['ml_time_ms']:.1f}ms ({row['time_ratio']:.2f}x time, {row['ratio']:.2f}x steps)")

def main():
    parser = argparse.ArgumentParser(description="Generate evaluation metrics.")
    parser.add_argument('--exp', type=str, help="Specific experiment name (e.g. lattice_l10_b3)")
    parser.add_argument('--model', type=str, default="o1_features", help="Model configuration name")
    args = parser.parse_args()
    
    if args.exp:
        analyze_single_config(args.exp, args.model)
    else:
        analyze_all_configs()

if __name__ == "__main__":
    main()
