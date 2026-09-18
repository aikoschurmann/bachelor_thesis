import os
import sys
import json
import argparse
import xgboost as xgb
import matplotlib.pyplot as plt

try:
    import shap
except ImportError:
    print("Please install shap: pip install shap")
    sys.exit(1)

# Ensure we can import the existing load_data function
script_dir = os.path.dirname(os.path.abspath(__file__))
sys.path.append(script_dir)
try:
    from train_lattice_rank_model import load_data
except ImportError:
    print("Could not import load_data from train_lattice_rank_model.py")
    sys.exit(1)

def main():
    PROJECT_ROOT = os.path.abspath(os.path.join(script_dir, ".."))
    DEFAULT_DATA_ROOT = os.path.join(PROJECT_ROOT, "data", "lattice_l10_b3") # Using a known existing dataset
    DEFAULT_MODEL_PATH = os.path.join(PROJECT_ROOT, "models", "xgboost_lattice_oracle_rank.json")
    DEFAULT_FEATURES_PATH = os.path.join(PROJECT_ROOT, "models", "feature_names_lattice_rank.json")
    DEFAULT_OUT_DIR = os.path.join(PROJECT_ROOT, "figures")

    parser = argparse.ArgumentParser(description="Run SHAP analysis on trained XGBoost model.")
    parser.add_argument("--data_root", type=str, default=DEFAULT_DATA_ROOT, help="Path to evaluation data directory.")
    parser.add_argument("--model_path", type=str, default=DEFAULT_MODEL_PATH, help="Path to XGBoost model.")
    parser.add_argument("--feature_list", type=str, default=DEFAULT_FEATURES_PATH, help="Path to feature names JSON.")
    parser.add_argument("--out_dir", type=str, default=DEFAULT_OUT_DIR, help="Directory to save SHAP plots.")
    parser.add_argument("--sample_size", type=int, default=2000, help="Number of samples to use for SHAP (default 2000 for speed).")
    args = parser.parse_args()

    os.makedirs(args.out_dir, exist_ok=True)

    print(f"Loading model from {args.model_path}...")
    if not os.path.exists(args.model_path):
        print(f"Model not found at {args.model_path}")
        sys.exit(1)
    
    model = xgb.Booster()
    model.load_model(args.model_path)

    print(f"Loading feature names from {args.feature_list}...")
    if not os.path.exists(args.feature_list):
        print(f"Feature list not found at {args.feature_list}")
        sys.exit(1)
        
    with open(args.feature_list, 'r') as f:
        features = json.load(f)

    print(f"Loading data from {args.data_root}...")
    try:
        df = load_data(args.data_root)
    except Exception as e:
        print(f"Error loading data: {e}")
        sys.exit(1)

    # Subsample data early if needed (saves memory/time if df is huge)
    if len(df) > args.sample_size:
        print(f"Subsampling data to {args.sample_size} rows for SHAP calculation...")
        df_sample = df.sample(args.sample_size, random_state=42)
    else:
        df_sample = df

    # Extract features matching the model
    missing_features = [f for f in features if f not in df_sample.columns]
    if missing_features:
        print(f"Warning: The following features are missing from the data: {missing_features}")
    
    X_sample = df_sample[[f for f in features if f in df_sample.columns]]

    print("Calculating SHAP values...")
    explainer = shap.TreeExplainer(model)
    shap_values = explainer.shap_values(X_sample)

    print("Generating SHAP summary plot (Beeswarm)...")
    plt.figure(figsize=(10, 8))
    shap.summary_plot(shap_values, X_sample, show=False)
    summary_plot_path = os.path.join(args.out_dir, "shap_summary.png")
    plt.savefig(summary_plot_path, bbox_inches='tight', dpi=300)
    plt.close()
    print(f"Saved summary plot to {summary_plot_path}")

    print("Generating SHAP feature importance (Bar) plot...")
    plt.figure(figsize=(10, 8))
    shap.summary_plot(shap_values, X_sample, plot_type="bar", show=False)
    bar_plot_path = os.path.join(args.out_dir, "shap_importance_bar.png")
    plt.savefig(bar_plot_path, bbox_inches='tight', dpi=300)
    plt.close()
    print(f"Saved feature importance plot to {bar_plot_path}")
    
    print("\nSHAP analysis complete!")

if __name__ == "__main__":
    main()
