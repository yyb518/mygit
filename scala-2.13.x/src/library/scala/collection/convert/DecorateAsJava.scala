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
package convert

import java.{ lang => jl, util => ju }, java.util.{ concurrent => juc }
import Decorators._
import scala.language.implicitConversions

/** Defines `asJava` extension methods for [[JavaConverters]]. */
trait DecorateAsJava extends AsJavaConverters {
  /**
   * Adds an `asJava` method that implicitly converts a Scala `Iterator` to a Java `Iterator`.
   * @see [[asJavaIterator]]
   */
  implicit def asJavaIteratorConverter[A](i : Iterator[A]): AsJava[ju.Iterator[A]] =
    new AsJava(asJavaIterator(i))

  /**
   * Adds an `asJavaEnumeration` method that implicitly converts a Scala `Iterator` to a Java `Enumeration`.
   * @see [[asJavaEnumeration]]
   */
  implicit def asJavaEnumerationConverter[A](i : Iterator[A]): AsJavaEnumeration[A] =
    new AsJavaEnumeration(i)

  /**
   * Adds an `asJava` method that implicitly converts a Scala `Iterable` to a Java `Iterable`.
   * @see [[asJavaIterable]]
   */
  implicit def asJavaIterableConverter[A](i : Iterable[A]): AsJava[jl.Iterable[A]] =
    new AsJava(asJavaIterable(i))

  /**
   * Adds an `asJavaCollection` method that implicitly converts a Scala `Iterable` to an immutable Java `Collection`.
   * @see [[asJavaCollection]]
   */
  implicit def asJavaCollectionConverter[A](i : Iterable[A]): AsJavaCollection[A] =
    new AsJavaCollection(i)

  /**
   * Adds an `asJava` method that implicitly converts a Scala mutable `Buffer` to a Java `List`.
   * @see [[bufferAsJavaList]]
   */
  implicit def bufferAsJavaListConverter[A](b : mutable.Buffer[A]): AsJava[ju.List[A]] =
    new AsJava(bufferAsJavaList(b))

  /**
   * Adds an `asJava` method that implicitly converts a Scala mutable `Seq` to a Java `List`.
   * @see [[mutableSeqAsJavaList]]
   */
  implicit def mutableSeqAsJavaListConverter[A](b : mutable.Seq[A]): AsJava[ju.List[A]] =
    new AsJava(mutableSeqAsJavaList(b))

  /**
   * Adds an `asJava` method that implicitly converts a Scala `Seq` to a Java `List`.
   * @see [[seqAsJavaList]]
   */
  implicit def seqAsJavaListConverter[A](b : Seq[A]): AsJava[ju.List[A]] =
    new AsJava(seqAsJavaList(b))

  /**
   * Adds an `asJava` method that implicitly converts a Scala mutable `Set` to a Java `Set`.
   * @see [[mutableSetAsJavaSet]]
   */
  implicit def mutableSetAsJavaSetConverter[A](s : mutable.Set[A]): AsJava[ju.Set[A]] =
    new AsJava(mutableSetAsJavaSet(s))

  /**
   * Adds an `asJava` method that implicitly converts a Scala `Set` to a Java `Set`.
   * @see [[setAsJavaSet]]
   */
  implicit def setAsJavaSetConverter[A](s : Set[A]): AsJava[ju.Set[A]] =
    new AsJava(setAsJavaSet(s))

  /**
   * Adds an `asJava` method that implicitly converts a Scala mutable `Map` to a Java `Map`.
   * @see [[mutableMapAsJavaMap]]
   */
  implicit def mutableMapAsJavaMapConverter[K, V](m : mutable.Map[K, V]): AsJava[ju.Map[K, V]] =
    new AsJava(mutableMapAsJavaMap(m))

  /**
   * Adds an `asJavaDictionary` method that implicitly converts a Scala mutable `Map` to a Java `Dictionary`.
   * @see [[asJavaDictionary]]
   */
  implicit def asJavaDictionaryConverter[K, V](m : mutable.Map[K, V]): AsJavaDictionary[K, V] =
    new AsJavaDictionary(m)

  /**
   * Adds an `asJava` method that implicitly converts a Scala `Map` to a Java `Map`.
   * @see [[mapAsJavaMap]]
   */
  implicit def mapAsJavaMapConverter[K, V](m : Map[K, V]): AsJava[ju.Map[K, V]] =
    new AsJava(mapAsJavaMap(m))

  /**
   * Adds an `asJava` method that implicitly converts a Scala mutable `concurrent.Map` to a Java `ConcurrentMap`.
   * @see [[mapAsJavaConcurrentMap]].
   */
  implicit def mapAsJavaConcurrentMapConverter[K, V](m: concurrent.Map[K, V]): AsJava[juc.ConcurrentMap[K, V]] =
    new AsJava(mapAsJavaConcurrentMap(m))
}
