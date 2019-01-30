/*
 * Scala (https://www.scala-lang.org)
 *
 * Copyright EPFL and Lightbend, Inc.
 *
 * Licensed under Apache License 2.0
 * (http://www.apache.org/licenses/LICENSE-2.0).
 *
 * See the NOTICE file distributed with this work for
 * additional information regarding copyright ownership.
 */

package scala
package collection
package immutable


import scala.collection.immutable.Set.Set4
import scala.collection.mutable.{Builder, ReusableBuilder, ImmutableBuilder}
import scala.language.higherKinds


/** Base trait for immutable set collections */
trait Set[A] extends Iterable[A] with collection.Set[A] with SetOps[A, Set, Set[A]] {
  override def iterableFactory: IterableFactory[IterableCC] = Set
}

/** Base trait for immutable set operations
  *
  * @define coll immutable set
  * @define Coll `immutable.Set`
  */
trait SetOps[A, +CC[X], +C <: SetOps[A, CC, C]]
  extends collection.SetOps[A, CC, C] {

  /** Creates a new set with an additional element, unless the element is
    *  already present.
    *
    *  @param elem the element to be added
    *  @return a new set that contains all elements of this set and that also
    *          contains `elem`.
    */
  def incl(elem: A): C

  /** Alias for `incl` */
  @deprecatedOverriding("This method should be final, but is not due to scala/bug#10853", "2.13.0")
  override /*final*/ def + (elem: A): C = incl(elem) // like in collection.Set but not deprecated

  /** Creates a new set with a given element removed from this set.
    *
    *  @param elem the element to be removed
    *  @return a new set that contains all elements of this set but that does not
    *          contain `elem`.
    */
  def excl(elem: A): C

  /** Alias for `excl` */
  @deprecatedOverriding("This method should be final, but is not due to scala/bug#10853", "2.13.0")
  /*@`inline` final*/ override def - (elem: A): C = excl(elem)

  def diff(that: collection.Set[A]): C =
    toIterable.foldLeft(empty)((result, elem) => if (that contains elem) result else result + elem)

  /** Creates a new $coll from this $coll by removing all elements of another
    *  collection.
    *
    *  @param that the collection containing the elements to remove.
    *  @return a new $coll with the given elements removed, omitting duplicates.
    */
  def removedAll(that: IterableOnce[A]): C = that.iterator.foldLeft[C](coll)(_ - _)

  /** Alias for removeAll */
  @deprecatedOverriding("This method should be final, but is not due to scala/bug#10853", "2.13.0")
  override /*final*/ def -- (that: IterableOnce[A]): C = removedAll(that)
}

trait StrictOptimizedSetOps[A, +CC[X], +C <: SetOps[A, CC, C]]
  extends SetOps[A, CC, C]
    with collection.StrictOptimizedSetOps[A, CC, C]
    with StrictOptimizedIterableOps[A, CC, C] {

  override def concat(that: collection.IterableOnce[A]): C = {
    var result: C = coll
    val it = that.iterator
    while (it.hasNext) result = result + it.next()
    result
  }
}

/**
  * $factoryInfo
  * @define coll immutable set
  * @define Coll `immutable.Set`
  */
@SerialVersionUID(3L)
object Set extends IterableFactory[Set] {

  def empty[A]: Set[A] = EmptySet.asInstanceOf[Set[A]]

  def from[E](it: collection.IterableOnce[E]): Set[E] =
    it match {
      // We want `SortedSet` (and subclasses, such as `BitSet`) to
      // rebuild themselves to avoid element type widening issues
      case _: SortedSet[E]         => (newBuilder[E] ++= it).result()
      case _ if it.knownSize == 0  => empty[E]
      case s: Set[E]               => s
      case _                       => (newBuilder[E] ++= it).result()
    }

  def newBuilder[A]: Builder[A, Set[A]] = new SetBuilderImpl[A]

  /** An optimized representation for immutable empty sets */
  private object EmptySet extends AbstractSet[Any] {
    override def size: Int = 0
    override def isEmpty = true
    override def knownSize: Int = size
    def contains(elem: Any): Boolean = false
    def incl(elem: Any): Set[Any] = new Set1(elem)
    def excl(elem: Any): Set[Any] = this
    def iterator: Iterator[Any] = Iterator.empty
    override def foreach[U](f: Any => U): Unit = ()
  }
  private[collection] def emptyInstance: Set[Any] = EmptySet

  /** An optimized representation for immutable sets of size 1 */
  final class Set1[A] private[collection] (elem1: A) extends AbstractSet[A] with StrictOptimizedIterableOps[A, Set, Set[A]] {
    override def size: Int = 1
    override def isEmpty = false
    override def knownSize: Int = size
    def contains(elem: A): Boolean = elem == elem1
    def incl(elem: A): Set[A] =
      if (contains(elem)) this
      else new Set2(elem1, elem)
    def excl(elem: A): Set[A] =
      if (elem == elem1) Set.empty
      else this
    def iterator: Iterator[A] = Iterator.single(elem1)
    override def foreach[U](f: A => U): Unit = f(elem1)
    override def exists(p: A => Boolean): Boolean = p(elem1)
    override def forall(p: A => Boolean): Boolean = p(elem1)
    override def find(p: A => Boolean): Option[A] =
      if (p(elem1)) Some(elem1)
      else None
    override def head: A = elem1
    override def tail: Set[A] = Set.empty
  }

  /** An optimized representation for immutable sets of size 2 */
  final class Set2[A] private[collection] (elem1: A, elem2: A) extends AbstractSet[A] with StrictOptimizedIterableOps[A, Set, Set[A]] {
    override def size: Int = 2
    override def isEmpty = false
    override def knownSize: Int = size
    def contains(elem: A): Boolean = elem == elem1 || elem == elem2
    def incl(elem: A): Set[A] =
      if (contains(elem)) this
      else new Set3(elem1, elem2, elem)
    def excl(elem: A): Set[A] =
      if (elem == elem1) new Set1(elem2)
      else if (elem == elem2) new Set1(elem1)
      else this
    def iterator: Iterator[A] = (elem1 :: elem2 :: Nil).iterator
    override def foreach[U](f: A => U): Unit = {
      f(elem1); f(elem2)
    }
    override def exists(p: A => Boolean): Boolean = {
      p(elem1) || p(elem2)
    }
    override def forall(p: A => Boolean): Boolean = {
      p(elem1) && p(elem2)
    }
    override def find(p: A => Boolean): Option[A] = {
      if (p(elem1)) Some(elem1)
      else if (p(elem2)) Some(elem2)
      else None
    }
    override def head: A = elem1
    override def tail: Set[A] = new Set1(elem2)
  }

  /** An optimized representation for immutable sets of size 3 */
  final class Set3[A] private[collection] (elem1: A, elem2: A, elem3: A) extends AbstractSet[A] with StrictOptimizedIterableOps[A, Set, Set[A]] {
    override def size: Int = 3
    override def isEmpty = false
    override def knownSize: Int = size
    def contains(elem: A): Boolean =
      elem == elem1 || elem == elem2 || elem == elem3
    def incl(elem: A): Set[A] =
      if (contains(elem)) this
      else new Set4(elem1, elem2, elem3, elem)
    def excl(elem: A): Set[A] =
      if (elem == elem1) new Set2(elem2, elem3)
      else if (elem == elem2) new Set2(elem1, elem3)
      else if (elem == elem3) new Set2(elem1, elem2)
      else this
    def iterator: Iterator[A] = (elem1 :: elem2 :: elem3 :: Nil).iterator
    override def foreach[U](f: A => U): Unit = {
      f(elem1); f(elem2); f(elem3)
    }
    override def exists(p: A => Boolean): Boolean = {
      p(elem1) || p(elem2) || p(elem3)
    }
    override def forall(p: A => Boolean): Boolean = {
      p(elem1) && p(elem2) && p(elem3)
    }
    override def find(p: A => Boolean): Option[A] = {
      if (p(elem1)) Some(elem1)
      else if (p(elem2)) Some(elem2)
      else if (p(elem3)) Some(elem3)
      else None
    }
    override def head: A = elem1
    override def tail: Set[A] = new Set2(elem2, elem3)
  }

  /** An optimized representation for immutable sets of size 4 */
  final class Set4[A] private[collection] (elem1: A, elem2: A, elem3: A, elem4: A) extends AbstractSet[A] with StrictOptimizedIterableOps[A, Set, Set[A]] {
    override def size: Int = 4
    override def isEmpty = false
    override def knownSize: Int = size
    def contains(elem: A): Boolean =
      elem == elem1 || elem == elem2 || elem == elem3 || elem == elem4
    def incl(elem: A): Set[A] =
      if (contains(elem)) this
      else HashSet.empty[A] + elem1 + elem2 + elem3 + elem4 + elem
    def excl(elem: A): Set[A] =
      if (elem == elem1) new Set3(elem2, elem3, elem4)
      else if (elem == elem2) new Set3(elem1, elem3, elem4)
      else if (elem == elem3) new Set3(elem1, elem2, elem4)
      else if (elem == elem4) new Set3(elem1, elem2, elem3)
      else this
    def iterator: Iterator[A] = (elem1 :: elem2 :: elem3 :: elem4 :: Nil).iterator
    override def foreach[U](f: A => U): Unit = {
      f(elem1); f(elem2); f(elem3); f(elem4)
    }
    override def exists(p: A => Boolean): Boolean = {
      p(elem1) || p(elem2) || p(elem3) || p(elem4)
    }
    override def forall(p: A => Boolean): Boolean = {
      p(elem1) && p(elem2) && p(elem3) && p(elem4)
    }
    override def find(p: A => Boolean): Option[A] = {
      if (p(elem1)) Some(elem1)
      else if (p(elem2)) Some(elem2)
      else if (p(elem3)) Some(elem3)
      else if (p(elem4)) Some(elem4)
      else None
    }
    override def head: A = elem1
    override def tail: Set[A] = new Set3(elem2, elem3, elem4)

    private[immutable] def buildTo(builder: Builder[A, Set[A]]): builder.type =
      builder.addOne(elem1).addOne(elem2).addOne(elem3).addOne(elem4)
  }
}

/** Explicit instantiation of the `Set` trait to reduce class file size in subclasses. */
@SerialVersionUID(3L)
abstract class AbstractSet[A] extends scala.collection.AbstractSet[A] with Set[A]


/** Builder for Set.
  * $multipleResults
  */
private final class SetBuilderImpl[A] extends ReusableBuilder[A, Set[A]] {
  private[this] var elems: Set[A] = Set.empty
  private[this] var switchedToHashSetBuilder: Boolean = false
  private[this] var hashSetBuilder: HashSetBuilder[A] = _

  override def clear(): Unit = {
    elems = Set.empty
    if (hashSetBuilder != null) {
      hashSetBuilder.clear()
    }
    switchedToHashSetBuilder = false
  }

  override def result(): Set[A] =
    if (switchedToHashSetBuilder) hashSetBuilder.result() else elems

  def addOne(elem: A) = {
    if (switchedToHashSetBuilder) {
      hashSetBuilder.addOne(elem)
    } else if (elems.size < 4) {
      elems = elems + elem
    } else {
      // assert(elems.size == 4)
      if (elems.contains(elem)) {
        () // do nothing
      } else {
        switchedToHashSetBuilder = true
        if (hashSetBuilder == null) {
          hashSetBuilder = new HashSetBuilder
        }
        elems.asInstanceOf[Set4[A]].buildTo(hashSetBuilder)
        hashSetBuilder.addOne(elem)
      }
    }

    this
  }

  override def addAll(xs: IterableOnce[A]): this.type =
    if (switchedToHashSetBuilder) {
      hashSetBuilder.addAll(xs)
      this
    } else {
      super.addAll(xs)
    }
}
