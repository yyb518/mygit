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

/** Defines `asScala` extension methods for [[JavaConverters]]. */
trait DecorateAsScala extends AsScalaConverters {
  /**
   * Adds an `asScala` method that implicitly converts a Java `Iterator` to a Scala `Iterator`.
   * @see [[asScalaIterator]]
   */
  implicit def asScalaIteratorConverter[A](i : ju.Iterator[A]): AsScala[Iterator[A]] =
    new AsScala(asScalaIterator(i))

  /**
   * Adds an `asScala` method that implicitly converts a Java `Enumeration` to a Scala `Iterator`.
   * @see [[enumerationAsScalaIterator]]
   */
  implicit def enumerationAsScalaIteratorConverter[A](i : ju.Enumeration[A]): AsScala[Iterator[A]] =
    new AsScala(enumerationAsScalaIterator(i))

  /**
   * Adds an `asScala` method that implicitly converts a Java `Iterable` to a Scala `Iterable`.
   * @see [[iterableAsScalaIterable]]
   */
  implicit def iterableAsScalaIterableConverter[A](i : jl.Iterable[A]): AsScala[Iterable[A]] =
    new AsScala(iterableAsScalaIterable(i))

  /**
   * Adds an `asScala` method that implicitly converts a Java `Collection` to an Scala `Iterable`.
   * @see [[collectionAsScalaIterable]]
   */
  implicit def collectionAsScalaIterableConverter[A](i : ju.Collection[A]): AsScala[Iterable[A]] =
    new AsScala(collectionAsScalaIterable(i))

  /**
   * Adds an `asScala` method that implicitly converts a Java `List` to a Scala mutable `Buffer`.
   * @see [[asScalaBuffer]]
   */
  implicit def asScalaBufferConverter[A](l : ju.List[A]): AsScala[mutable.Buffer[A]] =
    new AsScala(asScalaBuffer(l))

  /**
   * Adds an `asScala` method that implicitly converts a Java `Set` to a Scala mutable `Set`.
   * @see [[asScalaSet]]
   */
  implicit def asScalaSetConverter[A](s : ju.Set[A]): AsScala[mutable.Set[A]] =
    new AsScala(asScalaSet(s))

  /**
   * Adds an `asScala` method that implicitly converts a Java `Map` to a Scala mutable `Map`.
   * @see [[mapAsScalaMap]]
   */
  implicit def mapAsScalaMapConverter[K, V](m : ju.Map[K, V]): AsScala[mutable.Map[K, V]] =
    new AsScala(mapAsScalaMap(m))

  /**
   * Adds an `asScala` method that implicitly converts a Java `ConcurrentMap` to a Scala mutable `concurrent.Map`.
   * @see [[mapAsScalaConcurrentMap]]
   */
  implicit def mapAsScalaConcurrentMapConverter[K, V](m: juc.ConcurrentMap[K, V]): AsScala[concurrent.Map[K, V]] =
    new AsScala(mapAsScalaConcurrentMap(m))

  /**
   * Adds an `asScala` method that implicitly converts a Java `Dictionary` to a Scala mutable `Map`.
   * @see [[dictionaryAsScalaMap]]
   */
  implicit def dictionaryAsScalaMapConverter[K, V](p: ju.Dictionary[K, V]): AsScala[mutable.Map[K, V]] =
    new AsScala(dictionaryAsScalaMap(p))

  /**
   * Adds an `asScala` method that implicitly converts a Java `Properties` to a Scala mutable `Map[String, String]`.
   * @see [[propertiesAsScalaMap]]
   */
  implicit def propertiesAsScalaMapConverter(p: ju.Properties): AsScala[mutable.Map[String, String]] =
    new AsScala(propertiesAsScalaMap(p))
}
