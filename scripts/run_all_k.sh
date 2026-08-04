#!/bin/bash

for i in {1..5}; do 
    echo "== "
    echo "== Running for k = $i"
    echo "=="
    python3 scripts/lattice_pipeline.py --action generate --lookahead 25 --beam 1 --k $i --cores 32
done
