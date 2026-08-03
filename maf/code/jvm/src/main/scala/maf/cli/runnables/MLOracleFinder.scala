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
import java.io.{File, PrintWriter, BufferedWriter, FileWriter}
import scala.collection.mutable
import scala.util.Random
import scala.math.*
import scala.language.unsafeNulls

case class MLConfig(modelDir: String, epsilon: Double = 0.0, starvationThreshold: Int = 50)

class XGBoostScorer(modelDir: String, extractor: FeatureBuilder):
    private val jsonStr = scala.io.Source.fromFile(s"$modelDir/feature_names_lattice_rank.json").mkString
    private val jsonFeatureList = jsonStr.replace("[", "").replace("]", "").split(",").map(_.trim.replace("\"", "")).filter(_.nonEmpty)
    
    private val rawFeatureNames = extractor.featureNames
    private case class FeatureExtractor(rawIndex: Int, op: (Float, Float) => Float)
    
    private val extractors = jsonFeatureList.map { f =>
        if f.startsWith("norm_") then
            val rawName = f.substring(5)
            val idx = rawFeatureNames.indexOf(rawName)
            if idx < 0 then throw new Exception(s"Unknown raw feature: $rawName")
            FeatureExtractor(idx, (r, m) => if m > 0.0f then r / m else 0.0f)
        else if f.startsWith("log_") then
            val rawName = f.substring(4)
            val idx = rawFeatureNames.indexOf(rawName)
            if idx < 0 then throw new Exception(s"Unknown raw feature: $rawName")
            FeatureExtractor(idx, (r, _) => math.log1p(r.toDouble).toFloat)
        else
            val idx = rawFeatureNames.indexOf(f)
            if idx < 0 then throw new Exception(s"Unknown raw feature: $f")
            FeatureExtractor(idx, (r, _) => r)
    }

    def score(poolSize: Int, raw: Array[Array[Float]]): Array[Float] =
        if raw.isEmpty then Array.emptyFloatArray
        else
            val numRawFeats = rawFeatureNames.length
            val maxMap = new Array[Float](numRawFeats)
            for j <- 0 until numRawFeats do maxMap(j) = Float.MinValue
            for r <- raw do
                for j <- 0 until numRawFeats do
                    if r(j) > maxMap(j) then maxMap(j) = r(j)
            
            for j <- 0 until numRawFeats do
                if maxMap(j) <= 0.0f then maxMap(j) = 1.0f

            val scores = new Array[Float](poolSize)
            for i <- 0 until poolSize do
                val r = raw(i)
                val processed = new Array[Float](extractors.length)
                for j <- 0 until extractors.length do
                    val ex = extractors(j)
                    processed(j) = ex.op(r(ex.rawIndex), maxMap(ex.rawIndex))
                scores(i) = TranspiledOracle.score(processed)
            scores

class MLGuidedWorkList(val extractor: LatticeFeatureBuilder, val scorer: XGBoostScorer, val config: MLConfig) extends WorkList[SchemeModFComponent]:
    protected val pool = mutable.ArrayBuffer[SchemeModFComponent]()
    var currentStep: Int = 0
    private var cachedHead: Option[SchemeModFComponent] = None
    private var lastHeadStep: Int = -1

    private val scoreCache = mutable.Map[SchemeModFComponent, Float]()
    private val lastScoredStep = mutable.Map[SchemeModFComponent, Int]()
    private val lastScoredDelta = mutable.Map[SchemeModFComponent, Double]()
    private val batchQueue = mutable.Queue[SchemeModFComponent]()
    private val BATCH_SIZE = 20
    private val STALE_THRESHOLD = 20

    def add(x: SchemeModFComponent) = { if !pool.contains(x) then pool += x; this }
    def addAll(xs: Iterable[SchemeModFComponent]) = { xs.foreach(add); this }
    def isEmpty = pool.isEmpty
    def nonEmpty = pool.nonEmpty
    def contains(x: SchemeModFComponent) = pool.contains(x)
    
    def head = 
        if cachedHead.isDefined && lastHeadStep == currentStep then cachedHead.get
        else
            val h = selectNextComponent()
            cachedHead = Some(h)
            lastHeadStep = currentStep
            h

    def tail = 
        val h = head
        pool -= h
        scoreCache -= h
        lastScoredStep -= h
        lastScoredDelta -= h
        extractor.recordSelection(h)
        cachedHead = None
        this

    protected def selectNextComponent(): SchemeModFComponent =
        if pool.isEmpty then throw new NoSuchElementException()
        
        // 1. Starvation Safety
        var starved: SchemeModFComponent = null
        var i = 0
        while i < pool.length && starved == null do
            val c = pool(i)
            if (currentStep - extractor.enqueuedStep.getOrElse(c, currentStep)) > config.starvationThreshold then
                starved = c
            i += 1
        if starved != null then return starved

        // 2. Batching
        while batchQueue.nonEmpty do
            val next = batchQueue.dequeue()
            if pool.contains(next) then return next

        // 3. Caching & Scoring (Zero GC allocations via while loops)
        val toScore = new Array[SchemeModFComponent](pool.length)
        var toScoreCount = 0
        i = 0
        while i < pool.length do
            val c = pool(i)
            val isStale = !scoreCache.contains(c) ||
                          extractor.deltaChange(c) > lastScoredDelta.getOrElse(c, -1.0) ||
                          (currentStep - lastScoredStep.getOrElse(c, 0)) > STALE_THRESHOLD
            if isStale then
                toScore(toScoreCount) = c
                toScoreCount += 1
            i += 1

        if toScoreCount > 0 then
            val raw = new Array[Array[Float]](toScoreCount)
            var j = 0
            while j < toScoreCount do
                raw(j) = extractor.extractFeatures(toScore(j), currentStep, pool.size)
                j += 1
            
            val newScores = scorer.score(toScoreCount, raw)
            j = 0
            while j < toScoreCount do
                val c = toScore(j)
                scoreCache(c) = newScores(j)
                lastScoredStep(c) = currentStep
                lastScoredDelta(c) = extractor.deltaChange(c)
                j += 1

        // 4. Batch Selection (O(N) Top-K scan instead of O(N log N) sorting)
        val bestK = new Array[SchemeModFComponent](BATCH_SIZE)
        val bestScores = new Array[Float](BATCH_SIZE)
        var b = 0
        while b < BATCH_SIZE do
            bestScores(b) = Float.MinValue
            b += 1
            
        i = 0
        while i < pool.length do
            val c = pool(i)
            val s = scoreCache(c)
            if s > bestScores(BATCH_SIZE - 1) then
                var j = BATCH_SIZE - 1
                while j > 0 && s > bestScores(j - 1) do
                    bestScores(j) = bestScores(j - 1)
                    bestK(j) = bestK(j - 1)
                    j -= 1
                bestScores(j) = s
                bestK(j) = c
            i += 1
            
        val best = bestK(0)
        b = 1
        while b < BATCH_SIZE do
            if bestK(b) != null then batchQueue.enqueue(bestK(b))
            b += 1
            
        best

    override def toList = pool.toList
    override def toSet = pool.toSet
    override def filter(f: SchemeModFComponent => Boolean) = { val toRem = pool.filterNot(f); toRem.foreach(x => { pool -= x; scoreCache -= x }); this }
    override def filterNot(f: SchemeModFComponent => Boolean) = filter(x => !f(x))
    override def map[Y](f: SchemeModFComponent => Y) = throw new UnsupportedOperationException()
    override def -(x: SchemeModFComponent) = { pool -= x; scoreCache -= x; this }

object MLOracleFinder:
    def main(args: Array[String]): Unit =
        val modelDir   = if args.length > 0 then args(0) else "../models"
        val testDir    = if args.length > 1 then new File(args(1)) else new File("test/R5RS/gambit")
        val resultFile = if args.length > 2 then new File(args(2)) else new File("evaluation_results.csv")
        val lookahead  = if args.length > 3 then args(3) else "unknown"
        val beamWidth  = if args.length > 4 then args(4) else "unknown"
        val numRuns    = if args.length > 5 then args(5).toInt else 1
        val k_cfa      = if args.length > 6 then args(6).toInt else 0
        
        val dummyExtractor = new LatticeFeatureBuilder()
        val scorer = new XGBoostScorer(modelDir, dummyExtractor)
        val files = Option(testDir.listFiles).getOrElse(Array.empty[File]).filter(_.getName.nn.endsWith(".scm")).sortBy(_.getName.nn)
        
        val writer = new PrintWriter(new BufferedWriter(new FileWriter(resultFile, false)))
        writer.println("program,lookahead,beam,fifo_steps,ml_steps,ratio,fifo_time_ms,ml_time_ms,overhead_factor")

        files.foreach { file =>
            val prog = SchemeParser.parseProgram(Reader.loadFile(file.getPath.nn))

            // 1. FIFO Analysis
            println(s">>>> Testing FIFO Strategy on ${file.getName.nn} ($numRuns runs) <<<")
            var fifoSteps = 0
            var totalFifoTime = 0.0
            for _ <- 0 until numRuns do
                fifoSteps = 0
                val fifoAnalysis = new SimpleSchemeModFAnalysis(prog) with SchemeModFKCallSiteSensitivity with SchemeConstantPropagationDomain with SequentialWorklistAlgorithm[SchemeExp] {
                    val k = k_cfa
                    override def emptyWorkList = FIFOWorkList.empty
                    override def step(t: Timeout.T) = { 
                        super.step(t)
                        fifoSteps += 1 
                    }
                }
                val startFIFO = System.nanoTime()
                fifoAnalysis.analyze()
                val endFIFO = System.nanoTime()
                totalFifoTime += (endFIFO - startFIFO) / 1_000_000.0
            
            val fifoTimeMs = totalFifoTime / numRuns
            println(f"FIFO finished in $fifoSteps steps ($fifoTimeMs%.2f ms avg).")

            // 2. ML Analysis
            println(s">>>> Testing ML on ${file.getName.nn} ($numRuns runs) <<<")
            var steps = 0
            var totalMlTime = 0.0
            for _ <- 0 until numRuns do
                val extractor = new LatticeFeatureBuilder()
                steps = 0
                val wl = new MLGuidedWorkList(extractor, scorer, MLConfig(modelDir))
                val analysis = new SimpleSchemeModFAnalysis(prog) with SchemeModFKCallSiteSensitivity with SchemeConstantPropagationDomain with SequentialWorklistAlgorithm[SchemeExp] {
                    val k = k_cfa
                    val ref = this
                    override def emptyWorkList = wl
                    override def step(t: Timeout.T) = { 
                        wl.currentStep = steps
                        if !wl.isEmpty then extractor.onIteration(wl, steps) // Update SCCs
                        super.step(t)
                        steps += 1 
                    }
                    override def spawn(c: SchemeModFComponent) = { extractor.onSpawn(c, steps); super.spawn(c) }
                    override def intraAnalysis(c: SchemeModFComponent) = new IntraAnalysis(c) with BigStepModFIntra:
                        override def spawn(callee: SchemeModFComponent) = { extractor.onIntraSpawn(c, callee); super.spawn(callee) }
                        override def trigger(dep: Dependency) = { 
                            val value = ref.returnValue(c)
                            val normalizedTotalLevel = value match
                                case h: HMap =>
                                    def calculateProgressionSum(h: HMap): Double =
                                        var sum = 0.0
                                        var count = 0
                                        h.keys.foreach { k =>
                                            h.getAbstract(k).foreach { v =>
                                                val lvl = k.lattice.level(v).toDouble
                                                val toTop = k.lattice.levelToTop(v)
                                                val progress = if toTop == Int.MaxValue then
                                                    if lvl <= 0 then 0.0 else 1.0 - (1.0 / (1.0 + math.log(1.0 + lvl)))
                                                else
                                                    lvl / (lvl + toTop).toDouble
                                                sum += progress
                                                count += 1
                                            }
                                        }
                                        if count == 0 then 0.0 else sum / count
                                    calculateProgressionSum(h)
                                case null => 0.0
                            val consumers = ref.deps.getOrElse(dep, Set())
                            extractor.onTrigger(c, consumers, steps, normalizedTotalLevel.toFloat)
                            super.trigger(dep) 
                        }
                }
                val startML = System.nanoTime()
                analysis.analyze()
                val endML = System.nanoTime()
                totalMlTime += (endML - startML) / 1_000_000.0
            
            val mlTimeMs = totalMlTime / numRuns
            
            println(f"ML finished in $steps steps ($mlTimeMs%.2f ms avg).")
            val ratio = steps.toDouble / fifoSteps
            val overhead = (mlTimeMs / steps) / (fifoTimeMs / fifoSteps)
            println(f"Ratio (ML/FIFO Steps): $ratio%.3f")
            println(f"TPI Overhead Factor: $overhead%.2fx")
            println("-" * 40)
            
            import java.util.Locale
            val ratioStr = String.format(Locale.US, "%.4f", ratio)
            val fifoTimeStr = String.format(Locale.US, "%.2f", fifoTimeMs)
            val mlTimeStr = String.format(Locale.US, "%.2f", mlTimeMs)
            val overheadStr = String.format(Locale.US, "%.2f", overhead)
            
            writer.println(s"${file.getName.nn},$lookahead,$beamWidth,$fifoSteps,$steps,$ratioStr,$fifoTimeStr,$mlTimeStr,$overheadStr")
            writer.flush()
        }
        writer.close()
