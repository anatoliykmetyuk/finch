package io.finch.sse

import java.nio.charset.Charset

import cats.Show
import com.twitter.concurrent.AsyncStream
import com.twitter.io.{Charsets, ConcatBuf}
import io.finch.internal.BufText
import org.scalacheck.{Arbitrary, Gen}
import org.scalacheck.Gen.Choose
import org.scalacheck.Prop.BooleanOperators
import org.scalatest.{FlatSpec, Matchers}
import org.scalatest.prop.Checkers

class ServerSentEventSpec extends FlatSpec with Matchers with Checkers {
  behavior of "ServerSentEvent"

  import ServerSentEvent._

  def dataOnlySse: Gen[ServerSentEvent[String]] = for {
    data  <- Gen.alphaStr
  } yield ServerSentEvent(data)

  def sseWithId: Gen[ServerSentEvent[String]] = for {
    sse <- dataOnlySse
    id <- Gen.alphaStr
  } yield sse.copy(id = Some(id))

  def sseWithEventType: Gen[ServerSentEvent[String]] = for {
    sse <- dataOnlySse
    eventType <- Gen.alphaStr
  } yield sse.copy(event = Some(eventType))

  def sseWithRetry: Gen[ServerSentEvent[String]] = for {
    sse <- dataOnlySse
    retry <- Choose.chooseLong.choose(-1000, 1000)
  } yield sse.copy(retry = Some(retry))

  def streamDataOnlyGenerator: Gen[AsyncStream[ServerSentEvent[String]]] = for {
    strs <- Gen.nonEmptyListOf(dataOnlySse)
  } yield AsyncStream.fromSeq(strs)

  def genCharset: Gen[Charset] = Gen.oneOf(
    Charsets.UsAscii, Charsets.Utf8, Charsets.Utf16BE,
    Charsets.Utf16, Charsets.Iso8859_1, Charsets.Utf16LE
  )

  implicit def arbitrarySse: Arbitrary[AsyncStream[ServerSentEvent[String]]] = Arbitrary(streamDataOnlyGenerator)

  implicit def arbitraryCharset: Arbitrary[Charset] = Arbitrary(genCharset)

  val encoder = encodeEventStream[String](Show.fromToString)

  it should "encode the event when only 'data' is present" in {
    implicit def arbitraryEvents: Arbitrary[ServerSentEvent[String]] = Arbitrary(dataOnlySse)

    check { (event: ServerSentEvent[String], cs: Charset) =>
      val encoded = encoder(event, cs)
      val expected = ConcatBuf(Vector(BufText("data:", cs), BufText(event.data, cs), BufText("\n", cs)))
      encoded === expected
    }
  }

  it should "encode the event when an 'eventType' is present" in {
    implicit def arbitraryEvents: Arbitrary[ServerSentEvent[String]] = Arbitrary(sseWithEventType)

    check { (event: ServerSentEvent[String], cs: Charset) =>
      (event.event.isDefined && event.id.isEmpty && event.retry.isEmpty) ==> {
        val encoded = encoder(event, cs)
        val actualText = BufText.extract(encoded, cs)
        val expectedParts = ConcatBuf(Vector(
          BufText("data:", cs), BufText(event.data, cs), BufText("\n", cs), BufText(s"event:${event.event.get}\n", cs)
        ))
        actualText === BufText.extract(expectedParts, cs)
      }
    }
  }

  it should "encode the event when an 'id' is present" in {
    implicit def arbitraryEvents: Arbitrary[ServerSentEvent[String]] = Arbitrary(sseWithId)

    check { (event: ServerSentEvent[String], cs: Charset) =>
      val encoded = encodeEventStream[String](Show.fromToString).apply(event, cs)
      (event.event.isEmpty && event.id.isDefined && event.retry.isEmpty) ==> {
        val encoded = encoder(event, cs)
        val actualText = BufText.extract(encoded, cs)
        val expectedParts = ConcatBuf(Vector(
          BufText("data:", cs), BufText(event.data, cs), BufText("\n", cs), BufText(s"id:${event.id.get}\n", cs)
        ))
        actualText === BufText.extract(expectedParts, cs)
      }
    }
  }

  it should "encode the event when a 'retry' is present" in {
    implicit def arbitraryEvents: Arbitrary[ServerSentEvent[String]] = Arbitrary(sseWithRetry)

    check { (event: ServerSentEvent[String], cs: Charset) =>
      val encoded = encodeEventStream[String](Show.fromToString).apply(event, cs)
      (event.event.isEmpty && event.id.isEmpty && event.retry.isDefined) ==> {
        val encoded = encoder(event, cs)
        val actualText = BufText.extract(encoded, cs)
        val expectedParts = ConcatBuf(Vector(
          BufText("data:", cs), BufText(event.data, cs), BufText("\n", cs), BufText(s"retry:${event.retry.get}\n", cs)
        ))
        actualText === BufText.extract(expectedParts, cs)
      }
    }
  }
}
