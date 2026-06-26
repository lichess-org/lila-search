excludeDependencies ++= Seq(
  ExclusionRule("org.scala-lang.modules", "scala-collection-compat_2.13"),
  ExclusionRule("org.scala-lang.modules", "scala-xml_2.13")
)

addSbtPlugin("ch.epfl.scala" % "sbt-scalafix" % "0.14.7")

addSbtPlugin("com.disneystreaming.smithy4s" % "smithy4s-sbt-codegen" % "0.19.7")

addSbtPlugin("com.eed3si9n" % "sbt-buildinfo" % "0.13.1")

addSbtPlugin("com.github.sbt" % "sbt-git" % "2.1.0")

addSbtPlugin("com.github.sbt" % "sbt-native-packager" % "1.11.7")

addSbtPlugin("com.github.sbt" % "sbt-release" % "1.5.0")

addSbtPlugin("org.polyvariant" % "smithy-scala-tools-sbt" % "0.3.1")

addSbtPlugin("com.siriusxm" % "sbt-snapshot4s" % "0.2.10")

addSbtPlugin("org.scalameta" % "sbt-scalafmt" % "2.6.1")

addSbtPlugin("org.typelevel" % "sbt-tpolecat" % "0.5.7")
