package org.bitcoins.server.routes

import akka.actor.ActorSystem
import akka.event.Logging
import akka.http.scaladsl._
import akka.http.scaladsl.model._
import akka.http.scaladsl.server.Directives._
import akka.http.scaladsl.server._
import akka.http.scaladsl.server.directives.DebuggingDirectives
import de.heikoseeberger.akkahttpupickle.UpickleSupport._
import org.bitcoins.commons.config.AppConfig
import upickle.{default => up}

import scala.concurrent.Future

case class Server(
    conf: AppConfig,
    handlers: Seq[ServerRoute],
    rpcbindOpt: Option[String],
    rpcport: Int)(implicit system: ActorSystem)
    extends HttpLogger {

  import system.dispatcher

  /** Handles all server commands by throwing a MethodNotFound */
  private val catchAllHandler: PartialFunction[ServerCommand, StandardRoute] = {
    case ServerCommand(name, _) => throw HttpError.MethodNotFound(name)
  }

  /** HTTP directive that handles both exceptions and rejections */
  private def withErrorHandling(route: Route): Route = {

    val rejectionHandler =
      RejectionHandler
        .newBuilder()
        .handleNotFound {
          complete {
            Server.httpError(
              """Resource not found. Hint: all RPC calls are made against root ('/')""",
              StatusCodes.BadRequest)
          }
        }
        .result()

    val exceptionHandler = ExceptionHandler {
      case HttpError.MethodNotFound(method) =>
        complete(
          Server.httpError(s"'$method' is not a valid method",
                           StatusCodes.BadRequest))
      case err: Throwable =>
        logger.info(s"Unhandled error in server:", err)
        complete(Server.httpError(s"Request failed: ${err.getMessage}"))
    }

    handleRejections(rejectionHandler) {
      handleExceptions(exceptionHandler) {
        route
      }
    }
  }

  val route: Route =
    // TODO implement better logging
    DebuggingDirectives.logRequestResult(
      ("http-rpc-server", Logging.DebugLevel)) {
      withErrorHandling {
        pathSingleSlash {
          post {
            entity(as[ServerCommand]) { cmd =>
              val init = PartialFunction.empty[ServerCommand, Route]
              val handler = handlers.foldLeft(init) { case (accum, curr) =>
                accum.orElse(curr.handleCommand)
              }
              handler.orElse(catchAllHandler).apply(cmd)
            }
          }
        }
      }
    }

  def start(): Future[Http.ServerBinding] = {
    val httpFut =
      Http()
        .newServerAt(rpcbindOpt.getOrElse("localhost"), rpcport)
        .bindFlow(route)
    httpFut.foreach { http =>
      logger.info(s"Started Bitcoin-S HTTP server at ${http.localAddress}")
    }
    httpFut
  }
}

object Server {

  // TODO id parameter
  case class Response(
      result: Option[ujson.Value] = None,
      error: Option[String] = None) {

    def toJsonMap: Map[String, ujson.Value] = {
      Map(
        "result" -> (result match {
          case None      => ujson.Null
          case Some(res) => res
        }),
        "error" -> (error match {
          case None      => ujson.Null
          case Some(err) => err
        })
      )
    }
  }

  /** Creates a HTTP response with the given body as a JSON response */
  def httpSuccess[T](body: T)(implicit
      writer: up.Writer[T]): HttpEntity.Strict = {
    val response = Response(result = Some(up.writeJs(body)))
    HttpEntity(
      ContentTypes.`application/json`,
      up.write(response.toJsonMap)
    )
  }

  def httpError(
      msg: String,
      status: StatusCode = StatusCodes.InternalServerError): HttpResponse = {

    val entity = {
      val response = Response(error = Some(msg))
      HttpEntity(
        ContentTypes.`application/json`,
        up.write(response.toJsonMap)
      )
    }

    HttpResponse(status = status, entity = entity)
  }
}
