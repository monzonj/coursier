package coursier.cli.publish.fileset

import java.nio.charset.StandardCharsets
import java.time.{Instant, ZoneOffset}

import coursier.cli.publish
import coursier.cli.publish.{Content, Pom}
import coursier.cli.publish.dir.DirContent
import coursier.cli.publish.upload.Upload
import coursier.core.{ModuleName, Organization, Version}
import coursier.maven.MavenRepository
import coursier.util.Task

import scala.xml.XML

/**
  * A subset of a [[FileSet]], with particular semantic.
  */
sealed abstract class Group extends Product with Serializable {

  /**
    * [[FileSet]] corresponding to this [[Group]]
    */
  def fileSet: FileSet

  def organization: Organization
}

object Group {

  /**
    * Subset of a [[FileSet]] corresponding to a particular module.
    *
    * That is to the files of a particular - published - version of a given module.
    */
  final case class Module(
    organization: Organization,
    name: ModuleName,
    version: String,
    snapshotVersioning: Option[String],
    files: DirContent
  ) extends Group {

    def baseDir: Seq[String] =
      organization.value.split('.').toSeq ++ Seq(name.value, version)

    def fileSet: FileSet = {
      val dirPath = FileSet.Path(baseDir)
      FileSet(
        files.elements.map {
          case (n, c) =>
            (dirPath / n) -> c
        }
      )
    }

    private def stripPrefixes: Module = {

      val prefix = s"${name.value}-$snapshotVersioning"

      val updatedContent = DirContent(
        files.elements.collect {
          case (n, c) =>
            val newName =
              if (n == "maven-metadata.xml" || n.startsWith("maven-metadata.xml."))
                n
              else
                n.stripPrefix(prefix) // FIXME Hard-check?
            (newName, c)
        }
      )

      copy(files = updatedContent)
    }

    private def updateFileNames: Module = {

      val newPrefix = s"${name.value}-$snapshotVersioning"

      val updatedContent = DirContent(
        files.elements.collect {
          case (n, c) =>
            val newName =
              if (n == "maven-metadata.xml" || n.startsWith("maven-metadata.xml."))
                n
              else
                s"$newPrefix$n"
            (newName, c)
        }
      )

      copy(files = updatedContent)
    }

    private def updateOrgNameVer(
      org: Option[Organization],
      name: Option[ModuleName],
      version: Option[String],
    ): Module =
      stripPrefixes
        .copy(
          organization = org.getOrElse(this.organization),
          name = name.getOrElse(this.name),
          version = version.getOrElse(this.version)
        )
        .updateFileNames

    /**
      * Adjust the organization / name / version.
      *
      * Possibly changing those in POM or maven-metadata.xml files.
      */
    def updateMetadata(
      org: Option[Organization],
      name: Option[ModuleName],
      version: Option[String],
      now: Instant
    ): Task[Module] =
      if (org.isEmpty && name.isEmpty && version.isEmpty)
        Task.point(this)
      else
        updateOrgNameVer(org, name, version)
          .updatePom(now)
          .flatMap(_.updateMavenMetadata(now))

    /**
      * The POM file of this [[Module]], if any.
      */
    def pomOpt: Option[Content] = {
      val fileName = s"${name.value}-$snapshotVersioning.pom"
      files
        .elements
        .collectFirst {
          case (`fileName`, c) =>
            c
        }
    }

    /**
      * Adjust the POM of this [[Module]], so that it contains the same org / name / version as this [[Module]].
      *
      * Calling this method, or running its [[Task]], doesn't write anything on disk. The new POM
      * stays in memory (via [[Content.InMemory]]). The returned [[Module]] only lives in memory.
      * The only effect here is possibly reading stuff on disk.
      *
      * @param now: if the POM is edited, its last modified time.
      */
    def updatePom(now: Instant): Task[Module] =
      pomOpt match {
        case None =>
          Task.fail(new Exception(s"No POM found (files: ${files.elements.map(_._1).mkString(", ")})"))
        case Some(c) =>
          c.contentTask.map { pomBytes =>
            var elem = XML.loadString(new String(pomBytes, StandardCharsets.UTF_8))
            elem = Pom.overrideOrganization(organization, elem)
            elem = Pom.overrideModuleName(name, elem)
            elem = Pom.overrideVersion(version, elem)

            val pomContent0 = Content.InMemory(now, Pom.print(elem).getBytes(StandardCharsets.UTF_8))

            val newPrefix = s"${name.value}-$snapshotVersioning"

            val updatedContent = files.update(s"$newPrefix.pom", pomContent0)
            copy(files = updatedContent)
          }
      }


    /**
      * Adds a maven-metadata.xml file to this module if it doesn't have one already.
      * @param now: last modified time of the added maven-metadata.xml, if one is indeed added.
      */
    def addMavenMetadata(now: Instant): Module = {

      val mavenMetadataFound = files
        .elements
        .exists(_._1 == "maven-metadata.xml")

      if (mavenMetadataFound)
        this
      else {
        val updatedContent = {
          val b = {
            val content = coursier.cli.publish.MavenMetadata.create(
              organization,
              name,
              None,
              None,
              Nil,
              now
            )
            coursier.cli.publish.MavenMetadata.print(content).getBytes(StandardCharsets.UTF_8)
          }
          files.update("maven-metadata.xml", Content.InMemory(now, b))
        }

        copy(files = updatedContent)
      }
    }

    def mavenMetadataContentOpt = files
      .elements
      .find(_._1 == "maven-metadata.xml")
      .map(_._2)

    /**
      * Updates the maven-metadata.xml file of this [[Module]], so that it contains the same org / name.
      * @param now: if maven-metadata.xml is edited, its last modified time.
      */
    def updateMavenMetadata(now: Instant): Task[Module] =
      mavenMetadataContentOpt match {
        case None =>
          Task.point(this)

        case Some(content) =>
          content.contentTask.map { b =>

            val updatedMetadataBytes = {
              val elem = XML.loadString(new String(b, StandardCharsets.UTF_8))
              val newContent = coursier.cli.publish.MavenMetadata.update(
                elem,
                Some(organization),
                Some(name),
                None,
                None,
                Nil,
                Some(now.atOffset(ZoneOffset.UTC).toLocalDateTime)
              )
              coursier.cli.publish.MavenMetadata.print(newContent).getBytes(StandardCharsets.UTF_8)
            }

            val updatedContent = files.update("maven-metadata.xml", Content.InMemory(now, updatedMetadataBytes))
            copy(files = updatedContent)
          }
      }

    def addSnapshotVersioning(now: Instant) = {

      assert(snapshotVersioning.isEmpty) // kind of meh

      mavenMetadataContentOpt match {
        case None =>
        case Some(c) =>
          c.contentTask.map { b =>
            val updatedMetadataBytes = {
              val elem = XML.loadString(new String(b, StandardCharsets.UTF_8))
            }
          }
      }

      publish.MavenMetadata.updateSnapshotVersioning(
        ???,
        None,
        None,
        Some(version),
        ???,
        Some(now.atZone(ZoneOffset.UTC).toLocalDateTime),
        ???
      )

      ???
    }

  }

  /**
    * Subset of a [[FileSet]] corresponding to maven-metadata.xml files.
    *
    * This correspond to the maven-metadata.xml file under org/name/maven-metadata.xml, not the
    * ones that can be found under org/name/version/maven-metadata.xml (these are in [[Module]]).
    */
  final case class MavenMetadata(
    organization: Organization,
    name: ModuleName,
    files: DirContent
  ) extends Group {

    def fileSet: FileSet = {
      val dirPath = FileSet.Path(organization.value.split('.').toSeq ++ Seq(name.value))
      FileSet(
        files.elements.map {
          case (n, c) =>
            (dirPath / n) -> c
        }
      )
    }

    def xmlOpt: Option[Content] = {
      val fileName = "maven-metadata.xml"
      files
        .elements
        .collectFirst {
          case (`fileName`, c) =>
            c
        }
    }

    def updateMetadata(
      org: Option[Organization],
      name: Option[ModuleName],
      latest: Option[String],
      release: Option[String],
      addVersions: Seq[String],
      now: Instant
    ): Task[MavenMetadata] =
      xmlOpt match {
        case None =>
          Task.point(this)
        case Some(c) =>
          c.contentTask.map { b =>
            val elem = XML.loadString(new String(b, StandardCharsets.UTF_8))
            val updated = coursier.cli.publish.MavenMetadata.update(
              elem,
              org,
              name,
              latest,
              release,
              addVersions,
              Some(now.atOffset(ZoneOffset.UTC).toLocalDateTime)
            )
            val b0 = coursier.cli.publish.MavenMetadata.print(updated)
              .getBytes(StandardCharsets.UTF_8)
            val c0 = Content.InMemory(now, b0)
            copy(
              files = files.update("maven-metadata.xml", c0)
            )
          }
      }
  }


  /**
    * Identify the [[Group]]s each file of the passed [[FileSet]] correspond to.
    */
  def split(fs: FileSet): Seq[Group] = {

    val byDir = fs.elements.groupBy(_._1.dropLast)

    // FIXME Plenty of unhandled errors here

    byDir.toSeq.map {
      case (dir, elements) =>

        val canBeMavenMetadata =
          elements.exists(_._1.elements.lastOption.contains("maven-metadata.xml")) &&
          !elements.exists(_._1.elements.lastOption.exists(_.endsWith(".pom")))

        dir.elements.reverse match {
          case Seq(ver, strName, reverseOrg @ _*) if reverseOrg.nonEmpty && !canBeMavenMetadata =>
            val org = Organization(reverseOrg.reverse.mkString("."))
            val name = ModuleName(strName)
            val snapshotVersioningOpt =
              if (ver.endsWith("SNAPSHOT"))
                Some(elements.map(_._1.elements.last).filter(_.endsWith(".pom")))
                  .filter(_.nonEmpty)
                  .map(_.minBy(_.length))
                  .filter(_.startsWith(s"${name.value}-"))
                  .map(_.stripPrefix(s"${name.value}-").stripSuffix(".pom"))
                  .filter(_ != ver)
              else
                None
            val fileNamePrefixes = {
              val p = s"${name.value}-${snapshotVersioningOpt.getOrElse(ver)}"
              Set(".", "-").map(p + _)
            }

            def recognized(p: FileSet.Path): Boolean =
              p.elements.lastOption.exists(n => fileNamePrefixes.exists(n.startsWith)) ||
                p.elements.lastOption.contains("maven-metadata.xml") ||
                p.elements.lastOption.exists(_.startsWith("maven-metadata.xml."))

            if (elements.forall(t => recognized(t._1))) {
              val strippedDir = elements.map {
                case (p, c) =>
                  p.elements.last -> c
              }
              Module(org, name, ver, snapshotVersioningOpt, DirContent(strippedDir))
            } else
              ???

          case Seq(strName, reverseOrg @ _*) if reverseOrg.nonEmpty && canBeMavenMetadata =>
            val org = Organization(reverseOrg.reverse.mkString("."))
            val name = ModuleName(strName)

            def recognized(p: FileSet.Path): Boolean =
              p.elements.lastOption.contains("maven-metadata.xml") ||
                p.elements.lastOption.exists(_.startsWith("maven-metadata.xml."))

            if (elements.forall(t => recognized(t._1))) {
              val strippedDir = elements.map {
                case (p, c) =>
                  p.elements.last -> c
              }
              MavenMetadata(org, name, DirContent(strippedDir))
            } else
              ???

          case _ =>
            ???
        }
    }
  }

  /**
    * Merge [[Group]]s as a [[FileSet]].
    *
    * Can be "left" if some duplicated [[Module]]s or [[MavenMetadata]]s are found.
    */
  def merge(groups: Seq[Group]): Either[String, FileSet] = {

    val duplicatedModules = groups
      .collect { case m: Module => m }
      .groupBy(m => (m.organization, m.name, m.version))
      .filter(_._2.lengthCompare(1) > 0)
      .iterator
      .toMap

    val duplicatedMeta = groups
      .collect { case m: MavenMetadata => m }
      .groupBy(m => (m.organization, m.name))
      .filter(_._2.lengthCompare(1) > 0)
      .iterator
      .toMap

    if (duplicatedModules.isEmpty && duplicatedMeta.isEmpty)
      Right(groups.foldLeft(FileSet.empty)(_ ++ _.fileSet))
    else {
      ???
    }
  }

  /**
    * Ensure all [[Module]]s in the passed `groups` have a corresponding [[MavenMetadata]] group.
    *
    * @param now: if new files are created, their last-modified time.
    */
  def addOrUpdateMavenMetadata(groups: Seq[Group], now: Instant): Task[Seq[Group]] = {

    val modules = groups
      .collect { case m: Group.Module => m }
      .groupBy(m => (m.organization, m.name))
    val meta = groups
      .collect { case m: Group.MavenMetadata => m }
      .groupBy(m => (m.organization, m.name))
      .mapValues {
        case Seq(md) => md
        case l => ???
      }
      .iterator
      .toMap

    val a = for ((k @ (org, name), m) <- modules.toSeq) yield {

      val versions = m.map(_.version)
      val latest = versions.map(Version(_)).max.repr
      val releaseOpt = Some(versions.filter(publish.MavenMetadata.isReleaseVersion).map(Version(_)))
        .filter(_.nonEmpty)
        .map(_.max.repr)

      meta.get(k) match {
        case None =>
          val elem = publish.MavenMetadata.create(
            org, name, Some(latest), releaseOpt, versions, now
          )
          val b = publish.MavenMetadata.print(elem).getBytes(StandardCharsets.UTF_8)
          val content = DirContent(Seq(
            "maven-metadata.xml" -> Content.InMemory(now, b)
          ))
          Seq(Task.point(k -> Group.MavenMetadata(org, name, content)))
        case Some(md) =>
          Seq(md.updateMetadata(
            None,
            None,
            Some(latest),
            releaseOpt,
            versions,
            now
          ).map(k -> _))
      }
    }

    Task.gather.gather(a.flatten)
      .map(l => modules.values.toSeq.flatten ++ (meta ++ l.toMap).values.toSeq)
  }

  def downloadMavenMetadata(
    orgNames: Seq[(Organization, ModuleName)],
    upload: Upload,
    repository: MavenRepository,
    logger: Upload.Logger
  ): Task[Seq[MavenMetadata]] = {

    val root = repository.root.stripSuffix("/") + "/"

    Task.gather.gather {
      orgNames.map {
        case (org, name) =>
          val url = root + s"${org.value.split('.').mkString("/")}/${name.value}/maven-metadata.xml"
          upload.downloadIfExists(url, repository.authentication, logger).map(_.map {
            case (lastModifiedOpt, b) =>
              // download and verify checksums too?
              MavenMetadata(
                org,
                name,
                DirContent(
                  Seq(
                    "maven-metadata.xml" -> Content.InMemory(lastModifiedOpt.getOrElse(Instant.EPOCH), b)
                  )
                )
              )
          })
      }
    }.map(_.flatten)
  }

  def mergeMavenMetadata(
    groups: Seq[MavenMetadata],
    now: Instant
  ): Task[Seq[MavenMetadata]] = {

    val tasks = groups
      .groupBy(m => (m.organization, m.name))
      .valuesIterator
      .map { l =>
        val (dontKnow, withContent) = l.partition(_.xmlOpt.isEmpty)

        // dontKnow should be empty anyway…

        val merged = withContent match {
          case Seq() => sys.error("can't possibly happen")
          case Seq(m) => Task.point(m)
          case Seq(m, others @ _*) =>

            m.xmlOpt.get.contentTask.flatMap { b =>
              val mainElem = XML.loadString(new String(b, StandardCharsets.UTF_8))

              others.foldLeft(Task.point(mainElem)) {
                case (mainElemTask, m0) =>
                  for {
                    mainElem0 <- mainElemTask
                    b <- m0.xmlOpt.get.contentTask
                  } yield {
                    val elem = XML.loadString(new String(b, StandardCharsets.UTF_8))
                    val info = publish.MavenMetadata.info(elem)
                    publish.MavenMetadata.update(
                      mainElem0,
                      None,
                      None,
                      info.latest,
                      info.release,
                      info.versions,
                      info.lastUpdated
                    )
                  }
              }.map { elem =>
                val b = publish.MavenMetadata.print(elem).getBytes(StandardCharsets.UTF_8)
                m.copy(
                  files = m.files.update("maven-metadata.xml", Content.InMemory(now, b))
                )
              }
            }
        }

        merged.map(dontKnow :+ _)
      }
      .toSeq

    Task.gather.gather(tasks).map(_.flatten)
  }


}
