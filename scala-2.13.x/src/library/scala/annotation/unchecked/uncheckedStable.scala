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

package scala.annotation.unchecked

/** An annotation for values that are assumed to be stable even though their
 *  types are volatile.
 *
 *  @since 2.7
 */
final class uncheckedStable extends scala.annotation.StaticAnnotation {}
