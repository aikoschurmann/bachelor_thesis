package maf.cli.runnables

import maf.language.scheme.*
import maf.modular.*
import maf.core.*
import maf.modular.scheme.*
import maf.modular.scheme.modf.*
import maf.modular.worklist.*
import maf.core.worklist.{WorkList, FIFOWorkList, TrueRandomWorkList}
import maf.util.Reader
import maf.util.benchmarks.Timeout
import java.io.{File, PrintWriter, BufferedWriter, FileWriter}
import scala.language.unsafeNulls
import scala.concurrent.*
import scala.concurrent.duration.*
import java.util.concurrent.Executors

object RandomTrajectoryGenerator:
    def main(args: Array[String]): Unit =
        val testDir    = if args.length > 0 then new File(args(0)) else new File("test/R5RS/various")
        val resultFile = if args.length > 1 then new File(args(1)) else new File("data/raw/random_trajectories.csv")
        val numRuns    = if args.length > 2 then args(2).toInt else 100
        val k_cfa      = if args.length > 3 then args(3).toInt else 0
        val numCores   = if args.length > 4 then args(4).toInt else 10
        
        implicit val ec: ExecutionContext = ExecutionContext.fromExecutor(
            Executors.newFixedThreadPool(numCores)
        )

        resultFile.getParentFile.mkdirs()
        val writer = new PrintWriter(new BufferedWriter(new FileWriter(resultFile, false)))
        writer.println("filename,strategy,iterations")

        val files = Option(testDir.listFiles).getOrElse(Array.empty[File]).filter(_.getName.nn.endsWith(".scm")).sortBy(_.getName.nn)

        for file <- files do
            val prog = SchemeParser.parseProgram(Reader.loadFile(file.getPath.nn))
            
            // 1. FIFO
            println(s"Running FIFO for ${file.getName}...")
            var fifoSteps = 0
            val fifoAnalysis = new SimpleSchemeModFAnalysis(prog) with SchemeModFKCallSiteSensitivity with SchemeConstantPropagationDomain with SequentialWorklistAlgorithm[SchemeExp] {
                val k = k_cfa
                override def emptyWorkList = FIFOWorkList.empty
                override def step(t: Timeout.T) = { 
                    super.step(t)
                    fifoSteps += 1 
                }
            }
            fifoAnalysis.analyze()
            writer.println(s"${file.getName},FIFO,$fifoSteps")
            writer.flush()
            
            // 2. Random (Multi-processed)
            println(s"Running Random for ${file.getName} ($numRuns runs on $numCores cores)...")
            
            val randomFutures = (1 to numRuns).map { i =>
                Future {
                    var randomSteps = 0
                    val randomAnalysis = new SimpleSchemeModFAnalysis(prog) with SchemeModFKCallSiteSensitivity with SchemeConstantPropagationDomain with SequentialWorklistAlgorithm[SchemeExp] {
                        val k = k_cfa
                        override def emptyWorkList = TrueRandomWorkList.empty
                        override def step(t: Timeout.T) = { 
                            super.step(t)
                            randomSteps += 1 
                        }
                    }
                    randomAnalysis.analyze()
                    randomSteps
                }
            }

            // Wait for all runs to finish, then write out sequentially to avoid file I/O threading issues
            val randomResults = Await.result(Future.sequence(randomFutures), Duration.Inf)
            
            randomResults.foreach { steps =>
                writer.println(s"${file.getName},Random,$steps")
            }
            writer.flush()

        writer.close()
        println("Done.")
        sys.exit(0)