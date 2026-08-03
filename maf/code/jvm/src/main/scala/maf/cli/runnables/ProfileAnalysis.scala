package maf.cli.runnables

import maf.language.scheme.*
import maf.modular.*
import maf.core.*
import maf.modular.scheme.*
import maf.modular.scheme.modf.*
import maf.modular.worklist.*
import maf.core.worklist.{WorkList, FIFOWorkList}
import maf.util.Reader
import maf.util.benchmarks.Timeout
import java.io.File
import scala.language.unsafeNulls
import scala.concurrent.duration._

object ProfileAnalysis:
    def main(args: Array[String]): Unit =
        val fileStr = "../val/icp_1c_multiple-dwelling.scm"
        val modelDir = "../data/experiments/lattice_l10_b3/models/o1_features"
        
        val file = new File(fileStr)
        if file.exists() then
            println(s"\n=== Profiling ML on ${file.getName} ===")
            val progSource = Reader.loadFile(file.getPath.nn)
            val prog = SchemeParser.parseProgram(progSource)

            println("Initializing ML Scorer...")
            val extractor = new LatticeFeatureBuilder()
            val scorer = new XGBoostScorer(modelDir, extractor)
            val mlConfig = MLConfig(modelDir, 0.0, 50)

            println("Starting ML Analysis...")
            var mlSteps = 0
            val mlAnalysis = new SimpleSchemeModFAnalysis(prog) with SchemeModFKCallSiteSensitivity with SchemeConstantPropagationDomain with SequentialWorklistAlgorithm[SchemeExp] {
                val k = 1
                override def emptyWorkList = new MLGuidedWorkList(extractor, scorer, mlConfig)
                override def step(t: Timeout.T) = { 
                    super.step(t)
                    mlSteps += 1 
                }
            }
            mlAnalysis.analyze()
            println(f"ML -> Steps: $mlSteps")
