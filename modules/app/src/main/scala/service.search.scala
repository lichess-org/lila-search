package lila.search
package app

import cats.effect.*
import lila.search.spec.*
import org.typelevel.log4cats.Logger
import org.typelevel.log4cats.syntax.*
import forum.ForumQuery.*
import io.github.arainko.ducktape.*

class SearchServiceImpl(esClient: ESClient[IO])(using Logger[IO]) extends SearchService[IO]:

  override def countForum(text: String, troll: Boolean): IO[CountResponse] =
    esClient
      .count(Index("forum"), Forum(text, troll))
      .map(_.to[CountResponse])
      .handleErrorWith: e =>
        error"Error in countForum: text=$text, troll=$troll" *>
          IO.raiseError(InternalServerError("Internal server error"))

  override def searchForum(body: ForumInputBody, from: Int, size: Int): IO[SearchResponse] =
    esClient
      .search(Index("forum"), Forum(body.text, body.troll), From(from), Size(size))
      .map(_.to[SearchResponse])
      .handleErrorWith: e =>
        error"Error in searchForum: body=$body, from=$from, size=$size" *>
          IO.raiseError(InternalServerError("Internal server error"))
