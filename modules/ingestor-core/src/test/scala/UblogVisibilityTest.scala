package lila.search
package ingestor

import weaver.*

object UblogVisibilityTest extends FunSuite:

  private def post(
      live: Boolean = true,
      tier: Option[Int] = Some(2),
      enabled: Boolean = true,
      quality: Option[Int] = Some(1)
  ) =
    DbUblog(
      id = "post",
      title = "title",
      intro = "intro",
      markdown = "markdown",
      blog = "user:author",
      language = "en",
      likes = 0,
      topics = Nil,
      live = live,
      livedAt = None,
      quality = quality,
      blogTier = tier,
      userEnabled = enabled
    )

  test("a live post in a visible blog by an enabled user is visible"):
    expect(post().visible)

  test("a hidden blog tier hides the post"):
    expect(!post(tier = Some(0)).visible)

  test("a disabled user hides the post"):
    expect(!post(enabled = false).visible)

  test("a missing blog tier hides the post"):
    expect(!post(tier = None).visible)

  test("a draft hides the post"):
    expect(!post(live = false).visible)

  test("a spam post hides the post"):
    expect(!post(quality = Some(0)).visible)
