package net.scalytica.clammyscan

import java.net.URLDecoder.decode

import akka.actor.ActorSystem
import akka.stream._
import akka.stream.scaladsl._
import akka.util.ByteString
import com.google.inject.Inject
import play.api.libs.Files.TemporaryFile
import play.api.libs.json.Json
import play.api.libs.streams.Accumulator
import play.api.mvc.BodyParsers.parse._
import play.api.mvc._
import play.api.{Configuration, Logger}
import play.core.parsers.Multipart.FileInfo

import scala.concurrent.{ExecutionContext, Future}

/**
 * Enables streaming upload of files/attachments with custom metadata to GridFS
 */
trait ClammyScan {

  val system: ActorSystem
  val materializer: Materializer
  val clamConfig: ClamConfig

  /**
   *
   * @param save
   * @param remove
   * @param ec
   * @tparam A
   * @return
   */
  def scan[A](
    save: (String, Option[String]) => SaveSink[A],
    remove: A => Unit
  )(implicit ec: ExecutionContext): ClamParser[A]

  /**
   * Scans file for virus and buffers to a temporary file. Temp file is
   * removed if file is infected.
   */
  def scanWithTmpFile(implicit e: ExecutionContext): ClamParser[TemporaryFile]

  /**
   * Mostly for convenience this. If you need a service for just scanning
   * a file for infections, this is it.
   */
  def scanOnly(implicit ec: ExecutionContext): ClamParser[Unit]

}

class ClammyScanParser @Inject() (
  sys: ActorSystem,
  mat: Materializer,
  config: Configuration
) extends ClammyScan {

  implicit val system: ActorSystem = sys
  implicit val materializer: Materializer = mat

  val clamConfig = new ClamConfig(config)

  import clamConfig._

  val cbpLogger = Logger(classOf[ClammyScanParser])

  /**
   * Sets up a `ClamSink` that is ready to receive the incoming stream. Or one
   * that is cancelled with success status.
   *
   * Controlled by the config property `clammyscan.scanDisabled`.
   */
  private[this] def clammySink(
    filename: String
  )(implicit ec: ExecutionContext) = {
    if (!scanDisabled) {
      ClamIO(host, port, timeout).scan(filename)
    } else {
      // Scanning disabled
      cbpLogger.info(s"Scanning is disabled. $filename will not be scanned")
      ClamIO.cancelled(Right(FileOk()))
    }
  }

  /**
   *
   */
  def broadcastGraph[A](
    c: ClamSink,
    s: SaveSink[A]
  )(implicit e: ExecutionContext): Sink[ByteString, Future[TupledResponse[A]]] =
    Sink.fromGraph[ByteString, (Future[ClamResponse], Future[Option[A]])] {
      GraphDSL.create(c, s)((cs, ss) => (cs, ss)) { implicit b => (cs, ss) =>
        import GraphDSL.Implicits._

        val bro = b.add(Broadcast[ByteString](2))
        bro ~> cs
        bro ~> ss

        SinkShape(bro.in)
      }
    }.mapMaterializedValue { mat =>
      for {
        cr <- mat._1.recover {
          case ClammyException(err) => Left(err)
          case stex: StreamTcpException => Left(ScanError(stex.getMessage))
          case ex =>
            cbpLogger.error("", ex)
            Left(ScanError(unhandledException))
        }
        sr <- mat._2
      } yield (cr, sr)
    }

  /**
   *
   */
  def sinks[A](filename: String, contentType: Option[String])(
    save: (String, Option[String]) => SaveSink[A]
  )(implicit ec: ExecutionContext): (ClamSink, SaveSink[A]) =
    if (fileNameValid(filename)) {
      (clammySink(filename), save(filename, contentType))
    } else {
      val errSave = Sink.cancelled[ByteString].mapMaterializedValue { _ =>
        Future.successful(None)
      }
      val errScan = ClamIO.cancelled(Left(
        InvalidFilename(s"Filename $filename contains illegal characters")
      ))

      (errScan, errSave)
    }

  /**
   *
   * @param save
   * @param remove
   * @param ec
   * @tparam A
   * @return
   */
  def scan[A](
    save: (String, Option[String]) => SaveSink[A],
    remove: A => Unit
  )(implicit ec: ExecutionContext): ClamParser[A] = {
    multipartFormData[TupledResponse[A]] {
      case FileInfo(partName, filename, contentType) =>
        val theSinks = sinks(filename, contentType)(save)
        val comb = broadcastGraph(theSinks._1, theSinks._2)

        Accumulator(comb).map { ref =>
          MultipartFormData.FilePart(partName, filename, contentType, ref)
        }

    }.validateM((data: MultipartFormData[TupledResponse[A]]) => {
      data.files.headOption.map(hf => hf.ref._1 match {
        case Left(err) =>
          // Ooops...there seems to be a problem with the clamd scan result.
          val maybeFile = data.files.headOption.flatMap(_.ref._2)
          handleError(data, err) {
            maybeFile.foreach(f => remove(f))
          }
        case Right(ok) =>
          Future.successful(Right(data))

      }).getOrElse {
        Future.successful {
          Left(Results.BadRequest(Json.obj(
            "message" -> "Unable to locate any files after scan result"
          )))
        }
      }
    })
  }

  def scanWithTmpFile(implicit e: ExecutionContext): ClamParser[TemporaryFile] =
    scan[TemporaryFile](
      save = {
      (fname, ctype) =>
        val tempFile = TemporaryFile("multipartBody", "scanWithTempFile")
        FileIO.toFile(tempFile.file).mapMaterializedValue { fio =>
          fio.map(_ => Option(tempFile))
        }
    },
      remove = tmpFile => tmpFile.clean()
    )

  def scanOnly(implicit ec: ExecutionContext): ClamParser[Unit] =
    scan[Unit](
      save = (f, c) =>
      Sink.cancelled[ByteString].mapMaterializedValue { notUsed =>
        Future.successful(None)
      },
      remove = _ => cbpLogger.debug("Only scanning, no file to remove")
    )

  /**
   * Function specifically for handling the ClamError cases in the
   * validation step. The logic here is highly dependent on how the
   * parser is configured.
   */
  private def handleError[A](
    fud: MultipartFormData[TupledResponse[A]],
    err: ClamError
  )(remove: => Unit)(
    implicit
    ec: ExecutionContext
  ): Future[Either[Result, MultipartFormData[TupledResponse[A]]]] = {
    Future.successful {
      err match {
        case vf: VirusFound =>
          // We have encountered the dreaded VIRUS...run awaaaaay
          if (canRemoveInfectedFiles) {
            remove
            Left(Results.NotAcceptable(
              Json.obj("message" -> vf.message)
            ))
          } else {
            // We cannot remove the uploaded file, so we return the parsed
            // result back to the controller to let it handle it.
            Right(fud)
          }

        case clamError =>
          if (canRemoveOnError) remove
          if (shouldFailOnError) {
            Left(Results.BadRequest(
              Json.obj("message" -> clamError.message)
            ))
          } else {
            Right(fud)
          }
      }
    }
  }

  /**
   * Will validate the filename based on the configured regular expression
   * defined in application.conf.
   */
  private[this] def fileNameValid(filename: String): Boolean =
    validFilenameRegex.forall(regex =>
      regex.r.findFirstMatchIn(decode(filename, Codec.utf_8.charset)) match {
        case Some(m) => false
        case _ => true
      })

}