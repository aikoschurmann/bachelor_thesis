import pandas as pd
import numpy as np
import xgboost as xgb
import os
import json
import argparse
from sklearn.model_selection import GroupShuffleSplit

# ==========================================================
# LATTICE-PROGRESSION Training (Ranking Objective)
# ==========================================================

def load_data(root_dir):
    all_dfs = []
    print(f"--- Loading and Balancing Lattice Data from {root_dir} ---")
    
    prog_data = {}
    if not os.path.exists(root_dir):
        raise ValueError(f"Data directory {root_dir} does not exist!")

    for prog_dir in os.listdir(root_dir):
        prog_path = os.path.join(root_dir, prog_dir)
        if not os.path.isdir(prog_path): continue
        csv_path = os.path.join(prog_path, "TRAIN_DATA.csv")
        if os.path.exists(csv_path):
            df = pd.read_csv(csv_path)
            if not df.empty:
                df['program'] = prog_dir
                prog_data[prog_dir] = df

    if not prog_data:
        raise ValueError(f"No TRAIN_DATA.csv files found in {root_dir}!")

    for name, df in prog_data.items():
        # Feature Engineering: Automatically scale numerical features
        skip_features = {'step', 'wl_size', 'name_hash', 'target_score', 'program', 'query_id', 'arrival_index'}
        categorical_features = {'was_selected', 'is_main'}
        
        raw_features = [c for c in df.columns if c not in skip_features and c not in categorical_features]
        
        for col in raw_features:
            # Normalization per step
            ma = df.groupby('step')[col].transform('max')
            df[f'norm_{col}'] = np.where(ma > 0, df[col] / ma, 0.0)
            
            # Log transform (only if non-negative)
            if (df[col] >= 0).all():
                df[f'log_{col}'] = np.log1p(df[col])
                
        all_dfs.append(df)
            
    return pd.concat(all_dfs, ignore_index=True).dropna()

def train_model(df, subset_features=None):
    # Convert float target_score [0,1] to integer relevance [0,31] for rank:ndcg
    y = (df['target_score'] * 31).round().astype(int)
    
    skip_cols = {'step', 'wl_size', 'name_hash', 'target_score', 'program', 'query_id', 'arrival_index'}
    
    # Exclude raw numeric features (they have 'norm_' and 'log_' equivalents)
    raw_numeric_cols = [c for c in df.columns if not c.startswith('norm_') and not c.startswith('log_') and c not in {'was_selected', 'is_main'} and c not in skip_cols]
    
    # By default, use all generated (norm_, log_) and pass-through (categorical) columns
    cols = [c for c in df.columns if c not in skip_cols and c not in raw_numeric_cols]
    
    if subset_features:
        allowed_cols = set()
        for f in subset_features:
            allowed_cols.add(f)
            allowed_cols.add(f"norm_{f}")
            allowed_cols.add(f"log_{f}")
        cols = [c for c in cols if c in allowed_cols]
        
    X = df[cols]
    
    print(f"\n > Training on {len(df)} samples with {len(cols)} features.")
    
    # We must group by query_id (step within a program) so XGBoost compares candidates within the same step
    df['query_id'] = df['program'].astype(str) + '_' + df['step'].astype(str)
    
    # GroupShuffleSplit based on query_id
    gss = GroupShuffleSplit(test_size=0.15, n_splits=1, random_state=42)
    train_idx, val_idx = next(gss.split(X, y, groups=df['query_id']))
    
    X_train, y_train = X.iloc[train_idx], y.iloc[train_idx]
    X_val, y_val = X.iloc[val_idx], y.iloc[val_idx]
    
    # Get group sizes for ranking (how many candidates in each step)
    train_groups = df.iloc[train_idx].groupby('query_id', sort=False).size().values
    val_groups = df.iloc[val_idx].groupby('query_id', sort=False).size().values
    
    dtrain = xgb.DMatrix(X_train, label=y_train)
    dtrain.set_group(train_groups)
    dval = xgb.DMatrix(X_val, label=y_val)
    dval.set_group(val_groups)

    params = {
        'objective': 'rank:ndcg', # Optimize for ranking relative to each other
        'eta': 0.03,
        'max_depth': 8,
        'subsample': 0.8,
        'colsample_bytree': 0.8,
        'eval_metric': 'ndcg',
        'seed': 42
    }
    
    print("\n--- Training Lattice Model (Ranking) ---")
    bst = xgb.train(
        params, 
        dtrain, 
        num_boost_round=1500, 
        evals=[(dtrain, 'train'), (dval, 'val')],
        early_stopping_rounds=100,
        verbose_eval=100
    )
    
    return bst, list(X.columns)

if __name__ == "__main__":
    PROJECT_ROOT = os.path.abspath(os.path.join(os.path.dirname(__file__), ".."))
    DEFAULT_DATA_ROOT = os.path.join(PROJECT_ROOT, "data", "lattice_oracle_lookahead_gen")
    DEFAULT_MODELS_DIR = os.path.join(PROJECT_ROOT, "models")

    parser = argparse.ArgumentParser(description="Train XGBoost ranking model on lattice progression data.")
    parser.add_argument("--data_root", type=str, default=DEFAULT_DATA_ROOT, help="Path to training data.")
    parser.add_argument("--models_dir", type=str, default=DEFAULT_MODELS_DIR, help="Directory to save the model.")
    parser.add_argument("--model_filename", type=str, default="xgboost_lattice_oracle_rank.json", help="Filename for the saved model.")
    parser.add_argument("--feature_list_filename", type=str, default="feature_names_lattice_rank.json", help="Filename for the saved feature names.")
    parser.add_argument("--features", type=str, default=None, help="Comma-separated list of raw features to use. If not specified, all available features are used.")
    
    args = parser.parse_args()

    os.makedirs(args.models_dir, exist_ok=True)
    df = load_data(args.data_root)
    
    subset_features = args.features.split(",") if args.features else None
    model, features = train_model(df, subset_features)
    
    model_path = os.path.join(args.models_dir, args.model_filename)
    model.save_model(model_path)
    
    with open(os.path.join(args.models_dir, args.feature_list_filename), 'w') as f:
        json.dump(features, f, indent=2)
        
    print(f"\n[SUCCESS] Lattice Ranking Model saved to {model_path}")
