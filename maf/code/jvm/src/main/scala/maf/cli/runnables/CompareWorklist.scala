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

object CompareWorklist:
    def main(args: Array[String]): Unit =
        val testFiles = List("../val/icp_1c_multiple-dwelling.scm", "../val/earley.scm", "../val/boyer.scm")
        
        for fileStr <- testFiles do
            val file = new File(fileStr)
            if file.exists() then
                println(s"\n=== Testing ${file.getName} ===")
                val progSource = Reader.loadFile(file.getPath.nn)
                val prog = SchemeParser.parseProgram(progSource)

                // k = 0
                var k0Steps = 0
                var k0TotalSize = 0L
                var k0MaxSize = 0
                val k0Analysis = new SimpleSchemeModFAnalysis(prog) with SchemeModFNoSensitivity with SchemeConstantPropagationDomain with SequentialWorklistAlgorithm[SchemeExp] {
                    override def emptyWorkList = FIFOWorkList.empty
                    override def step(t: Timeout.T) = { 
                        val s = workList.toList.size
                        k0TotalSize += s
                        if s > k0MaxSize then k0MaxSize = s
                        super.step(t)
                        k0Steps += 1 
                    }
                }
                k0Analysis.analyze()
                val k0Avg = if k0Steps > 0 then k0TotalSize.toDouble / k0Steps else 0.0
                println(f"k=0 -> Steps: $k0Steps%7d | Max WL: $k0MaxSize%5d | Avg WL: $k0Avg%.2f")

                // k = 1
                var k1Steps = 0
                var k1TotalSize = 0L
                var k1MaxSize = 0
                val k1Analysis = new SimpleSchemeModFAnalysis(prog) with SchemeModFKCallSiteSensitivity with SchemeConstantPropagationDomain with SequentialWorklistAlgorithm[SchemeExp] {
                    val k = 1
                    override def emptyWorkList = FIFOWorkList.empty
                    override def step(t: Timeout.T) = { 
                        val s = workList.toList.size
                        k1TotalSize += s
                        if s > k1MaxSize then k1MaxSize = s
                        super.step(t)
                        k1Steps += 1 
                    }
                }
                k1Analysis.analyze()
                val k1Avg = if k1Steps > 0 then k1TotalSize.toDouble / k1Steps else 0.0
                println(f"k=1 -> Steps: $k1Steps%7d | Max WL: $k1MaxSize%5d | Avg WL: $k1Avg%.2f")
