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

package scala.tools.nsc.interpreter.jline

import scala.tools.nsc.interpreter.shell.Parsed

import _root_.jline.console.completer.ArgumentCompleter.{ ArgumentDelimiter, ArgumentList }

// implements a jline interface
class JLineDelimiter extends ArgumentDelimiter {
  def toJLine(args: List[String], cursor: Int): ArgumentList = args match {
    case Nil => new ArgumentList(new Array[String](0), 0, 0, cursor)
    case xs => new ArgumentList(xs.toArray, xs.size - 1, xs.last.length, cursor)
  }

  def delimit(buffer: CharSequence, cursor: Int) = {
    val p = Parsed(buffer.toString, cursor)
    toJLine(p.args, cursor)
  }

  def isDelimiter(buffer: CharSequence, cursor: Int) = Parsed(buffer.toString, cursor).isDelimiter
}
