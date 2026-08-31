import subprocess
import os
import sys
import utils

PROJECT_ROOT = os.path.abspath(os.path.join(os.path.dirname(__file__), ".."))
DATA_BASE_DIR = os.path.join(PROJECT_ROOT, "data")
MODELS_DIR = os.path.join(PROJECT_ROOT, "models")
MAF_DIR = os.path.join(PROJECT_ROOT, "maf")

venv_python = os.path.join(PROJECT_ROOT, ".venv", "bin", "python")
PYTHON_CMD = venv_python if os.path.exists(venv_python) else sys.executable

def _run_command(cmd, cwd=None):
    print(f"\n>> Running: {cmd}")
    process = subprocess.Popen(cmd, shell=True, cwd=cwd)
    process.wait()
        
    if process.returncode != 0:
        print(f"\nERROR: Command failed with return code {process.returncode}")
        return False
    return True

def _get_exp_paths(lookahead, beam, k = 0, data_suffix=""):
    exp_name = f"lattice_l{lookahead}_b{beam}_k{k}"
    data_path = os.path.join(DATA_BASE_DIR, f"{exp_name}{data_suffix}")
    model_name = f"xgboost_{exp_name}_rank.json"
    model_path = os.path.join(MODELS_DIR, model_name)
    return exp_name, data_path, model_name, model_path

def generate_data(lookahead: int, beam: int, train_dir: str, num_cores: int, data_suffix: str = "", k_cfa: int = 0) -> bool:
    """Runs Phase 1: Scala Lattice Data Generation."""
    exp_name, data_path, _, _ = _get_exp_paths(lookahead, beam, k_cfa, data_suffix)
    print(f"\n{'='*60}\n [PHASE 1] Data Generation: {exp_name}{data_suffix}\n{'='*60}")
    
    if not os.path.exists(data_path):
        os.makedirs(data_path)
        
    jar = utils.path_to_jar("oracle-lattice-generator.jar")
    cmd = f'java -jar {jar} {lookahead} {beam} {data_path} {num_cores} {train_dir} {k_cfa}'
    return _run_command(cmd, cwd=MAF_DIR)

def train_model(lookahead: int, beam: int, train_dir: str, num_cores: int, features: list = None, model_dir: str = None, data_suffix: str = "", k_cfa = 0) -> bool:
    """Runs Phase 2: Python XGBoost Rank Model Training."""
    exp_name, data_path, model_name, default_model_path = _get_exp_paths(lookahead, beam, k_cfa, data_suffix)
    
    actual_model_dir = model_dir if model_dir else MODELS_DIR
    print(f"\n{'='*60}\n [PHASE 2] Model Training (Rank): {exp_name}{data_suffix} -> {actual_model_dir}\n{'='*60}")
    
    # Build command
    cmd = f'{PYTHON_CMD} scripts/train_lattice_rank_model.py --data_root {data_path} --models_dir {actual_model_dir}'
    if features:
        features_str = ",".join(features)
        cmd += f' --features "{features_str}"'
        
    if not _run_command(cmd, cwd=PROJECT_ROOT):
        return False
        
    # Phase 2b: Transpile the model to Scala
    json_model_path = os.path.join(actual_model_dir, "xgboost_lattice_oracle_rank.json")
    scala_output_path = os.path.join(MAF_DIR, "code", "jvm", "src", "main", "scala", "maf", "cli", "runnables", "TranspiledOracle.scala")
    
    transpile_cmd = f'{PYTHON_CMD} scripts/transpile_xgboost.py --json_model {json_model_path} --output {scala_output_path}'
    if not _run_command(transpile_cmd, cwd=PROJECT_ROOT):
        return False

    # Phase 2c: the transpiled oracle is compiled into the evaluation jar, so it has to
    # be reassembled before it can be used by evaluate_model.
    return _run_command('sbt --warn mlOracleFinder/buildJar', cwd=MAF_DIR)

def evaluate_model(lookahead: int, beam: int, test_dir: str, num_cores: int, model_dir: str = None, num_runs: int = 1, data_suffix: str = "", k_cfa: int = 0) -> bool:
    """Runs Phase 3: Scala ML Oracle Evaluation."""
    exp_name, data_path, _, default_model_path = _get_exp_paths(lookahead, beam, k_cfa, data_suffix)
    
    actual_model_dir = model_dir if model_dir else MODELS_DIR
    print(f"\n{'='*60}\n [PHASE 3] Evaluation: {exp_name}{data_suffix} -> {actual_model_dir}\n{'='*60}")
    
    # Store results right next to the model
    results_csv = os.path.join(actual_model_dir, "evaluation_results.csv")
    
    # jar args: modelDir testDir resultFile lookahead beam numRuns k_cfa
    jar = utils.path_to_jar("ml-oracle-finder.jar")
    cmd = f'java -jar {jar} {actual_model_dir} {test_dir} {results_csv} {lookahead} {beam} {num_runs} {k_cfa}'
    return _run_command(cmd, cwd=MAF_DIR)

def generate_random(test_dir: str, num_runs: int = 100, k_cfa: int = 0, num_cores: int = 10) -> bool:
    """Runs Random Trajectory Generation."""
    print(f"\n{'='*60}\n Generating Random Trajectories \n{'='*60}")
    
    jar = utils.path_to_jar("random-trajectory-generator.jar")
    result_file = os.path.join(DATA_BASE_DIR, "raw", "random_trajectories.csv")
    
    # jar args: testDir resultFile numRuns k_cfa numCores
    cmd = f'java -jar {jar} {test_dir} {result_file} {num_runs} {k_cfa} {num_cores}'
    return _run_command(cmd, cwd=MAF_DIR)

def run_all(lookahead: int, beam: int, train_dir: str, test_dir: str, num_cores: int, features: list = None, model_dir: str = None, data_suffix: str = "", k_cfa: int = 0) -> bool:
    """Runs the full pipeline sequentially."""
    if generate_data(lookahead, beam, train_dir, num_cores, data_suffix, k_cfa):
        if train_model(lookahead, beam, train_dir, num_cores, features, model_dir, data_suffix, k_cfa = k_cfa):
            return evaluate_model(lookahead, beam, test_dir, num_cores, model_dir, num_runs=1, data_suffix=data_suffix, k_cfa=k_cfa)
    return False

if __name__ == "__main__":
    import argparse
    parser = argparse.ArgumentParser(description="Lattice ML Pipeline")
    parser.add_argument("--action", choices=["all", "generate", "train", "evaluate", "generate-random"], default="all", help="Which phase to run")
    parser.add_argument("--lookahead", type=int, default=10)
    parser.add_argument("--beam", type=int, default=3)
    parser.add_argument("--train-dir", type=str, default="test/R5RS/various")
    parser.add_argument("--test-dir", type=str, default="../val")
    parser.add_argument("--cores", type=int, default=10)
    parser.add_argument("--num-runs", type=int, default=100, help="Number of random runs for generate-random")
    parser.add_argument("--model-dir", type=str, default=None)
    parser.add_argument("--data-suffix", type=str, default="")
    parser.add_argument("--features", type=str, help="Comma separated features list")
    parser.add_argument("--k", type=int, default=0, help="k-CFA value (default: 0)")
    
    args = parser.parse_args()
    features = args.features.split(",") if args.features else None

    utils.assert_all_jars_exist()
    
    if args.action == "all":
        run_all(args.lookahead, args.beam, args.train_dir, args.test_dir, args.cores, features, args.model_dir, args.data_suffix, args.k)
    elif args.action == "generate":
        generate_data(args.lookahead, args.beam, args.train_dir, args.cores, args.data_suffix, args.k)
    elif args.action == "train":
        train_model(args.lookahead, args.beam, args.train_dir, args.cores, features, args.model_dir, args.data_suffix, k_cfa=args.k)
    elif args.action == "evaluate":
        evaluate_model(args.lookahead, args.beam, args.test_dir, args.cores, args.model_dir, num_runs=3, data_suffix=args.data_suffix, k_cfa=args.k)
    elif args.action == "generate-random":
            generate_random(args.test_dir, args.num_runs, args.k, args.cores)