package maf.cli.runnables

import maf.modular.scheme.modf.SchemeModFComponent
import maf.core.worklist.WorkList
import scala.collection.mutable
import scala.language.unsafeNulls
import maf.language.scheme._

trait FeatureBuilder:
    def featureNames: Array[String]
    def logHeader: String = featureNames.mkString(",") + ",is_selected"
    def extractFeatures(comp: SchemeModFComponent, step: Int, worklistSize: Int): Array[Float]
    def recordFeatures(comp: SchemeModFComponent, step: Int, worklistSize: Int, isSelected: Boolean): String = 
        val feats = extractFeatures(comp, step, worklistSize).mkString(",")
        s"$feats,${if isSelected then 1 else 0}"
    
    // Lifecycle hooks
    def onSpawn(cmp: SchemeModFComponent, step: Int): Unit = ()
    def onIntraSpawn(caller: SchemeModFComponent, callee: SchemeModFComponent): Unit = ()
    def onIteration(wl: WorkList[SchemeModFComponent], step: Int): Unit = ()
    def onRegister(cmp: SchemeModFComponent): Unit = ()
    def onTrigger(producer: SchemeModFComponent, consumers: Iterable[SchemeModFComponent], step: Int, levelToTop: Float): Unit = ()
    def recordSelection(comp: SchemeModFComponent): Unit = ()

trait ComponentSizeMetrics:
    protected val sizeCache = mutable.Map[SchemeModFComponent, Int]()
    protected def getComponentSize(comp: SchemeModFComponent): Int = 
        sizeCache.getOrElseUpdate(comp, comp match
            case c: SchemeModFComponent.Call[_] => countNodes(c.lambda)
            case _ => 1
        )
    private def countNodes(exp: maf.core.Expression): Int = 1 + exp.subexpressions.map(countNodes).sum

trait CallGraphMetrics:
    protected val outEdges = mutable.Map[SchemeModFComponent, Set[SchemeModFComponent]]().withDefaultValue(Set())
    protected val inEdges = mutable.Map[SchemeModFComponent, Set[SchemeModFComponent]]().withDefaultValue(Set())

    def updateEdges(caller: SchemeModFComponent, callee: SchemeModFComponent): Unit =
        outEdges(caller) = outEdges(caller) + callee
        inEdges(callee) = inEdges(callee) + caller

trait CentralityMetrics extends CallGraphMetrics:
    protected var cachedTransitiveDeps = Map[SchemeModFComponent, Int]()
    protected var cachedDAGDepth = Map[SchemeModFComponent, Int]()
    protected var cachedPageRank = Map[SchemeModFComponent, Double]()
    protected var cachedBetweenness = Map[SchemeModFComponent, Double]()
    protected var lastDepChainGraphSize = 0

    protected def recomputeDepChains(): Unit =
        val allNodes = (outEdges.keys ++ outEdges.values.flatten).toSet
        val transitiveCache = mutable.Map[SchemeModFComponent, Int]()
        
        allNodes.foreach { node =>
            val visited = mutable.Set[SchemeModFComponent]()
            val queue = mutable.Queue[SchemeModFComponent](node)
            visited.add(node)
            var count = 0
            while queue.nonEmpty do
                val curr = queue.dequeue()
                for child <- outEdges.getOrElse(curr, Set.empty) do
                    if visited.add(child) then
                        count += 1
                        queue.enqueue(child)
            transitiveCache(node) = count
        }
        cachedTransitiveDeps = transitiveCache.toMap

        val depthCache = mutable.Map[SchemeModFComponent, Int]()
        def computeDepth(node: SchemeModFComponent, visited: Set[SchemeModFComponent] = Set.empty): Int =
            if visited.contains(node) then return 0
            depthCache.getOrElseUpdate(node, {
                val parents = inEdges.getOrElse(node, Set.empty) -- visited
                if parents.isEmpty then 0
                else parents.map(p => computeDepth(p, visited + node)).max + 1
            })
        
        allNodes.foreach(n => computeDepth(n))
        cachedDAGDepth = depthCache.toMap

        val n = allNodes.size
        if n > 0 then
            val nodeArray = allNodes.toArray
            val nodeToIndex = nodeArray.zipWithIndex.toMap
            
            // Primitive adjacency lists
            val adj = Array.fill(n)(Array.empty[Int])
            val inAdj = Array.fill(n)(Array.empty[Int])
            for i <- 0 until n do
                val node = nodeArray(i)
                adj(i) = outEdges.getOrElse(node, Set.empty).flatMap(nodeToIndex.get).toArray
                inAdj(i) = inEdges.getOrElse(node, Set.empty).flatMap(nodeToIndex.get).toArray

            // PageRank with primitives
            var ranks = Array.fill(n)(1.0 / n)
            val damping = 0.85
            for _ <- 0 until 20 do
                val newRanks = new Array[Double](n)
                for i <- 0 until n do
                    var incomingRank = 0.0
                    for pred <- inAdj(i) do
                        val predOutDeg = adj(pred).length
                        if predOutDeg > 0 then incomingRank += ranks(pred) / predOutDeg
                    newRanks(i) = (1.0 - damping) / n + damping * incomingRank
                ranks = newRanks
            cachedPageRank = (0 until n).map(i => nodeArray(i) -> ranks(i)).toMap

            // Betweenness with primitives
            val betweenness = new Array[Double](n)
            for s <- 0 until n do
                val stack = mutable.Stack[Int]()
                val preds = Array.fill(n)(mutable.ListBuffer.empty[Int])
                val sigma = new Array[Double](n)
                val dist = Array.fill(n)(-1)
                val delta = new Array[Double](n)
                
                sigma(s) = 1.0; dist(s) = 0
                val queue = mutable.Queue[Int](s)
                
                while queue.nonEmpty do
                    val v = queue.dequeue()
                    stack.push(v)
                    for w <- adj(v) do
                        if dist(w) < 0 then { queue.enqueue(w); dist(w) = dist(v) + 1 }
                        if dist(w) == dist(v) + 1 then
                            sigma(w) += sigma(v)
                            preds(w) += v
                            
                while stack.nonEmpty do
                    val w = stack.pop()
                    for v <- preds(w) do
                        delta(v) += (sigma(v) / sigma(w)) * (1.0 + delta(w))
                    if w != s then betweenness(w) += delta(w)
                    
            cachedBetweenness = (0 until n).map(i => nodeArray(i) -> betweenness(i)).toMap
        else
            cachedPageRank = Map.empty
            cachedBetweenness = Map.empty

trait WorklistStateMetrics:
    val enqueuedStep = mutable.Map[SchemeModFComponent, Int]() 
    protected val discoveryStep = mutable.Map[SchemeModFComponent, Int]()
    protected val visitCounts = mutable.Map[SchemeModFComponent, Int]().withDefaultValue(0)
    protected val triggeringProducers = mutable.Map[SchemeModFComponent, mutable.Set[SchemeModFComponent]]()
    protected val arrivalTime = mutable.Map[SchemeModFComponent, Long]()
    val deltaChange = mutable.Map[SchemeModFComponent, Double]().withDefaultValue(0.0)
    
    protected val inputLevelSum = mutable.Map[SchemeModFComponent, Float]().withDefaultValue(0.0f)
    protected val inputLevelCount = mutable.Map[SchemeModFComponent, Int]().withDefaultValue(0)
    protected val producerConvergence = mutable.Map[SchemeModFComponent, Float]().withDefaultValue(0.0f)
    
    protected var arrivalCounter = 0L
    protected var lastSelected: Option[SchemeModFComponent] = None

class LatticeFeatureBuilder extends FeatureBuilder 
    with ComponentSizeMetrics 
    with CallGraphMetrics
    with WorklistStateMetrics
    with TranspiledFeatureExtractor:

    val inWorklist = mutable.Set[SchemeModFComponent]()

    override def recordSelection(comp: SchemeModFComponent): Unit = 
        lastSelected = Some(comp)
        visitCounts(comp) += 1
        triggeringProducers.get(comp).foreach(_.clear())
        deltaChange(comp) = 0.0
        inputLevelSum(comp) = 0.0f
        inputLevelCount(comp) = 0
        inWorklist.remove(comp)

    override def onSpawn(cmp: SchemeModFComponent, step: Int): Unit = 
        enqueuedStep(cmp) = step
        inWorklist.add(cmp)
        if !discoveryStep.contains(cmp) then discoveryStep(cmp) = step
        if !arrivalTime.contains(cmp) then { arrivalTime(cmp) = arrivalCounter; arrivalCounter += 1 }
    
    override def onIntraSpawn(caller: SchemeModFComponent, callee: SchemeModFComponent): Unit =
        updateEdges(caller, callee)

    override def onTrigger(producer: SchemeModFComponent, consumers: Iterable[SchemeModFComponent], step: Int, levelToTop: Float): Unit =
        producerConvergence(producer) = levelToTop
        consumers.foreach { c => 
            enqueuedStep(c) = step
            inWorklist.add(c)
            triggeringProducers.getOrElseUpdate(c, mutable.Set.empty) += producer
            deltaChange(c) += 1.0
            inputLevelSum(c) += levelToTop
            inputLevelCount(c) += 1
        }


