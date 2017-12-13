import play.api._
import play.api.routing.Router

class AppLoader extends ApplicationLoader {
  private var components: AppComponents = _

  def load(context: ApplicationLoader.Context): Application = {
    components = new AppComponents(context)
    components.application
  }
}

class AppComponents(context: ApplicationLoader.Context)
  extends BuiltInComponentsFromContext(context) {

  def httpFilters = Nil

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
