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

package scala.tools.nsc
package plugins

import java.net.URL

import scala.reflect.internal.util.ScalaClassLoader
import scala.reflect.io.Path
import scala.tools.nsc
import scala.tools.nsc.io.Jar
import scala.tools.nsc.plugins.Plugin.pluginClassLoadersCache
import scala.tools.nsc.typechecker.Macros
import scala.tools.nsc.util.ClassPath
import scala.tools.util.PathResolver.Defaults

/** Support for run-time loading of compiler plugins.
 *
 *  @author Lex Spoon
 *  @version 1.1, 2009/1/2
 *  Updated 2009/1/2 by Anders Bach Nielsen: Added features to implement SIP 00002
 */
trait Plugins { global: Global =>

  /** Load a rough list of the plugins.  For speed, it
   *  does not instantiate a compiler run.  Therefore it cannot
   *  test for same-named phases or other problems that are
   *  filtered from the final list of plugins.
   */
  protected def loadRoughPluginsList(): List[Plugin] = {
    def asPath(p: String) = ClassPath split p
    val paths  = settings.plugin.value filter (_ != "") map (s => asPath(s) map Path.apply)
    val dirs   = {
      def injectDefault(s: String) = if (s.isEmpty) Defaults.scalaPluginPath else s
      asPath(settings.pluginsDir.value) map injectDefault map Path.apply
    }
    val maybes = Plugin.loadAllFrom(paths, dirs, settings.disable.value, findPluginClassLoader(_))
    val (goods, errors) = maybes partition (_.isSuccess)
    // Explicit parameterization of recover to avoid -Xlint warning about inferred Any
    errors foreach (_.recover[Any] {
      // legacy behavior ignores altogether, so at least warn devs
      case e: MissingPluginException => if (global.isDeveloper) warning(e.getMessage)
      case e: Exception              => inform(e.getMessage)
    })
    val classes = goods map (_.get)  // flatten

    // Each plugin must only be instantiated once. A common pattern
    // is to register annotation checkers during object construction, so
    // creating multiple plugin instances will leave behind stale checkers.
    classes map (Plugin.instantiate(_, this))
  }

  /**
    * Locate or create the classloader to load a compiler plugin with `classpath`.
    *
    * Subclasses may override to customise the behaviour.
    *
    * @param classpath
    * @return
    */
  protected def findPluginClassLoader(classpath: Seq[Path]): ClassLoader = {
    val disableCache = settings.YcachePluginClassLoader.value == settings.CachePolicy.None.name
    def newLoader = () => {
      val compilerLoader = classOf[Plugin].getClassLoader
      val urls = classpath map (_.toURL)
      ScalaClassLoader fromURLs (urls, compilerLoader)
    }

    // Create a class loader with the specified locations plus
    // the loader that loaded the Scala compiler.
    //
    // If the class loader has already been created before and the
    // file stamps are the same, the previous loader is returned to
    // mitigate the cost of dynamic classloading as it has been
    // measured in https://github.com/scala/scala-dev/issues/458.

    if (disableCache || classpath.exists(!Jar.isJarOrZip(_))) {
      val loader = newLoader()
      closeableRegistry.registerClosable(loader)
      loader
    } else pluginClassLoadersCache.getOrCreate(classpath.map(_.jfile.toPath()), newLoader, closeableRegistry)
  }

  protected lazy val roughPluginsList: List[Plugin] = loadRoughPluginsList()

  /** Load all available plugins.  Skips plugins that
   *  either have the same name as another one, or which
   *  define a phase name that another one does.
   */
  protected def loadPlugins(): List[Plugin] = {
    // remove any with conflicting names or subcomponent names
    def pick(
      plugins: List[Plugin],
      plugNames: Set[String],
      phaseNames: Set[String]): List[Plugin] =
    {
      if (plugins.isEmpty) return Nil // early return

      val plug :: tail      = plugins
      val plugPhaseNames    = Set(plug.components map (_.phaseName): _*)
      def withoutPlug       = pick(tail, plugNames, plugPhaseNames)
      def withPlug          = plug :: pick(tail, plugNames + plug.name, phaseNames ++ plugPhaseNames)
      lazy val commonPhases = phaseNames intersect plugPhaseNames

      def note(msg: String): Unit = if (settings.verbose) inform(msg format plug.name)
      def fail(msg: String)       = { note(msg) ; withoutPlug }

      if (plugNames contains plug.name)
        fail("[skipping a repeated plugin: %s]")
      else if (settings.disable.value contains plug.name)
        fail("[disabling plugin: %s]")
      else if (!commonPhases.isEmpty)
        fail("[skipping plugin %s because it repeats phase names: " + (commonPhases mkString ", ") + "]")
      else {
        note("[loaded plugin %s]")
        withPlug
      }
    }

    val plugs = pick(roughPluginsList, Set(), (phasesSet map (_.phaseName)).toSet)

    // Verify required plugins are present.
    for (req <- settings.require.value ; if !(plugs exists (_.name == req)))
      globalError("Missing required plugin: " + req)

    // Verify no non-existent plugin given with -P
    for {
      opt <- settings.pluginOptions.value
      if !(plugs exists (opt startsWith _.name + ":"))
    } globalError("bad option: -P:" + opt)

    // Plugins may opt out, unless we just want to show info
    plugs filter (p => p.init(p.options, globalError) || (settings.debug && settings.isInfo))
  }

  lazy val plugins: List[Plugin] = loadPlugins()

  /** A description of all the plugins that are loaded */
  def pluginDescriptions: String =
    roughPluginsList map (x => "%s - %s".format(x.name, x.description)) mkString "\n"

  /**
   * Extract all phases supplied by plugins and add them to the phasesSet.
   * @see phasesSet
   */
  protected def computePluginPhases(): Unit =
    for (p <- plugins; c <- p.components) addToPhasesSet(c, c.description)

  /** Summary of the options for all loaded plugins */
  def pluginOptionsHelp: String =
    (for (plug <- roughPluginsList ; help <- plug.optionsHelp) yield {
      "\nOptions for plugin '%s':\n%s\n".format(plug.name, help)
    }).mkString

  /** Obtains a `ClassLoader` instance used for macro expansion.
    *
    *  By default a new `ScalaClassLoader` is created using the classpath
    *  from global and the classloader of self as parent.
    *
    *  Mirrors with runtime definitions (e.g. Repl) need to adjust this method.
    */
  protected[scala] def findMacroClassLoader(): ClassLoader = {
    val classpath: Seq[URL] = if (settings.YmacroClasspath.isSetByUser) {
      for {
        file <- scala.tools.nsc.util.ClassPath.expandPath(settings.YmacroClasspath.value, true)
        af <- Option(nsc.io.AbstractFile getDirectory file)
      } yield af.file.toURI.toURL
    } else global.classPath.asURLs
    def newLoader: () => ScalaClassLoader.URLClassLoader = () => {
      analyzer.macroLogVerbose("macro classloader: initializing from -cp: %s".format(classpath))
      ScalaClassLoader.fromURLs(classpath, getClass.getClassLoader)
    }

    val disableCache = settings.YcacheMacroClassLoader.value == settings.CachePolicy.None.name
    if (disableCache) newLoader()
    else {
      import scala.tools.nsc.io.Jar
      import scala.reflect.io.{AbstractFile, Path}

      val urlsAndFiles = classpath.map(u => u -> AbstractFile.getURL(u))
      val hasNullURL = urlsAndFiles.filter(_._2 eq null)
      if (hasNullURL.nonEmpty) {
        // TODO if the only null is jrt:// we can still cache
        // TODO filter out classpath elements pointing to non-existing files before we get here, that's another source of null
        analyzer.macroLogVerbose(s"macro classloader: caching is disabled because `AbstractFile.getURL` returned `null` for ${hasNullURL.map(_._1).mkString(", ")}.")
        perRunCaches.recordClassloader(newLoader())
      } else {
        val locations = urlsAndFiles.map(t => Path(t._2.file))
        val nonJarZips = locations.filterNot(Jar.isJarOrZip(_))
        if (nonJarZips.nonEmpty) {
          analyzer.macroLogVerbose(s"macro classloader: caching is disabled because the following paths are not supported: ${nonJarZips.mkString(",")}.")
          perRunCaches.recordClassloader(newLoader())
        } else {
          Macros.macroClassLoadersCache.getOrCreate(locations.map(_.jfile.toPath()), newLoader, closeableRegistry)
        }
      }
    }
  }
}