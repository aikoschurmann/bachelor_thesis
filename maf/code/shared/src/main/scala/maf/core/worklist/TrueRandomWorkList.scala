package maf.core.worklist

import scala.util.Random

case class TrueRandomWorkList[X](elems: Vector[X], presence: Set[X]) extends WorkList[X]:
    def isEmpty: Boolean = elems.isEmpty
    def nonEmpty: Boolean = elems.nonEmpty

    private val randomIndex = if isEmpty then 0 else Random.nextInt(elems.size)
    
    def head: X = elems(randomIndex)

    def tail: TrueRandomWorkList[X] = 
        val selected = head
        // Remove the element at the random index
        val newElems = elems.patch(randomIndex, Nil, 1) 
        TrueRandomWorkList(newElems, presence - selected)

    def add(x: X): TrueRandomWorkList[X] = 
        if presence.contains(x) then this
        else TrueRandomWorkList(elems :+ x, presence + x)

    def addAll(xs: Iterable[X]): TrueRandomWorkList[X] = 
        xs.foldLeft(this)((acc, elm) => acc.add(elm))

    def toSet: Set[X] = presence
    def toList: List[X] = elems.toList
    def contains(x: X): Boolean = presence.contains(x)
    def map[Y](f: X => Y): TrueRandomWorkList[Y] = 
        val newSet = presence.map(f)
        TrueRandomWorkList(newSet.toVector, newSet)
    def filter(f: X => Boolean): TrueRandomWorkList[X] = 
        val newElems = elems.filter(f)
        TrueRandomWorkList(newElems, newElems.toSet)
    def filterNot(f: X => Boolean): TrueRandomWorkList[X] = 
        val newElems = elems.filterNot(f)
        TrueRandomWorkList(newElems, newElems.toSet)
    def -(x: X): TrueRandomWorkList[X] = 
        TrueRandomWorkList(elems.filterNot(_ == x), presence - x)

object TrueRandomWorkList:
    def empty[X]: TrueRandomWorkList[X] = TrueRandomWorkList(Vector.empty, Set.empty)
    def apply[X](xs: Iterable[X]): TrueRandomWorkList[X] = 
        val s = xs.toSet
        TrueRandomWorkList(s.toVector, s)