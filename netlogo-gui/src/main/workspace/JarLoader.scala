// (C) Uri Wilensky. https://github.com/NetLogo/NetLogo

package org.nlogo.workspace

import java.net.{ URL, MalformedURLException, JarURLConnection }
import java.io.{ File, FileNotFoundException, IOException }

import ExtensionManager.ExtensionData
import ExtensionManagerException._

import org.nlogo.api.ClassManager

import scala.util.{ Success, Try }

class JarLoader(workspace: ExtendableWorkspace) extends ExtensionManager.ExtensionLoader {

  def locateExtension(extensionName: String): Option[URL] =
    try {
      resolvePathAsURL(identifierToJar(extensionName))
    } catch {
      case ex@(_: RuntimeException | _: MalformedURLException)=>
        None
    }

  def extensionData(extensionName: String, fileURL: URL) = {
    val connection = connectToJar(fileURL)
    Option(connection.getManifest).map { manifest =>
      val attr = manifest.getMainAttributes

      val version       = Option(attr.getValue("NetLogo-Extension-API-Version"))
      // note - the prefix is not really used anywhere, the extensionName drives the behavior.
      // But no assertion is ever made that prefix == extensionName
      val prefix        = Option(attr.getValue("Extension-Name")).getOrElse(throw new ExtensionManagerException(NoExtensionName(extensionName)))
      val classMangName = Option(attr.getValue("Class-Manager")).getOrElse(throw new ExtensionManagerException(NoClassManager(extensionName)))

      ExtensionData(extensionName, fileURL, prefix, classMangName, version, connection.getLastModified)
    }.getOrElse(throw new ExtensionManagerException(NoManifest(extensionName)))
  }

  def extensionClassLoader(fileURL: URL, parentLoader: ClassLoader): ClassLoader = {
    val folderContainingJar = new File(new File(fileURL.toURI).getParent)
    val urls = fileURL +: (getAdditionalJars(folderContainingJar) ++ getAdditionalJars(new File("extensions")))
    java.net.URLClassLoader.newInstance(urls.toArray, parentLoader)
  }

  def extensionClassManager(classLoader: ClassLoader, data: ExtensionData): ClassManager =
    try {
      classLoader.loadClass(data.classManagerName).newInstance match {
        case cm: ClassManager => cm
        case _                =>
          throw new ExtensionManagerException(InvalidClassManager(data.extensionName))
      }
    } catch {
      case ex: ClassNotFoundException =>
        throw new ExtensionManagerException(NotFoundClassManager(data.classManagerName))
      case ex@(_: InstantiationException | _: IllegalAccessException) =>
        throw new IllegalStateException(ex)
    }

  private def connectToJar(fileURL: URL): JarURLConnection =
    try {
      val jarURL = new URL("jar", "", fileURL.toString + "!/")
      jarURL.openConnection.asInstanceOf[JarURLConnection]
    } catch {
      case _ : FileNotFoundException | _ : IOException =>
        throw new ExtensionManagerException(ExtensionNotFound(fileURL.toString))
    }

  private def identifierToJar(id: String): String =
    if (!id.endsWith(".jar"))
      id + File.separator + id + ".jar"
    else
      id

  private[workspace] def resolvePathAsURL(path: String): Option[URL] = {
    val potentialFiles = Seq(
        Try(new File(workspace.attachModelDir(path))),
        Try(new File(ExtensionManager.userExtensionsPath + File.separator + path)),
        Try(new File(ExtensionManager.extensionsPath + File.separator + path))
      ).collect { case Success(v) => v }

    val pathsToTry: Seq[Try[URL]] = Try(new URL(path)) +:
      potentialFiles.filter(f => f.exists).map(f => Try(f.toURI.toURL))

    pathsToTry.collectFirst { case Success(url) => url }
  }

  private def getAdditionalJars(folder: File): Seq[URL] =
    if (folder.exists && folder.isDirectory)
      folder.listFiles
        .filter(file => file.isFile && file.getName.toUpperCase.endsWith(".JAR"))
        .map(f => Try(f.toURI.toURL).recover {
          case ex: MalformedURLException => throw new IllegalStateException(ex)
        }.get)
    else
      Seq[URL]()
}
