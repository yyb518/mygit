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

package scala.tools.nsc.classpath

import java.io.{Closeable, File}
import java.net.URL
import java.nio.file.Files
import java.nio.file.attribute.{BasicFileAttributes, FileTime}
import java.util.{Timer, TimerTask}
import java.util.concurrent.atomic.AtomicInteger

import scala.annotation.tailrec
import scala.reflect.io.{AbstractFile, FileZipArchive, ManifestResources}
import scala.tools.nsc.util.{ClassPath, ClassRepresentation}
import scala.tools.nsc.{CloseableRegistry, Settings}
import FileUtils._

/**
 * A trait providing an optional cache for classpath entries obtained from zip and jar files.
 * It allows us to e.g. reduce significantly memory used by PresentationCompilers in Scala IDE
 * when there are a lot of projects having a lot of common dependencies.
 */
sealed trait ZipAndJarFileLookupFactory {
  private val cache = new FileBasedCache[ClassPath with Closeable]

  def create(zipFile: AbstractFile, settings: Settings, closeableRegistry: CloseableRegistry): ClassPath = {
    if (settings.YdisableFlatCpCaching || zipFile.file == null) {
      val result: ClassPath with Closeable = createForZipFile(zipFile, settings.releaseValue)
      closeableRegistry.registerClosable(result)
      result
    } else {
      cache.getOrCreate(List(zipFile.file.toPath), () => createForZipFile(zipFile, settings.releaseValue), closeableRegistry)
    }
  }

  protected def createForZipFile(zipFile: AbstractFile, release: Option[String]): ClassPath with Closeable
}

/**
 * Manages creation of classpath for class files placed in zip and jar files.
 * It should be the only way of creating them as it provides caching.
 */
object ZipAndJarClassPathFactory extends ZipAndJarFileLookupFactory {
  private case class ZipArchiveClassPath(zipFile: File, override val release: Option[String])
    extends ZipArchiveFileLookup[ClassFileEntryImpl]
    with NoSourcePaths {

    override def findClassFile(className: String): Option[AbstractFile] = {
      val (pkg, simpleClassName) = PackageNameUtils.separatePkgAndClassNames(className)
      file(pkg, simpleClassName + ".class").map(_.file)
    }
    // This method is performance sensitive as it is used by SBT's ExtractDependencies phase.
    override def findClass(className: String): Option[ClassRepresentation] = {
      val (pkg, simpleClassName) = PackageNameUtils.separatePkgAndClassNames(className)
      file(pkg, simpleClassName + ".class")
    }

    override private[nsc] def classes(inPackage: String): Seq[ClassFileEntry] = files(inPackage)

    override protected def createFileEntry(file: FileZipArchive#Entry): ClassFileEntryImpl = ClassFileEntryImpl(file)
    override protected def isRequiredFileType(file: AbstractFile): Boolean = file.isClass
  }

  /**
   * This type of classpath is closely related to the support for JSR-223.
   * Its usage can be observed e.g. when running:
   * jrunscript -classpath scala-compiler.jar;scala-reflect.jar;scala-library.jar -l scala
   * with a particularly prepared scala-library.jar. It should have all classes listed in the manifest like e.g. this entry:
   * Name: scala/Function2$mcFJD$sp.class
   */
  private case class ManifestResourcesClassPath(file: ManifestResources) extends ClassPath with NoSourcePaths with Closeable {
    override def findClassFile(className: String): Option[AbstractFile] = {
      val (pkg, simpleClassName) = PackageNameUtils.separatePkgAndClassNames(className)
      classes(pkg).find(_.name == simpleClassName).map(_.file)
    }

    override def asClassPathStrings: Seq[String] = Seq(file.path)

    override def asURLs: Seq[URL] = file.toURLs()
    override def close(): Unit = file.close()

    import ManifestResourcesClassPath.PackageFileInfo
    import ManifestResourcesClassPath.PackageInfo

    /**
     * A cache mapping package name to abstract file for package directory and subpackages of given package.
     *
     * ManifestResources can iterate through the collections of entries from e.g. remote jar file.
     * We can't just specify the path to the concrete directory etc. so we can't just 'jump' into
     * given package, when it's needed. On the other hand we can iterate over entries to get
     * AbstractFiles, iterate over entries of these files etc.
     *
     * Instead of traversing a tree of AbstractFiles once and caching all entries or traversing each time,
     * when we need subpackages of a given package or its classes, we traverse once and cache only packages.
     * Classes for given package can be then easily loaded when they are needed.
     */
    private lazy val cachedPackages: collection.mutable.HashMap[String, PackageFileInfo] = {
      val packages = collection.mutable.HashMap[String, PackageFileInfo]()

      def getSubpackages(dir: AbstractFile): List[AbstractFile] =
        List.from(for (file <- dir.iterator if file.isPackage) yield file)

      @tailrec
      def traverse(packagePrefix: String,
                   filesForPrefix: List[AbstractFile],
                   subpackagesQueue: collection.mutable.Queue[PackageInfo]): Unit = filesForPrefix match {
        case pkgFile :: remainingFiles =>
          val subpackages = getSubpackages(pkgFile)
          val fullPkgName = packagePrefix + pkgFile.name
          packages.put(fullPkgName, PackageFileInfo(pkgFile, subpackages))
          val newPackagePrefix = fullPkgName + "."
          subpackagesQueue.enqueue(PackageInfo(newPackagePrefix, subpackages))
          traverse(packagePrefix, remainingFiles, subpackagesQueue)
        case Nil if subpackagesQueue.nonEmpty =>
          val PackageInfo(packagePrefix, filesForPrefix) = subpackagesQueue.dequeue()
          traverse(packagePrefix, filesForPrefix, subpackagesQueue)
        case _ =>
      }

      val subpackages = getSubpackages(file)
      packages.put(ClassPath.RootPackage, PackageFileInfo(file, subpackages))
      traverse(ClassPath.RootPackage, subpackages, collection.mutable.Queue())
      packages
    }

    override private[nsc] def packages(inPackage: String): Seq[PackageEntry] = cachedPackages.get(inPackage) match {
      case None => Seq.empty
      case Some(PackageFileInfo(_, subpackages)) =>
        val prefix = PackageNameUtils.packagePrefix(inPackage)
        subpackages.map(packageFile => PackageEntryImpl(prefix + packageFile.name))
    }

    override private[nsc] def classes(inPackage: String): Seq[ClassFileEntry] = cachedPackages.get(inPackage) match {
      case None => Seq.empty
      case Some(PackageFileInfo(pkg, _)) =>
        Seq.from(for (file <- pkg.iterator if file.isClass) yield ClassFileEntryImpl(file))
    }


    override private[nsc] def hasPackage(pkg: String) = cachedPackages.contains(pkg)
    override private[nsc] def list(inPackage: String): ClassPathEntries = ClassPathEntries(packages(inPackage), classes(inPackage))
  }

  private object ManifestResourcesClassPath {
    case class PackageFileInfo(packageFile: AbstractFile, subpackages: Seq[AbstractFile])
    case class PackageInfo(packageName: String, subpackages: List[AbstractFile])
  }

  override protected def createForZipFile(zipFile: AbstractFile, release: Option[String]): ClassPath with Closeable =
    if (zipFile.file == null) createWithoutUnderlyingFile(zipFile)
    else ZipArchiveClassPath(zipFile.file, release)

  private def createWithoutUnderlyingFile(zipFile: AbstractFile) = zipFile match {
    case manifestRes: ManifestResources =>
      ManifestResourcesClassPath(manifestRes)
    case _ =>
      val errorMsg = s"Abstract files which don't have an underlying file and are not ManifestResources are not supported. There was $zipFile"
      throw new IllegalArgumentException(errorMsg)
  }
}

/**
 * Manages creation of classpath for source files placed in zip and jar files.
 * It should be the only way of creating them as it provides caching.
 */
object ZipAndJarSourcePathFactory extends ZipAndJarFileLookupFactory {
  private case class ZipArchiveSourcePath(zipFile: File)
    extends ZipArchiveFileLookup[SourceFileEntryImpl]
    with NoClassPaths {
    def release: Option[String] = None

    override def asSourcePathString: String = asClassPathString

    override private[nsc] def sources(inPackage: String): Seq[SourceFileEntry] = files(inPackage)

    override protected def createFileEntry(file: FileZipArchive#Entry): SourceFileEntryImpl = SourceFileEntryImpl(file)
    override protected def isRequiredFileType(file: AbstractFile): Boolean = file.isScalaOrJavaSource
  }

  override protected def createForZipFile(zipFile: AbstractFile, release: Option[String]): ClassPath with Closeable = ZipArchiveSourcePath(zipFile.file)
}

final class FileBasedCache[T] {
  import java.nio.file.Path
  private case class Stamp(lastModified: FileTime, fileKey: Object)
  private case class Entry(stamps: Seq[Stamp], t: T) {
    val referenceCount: AtomicInteger = new AtomicInteger(1)
    def referenceCountDecrementer: Closeable = new Closeable {
      var closed = false
      override def close(): Unit = {
        if (!closed) {
          closed = true
          val count = referenceCount.decrementAndGet()
          if (count == 0) {
            t match {
              case cl: Closeable => FileBasedCache.deferredClose(referenceCount, cl)
              case _ =>
            }
          }
        }
      }
    }
  }
  private val cache = collection.mutable.Map.empty[Seq[Path], Entry]

  def getOrCreate(paths: Seq[Path], create: () => T, closeableRegistry: CloseableRegistry): T = cache.synchronized {
    val stamps = paths.map { path =>
      val attrs = Files.readAttributes(path, classOf[BasicFileAttributes])
      val lastModified = attrs.lastModifiedTime()
      // only null on some platforms, but that's okay, we just use the last modified timestamp as our stamp
      val fileKey = attrs.fileKey()
      Stamp(lastModified, fileKey)
    }

    cache.get(paths) match {
      case Some(e@Entry(cachedStamps, cached)) if cachedStamps == stamps =>
        e.referenceCount.incrementAndGet()
        closeableRegistry.registerClosable(e.referenceCountDecrementer)
        cached
      case _ =>
        val value = create()
        val entry = Entry(stamps, value)
        cache.put(paths, entry)
        closeableRegistry.registerClosable(entry.referenceCountDecrementer)
        value
    }
  }

  def clear(): Unit = cache.synchronized {
    // TODO support closing
    // cache.valuesIterator.foreach(_.close())
    cache.clear()
  }
}

object FileBasedCache {
  // The tension here is that too long a delay could lead to an error (on Windows) with an inability
  // to overwrite the JAR. To short a delay and the entry could be evicted before a subsequent
  // sub-project compilation is able to get a cache hit. A more comprehensive solution would be to
  // involve build tools in the policy: they could close entries with refcount of zero when that
  // entry's JAR is about to be overwritten.
  private val deferCloseMs = Integer.getInteger("scalac.filebasedcache.defer.close.ms", 1000)
  private val timer: Option[Timer] = {
    if (deferCloseMs > 0)
      Some(new java.util.Timer(true))
    else None
  }
  private def deferredClose(referenceCount: AtomicInteger, closable: Closeable): Unit = {
    timer match {
      case Some(timer) =>
        val task = new TimerTask {
          override def run(): Unit = {
            if (referenceCount.get == 0)
              closable.close()
          }
        }
        timer.schedule(task, FileBasedCache.deferCloseMs.toLong)
      case None =>
        closable.close()
    }
  }
}
