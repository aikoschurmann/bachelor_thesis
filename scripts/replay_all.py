import os
import sys
import subprocess
from pathlib import Path
from concurrent.futures import ThreadPoolExecutor

def run_replay(source_dir: str, target_dir: str):
    print(f"Replaying {source_dir} -> {target_dir}")
    # Run the SBT command to launch the ReplayLatticeGenerator
    source_abs = os.path.abspath(source_dir)
    target_abs = os.path.abspath(target_dir)
    cmd = f"sbt 'maf/runMain maf.cli.runnables.ReplayLatticeGenerator {source_abs} {target_abs}'"
    try:
        result = subprocess.run(cmd, shell=True, check=True, capture_output=True, text=True, cwd="maf")
        print(f"[SUCCESS] {source_dir} upgrade complete.")
    except subprocess.CalledProcessError as e:
        print(f"[ERROR] Replay failed for {source_dir}")
        print(e.stdout)
        print(e.stderr)

def main():
    base_data_dir = Path("data")
    
    # Find all original oracle datasets
    datasets = [
        d for d in base_data_dir.iterdir() 
        if d.is_dir() and d.name.startswith("lattice_") and not d.name.endswith("_advanced")
    ]
    
    if not datasets:
        print("No datasets found to replay.")
        sys.exit(0)
        
    print(f"Found {len(datasets)} datasets to upgrade: {[d.name for d in datasets]}")
    
    # We use ThreadPoolExecutor to run multiple SBT processes in parallel if desired,
    for dataset in datasets:
        source_dir = str(dataset.absolute())
        target_dir = str((base_data_dir / f"{dataset.name}_advanced").absolute())
        run_replay(source_dir, target_dir)

if __name__ == "__main__":
    main()
