package maf.core.worklist
import scala.language.unsafeNulls

/**
 * A WorkList that follows a path of Component IDs (Strings).
 */
case class GuidedWorkList[X](items: Vector[X], path: Iterator[String], targetID: String) extends WorkList[X]:

    private lazy val selectedIndex = items.indexWhere(item => 
        GuidedWorkList.sanitize(item.toString) == targetID
    )

    private def safeIndex: Int = 
        if selectedIndex != -1 then selectedIndex else 0

    def head: X = items(safeIndex)

    def tail: GuidedWorkList[X] = 
        val nextID = if path.hasNext then path.next() else ""
        val newItems = items.patch(safeIndex, Nil, 1)
        GuidedWorkList(newItems, path, nextID)

    def add(x: X): GuidedWorkList[X] = 
        if items.contains(x) then this
        else GuidedWorkList(items :+ x, path, targetID)

    def addAll(xs: Iterable[X]): GuidedWorkList[X] = 
        var newItems = items
        xs.foreach { x => 
            if !newItems.contains(x) then newItems = newItems :+ x 
        }
        GuidedWorkList(newItems, path, targetID)

    def size: Int = items.size
    def isEmpty: Boolean = items.isEmpty
    def nonEmpty: Boolean = items.nonEmpty
    def toList: List[X] = items.toList
    def toSet: Set[X] = items.toSet
    def map[Y](f: X => Y): GuidedWorkList[Y] = GuidedWorkList(items.map(f), path, targetID)
    def filter(f: X => Boolean): GuidedWorkList[X] = GuidedWorkList(items.filter(f), path, targetID)
    def filterNot(f: X => Boolean): GuidedWorkList[X] = GuidedWorkList(items.filterNot(f), path, targetID)
    def -(x: X): GuidedWorkList[X] = GuidedWorkList(items.filter(_ != x), path, targetID)
    def contains(x: X): Boolean = items.contains(x)

object GuidedWorkList:
    def sanitize(s: String): String = s.replace(",", ";").nn.replace("\n", " ").nn.trim

    def empty[X](path: Iterator[String]): GuidedWorkList[X] = 
        val firstID = if path.hasNext then path.next() else ""
        GuidedWorkList(Vector.empty[X], path, firstID)
    
    def apply[X](items: Iterable[X], path: Iterator[String]): GuidedWorkList[X] =
        val firstID = if path.hasNext then path.next() else ""
        GuidedWorkList(items.toVector, path, firstID)