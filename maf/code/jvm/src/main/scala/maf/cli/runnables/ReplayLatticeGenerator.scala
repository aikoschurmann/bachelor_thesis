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

object ReplayLatticeGenerator:
    def main(args: Array[String]): Unit =
        if args.length < 2 then
            println("Usage: ReplayLatticeGenerator <trace_dir> <output_dir>")
            sys.exit(1)
        
        val traceDir = new File(args(0))
        val outputDir = new File(args(1))
        outputDir.mkdirs()
        
        val progDirs = traceDir.listFiles().filter(_.isDirectory)
        for progDir <- progDirs do
            val csvFile = new File(progDir, "TRAIN_DATA.csv")
            if csvFile.exists() then
                println(s"Replaying ${progDir.getName}...")
                
                val outProgDir = new File(outputDir, progDir.getName)
                outProgDir.mkdirs()
                val outFile = new File(outProgDir, "TRAIN_DATA.csv")
                
                val lines = scala.io.Source.fromFile(csvFile).getLines().toList
                if lines.nonEmpty then
                    val header = lines.head.split(",")
                    val targetScoreIdx = header.indexOf("target_score")
                    val isSelectedIdx = header.indexOf("is_selected")
                    val hashIdx = header.indexOf("name_hash")
                    
                    val trueSelectedIdx = if isSelectedIdx >= 0 then isSelectedIdx else targetScoreIdx
                    
                    if hashIdx >= 0 && trueSelectedIdx >= 0 then
                        val selections = mutable.ArrayBuffer[Int]()
                        val originalScores = mutable.Map[(Int, Int), String]()
                        
                        var currentStep = 0
                        val stepIdx = header.indexOf("step")
                        
                        for line <- lines.tail do
                            val parts = line.split(",")
                            if parts.length > math.max(math.max(trueSelectedIdx, hashIdx), stepIdx) then
                                val s = parts(stepIdx).toFloat.toInt
                                val h = parts(hashIdx).toFloat.toInt
                                val score = parts(trueSelectedIdx)
                                originalScores((s, h)) = score
                                
                                // Oracle selects the highest target_score for the step, which might be 1.0 or just the highest.
                                // Actually, OracleLatticeGenerator writes `was_selected` as 1.0. If we don't have it, we pick the highest target_score.
                                if (score == "1" || score == "1.0") then selections += h
                        
                        val prgName = progDir.getName + ".scm"
                        val prgFile = new File(s"test/R5RS/various/$prgName")
                        
                        if prgFile.exists() then
                            val prgSource = Reader.loadFile(prgFile.getPath.nn)
                            val prg = SchemeParser.parseProgram(prgSource)
                            val extractor = new LatticeFeatureBuilder()
                            
                            var stepCount = 0
                            val writer = new PrintWriter(new BufferedWriter(new FileWriter(outFile)))
                            writer.println(extractor.logHeader.replace(",is_selected", if targetScoreIdx >= 0 then ",target_score" else ",is_selected"))
                            
                            val analysis = new SimpleSchemeModFAnalysis(prg) 
                                with SchemeModFNoSensitivity 
                                with SchemeConstantPropagationDomain 
                                with SequentialWorklistAlgorithm[SchemeExp]:
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
                                    
                                    override def step(t: Timeout.T): Unit =
                                        if !this.workList.isEmpty then
                                            extractor.onIteration(this.workList, stepCount)
                                            val wlSize = this.workList.toList.size
                                            val pool = this.workList.toList
                                            
                                            // Determine chosen node by checking which node in the pool has score 1.0 in originalScores
                                            var chosenNode: SchemeModFComponent = null
                                            for c <- pool do
                                                val h = c.toString.hashCode
                                                if originalScores.getOrElse((stepCount, h), "0.0") == "1.0" || originalScores.getOrElse((stepCount, h), "0.0") == "1" then
                                                    chosenNode = c
                                            
                                            // Fallback to selections array if target_score wasn't strictly 1.0
                                            if chosenNode == null then
                                                val targetHash = if stepCount < selections.length then selections(stepCount) else 0
                                                pool.foreach { c => if c.toString.hashCode == targetHash then chosenNode = c }
                                            
                                            if chosenNode == null then chosenNode = pool.head
                                            
                                            for c <- pool do
                                                val h = c.toString.hashCode
                                                val originalScore = originalScores.getOrElse((stepCount, h), if c == chosenNode then "1.0" else "0.0")
                                                val features = extractor.extractFeatures(c, stepCount, wlSize).mkString(",")
                                                writer.println(s"$features,$originalScore")
                                            
                                            extractor.recordSelection(chosenNode)
                                            val others = pool.filterNot(_ == chosenNode)
                                            var newWl: WorkList[SchemeModFComponent] = FIFOWorkList.empty.add(chosenNode)
                                            others.foreach { c => newWl = newWl.add(c) }
                                            this.workList = newWl
                                            super.step(t)
                                            stepCount += 1
                            
                            analysis.analyze()
                            writer.close()
