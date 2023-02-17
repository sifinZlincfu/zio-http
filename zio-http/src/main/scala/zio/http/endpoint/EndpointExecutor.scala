package zio.http.endpoint

import zio._
import zio.http._
import zio.http.endpoint.internal.EndpointClient
import zio.stacktracer.TracingImplicits.disableAutoTrace
import zio.http.codec.Alternator // scalafix:ok;

/**
 * A [[zio.http.endpoint.EndpointExecutor]] is responsible for taking an
 * endpoint invocation, and executing the invocation, returning the final
 * result, or failing with a pre-defined RPC error.
 */
final case class EndpointExecutor[+MI](
  client: Client,
  locator: EndpointLocator,
  middlewareInput: UIO[MI],
) {
  private val metadata = {
    implicit val trace0 = Trace.empty
    zio.http.endpoint.internal
      .MemoizedZIO[Endpoint[_, _, _, _ <: EndpointMiddleware], EndpointError, EndpointClient[Any, Any, Any, _]] {
        (api: Endpoint[_, _, _, _ <: EndpointMiddleware]) =>
          locator.locate(api).map { location =>
            EndpointClient(
              location,
              api.asInstanceOf[Endpoint[Any, Any, Any, _ <: EndpointMiddleware]],
            )
          }
      }
  }

  private def getClient[I, E, O, M <: EndpointMiddleware](
    endpoint: Endpoint[I, E, O, M],
  )(implicit trace: Trace): IO[EndpointError, EndpointClient[I, E, O, M]] =
    metadata.get(endpoint).map(_.asInstanceOf[EndpointClient[I, E, O, M]])

  def apply[A, E, B, M <: EndpointMiddleware](
    invocation: Invocation[A, E, B, M],
  )(implicit
    alt: Alternator[E, invocation.middleware.Err],
    ev: MI <:< invocation.middleware.In,
    trace: Trace,
  ): ZIO[Any, alt.Out, B] = {
    middlewareInput.flatMap { mi =>
      getClient(invocation.endpoint).orDie.flatMap { endpointClient =>
        endpointClient.execute(client, invocation)(ev(mi))
      }
    }
  }
}
object EndpointExecutor {
  final case class Config(url: URL)
  object Config {
    import zio.{Config => ZConfig}
    val config: ZConfig[Config] =
      ZConfig
        .uri("url")
        .map { uri =>
          URL
            .fromString(uri.toString())
            .getOrElse(throw new RuntimeException(s"Illegal format of URI ${uri} for endpoint executor configuration"))
        }
        .map(Config(_))
  }

  def make(serviceName: String)(implicit trace: Trace): ZLayer[Client, zio.Config.Error, EndpointExecutor[Unit]] =
    ZLayer {
      for {
        client <- ZIO.service[Client]
        config <- ZIO.config(Config.config.nested(serviceName))
      } yield EndpointExecutor(client, EndpointLocator.fromURL(config.url), ZIO.unit)
    }
}
