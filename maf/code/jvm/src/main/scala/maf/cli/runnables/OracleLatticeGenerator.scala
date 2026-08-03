package maf.cli.runnables

import maf.language.scheme.*
import maf.modular.*
import maf.core.*
import maf.modular.scheme.*
import maf.modular.scheme.modf.*
import maf.modular.worklist.*
import maf.core.worklist.{WorkList, FIFOWorkList}
import maf.lattice.HMap
import maf.util.Reader
import maf.util.benchmarks.Timeout
import java.io.*
import scala.collection.mutable
import scala.math.*
import scala.language.unsafeNulls
import scala.util.Random
import scala.concurrent.*
import scala.concurrent.duration.*
import java.util.concurrent.Executors

/**
 * OracleLatticeGenerator performs a beam search to find the component that 
 * maximizes lattice progression at each step. It records features and 
 * the resulting lattice increase as the target value.
 */
object OracleLatticeGenerator:

    /**
     * Calculates the total lattice progression sum.
     */
    def calculateProgressionSum(analysis: SimpleSchemeModFAnalysis with SchemeDomain): Double =
        val store = analysis.store
        if store.isEmpty then return 0.0
        
        var sum = 0.0
        store.foreach { case (_, value) =>
            val lvl = analysis.lattice.level(value).toDouble
            val toTop = analysis.lattice.levelToTop(value)
            
            if toTop == Int.MaxValue then
                if lvl > 0 then sum += 1.0 - (1.0 / (1.0 + math.log(1.0 + lvl)))
            else
                val total = lvl + toTop
                if total > 0 then sum += lvl / total.toDouble
        }
        sum

    def main(args: Array[String]): Unit =
        val lookahead            = if args.length > 0 then args(0).toInt else 25
        val beamWidth            = if args.length > 1 then args(1).toInt else 1
        val outBaseDir           = if args.length > 2 then new File(args(2)) else new File("../data/lattice_oracle_lookahead_gen")
        val gamma                = 0.90  
        val discoveryBonusWeight = 0.1   
        val numCores             = if args.length > 3 then args(3).toInt else 10
        val testDir              = if args.length > 4 then new File(args(4)) else new File("test/R5RS/various")
        val k_cfa                = if args.length > 5 then args(5).toInt else 0
        val random               = new Random(42)

        implicit val ec: ExecutionContext = ExecutionContext.fromExecutor(
            Executors.newFixedThreadPool(numCores)
        )

        if !outBaseDir.exists() then outBaseDir.mkdirs()

        val files = Option(testDir.listFiles()).getOrElse(Array.empty[File])
            .filter(_.getName.nn.endsWith(".scm")).sortBy(_.getName.nn)

        files.foreach { file =>
            val progName = file.getName.nn.replace(".scm", "")
            val progSource = Reader.loadFile(file.getPath.nn)
            val prog = SchemeParser.parseProgram(progSource)

            // --- Pre-check: Filter out programs smaller than 50 steps (using FIFO) ---
            var fifoSteps = 0
            val fifoAnalysis = new SimpleSchemeModFAnalysis(prog) with SchemeModFKCallSiteSensitivity with SchemeConstantPropagationDomain with SequentialWorklistAlgorithm[SchemeExp] {
                val k = k_cfa
                override def emptyWorkList = FIFOWorkList.empty
                override def step(t: Timeout.T) = { super.step(t); fifoSteps += 1 }
            }
            fifoAnalysis.analyze()

            if fifoSteps < 50 then
                println(s">>> SKIPPING $progName (Too small: $fifoSteps steps) <<<")
            else
                println(s"\n>>> EXPLORING $progName with Lattice-Beam (Lookahead: $lookahead | Beam: $beamWidth | Cores: $numCores) <<<")
                
                val extractor = new LatticeFeatureBuilder()
                val progDir = new File(outBaseDir, progName)
                progDir.mkdirs()

                val writer = new PrintWriter(new BufferedWriter(new FileWriter(new File(progDir, "TRAIN_DATA.csv"))))
                
                // 1. Setup analysis with hooks for Feature Extraction
                var stepCount = 0
                val analysis = new SimpleSchemeModFAnalysis(prog) 
                    with SchemeModFKCallSiteSensitivity 
                    with SchemeConstantPropagationDomain 
                    with SequentialWorklistAlgorithm[SchemeExp]:
                        val k = k_cfa
                        val ref = this
                        override def emptyWorkList = FIFOWorkList(initialComponent)
                        override def spawn(c: SchemeModFComponent) = { extractor.onSpawn(c, stepCount); super.spawn(c) }
                        override def intraAnalysis(c: SchemeModFComponent) = new IntraAnalysis(c) with BigStepModFIntra:
                            override def spawn(callee: SchemeModFComponent) = { extractor.onIntraSpawn(c, callee); super.spawn(callee) }
                            override def trigger(dep: Dependency) = { 
                                val value = ref.returnValue(c)
                                val normalizedTotalLevel = value match
                                    case h: HMap =>
                                        val progressValues = h.keys.flatMap { k =>
                                            h.getAbstract(k).map { v =>
                                                val lvl = k.lattice.level(v).toDouble
                                                val toTop = k.lattice.levelToTop(v)
                                                if toTop == Int.MaxValue then
                                                    if lvl <= 0 then 0.0 else 1.0 - (1.0 / (1.0 + math.log(1.0 + lvl)))
                                                else lvl / (lvl + toTop).toDouble
                                            }
                                        }
                                        if progressValues.isEmpty then 0.0 else progressValues.sum / progressValues.size
                                    case null => 0.0
                                val consumers = ref.deps.getOrElse(dep, Set())
                                extractor.onTrigger(c, consumers, stepCount, normalizedTotalLevel.toFloat)
                                super.trigger(dep) 
                            }

                analysis.init()
                writer.println(extractor.logHeader.replace(",is_selected", ",target_score"))

                while !analysis.finished do
                    if !analysis.workList.isEmpty then extractor.onIteration(analysis.workList, stepCount)

                    val currentWl = analysis.workList.toList
                    val oldProg = calculateProgressionSum(analysis)
                    
                    val candidateFutures = currentWl.map { comp =>
                        Future {
                            val branch = analysis.deepCopy()
                            branch.workList = FIFOWorkList(comp)
                            var beams = List((branch, 0.0, oldProg, branch.visited))
                            var i = 1
                            while i <= lookahead do
                                val nextBeams = beams.flatMap { case (b, bReward, bP, bKnown) =>
                                    if b.finished then List((b, bReward, bP, bKnown))
                                    else b.workList.toList.map { subComp =>
                                        val subBranch = b.deepCopy()
                                        subBranch.workList = FIFOWorkList(subComp)
                                        subBranch.step(Timeout.none)
                                        val latGain = Math.max(0.0, calculateProgressionSum(subBranch) - bP)
                                        val newComps = subBranch.visited -- bKnown
                                        val reward = latGain + (newComps.size * discoveryBonusWeight)
                                        val newReward = bReward + reward * Math.pow(gamma, i)
                                        (subBranch, newReward, calculateProgressionSum(subBranch), bKnown ++ newComps)
                                    }
                                }
                                beams = nextBeams.sortBy(-_._2).take(beamWidth)
                                if beams.forall(_._1.finished) then i = lookahead
                                i += 1
                            val cumulativeDiscountedReward = if beams.nonEmpty then beams.map(_._2).max else 0.0
                            (comp, cumulativeDiscountedReward / lookahead)
                        }
                    }

                    val candidates = Await.result(Future.sequence(candidateFutures), Duration.Inf)
                    // Z-score target: flat steps → labels ~0.5 (no NDCG gradient), decisive steps → spread to 0/1
                    val gains = candidates.map(_._2)
                    val meanGain = if gains.nonEmpty then gains.sum / gains.size else 0.0
                    val variance = if gains.size > 1 then gains.map(g => (g - meanGain) * (g - meanGain)).sum / (gains.size - 1) else 0.0
                    val stdGain = math.sqrt(variance)
                    val isPlateau = stdGain < 1e-9

                    // For plateau steps: FIFO fallback — oldest enqueued gets highest score
                    val maxWait = if isPlateau then
                        currentWl.map(c => (stepCount - extractor.enqueuedStep.getOrElse(c, stepCount)).toDouble).max
                    else 0.0

                    currentWl.foreach { comp =>
                        val gain = candidates.find(_._1 == comp).get._2
                        val featString = extractor.recordFeatures(comp, stepCount, currentWl.size, false)
                        val z = if !isPlateau then (gain - meanGain) / stdGain else 0.0
                        val score = if !isPlateau then
                            1.0 / (1.0 + math.exp(-z))  // Sigmoid maps z-score to [0,1]
                        else if maxWait > 0 then
                            (stepCount - extractor.enqueuedStep.getOrElse(comp, stepCount)).toDouble / maxWait
                        else 0.5
                        val finalLine = featString.substring(0, featString.lastIndexOf(",")) + "," + score
                        writer.println(finalLine)
                    }

                    val (bestComp, bestGain) = candidates.maxBy(_._2)
                    val selection = if isPlateau then currentWl.head
                        else bestComp
                    extractor.recordSelection(selection)

                    val others = currentWl.filterNot(_ == selection)
                    var newWl: WorkList[SchemeModFComponent] = FIFOWorkList.empty.add(selection)
                    others.foreach { c => newWl = newWl.add(c) }
                    analysis.workList = newWl
                    analysis.step(Timeout.none)
                    
                    if stepCount % 10 == 0 then
                        val bestScore = if !isPlateau then 1.0 / (1.0 + math.exp(-((bestGain - meanGain) / stdGain))) else 0.5
                        print(f"\r  [Step $stepCount%4d] Worklist Size: ${candidates.size}%3d | Best Score: $bestScore%1.4f | σ: $stdGain%1.6f | Convergence: $oldProg%1.4f\u001b[K")
                    stepCount += 1

                writer.close()
                println(s"\n  Finished $progName in $stepCount steps.")
        }
