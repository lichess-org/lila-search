import play.api._
import play.api.routing.Router

class MyApplicationLoader extends ApplicationLoader {
  private var components: MyComponents = _

  def load(context: ApplicationLoader.Context): Application = {
    components = new MyComponents(context)
    components.application
  }
}

class MyComponents(context: ApplicationLoader.Context)
  extends BuiltInComponentsFromContext(context)
  with play.filters.HttpFiltersComponents
  with _root_.controllers.AssetsComponents {

  lazy val client = ESConnect(
    actorSystem,
    context.lifecycle,
    configuration
  )

  lazy val homeController = new _root_.controllers.WebApi(
    controllerComponents,
    client
  )

  lazy val router: Router = new _root_.router.Routes(httpErrorHandler, homeController)
}
