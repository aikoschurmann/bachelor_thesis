#!/bin/bash
set -e

echo "=== Evaluating O1 Features (5 runs) ==="
.venv/bin/python scripts/transpile_xgboost.py --json_model data/experiments/lattice_l10_b3/models/o1_features/xgboost_lattice_oracle_rank.json --output maf/code/jvm/src/main/scala/maf/cli/runnables/TranspiledOracle.scala
cd maf
sbt --warn mlOracleFinder/buildJar
java -jar build/ml-oracle-finder.jar ../data/experiments/lattice_l10_b3/models/o1_features ../val ../data/experiments/lattice_l10_b3/models/o1_features/eval_5runs.csv 10 3 5 0
cd ..

echo "=== Evaluating All Features (5 runs) ==="
.venv/bin/python scripts/transpile_xgboost.py --json_model data/experiments/lattice_l10_b3/models/all_features/xgboost_lattice_oracle_rank.json --output maf/code/jvm/src/main/scala/maf/cli/runnables/TranspiledOracle.scala
cd maf
sbt --warn mlOracleFinder/buildJar
java -jar build/ml-oracle-finder.jar ../data/experiments/lattice_l10_b3/models/all_features ../val ../data/experiments/lattice_l10_b3/models/all_features/eval_5runs.csv 10 3 5 0
cd ..

echo "=== DONE ==="
