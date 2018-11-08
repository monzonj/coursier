package coursier.cli.publish.signing

import java.nio.charset.StandardCharsets
import java.time.Instant

import coursier.cli.publish.Content
import coursier.cli.publish.fileset.FileSet
import coursier.util.Task

/**
  * Signs artifacts.
  */
trait Signer {

  /**
    * Computes the signature of the passed `content`.
    *
    * @return an error message (left), or the signature file content (right), wrapped in [[Task]]
    */
  def sign(content: Content): Task[Either[String, String]]

  /**
    * Adds missing signatures in a [[FileSet]].
    *
    * @param fileSet: [[FileSet]] to add signatures to - can optionally contain some already calculated signatures
    * @param now: last modified time for the added signature files
    * @return a [[FileSet]] of the missing signature files
    */
  def signatures(
    fileSet: FileSet,
    now: Instant,
    dontSignExtensions: Set[String],
    dontSignFiles: Set[String],
    logger: SignerLogger
  ): Task[Either[(FileSet.Path, Content, String), FileSet]] = {

    val elementsOrSignatures = fileSet.elements.flatMap {
      case (path, content) =>
        if (path.elements.lastOption.exists(n => n.endsWith(".asc")))
          // found a signature
          Seq(Right(path.mapLast(_.stripSuffix(".asc"))))
        else if (path.elements.lastOption.exists(n => n.contains(".asc.")))
          // FIXME May not be ok if e.g. version contains .asc., like 2.0.asc.1 (this is unlikely though)
          // ignored file
          Nil
        else
          // may need to be signed
          Seq(Left((path, content)))
    }

    val signed = elementsOrSignatures
      .collect {
        case Right(path) => path
      }
      .toSet

    val toSign = elementsOrSignatures
      .collect {
        case Left((path, content))
          if !signed(path) &&
            !path.elements.lastOption.exists(n => dontSignExtensions.exists(e => n.endsWith("." + e))) &&
            !path.elements.lastOption.exists(n => dontSignFiles(n) || dontSignFiles.exists(f => n.startsWith(f + "."))) =>
          (path, content)
      }

    def signaturesTask(id: Object) =
      toSign.foldLeft(Task.point[Either[(FileSet.Path, Content, String), List[(FileSet.Path, Content)]]](Right(Nil))) {
        case (acc, (path, content)) =>
          for {
            previous <- acc
            res <- {
              previous match {
                case l @ Left(_) => Task.point(l)
                case Right(l) =>
                  val doSign = sign(content).map {
                    case Left(e) =>
                      Left((path, content, e))
                    case Right(s) =>
                      Right((path.mapLast(_ + ".asc"), Content.InMemory(now, s.getBytes(StandardCharsets.UTF_8))) :: l)
                  }

                  for {
                    _ <- Task.delay(logger.signingElement(id, path))
                    a <- doSign.attempt
                    // FIXME Left case of doSign not passed as error here
                    _ <- Task.delay(logger.signedElement(id, path, a.left.toOption))
                    res <- Task.fromEither(a)
                  } yield res
              }
            }
          } yield res
      }.map(_.right.map { elements =>
        FileSet(elements.reverse)
      })

    val toSignFs = FileSet(toSign)

    if (toSignFs.isEmpty)
      Task.point(Right(FileSet.empty))
    else {
      val before = Task.delay {
        val id = new Object
        logger.start()
        logger.signing(id, toSignFs)
        id
      }

      def after(id: Object) = Task.delay {
        logger.signed(id, toSignFs)
        logger.stop()
      }

      for {
        id <- before
        a <- signaturesTask(id).attempt
        _ <- after(id)
        res <- Task.fromEither(a)
      } yield res
    }
  }

}
