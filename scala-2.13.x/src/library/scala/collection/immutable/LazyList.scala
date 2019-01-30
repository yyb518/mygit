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

import java.io.{ObjectInputStream, ObjectOutputStream}
import java.lang.{StringBuilder => JStringBuilder}

import scala.annotation.tailrec
import scala.collection.generic.SerializeEnd
import scala.collection.mutable.{ArrayBuffer, Builder, StringBuilder}
import scala.language.implicitConversions

/**  The class `LazyList` implements lazy lists where elements
  *  are only evaluated when they are needed. Here is an example:
  *
  *  {{{
  *  import scala.math.BigInt
  *  object Main extends App {
  *
  *    lazy val fibs: LazyList[BigInt] = BigInt(0) #:: BigInt(1) #:: fibs.zip(fibs.tail).map { n => n._1 + n._2 }
  *
  *    fibs take 5 foreach println
  *  }
  *
  *  // prints
  *  //
  *  // 0
  *  // 1
  *  // 1
  *  // 2
  *  // 3
  *  }}}
  *
  *  The `LazyList` class also employs memoization such that previously computed
  *  values are converted from `LazyList` elements to concrete values of type `A`.
  *  To illustrate, we will alter body of the `fibs` value above and take some
  *  more values:
  *
  *  {{{
  *  import scala.math.BigInt
  *  object Main extends App {
  *
  *    lazy val fibs: LazyList[BigInt] = BigInt(0) #:: BigInt(1) #:: fibs.zip(
  *      fibs.tail).map(n => {
  *        println("Adding %d and %d".format(n._1, n._2))
  *        n._1 + n._2
  *      })
  *
  *    fibs take 5 foreach println
  *    fibs take 6 foreach println
  *  }
  *
  *  // prints
  *  //
  *  // 0
  *  // 1
  *  // Adding 0 and 1
  *  // 1
  *  // Adding 1 and 1
  *  // 2
  *  // Adding 1 and 2
  *  // 3
  *
  *  // And then prints
  *  //
  *  // 0
  *  // 1
  *  // 1
  *  // 2
  *  // 3
  *  // Adding 2 and 3
  *  // 5
  *  }}}
  *
  *  There are a number of subtle points to the above example.
  *
  *  - The definition of `fibs` is a `val` not a method.  The memoization of the
  *  `LazyList` requires us to have somewhere to store the information and a `val`
  *  allows us to do that.
  *
  *  - While the `LazyList` is actually being modified during access, this does not
  *  change the notion of its immutability.  Once the values are memoized they do
  *  not change and values that have yet to be memoized still "exist", they
  *  simply haven't been realized yet.
  *
  *  - One must be cautious of memoization; you can very quickly eat up large
  *  amounts of memory if you're not careful.  The reason for this is that the
  *  memoization of the `LazyList` creates a structure much like
  *  [[scala.collection.immutable.List]].  So long as something is holding on to
  *  the head, the head holds on to the tail, and so it continues recursively.
  *  If, on the other hand, there is nothing holding on to the head (e.g. we used
  *  `def` to define the `LazyList`) then once it is no longer being used directly,
  *  it disappears.
  *
  *  - Note that some operations, including [[drop]], [[dropWhile]],
  *  [[flatMap]] or [[collect]] may process a large number of intermediate
  *  elements before returning.  These necessarily hold onto the head, since
  *  they are methods on `LazyList`, and a lazy list holds its own head. For
  *  computations of this sort where memoization is not desired, use
  *  `Iterator` when possible.
  *
  *  {{{
  *  // For example, let's build the natural numbers and do some silly iteration
  *  // over them.
  *
  *  // We'll start with a silly iteration
  *  def loop(s: String, i: Int, iter: Iterator[Int]): Unit = {
  *    // Stop after 200,000
  *    if (i < 200001) {
  *      if (i % 50000 == 0) println(s + i)
  *      loop(s, iter.next(), iter)
  *    }
  *  }
  *
  *  // Our first LazyList definition will be a val definition
  *  val lazylist1: LazyList[Int] = {
  *    def loop(v: Int): LazyList[Int] = v #:: loop(v + 1)
  *    loop(0)
  *  }
  *
  *  // Because lazylist1 is a val, everything that the iterator produces is held
  *  // by virtue of the fact that the head of the LazyList is held in lazylist1
  *  val it1 = lazylist1.iterator
  *  loop("Iterator1: ", it1.next(), it1)
  *
  *  // We can redefine this LazyList such that all we have is the Iterator left
  *  // and allow the LazyList to be garbage collected as required.  Using a def
  *  // to provide the LazyList ensures that no val is holding onto the head as
  *  // is the case with lazylist1
  *  def lazylist2: LazyList[Int] = {
  *    def loop(v: Int): LazyList[Int] = v #:: loop(v + 1)
  *    loop(0)
  *  }
  *  val it2 = lazylist2.iterator
  *  loop("Iterator2: ", it2.next(), it2)
  *
  *  // And, of course, we don't actually need a LazyList at all for such a simple
  *  // problem.  There's no reason to use a LazyList if you don't actually need
  *  // one.
  *  val it3 = new Iterator[Int] {
  *    var i = -1
  *    def hasNext = true
  *    def next(): Int = { i += 1; i }
  *  }
  *  loop("Iterator3: ", it3.next(), it3)
  *  }}}
  *
  *  - The fact that `tail` works at all is of interest.  In the definition of
  *  `fibs` we have an initial `(0, 1, LazyList(...))` so `tail` is deterministic.
  *  If we defined `fibs` such that only `0` were concretely known then the act
  *  of determining `tail` would require the evaluation of `tail` which would
  *  cause an infinite recursion and stack overflow.  If we define a definition
  *  where the tail is not initially computable then we're going to have an
  *  infinite recursion:
  *  {{{
  *  // The first time we try to access the tail we're going to need more
  *  // information which will require us to recurse, which will require us to
  *  // recurse, which...
  *  lazy val sov: LazyList[Vector[Int]] = Vector(0) #:: sov.zip(sov.tail).map { n => n._1 ++ n._2 }
  *  }}}
  *
  *  The definition of `fibs` above creates a larger number of objects than
  *  necessary depending on how you might want to implement it.  The following
  *  implementation provides a more "cost effective" implementation due to the
  *  fact that it has a more direct route to the numbers themselves:
  *
  *  {{{
  *  lazy val fib: LazyList[Int] = {
  *    def loop(h: Int, n: Int): LazyList[Int] = h #:: loop(n, h + n)
  *    loop(1, 1)
  *  }
  *  }}}
  *
  *  @tparam A    the type of the elements contained in this lazy list.
  *
  *  @author Martin Odersky, Matthias Zenger
  *  @since   2.13
  *  @see [[http://docs.scala-lang.org/overviews/collections/concrete-immutable-collection-classes.html#lazylists "Scala's Collection Library overview"]]
  *  section on `LazyLists` for more information.

  *  @define Coll `LazyList`
  *  @define coll lazy list
  *  @define orderDependent
  *  @define orderDependentFold
  */
@SerialVersionUID(3L)
final class LazyList[+A] private(private[this] var lazyState: () => LazyList.State[A])
  extends AbstractSeq[A] with LinearSeq[A] with LinearSeqOps[A, LazyList, LazyList[A]] {
  import LazyList._

  @volatile private[this] var stateEvaluated: Boolean = false
  @inline private def stateDefined: Boolean = stateEvaluated

  private lazy val state: State[A] = {
    val res = lazyState()
    // if we set it to `true` before evaluating, we may infinite loop
    // if something expects `state` to already be evaluated
    stateEvaluated = true
    lazyState = null // allow GC
    res
  }

  override def iterableFactory: SeqFactory[LazyList] = LazyList

  override def isEmpty: Boolean = state eq State.Empty

  override def knownSize: Int = if (knownIsEmpty) 0 else -1

  override def head: A = state.head

  override def tail: LazyList[A] = state.tail

  @inline private[this] def knownIsEmpty: Boolean = stateEvaluated && (isEmpty: @inline)
  @inline private def knownNonEmpty: Boolean = stateEvaluated && !(isEmpty: @inline)

  def force: this.type = {
    // Use standard 2x 1x iterator trick for cycle detection ("those" is slow one)
    var these, those: LazyList[A] = this
    if (these.nonEmpty) {
      these.head
      these = these.tail
    }
    while (those ne these) {
      if (these.isEmpty) return this
      these.head
      these = these.tail
      if (these.isEmpty) return this
      these.head
      these = these.tail
      if (these eq those) return this
      those = those.tail
    }
    this
  }

  override def iterator: Iterator[A] =
    if (knownIsEmpty) Iterator.empty
    else new LazyIterator(this)

  /** Apply the given function `f` to each element of this linear sequence
    * (while respecting the order of the elements).
    *
    *  @param f The treatment to apply to each element.
    *  @note  Overridden here as final to trigger tail-call optimization, which
    *  replaces 'this' with 'tail' at each iteration. This is absolutely
    *  necessary for allowing the GC to collect the underlying LazyList as elements
    *  are consumed.
    *  @note  This function will force the realization of the entire LazyList
    *  unless the `f` throws an exception.
    */
  @tailrec
  override def foreach[U](f: A => U): Unit = {
    if (nonEmpty) {
      f(head)
      tail.foreach(f)
    }
  }

  /** LazyList specialization of foldLeft which allows GC to collect along the
    * way.
    *
    * @tparam B The type of value being accumulated.
    * @param z The initial value seeded into the function `op`.
    * @param op The operation to perform on successive elements of the `LazyList`.
    * @return The accumulated value from successive applications of `op`.
    */
  @tailrec
  override def foldLeft[B](z: B)(op: (B, A) => B): B =
    if (isEmpty) z
    else tail.foldLeft(op(z, head))(op)

  // State.Empty doesn't use the SerializationProxy
  override protected[this] def writeReplace(): AnyRef =
    if (knownNonEmpty) new LazyList.SerializationProxy[A](this) else this

  override protected[this] def className = "LazyList"

  /** The lazy list resulting from the concatenation of this lazy list with the argument lazy list.
    *
    * @param suffix The collection that gets appended to this lazy list
    * @return The lazy list containing elements of this lazy list and the iterable object.
    */
  def lazyAppendedAll[B >: A](suffix: => collection.IterableOnce[B]): LazyList[B] =
    newLL {
      if (isEmpty) suffix match {
        case lazyList: LazyList[B]       => lazyList.state // don't recompute the LazyList
        case coll if coll.knownSize == 0 => State.Empty
        case _                           => stateFromIterator(suffix.iterator)
      }
      else sCons(head, tail lazyAppendedAll suffix)
    }

  override def appendedAll[B >: A](suffix: IterableOnce[B]): LazyList[B] =
    if (knownIsEmpty) LazyList.from(suffix)
    else lazyAppendedAll(suffix)

  override def appended[B >: A](elem: B): LazyList[B] =
    if (knownIsEmpty) newLL(sCons(elem, LazyList.empty))
    else lazyAppendedAll(Iterator.single(elem))

  override def equals(that: Any): Boolean =
    if (this eq that.asInstanceOf[AnyRef]) true else super.equals(that)

  override def scanLeft[B](z: B)(op: (B, A) => B): LazyList[B] =
    if (knownIsEmpty) newLL(sCons(z, LazyList.empty))
    else newLL(scanLeftState(z)(op))

  private def scanLeftState[B](z: B)(op: (B, A) => B): State[B] =
    sCons(
      z,
      newLL {
        if (isEmpty) State.Empty
        else tail.scanLeftState(op(z, head))(op)
      }
    )

  /** LazyList specialization of reduceLeft which allows GC to collect
    *  along the way.
    *
    * @tparam B The type of value being accumulated.
    * @param f The operation to perform on successive elements of the `LazyList`.
    * @return The accumulated value from successive applications of `f`.
    */
  override def reduceLeft[B >: A](f: (B, A) => B): B = {
    if (this.isEmpty) throw new UnsupportedOperationException("empty.reduceLeft")
    else {
      var reducedRes: B = this.head
      var left: LazyList[A] = this.tail
      while (left.nonEmpty) {
        reducedRes = f(reducedRes, left.head)
        left = left.tail
      }
      reducedRes
    }
  }

  override def partition(p: A => Boolean): (LazyList[A], LazyList[A]) = (filter(p), filterNot(p))

  override def partitionMap[A1, A2](f: A => Either[A1, A2]): (LazyList[A1], LazyList[A2]) = {
    val (left, right) = map(f).partition(_.isLeft)
    (left.map(_.asInstanceOf[Left[A1, _]].value), right.map(_.asInstanceOf[Right[_, A2]].value))
  }

  override def filter(pred: A => Boolean): LazyList[A] =
    if (knownIsEmpty) LazyList.empty
    else filterTrampoline(pred, isFlipped = false)

  override def filterNot(pred: A => Boolean): LazyList[A] =
    if (knownIsEmpty) LazyList.empty
    else filterTrampoline(pred, isFlipped = true)

  // trampoline to allow for tail-recursive `filterState`
  @inline private def filterTrampoline(p: A => Boolean, isFlipped: Boolean): LazyList[A] =
    newLL(filterState(p, isFlipped))

  @tailrec
  private def filterState(p: A => Boolean, isFlipped: Boolean): State[A] = {
    if (isEmpty) State.Empty
    else {
      val elem = head
      if (p(elem) == isFlipped) tail.filterState(p, isFlipped)
      else sCons(elem, tail.filterTrampoline(p, isFlipped))
    }
  }

  /** A `collection.WithFilter` which allows GC of the head of lazy list during processing */
  override def withFilter(p: A => Boolean): collection.WithFilter[A, LazyList] =
    new LazyList.WithFilter(coll, p)

  override def prepended[B >: A](elem: B): LazyList[B] = newLL(sCons(elem, this))

  override def prependedAll[B >: A](prefix: collection.IterableOnce[B]): LazyList[B] =
    if (knownIsEmpty) LazyList.from(prefix)
    else if (prefix.knownSize == 0) this
    else newLL(stateFromIteratorConcatSuffix(prefix.iterator)(state))

  override def map[B](f: A => B): LazyList[B] =
    if (knownIsEmpty) LazyList.empty
    else (mapImpl(f): @inline)

  override def tapEach[U](f: A => U): LazyList[A] = map { a => f(a); a }

  private def mapImpl[B](f: A => B): LazyList[B] =
    newLL {
      if (isEmpty) State.Empty
      else sCons(f(head), tail.mapImpl(f))
    }

  override def collect[B](pf: PartialFunction[A, B]): LazyList[B] =
    if (knownIsEmpty) LazyList.empty
    else collectTrampoline(pf.lift)

  // trampoline to allow for tail-recursive `collectState`
  @inline private def collectTrampoline[B](lifted: A => Option[B]): LazyList[B] =
    newLL(collectState(lifted))

  @tailrec
  private def collectState[B](lifted: A => Option[B]): State[B] =
    if (isEmpty) State.Empty
    else lifted(head) match {
      case Some(elem) => sCons(elem, tail.collectTrampoline(lifted))
      case None       => tail.collectState(lifted)
    }

  // optimisations are not for speed, but for functionality
  // see tickets #153, #498, #2147, and corresponding tests in run/ (as well as run/stream_flatmap_odds.scala)
  override def flatMap[B](f: A => IterableOnce[B]): LazyList[B] =
    if (knownIsEmpty) LazyList.empty
    else newLL(flatMapState(f))

  // trampoline to allow for tail-recursive `flatMapState`
  @inline private def flatMapTrampoline[B](f: A => IterableOnce[B]): State[B] = flatMapState(f)

  @tailrec
  private def flatMapState[B](f: A => IterableOnce[B]): State[B] =
    if (isEmpty) State.Empty
    else {
      val it = f(head).iterator
      if (!it.hasNext) tail.flatMapState(f) // don't blow the stack
      else stateFromIteratorConcatSuffix(it)(tail.flatMapTrampoline(f))
    }

  override def flatten[B](implicit asIterable: A => IterableOnce[B]): LazyList[B] = flatMap(asIterable)

  override def zip[B](that: collection.IterableOnce[B]): LazyList[(A, B)] =
    if (this.knownIsEmpty || that.knownSize == 0) LazyList.empty
    else newLL(zipState(that.iterator))

  private def zipState[B](it: Iterator[B]): State[(A, B)] =
    if (this.isEmpty || !it.hasNext) State.Empty
    else sCons((head, it.next()), newLL { tail zipState it })

  override def zipWithIndex: LazyList[(A, Int)] = this zip LazyList.from(0)

  override def zipAll[A1 >: A, B](that: collection.Iterable[B], thisElem: A1, thatElem: B): LazyList[(A1, B)] = {
    if (this.knownIsEmpty) {
      if (that.knownSize == 0) LazyList.empty
      else LazyList.continually(thisElem) zip that
    } else {
      if (that.knownSize == 0) zip(LazyList.continually(thatElem))
      else newLL(zipAllState(that.iterator, thisElem, thatElem))
    }
  }

  private def zipAllState[A1 >: A, B](it: Iterator[B], thisElem: A1, thatElem: B): State[(A1, B)] = {
    if (it.hasNext) {
      if (this.isEmpty) sCons((thisElem, it.next()), newLL { LazyList.continually(thisElem) zipState it })
      else sCons((this.head, it.next()), newLL { this.tail.zipAllState(it, thisElem, thatElem) })
    } else {
      if (this.isEmpty) State.Empty
      else sCons((this.head, thatElem), this.tail zip LazyList.continually(thatElem))
    }
  }

  // just in case it can be meaningfully overridden at some point
  override def lazyZip[B](that: collection.Iterable[B]): LazyZip2[A, B, LazyList.this.type] =
    super.lazyZip(that)

  override def unzip[A1, A2](implicit asPair: A => (A1, A2)): (LazyList[A1], LazyList[A2]) =
    (map(asPair(_)._1), map(asPair(_)._2))

  override def unzip3[A1, A2, A3](implicit asTriple: A => (A1, A2, A3)): (LazyList[A1], LazyList[A2], LazyList[A3]) =
    (map(asTriple(_)._1), map(asTriple(_)._2), map(asTriple(_)._3))

  override def drop(n: Int): LazyList[A] =
    if (n <= 0) this
    else if (knownIsEmpty) LazyList.empty
    else newLL(dropState(n))

  @tailrec
  private def dropState(n: Int): State[A] =
    if (n <= 0) state
    else if (isEmpty) State.Empty
    else tail.dropState(n - 1)

  override def dropWhile(p: A => Boolean): LazyList[A] =
    if (knownIsEmpty) LazyList.empty
    else newLL(dropWhileState(p))

  @tailrec
  private def dropWhileState(p: A => Boolean): State[A] =
    if (isEmpty) State.Empty
    else if (p(head)) tail.dropWhileState(p)
    else state

  override def dropRight(n: Int): LazyList[A] = {
    if (n <= 0) this
    else if (knownIsEmpty) LazyList.empty
    else newLL {
      var scout = this
      var remaining = n
      // advance scout n elements ahead (or until empty)
      while (remaining > 0 && scout.nonEmpty) {
        remaining -= 1
        scout = scout.tail
      }
      dropRightState(scout)
    }
  }

  private def dropRightState(scout: LazyList[_]): State[A] =
    if (scout.isEmpty) State.Empty
    else sCons(head, newLL(tail.dropRightState(scout.tail)))

  override def take(n: Int): LazyList[A] =
    if (knownIsEmpty) LazyList.empty
    else (takeImpl(n): @inline)

  private def takeImpl(n: Int): LazyList[A] = {
    if (n <= 0) LazyList.empty
    else newLL {
      if (isEmpty) State.Empty
      else sCons(head, tail.takeImpl(n - 1))
    }
  }

  override def takeWhile(p: A => Boolean): LazyList[A] =
    if (knownIsEmpty) LazyList.empty
    else (takeWhileImpl(p): @inline)

  private def takeWhileImpl(p: A => Boolean): LazyList[A] =
    newLL {
      if (isEmpty || !p(head)) State.Empty
      else sCons(head, tail.takeWhileImpl(p))
    }

  override def takeRight(n: Int): LazyList[A] =
    if (n <= 0 || knownIsEmpty) LazyList.empty
    else newLL {
      var scout = this
      var remaining = n
      // advance scout n elements ahead (or until empty)
      while (remaining > 0 && scout.nonEmpty) {
        remaining -= 1
        scout = scout.tail
      }
      takeRightState(scout)
    }

  @tailrec
  private def takeRightState(scout: LazyList[_]): State[A] =
    if (scout.isEmpty) state
    else tail.takeRightState(scout.tail)

  override def slice(from: Int, until: Int): LazyList[A] = take(until).drop(from)

  override def reverse: LazyList[A] = reverseOnto(LazyList.empty)

  // need contravariant type B to make the compiler happy - still returns LazyList[A]
  @tailrec
  private def reverseOnto[B >: A](tl: LazyList[B]): LazyList[B] =
    if (isEmpty) tl
    else tail.reverseOnto(newLL(sCons(head, tl)))

  override def diff[B >: A](that: collection.Seq[B]): LazyList[A] =
    if (knownIsEmpty) LazyList.empty
    else super.diff(that)

  override def intersect[B >: A](that: collection.Seq[B]): LazyList[A] =
    if (knownIsEmpty) LazyList.empty
    else super.intersect(that)

  // TODO: override to detect cycles
  override def distinctBy[B](f: A => B): LazyList[A] = super.distinctBy(f)

  override def grouped(size: Int): Iterator[LazyList[A]] = {
    require(size > 0, "size must be positive, but was " + size)
    lazySlidingImpl(size, size)
  }

  override def sliding(size: Int, step: Int): Iterator[LazyList[A]] = {
    require(size > 0 && step > 0, s"size=$size and step=$step, but both must be positive")
    lazySlidingImpl(size, step)
  }

  @inline private def lazySlidingImpl(size: Int, step: Int): Iterator[LazyList[A]] =
    if (knownIsEmpty) Iterator.empty
    else Iterator.empty ++ slidingImpl(size, step) // concat with empty iterator so that `slidingImpl` is lazy

  private def slidingImpl(size: Int, step: Int): Iterator[LazyList[A]] =
    if (isEmpty) Iterator.empty
    else Iterator.single(take(size)) ++ drop(step).slidingImpl(size, step)

  override def padTo[B >: A](len: Int, elem: B): LazyList[B] = {
    if (len <= 0) this
    else newLL {
      if (isEmpty) LazyList.fill(len)(elem).state
      else sCons(head, tail.padTo(len - 1, elem))
    }
  }

  override def patch[B >: A](from: Int, other: IterableOnce[B], replaced: Int): LazyList[B] =
    if (knownIsEmpty) LazyList from other
    else patchImpl(from, other, replaced)

  private def patchImpl[B >: A](from: Int, other: IterableOnce[B], replaced: Int): LazyList[B] =
    newLL {
      if (from <= 0) stateFromIteratorConcatSuffix(other.iterator)(dropState(replaced))
      else if (isEmpty) stateFromIterator(other.iterator)
      else sCons(head, tail.patchImpl(from - 1, other, replaced))
    }

  // overridden just in case a lazy implementation is developed at some point
  override def transpose[B](implicit asIterable: A => collection.Iterable[B]): LazyList[LazyList[B]] = super.transpose

  override def updated[B >: A](index: Int, elem: B): LazyList[B] =
    if (index < 0) throw new IndexOutOfBoundsException(s"$index")
    else updatedImpl(index, elem, index)

  private def updatedImpl[B >: A](index: Int, elem: B, startIndex: Int): LazyList[B] = {
    newLL {
      if (index <= 0) sCons(elem, tail)
      else if (tail.isEmpty) throw new IndexOutOfBoundsException(startIndex.toString)
      else sCons(head, tail.updatedImpl(index - 1, elem, startIndex))
    }
  }

  /** Appends all elements of this $coll to a string builder using start, end, and separator strings.
    *  The written text begins with the string `start` and ends with the string `end`.
    *  Inside, the string representations (w.r.t. the method `toString`)
    *  of all elements of this $coll are separated by the string `sep`.
    *
    * Undefined elements are represented with `"_"`, an undefined tail is represented with `"?"`,
    * and cycles are represented with `"..."`.
    *
    *  @param sb    the string builder to which elements are appended.
    *  @param start the starting string.
    *  @param sep   the separator string.
    *  @param end   the ending string.
    *  @return      the string builder `b` to which elements were appended.
    */
  override def addString(sb: StringBuilder, start: String, sep: String, end: String): StringBuilder = {
    force
    addStringNoForce(sb.underlying, start, sep, end)
    sb
  }

  private[this] def addStringNoForce(b: JStringBuilder, start: String, sep: String, end: String): JStringBuilder = {
    b.append(start)
    if (!stateDefined) b.append('?')
    else if (nonEmpty) {
      b.append(head)
      var cursor = this
      def appendCursorElement(): Unit = b.append(sep).append(cursor.head)
      var scout = tail
      @inline def scoutNonEmpty: Boolean = scout.stateDefined && scout.nonEmpty
      if ((cursor ne scout) && (!scout.stateDefined || (cursor.state ne scout.state))) {
        cursor = scout
        if (scoutNonEmpty) {
          scout = scout.tail
          // Use 2x 1x iterator trick for cycle detection; slow iterator can add strings
          while ((cursor ne scout) && scoutNonEmpty && (cursor.state ne scout.state)) {
            appendCursorElement()
            cursor = cursor.tail
            scout = scout.tail
            if (scoutNonEmpty) scout = scout.tail
          }
        }
      }
      if (!scoutNonEmpty) {  // Not a cycle, scout hit an end
        while (cursor ne scout) {
          appendCursorElement()
          cursor = cursor.tail
        }
        // if cursor (eq scout) has state defined, it is empty; else unknown state
        if (!cursor.stateDefined) b.append(sep).append('?')
      } else {
        @inline def same(a: LazyList[A], b: LazyList[A]): Boolean = (a eq b) || (a.state eq b.state)
        // Cycle.
        // If we have a prefix of length P followed by a cycle of length C,
        // the scout will be at position (P%C) in the cycle when the cursor
        // enters it at P.  They'll then collide when the scout advances another
        // C - (P%C) ahead of the cursor.
        // If we run the scout P farther, then it will be at the start of
        // the cycle: (C - (P%C) + (P%C)) == C == 0.  So if another runner
        // starts at the beginning of the prefix, they'll collide exactly at
        // the start of the loop.
        var runner = this
        var k = 0
        while (!same(runner, scout)) {
          runner = runner.tail
          scout = scout.tail
          k += 1
        }
        // Now runner and scout are at the beginning of the cycle.  Advance
        // cursor, adding to string, until it hits; then we'll have covered
        // everything once.  If cursor is already at beginning, we'd better
        // advance one first unless runner didn't go anywhere (in which case
        // we've already looped once).
        if (same(cursor, scout) && (k > 0)) {
          appendCursorElement()
          cursor = cursor.tail
        }
        while (!same(cursor, scout)) {
          appendCursorElement()
          cursor = cursor.tail
        }
        b.append(sep).append("...")
      }
    }
    b.append(end)
  }

  /**
    * @return a string representation of this collection. Undefined elements are
    *         represented with `"_"`, an undefined tail is represented with `"?"`,
    *         and cycles are represented with `"..."`
    *
    *         Examples:
    *
    *           - `"LazyList(_, ?)"`, a non-empty lazy list, whose head has not been
    *             evaluated ;
    *           - `"LazyList(_, 1, _, ?)"`, a lazy list with at least three elements,
    *             the second one has been evaluated ;
    *           - `"LazyList(1, 2, 3, ...)"`, an infinite lazy list that contains
    *             a cycle at the fourth element.
    */
  override def toString(): String = addStringNoForce(new JStringBuilder(className), "(", ", ", ")").toString

  @deprecated("Check .knownSize instead of .hasDefiniteSize for more actionable information (see scaladoc for details)", "2.13.0")
  override def hasDefiniteSize: Boolean = {
    if (!stateDefined) false
    else if (isEmpty) true
    else {
      // Two-iterator trick (2x & 1x speed) for cycle detection.
      var those = this
      var these = tail
      while (those ne these) {
        if (!these.stateDefined) return false
        else if (these.isEmpty) return true
        these = these.tail
        if (!these.stateDefined) return false
        else if (these.isEmpty) return true
        these = these.tail
        if (those eq these) return false
        those = those.tail
      }
      false  // Cycle detected
    }
  }
}

/**
  * $factoryInfo
  * @define coll lazy list
  * @define Coll `LazyList`
  */
@SerialVersionUID(3L)
object LazyList extends SeqFactory[LazyList] {
  // Eagerly evaluate cached empty instance
  private[this] val _empty = newLL(State.Empty).force

  private sealed trait State[+A] extends Serializable {
    def head: A
    def tail: LazyList[A]
  }

  private object State {
    @SerialVersionUID(3L)
    object Empty extends State[Nothing] {
      def head: Nothing = throw new NoSuchElementException("head of empty lazy list")
      def tail: LazyList[Nothing] = throw new UnsupportedOperationException("tail of empty lazy list")
    }

    @SerialVersionUID(3L)
    final class Cons[A](val head: A, val tail: LazyList[A]) extends State[A]
  }

  private class LazyIterator[+A](private[this] var lazyList: LazyList[A]) extends AbstractIterator[A] {
    override def hasNext: Boolean = lazyList.nonEmpty

    override def next(): A =
      if (lazyList.isEmpty) Iterator.empty.next()
      else {
        val res = lazyList.head
        lazyList = lazyList.tail
        res
      }
  }

  /** Creates a new LazyList. */
  @inline private def newLL[A](state: => State[A]): LazyList[A] = new LazyList[A](() => state)

  /** Creates a new State.Cons. */
  @inline private def sCons[A](hd: A, tl: LazyList[A]): State[A] = new State.Cons[A](hd, tl)

  /** An alternative way of building and matching lazy lists using LazyList.cons(hd, tl).
    */
  object cons {
    /** A lazy list consisting of a given first element and remaining elements
      *  @param hd   The first element of the result lazy list
      *  @param tl   The remaining elements of the result lazy list
      */
    def apply[A](hd: => A, tl: => LazyList[A]): LazyList[A] = newLL(sCons(hd, tl))

    /** Maps a lazy list to its head and tail */
    def unapply[A](xs: LazyList[A]): Option[(A, LazyList[A])] = #::.unapply(xs)
  }

  implicit def toDeferrer[A](l: => LazyList[A]): Deferrer[A] = new Deferrer[A](() => l)

  final class Deferrer[A] private[LazyList] (private val l: () => LazyList[A]) extends AnyVal {
    /** Construct a LazyList consisting of a given first element followed by elements
      *  from another LazyList.
      */
    def #:: [B >: A](elem: => B): LazyList[B] = newLL(sCons(elem, l()))
    /** Construct a LazyList consisting of the concatenation of the given LazyList and
      *  another LazyList.
      */
    def #:::[B >: A](prefix: LazyList[B]): LazyList[B] = prefix lazyAppendedAll l()
  }

  object #:: {
    def unapply[A](s: LazyList[A]): Option[(A, LazyList[A])] =
      if (s.nonEmpty) Some((s.head, s.tail)) else None
  }

  def from[A](coll: collection.IterableOnce[A]): LazyList[A] = coll match {
    case lazyList: LazyList[A]    => lazyList
    case _ if coll.knownSize == 0 => empty[A]
    case _                        => newLL(stateFromIterator(coll.iterator))
  }

  def empty[A]: LazyList[A] = _empty

  /** Creates a State from an Iterator, with another State appended after the Iterator
    * is empty.
    */
  private def stateFromIteratorConcatSuffix[A](it: Iterator[A])(suffix: => State[A]): State[A] =
    if (it.hasNext) sCons(it.next(), newLL(stateFromIteratorConcatSuffix(it)(suffix)))
    else suffix

  /** Creates a State from an IterableOnce. */
  private def stateFromIterator[A](it: Iterator[A]): State[A] =
    if (it.hasNext) sCons(it.next(), newLL(stateFromIterator(it)))
    else State.Empty

  override def concat[A](xss: collection.Iterable[A]*): LazyList[A] =
    if (xss.knownSize == 0) empty
    else newLL(concatIterator(xss.iterator))

  private def concatIterator[A](it: Iterator[collection.Iterable[A]]): State[A] =
    if (!it.hasNext) State.Empty
    else stateFromIteratorConcatSuffix(it.next().iterator)(concatIterator(it))

  private final class WithFilter[A] private[LazyList](lazyList: LazyList[A], p: A => Boolean)
    extends collection.WithFilter[A, LazyList] {
    private[this] val filtered = lazyList.filter(p)
    def map[B](f: A => B): LazyList[B] = filtered.map(f)
    def flatMap[B](f: A => IterableOnce[B]): LazyList[B] = filtered.flatMap(f)
    def foreach[U](f: A => U): Unit = filtered.foreach(f)
    def withFilter(q: A => Boolean): collection.WithFilter[A, LazyList] = new WithFilter(filtered, q)
  }

  /** An infinite LazyList that repeatedly applies a given function to a start value.
    *
    *  @param start the start value of the LazyList
    *  @param f     the function that's repeatedly applied
    *  @return      the LazyList returning the infinite sequence of values `start, f(start), f(f(start)), ...`
    */
  def iterate[A](start: => A)(f: A => A): LazyList[A] =
    newLL {
      val head = start
      sCons(head, iterate(f(head))(f))
    }

  /**
    * Create an infinite LazyList starting at `start` and incrementing by
    * step `step`.
    *
    * @param start the start value of the LazyList
    * @param step the increment value of the LazyList
    * @return the LazyList starting at value `start`.
    */
  def from(start: Int, step: Int): LazyList[Int] =
    newLL(sCons(start, from(start + step, step)))

  /**
    * Create an infinite LazyList starting at `start` and incrementing by `1`.
    *
    * @param start the start value of the LazyList
    * @return the LazyList starting at value `start`.
    */
  def from(start: Int): LazyList[Int] = from(start, 1)

  /**
    * Create an infinite LazyList containing the given element expression (which
    * is computed for each occurrence).
    *
    * @param elem the element composing the resulting LazyList
    * @return the LazyList containing an infinite number of elem
    */
  def continually[A](elem: => A): LazyList[A] = newLL(sCons(elem, continually(elem)))

  override def fill[A](n: Int)(elem: => A): LazyList[A] =
    if (n > 0) newLL(sCons(elem, fill(n - 1)(elem))) else empty

  override def tabulate[A](n: Int)(f: Int => A): LazyList[A] = {
    def at(index: Int): LazyList[A] =
      if (index < n) newLL(sCons(f(index), at(index + 1))) else empty

    at(0)
  }

  // significantly simpler than the iterator returned by Iterator.unfold
  override def unfold[A, S](init: S)(f: S => Option[(A, S)]): LazyList[A] =
    newLL {
      f(init) match {
        case Some((elem, state)) => sCons(elem, unfold(state)(f))
        case None                => State.Empty
      }
    }

  def newBuilder[A]: Builder[A, LazyList[A]] = ArrayBuffer.newBuilder[A].mapResult(array => from(array))

  /** This serialization proxy is used for LazyLists which start with a sequence of evaluated cons cells.
    * The forced sequence is serialized in a compact, sequential format, followed by the unevaluated tail, which uses
    * standard Java serialization to store the complete structure of unevaluated thunks. This allows the serialization
    * of long evaluated lazy lists without exhausting the stack through recursive serialization of cons cells.
    */
  @SerialVersionUID(3L)
  final class SerializationProxy[A](@transient protected var coll: LazyList[A]) extends Serializable {

    private[this] def writeObject(out: ObjectOutputStream): Unit = {
      out.defaultWriteObject()
      var these = coll
      while(these.knownNonEmpty) {
        out.writeObject(these.head)
        these = these.tail
      }
      out.writeObject(SerializeEnd)
      out.writeObject(these)
    }

    private[this] def readObject(in: ObjectInputStream): Unit = {
      in.defaultReadObject()
      val init = new ArrayBuffer[A]
      var initRead = false
      while (!initRead) in.readObject match {
        case SerializeEnd => initRead = true
        case a => init += a.asInstanceOf[A]
      }
      val tail = in.readObject().asInstanceOf[LazyList[A]]
      coll = init ++: tail
    }

    private[this] def readResolve(): Any = coll
  }
}
