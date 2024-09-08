import sbt._, Keys._
import sbt.ScriptedPlugin.autoImport._
import sbtrelease.ReleasePlugin, ReleasePlugin.autoImport._, ReleaseTransformations._, ReleaseKeys._
import sbt.ScriptedPlugin.autoImport._

import sbt.plugins.{JvmPlugin, SbtPlugin}

object BuildPlugin extends AutoPlugin {
  override def trigger = allRequirements

  override def requires = ReleasePlugin

  override lazy val projectSettings = releaseSettings

  def releaseSettings: Seq[Setting[_]] =
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
