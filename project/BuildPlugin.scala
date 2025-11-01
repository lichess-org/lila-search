import sbt.*, Keys.*
import sbt.ScriptedPlugin.autoImport.*
import sbtrelease.ReleasePlugin, ReleasePlugin.autoImport.*, ReleaseTransformations.*, ReleaseKeys.*
import sbt.ScriptedPlugin.autoImport.*

import sbt.plugins.{ JvmPlugin, SbtPlugin }

object BuildPlugin extends AutoPlugin {
  override def trigger = allRequirements

  override def requires = ReleasePlugin

  override lazy val projectSettings = releaseSettings

  def releaseSettings: Seq[Setting[?]] =
    Seq(
      releaseProcess := Seq[ReleaseStep](
        checkSnapshotDependencies,
        inquireVersions,
        runClean,
        runTest,
        setReleaseVersion,
        commitReleaseVersion,
        tagRelease,
        setNextVersion,
        commitNextVersion,
        pushChanges
      )
    )
}
