package ch.epfl.data
package dblab.legobase
package queryengine
package monad

import sc.pardis.annotations.{ deep, needsCircular, dontLift, needs, reflect, pure, transformation }
import sc.pardis.shallow.{ Record, DynamicCompositeRecord }
import push.MultiMap
import scala.collection.mutable.MultiMap
import scala.collection.mutable.HashMap
import scala.collection.mutable.ArrayBuffer
import scala.collection.mutable.TreeSet
import scala.language.implicitConversions
import QueryStream.NULL

// @reflect[Query[_]]
// @transformation
abstract class QueryStream[T] { self =>
  def stream(): Option[T]
  def reset(): Unit
  @pure def map[S](f: T => S): QueryStream[S] = unstream { () =>
    val elem = stream()
    if (elem == NULL)
      NULL
    else
      elem.map(f)
  }
  @pure def filter(p: T => Boolean): QueryStream[T] = unstream { () =>
    val elem = stream()
    if (elem == NULL)
      NULL
    else
      // elem.flatMap(x => if (p(x)) Some(x) else None)
      elem match {
        case Some(x) if p(x) => Some(x)
        case _               => None
      }
  }
  def foreach(f: T => Unit): Unit = {
    reset()
    var elem: Option[T] = NULL
    while ({
      elem = stream()
      elem
    } != NULL) {
      for (e <- elem)
        f(e)
    }
  }

  @pure def foldLeft[S](z: S)(f: (S, T) => S): S = {
    var res = z
    for (e <- this) {
      res = f(res, e)
    }
    res
  }

  @pure def sum(implicit num: Numeric[T]): T = {
    var res = num.zero
    for (e <- this) {
      res = num.plus(res, e)
    }
    res
  }
  @pure def count: Int = {
    var size = 0
    for (e <- this) {
      size += 1
    }
    size
  }
  @pure def avg(implicit num: Fractional[T]): T =
    num.div(sum, num.fromInt(count))
  @pure def groupBy[K](par: T => K): GroupedQueryStream[K, T] =
    new GroupedQueryStream(this, par)
  @pure def filteredGroupBy[K](pred: T => Boolean, par: T => K): GroupedQueryStream[K, T] =
    filter(pred).groupBy(par)

  def sortBy[S](f: T => S)(implicit ord: Ordering[S]): QueryStream[T] = {
    val (treeSet, size) = {
      val treeSet = new TreeSet()(
        new Ordering[T] {
          def compare(o1: T, o2: T) = {
            val res = ord.compare(f(o1), f(o2))
            if (res == 0 && o1 != o2) {
              -1
            } else {
              res
            }
          }
        })
      for (elem <- this) {
        treeSet += elem
      }
      (treeSet, treeSet.size)
    }
    var index = 0
    unstream { () =>
      if (index >= size)
        NULL
      else {
        index += 1
        val elem = treeSet.head
        treeSet -= elem
        Some(elem)
      }
    }
  }

  // // @pure def sortByReverse[S](f: T => S)(implicit ord: Ordering[S]): Query[T] =
  // //   new Query(underlying.sortBy(f).reverse)

  // // @pure def take(i: Int): QueryStream[T] = (k: T => Unit) => {
  // //   var count = 0
  // //   foreach(e => {
  // //     if (count < i) {
  // //       k(e)
  // //       count += 1
  // //     }
  // //   })
  // // }

  @pure def minBy[S](f: T => S)(implicit ord: Ordering[S]): T = {
    var minResult: T = null.asInstanceOf[T]
    for (e <- this) {
      if (minResult == null || ord.compare(f(minResult), f(e)) > 0) {
        minResult = e
      }
    }
    minResult
  }

  def printRows(printFunc: T => Unit, limit: Int): Unit = {
    var rows = 0
    if (limit == -1) {
      for (e <- this) {
        printFunc(e)
        rows += 1
      }
    } else {
      var elem: Option[T] = NULL
      while (rows < limit && {
        elem = stream()
        elem
      } != NULL) {
        for (e <- elem) {
          printFunc(e)
          rows += 1
        }
      }
    }
    printf("(%d rows)\n", rows)
  }

  @pure def getList: List[T] = {
    var res = ArrayBuffer[T]()
    for (e <- this) {
      res += e
    }
    res.toList
  }

  def unstream[T](n: () => Option[T]): QueryStream[T] = new QueryStream[T] {
    def stream(): Option[T] = n()
    def reset(): Unit = self.reset()
  }
}

object QueryStream {
  def NULL[S]: S = null.asInstanceOf[S]
  @dontLift def apply[T](arr: Array[T]): QueryStream[T] = new QueryStream[T] {
    var index = 0
    def stream(): Option[T] =
      if (index >= arr.length)
        NULL
      else {
        index += 1
        Some(arr(index - 1))
      }
    def reset(): Unit = {
      index = 0
    }
  }
  def apply[T](set: scala.collection.mutable.Set[T]): SetStream[T] = new SetStream[T](set)
}

class SetStream[T](set: scala.collection.mutable.Set[T]) extends QueryStream[T] { self =>
  var currentSet: scala.collection.mutable.Set[T] = set

  def reset() = currentSet = set
  def stream() = {
    if (currentSet.isEmpty) {
      NULL
    } else {
      val elem = currentSet.head
      currentSet = currentSet.tail
      Some(elem)
    }
  }

  def withFilter(p: T => Boolean): SetStream[T] = new SetStream[T](set) {
    val underlying = self.filter(p)
    override def stream() = underlying.stream()
  }
}

class JoinableQueryStream[T <: Record](private val underlying: QueryStream[T]) {
  def hashJoin[S <: Record, R](q2: QueryStream[S])(leftHash: T => R)(rightHash: S => R)(
    joinCond: (T, S) => Boolean): QueryStream[DynamicCompositeRecord[T, S]] = {
    val hm = MultiMap[R, T]
    for (elem <- underlying) {
      hm.addBinding(leftHash(elem), elem)
    }
    var iterator: SetStream[T] = null
    var prevRightElem: Option[S] = None
    underlying.unstream { () =>
      var leftElem: Option[T] = None
      val rightElem = if (iterator == null || {
        leftElem = iterator.stream()
        leftElem
      } == NULL) {
        val re = {
          val e2 = q2.stream()
          e2 match {
            case null => NULL
            case Some(t) =>
              val k = rightHash(t)
              hm.get(k) match {
                case Some(tmpBuffer) =>
                  iterator = QueryStream(tmpBuffer).withFilter(e => joinCond(e, t))
                  leftElem = iterator.stream()
                  Some(t)
                case None =>
                  None
              }
            case None =>
              None
          }
        }
        prevRightElem = re
        re
      } else {
        prevRightElem
      }
      if (rightElem == NULL) {
        NULL
      } else {
        if (leftElem == NULL) {
          None
        } else {
          for (e1 <- leftElem; e2 <- rightElem) yield {
            e1.concatenateDynamic(e2)
          }
        }
      }
    }
  }

  def mergeJoin[S <: Record](q2: QueryStream[S])(
    ord: (T, S) => Int)(joinCond: (T, S) => Boolean): QueryStream[DynamicCompositeRecord[T, S]] = {
    var elem1: Option[T] = None
    var elem2: Option[S] = None
    var atEnd: Boolean = false
    def proceedLeft(): Unit = {
      elem1 = underlying.stream()
      atEnd ||= elem1 == NULL
    }
    def proceedRight(): Unit = {
      elem2 = q2.stream()
      atEnd ||= elem2 == NULL
    }
    underlying.unstream { () =>
      if (atEnd) {
        NULL
      } else {
        var leftShouldProceed: Boolean = false
        var nextJoinElem: Option[DynamicCompositeRecord[T, S]] = None
        elem1 match {
          case Some(ne1) =>
            elem2 match {
              case Some(ne2) =>
                val cmp = ord(ne1, ne2)
                if (cmp < 0) {
                  leftShouldProceed = true
                } else {
                  if (cmp == 0) {
                    nextJoinElem = Some(ne1.concatenateDynamic(ne2))
                  }
                }
              case None =>
            }
          case None =>
            leftShouldProceed = true
        }
        if (leftShouldProceed) {
          proceedLeft()
          nextJoinElem
        } else {
          proceedRight()
          nextJoinElem
        }
      }
    }
  }

  def leftHashSemiJoin[S <: Record, R](q2: QueryStream[S])(leftHash: T => R)(rightHash: S => R)(
    joinCond: (T, S) => Boolean): QueryStream[T] = {
    val hm = MultiMap[R, S]
    for (elem <- q2) {
      hm.addBinding(rightHash(elem), elem)
    }
    val leftIterator = underlying.filter(t => {
      val k = leftHash(t)
      hm.get(k).exists(buf =>
        buf.exists(e => joinCond(t, e)))
    })

    underlying.unstream { () =>
      leftIterator.stream()
    }
  }
}

class GroupedQueryStream[K, V](underlying: QueryStream[V], par: V => K) {
  def getGroupByResult: GroupByResult[K, V] = {
    val max_partitions = 50
    // Q3
    // val max_partitions = 150000
    // Q9
    // val max_partitions = 25 * 7
    val MAX_SIZE = max_partitions
    val keyIndex = new HashMap[K, Int]()
    val keyRevertIndex = new Array[Any](MAX_SIZE).asInstanceOf[Array[K]]
    var lastIndex = 0
    val array = new Array[Array[Any]](MAX_SIZE).asInstanceOf[Array[Array[V]]]
    val eachBucketSize = new Array[Int](MAX_SIZE)
    // val thisSize = 1 << 25
    val thisSize = 1 << 22
    val arraySize = thisSize / MAX_SIZE * 8
    Range(0, MAX_SIZE).foreach { i =>
      // val arraySize = originalArray.length
      // val arraySize = 128
      array(i) = new Array[Any](arraySize).asInstanceOf[Array[V]] // discovered a funny scalac bug!
      eachBucketSize(i) = 0
    }
    GroupByResult(array, keyRevertIndex, eachBucketSize, MAX_SIZE, keyIndex)
    // ???
  }

  @pure def mapValues[S](func: QueryStream[V] => S): QueryStream[(K, S)] = {

    val (groupByResult, partitions) = {
      val groupByResult = getGroupByResult
      val GroupByResult(array, keyRevertIndex, eachBucketSize, _, keyIndex) =
        groupByResult
      var lastIndex = 0

      for (elem <- underlying) {
        val key = par(elem)
        val bucket = keyIndex.getOrElseUpdate(key, {
          keyRevertIndex(lastIndex) = key
          lastIndex = lastIndex + 1
          lastIndex - 1
        })
        array(bucket)(eachBucketSize(bucket)) = elem
        eachBucketSize(bucket) += 1
      }
      (groupByResult, lastIndex)
    }

    var index: Int = 0

    underlying.unstream { () =>
      if (index >= partitions) {
        NULL
      } else {
        val GroupByResult(array, keyRevertIndex, eachBucketSize, _, _) =
          groupByResult
        val i = index
        index += 1
        val arr = array(i).dropRight(array(i).length - eachBucketSize(i))
        val key = keyRevertIndex(i)
        val newValue = func(QueryStream(arr))
        Some(key -> newValue)
      }
    }
  }
}