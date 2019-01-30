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

import java.{util => ju}

import _root_.jline.{console => jconsole}
import jline.console.completer.{CandidateListCompletionHandler, Completer}
import jconsole.history.{History => JHistory}

import scala.tools.nsc.interpreter.shell._

trait JLineCompletion extends Completion with Completer {
  final def complete(buf: String, cursor: Int, candidates: ju.List[CharSequence]): Int =
    complete(if (buf == null) "" else buf, cursor) match {
      case CompletionResult(newCursor, newCandidates) =>
        newCandidates foreach candidates.add
        newCursor
    }
}

/**
 * Reads from the console using JLine.
 *
 * Eagerly instantiates all relevant JLine classes, so that we can detect linkage errors on `new JLineReader` and retry.
 */
class JlineReader(isAcross: Boolean, isPaged: Boolean) extends InteractiveReader {
  def interactive = true

  val history: History = new JLineHistory.JLineFileHistory()

  private val consoleReader = {
    val reader = new JLineConsoleReader(isAcross)

    reader setPaginationEnabled isPaged

    // turn off magic !
    reader setExpandEvents false

    // enable detecting pasted tab char (when next char is immediately available) which is taken raw, not completion
    reader setCopyPasteDetection true

    reader setHistory history.asInstanceOf[JHistory]

    reader
  }

  private[this] var _completion: Completion = NoCompletion
  def completion: Completion = _completion

  override def initCompletion(completion: Completion) = {
    _completion = completion
    completion match {
      case NoCompletion => // ignore
      case jlineCompleter: Completer => consoleReader.initCompletion(jlineCompleter)
      case _ => // should not happen, but hey
    }
  }

  def reset()                     = consoleReader.getTerminal().reset()
  def redrawLine()                = consoleReader.redrawLineAndFlush()
  def readOneLine(prompt: String) = consoleReader.readLine(prompt)
  def readOneKey(prompt: String)  = consoleReader.readOneKey(prompt)
}

// implements a jline interface
private class JLineConsoleReader(val isAcross: Boolean) extends jconsole.ConsoleReader with VariColumnTabulator {
  val marginSize = 3

  def width  = getTerminal.getWidth()
  def height = getTerminal.getHeight()

  private def morePrompt = "--More--"

  private def emulateMore(): Int = {
    val key = readOneKey(morePrompt)
    try key match {
      case '\r' | '\n' => 1
      case 'q' => -1
      case _ => height - 1
    }
    finally {
      eraseLine()
      // TODO: still not quite managing to erase --More-- and get
      // back to a scala prompt without another keypress.
      if (key == 'q') {
        putString(getPrompt())
        redrawLine()
        flush()
      }
    }
  }

  override def printColumns(items: ju.Collection[_ <: CharSequence]): Unit = {
    import scala.collection.JavaConverters._

    printColumns_(items.asScala.toList map (_.toString))
  }

  private def printColumns_(items: List[String]): Unit = if (items exists (_ != "")) {
    val grouped = tabulate(items)
    var linesLeft = if (isPaginationEnabled()) height - 1 else Int.MaxValue
    grouped foreach { xs =>
      println(xs.mkString)
      linesLeft -= 1
      if (linesLeft <= 0) {
        linesLeft = emulateMore()
        if (linesLeft < 0)
          return
      }
    }
  }

  def readOneKey(prompt: String) = {
    this.print(prompt)
    this.flush()
    this.readCharacter()
  }

  def eraseLine() = resetPromptLine("", "", 0)

  def redrawLineAndFlush(): Unit = {
    flush(); drawLine(); flush()
  }

  // A hook for running code after the repl is done initializing.
  def initCompletion(completer: Completer): Unit = {
    this setBellEnabled false

    getCompletionHandler match {
      case clch: CandidateListCompletionHandler =>
        clch.setPrintSpaceAfterFullCompletion(false)
    }

    this addCompleter completer

    setAutoprintThreshold(400) // max completion candidates without warning
  }
}
