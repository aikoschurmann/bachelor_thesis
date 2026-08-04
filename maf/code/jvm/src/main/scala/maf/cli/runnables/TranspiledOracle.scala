package maf.cli.runnables

// STUB -- placeholder so that the project compiles without a trained model.
//
// This file is overwritten by `scripts/transpile_xgboost.py` (phase 2b of
// `scripts/lattice_pipeline.py`) with the transpiled XGBoost model. Until then,
// `score` returns the neutral base score for every input, which makes the ML
// oracle rank all candidates equally.
object TranspiledOracle:
    val baseScore: Float = 0.0f
    def score(features: Array[Float]): Float =
        baseScore
