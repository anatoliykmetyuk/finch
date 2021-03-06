package io.finch

import cats.Show
import com.twitter.io.Buf
import io.finch.internal.BufText
import java.nio.charset.Charset

/**
 * Encodes an HTTP payload (represented as an arbitrary type `A`) with a given [[Charset]].
 */
trait Encode[A] {
  type ContentType <: String

  def apply(a: A, cs: Charset): Buf
}

trait LowPriorityEncodeInstances {

  type Aux[A, CT <: String] = Encode[A] { type ContentType = CT }

  type Json[A] = Aux[A, Application.Json]
  type Text[A] = Aux[A, Text.Plain]

  final def instance[A, CT <: String](fn: (A, Charset) => Buf): Aux[A, CT] =
    new Encode[A] {
      type ContentType = CT
      def apply(a: A, cs: Charset): Buf = fn(a, cs)
    }

  final def json[A](fn: (A, Charset) => Buf): Json[A] =
    instance[A, Application.Json](fn)

  final def text[A](fn: (A, Charset) => Buf): Text[A] =
    instance[A, Text.Plain](fn)

  implicit def encodeShowAsTextPlain[A](implicit s: Show[A]): Text[A] =
    text((a, cs) => BufText(s.show(a), cs))
}

trait HighPriorityEncodeInstances extends LowPriorityEncodeInstances {

  private[this] final val anyToEmptyBuf: Aux[Any, Nothing] =
    instance[Any, Nothing]((_, _) => Buf.Empty)

  private[this] final val bufToBuf: Aux[Buf, Nothing] =
    instance[Buf, Nothing]((buf, _) => buf)

  implicit def encodeUnit[CT <: String]: Aux[Unit, CT] =
    anyToEmptyBuf.asInstanceOf[Aux[Unit, CT]]

  implicit def encodeException[CT <: String]: Aux[Exception, CT] =
    anyToEmptyBuf.asInstanceOf[Aux[Exception, CT]]

  implicit def encodeBuf[CT <: String]: Aux[Buf, CT] =
    bufToBuf.asInstanceOf[Aux[Buf, CT]]

  implicit def encodeEither[A, B, CT <: String](implicit
    ae: Encode.Aux[A, CT],
    be: Encode.Aux[B, CT]
  ): Aux[Either[A, B], CT] = instance[Either[A, B], CT](
    (either, cs) => either.fold(a => ae(a, cs), b => be(b, cs))
  )
}

object Encode extends HighPriorityEncodeInstances {

  /**
   * Returns a [[Encode]] instance for a given type (with required content type).
   */
  @inline final def apply[A, CT <: String](implicit e: Aux[A, CT]): Aux[A, CT] = e

  implicit val encodeExceptionAsTextPlain: Text[Exception] =
    text((e, cs) => BufText(Option(e.getMessage).getOrElse(""), cs))

  implicit val encodeStringAsTextPlain: Text[String] =
    text((s, cs) => BufText(s, cs))
}
