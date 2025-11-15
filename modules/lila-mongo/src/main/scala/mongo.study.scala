package lila.search
package ingestor

import cats.effect.IO
import cats.syntax.all.*
import mongo4cats.database.MongoDatabase
import org.typelevel.log4cats.syntax.*
import org.typelevel.log4cats.{ Logger, LoggerFactory }

import java.time.Instant

import Repo.*

object StudyRepo:

  def apply(
      study: MongoDatabase[IO],
      local: MongoDatabase[IO],
      config: IngestorConfig.Study
  )(using LoggerFactory[IO]): IO[Repo[(DbStudy, StudyChapterData)]] =
    given Logger[IO] = LoggerFactory[IO].getLogger
    (Study2Repo(study, local, config), ChapterRepo(study))
      .mapN(apply)

  def apply(
      study2: Repo[DbStudy],
      chapters: ChapterRepo
  )(using Logger[IO]): Repo[(DbStudy, StudyChapterData)] = new:

    def watch(since: Option[Instant]): fs2.Stream[IO, Result[(DbStudy, StudyChapterData)]] =
      study2.watch(since).evalMap(enrichWithChapters)

    def fetch(since: Instant, until: Instant): fs2.Stream[IO, Result[(DbStudy, StudyChapterData)]] =
      study2.fetch(since, until).evalMap(enrichWithChapters)

    private def enrichWithChapters(result: Result[DbStudy]): IO[Result[(DbStudy, StudyChapterData)]] =
      val studyIds = result.toIndex.map(_.id).distinct
      chapters
        .byStudyIds(studyIds)
        .flatMap: chapterMap =>
          result.toIndex
            .traverseFilter(_.toData(chapterMap))
            .map: enriched =>
              Result(
                enriched,
                result.toDelete,
                // result.toUpdate,
                result.timestamp
              )

    extension (study: DbStudy)
      private def toData(
          chapterMap: Map[String, StudyChapterData]
      ): IO[Option[(DbStudy, StudyChapterData)]] =
        chapterMap
          .get(study.id)
          .map(data => (study, data))
          .pure[IO]
          .flatTap: data =>
            def reason =
              if chapterMap.contains(study.id) then "" else "missing chapter data; "
            info"failed to prepare study data for ${study.id}: $reason".whenA(data.isEmpty)
