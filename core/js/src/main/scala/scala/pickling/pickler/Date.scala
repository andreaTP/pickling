package scala.pickling
package pickler

import java.util.Date

trait DatePicklers extends PrimitivePicklers {
  implicit val datePickler: Pickler[Date] with Unpickler[Date] =
  new AbstractPicklerUnpickler[Date] with AutoRegister[Date] {
    lazy val tag = FastTypeTag[Date]("java.util.Date")
    def pickle(picklee: Date, builder: PBuilder): Unit = {
      builder.beginEntry(picklee, tag)

      builder.putField("value", b => {
        b.hintElidedType(implicitly[FastTypeTag[String]])
        stringPickler.pickle(picklee.toString, b)
      })

      builder.endEntry()
    }
    def unpickle(tag: String, reader: PReader): Any = {
      val reader1 = reader.readField("value")
      reader1.hintElidedType(implicitly[FastTypeTag[String]])
      val result = stringPickler.unpickleEntry(reader1)
      new Date(result.asInstanceOf[String])
    }
  }
  internal.currentRuntime.picklers.registerPicklerUnpickler(datePickler)
}
