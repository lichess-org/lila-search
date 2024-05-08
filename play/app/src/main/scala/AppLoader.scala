import play.api._
import play.api.routing.Router
import lila.search.ESClient
import com.sksamuel.elastic4s.http.JavaClient
import com.sksamuel.elastic4s.{ ElasticClient, ElasticProperties }

class AppLoader extends ApplicationLoader {
  private var components: AppComponents = _

  def load(context: ApplicationLoader.Context): Application = {
    components = new AppComponents(context)
    components.application
  }
}

class AppComponents(context: ApplicationLoader.Context) extends BuiltInComponentsFromContext(context) {

  def httpFilters = Nil

  lazy val client = ESClient.makeFuture({

    val c = ElasticClient(JavaClient(ElasticProperties(configuration.get[String]("elasticsearch.uri"))))

    context.lifecycle.addStopHook(() =>
      scala.concurrent.Future {
        play.api.Logger("search").info("closing now!")
        c.close()
        Thread.sleep(1000)
      }
    )

    c
  })

  lazy val homeController = new _root_.controllers.WebApi(
    controllerComponents,
    client
  )

  lazy val router: Router = new _root_.router.Routes(httpErrorHandler, homeController)
}
