package scala.pickling.javafieldfail

object FakeByte {
  final val serialVersionUID = 1L
}

final class FakeByte(val value: Byte) {

  def byteValue() = value

}
