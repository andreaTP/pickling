package scala.pickling

import scala.pickling.internal._
import scala.language.existentials
import scala.language.implicitConversions

/*

This is a not working and dummy implementation just to keep things compile 'till now

*/


package object json extends JsonFormats

package json {

  import scala.pickling.PicklingErrors.{FieldNotFound, LogicPicklingError, JsonParseFailed, BasePicklingException}
  import scala.scalajs.js
  import scala.collection.mutable.{StringBuilder, Stack}

  trait JsonFormats {
    implicit val pickleFormat: JSONPickleFormat = new JSONPickleFormat
    implicit def toJSONPickle(value: String): JSONPickle = JSONPickle(value)
    implicit def jsonPickleToUnpickleOps(value: String): UnpickleOps = new UnpickleOps(JSONPickle(value))   
  }

  case class JSONPickle(value: String) extends Pickle {
    type ValueType = String
    type PickleFormatType = JSONPickleFormat
  }

  class JSONPickleFormat extends PickleFormat {
    type PickleType = JSONPickle
    type OutputType = Output[String]
    def createBuilder() = new JSONPickleBuilder(this, new StringOutput)
    def createBuilder(out: Output[String]): PBuilder = new JSONPickleBuilder(this, out)
    def createReader(pickle: JSONPickle) = {
      // TODO - Raw strings, null, etc. should be valid JSON.
      if(pickle.value == "null") new JSONPickleReader(null, this)
      else {
        val raw = js.JSON.parse(pickle.value)
        if (!js.isUndefined(raw))
          new JSONPickleReader(raw, this)
        else
          throw JsonParseFailed(pickle.value)
      }
    }
  }

  class JSONPickleBuilder(format: JSONPickleFormat, buf: Output[String]) extends PBuilder with PickleTools {
    // private val buf = new StringBuilder()
    private var nindent = 0
    private def indent() = nindent += 1
    private def unindent() = nindent -= 1
    private var pendingIndent = false
    private var lastIsBrace = false
    private var lastIsBracket = false
    private var isIgnoringFields = false
    private def append(s: String) = {
      val sindent = if (pendingIndent) "  " * nindent else ""
      buf.put(sindent + s)
      pendingIndent = false
      val trimmed = s.trim
      if (trimmed.nonEmpty) {
        val lastChar = trimmed.last
        lastIsBrace = lastChar == '{'
        lastIsBracket = lastChar == '['
      }
    }
    private def appendLine(s: String = "") = {
      append(s + "\n")
      pendingIndent = true
    }
    private val tags = new Stack[FastTypeTag[_]]()
    private def pickleArray(arr: Array[_], tag: FastTypeTag[_]) = {
      unindent()
      appendLine("[")
      pushHints()
      hintElidedType(tag)
      pinHints()
      var i = 0
      while (i < arr.length) {
        putElement(b => b.beginEntry(arr(i), tag).endEntry())
        i += 1
      }
      popHints()
      appendLine("")
      append("]")
      indent()
    }
    private val primitives = Map[String, Any => Unit](
      FastTypeTag.Unit.key -> ((picklee: Any) => append("\"()\"")),
      FastTypeTag.Null.key -> ((picklee: Any) => append("null")),
      FastTypeTag.Ref.key -> ((picklee: Any) => throw new Error("fatal error: shouldn't be invoked explicitly")),
      FastTypeTag.Int.key -> ((picklee: Any) => append(picklee.toString)),
      FastTypeTag.Long.key -> ((picklee: Any) => append("\"" + js.JSON.stringify(picklee.asInstanceOf[js.Any]) + "\"")),
      FastTypeTag.Short.key -> ((picklee: Any) => append(picklee.toString)),
      FastTypeTag.Double.key -> ((picklee: Any) => append(picklee.toString)),
      FastTypeTag.Float.key -> ((picklee: Any) => append(picklee.toString)),
      FastTypeTag.Boolean.key -> ((picklee: Any) => append(picklee.toString)),
      FastTypeTag.Byte.key -> ((picklee: Any) => append(picklee.toString)),
      FastTypeTag.Char.key -> ((picklee: Any) => append("\"" + js.JSON.stringify(picklee.asInstanceOf[js.Any]) + "\"")),
      FastTypeTag.String.key -> ((picklee: Any) => append("\"" + js.JSON.stringify(picklee.asInstanceOf[js.Any]) + "\"")),
      FastTypeTag.ArrayByte.key -> ((picklee: Any) => pickleArray(picklee.asInstanceOf[Array[Byte]], FastTypeTag.Byte)),
      FastTypeTag.ArrayShort.key -> ((picklee: Any) => pickleArray(picklee.asInstanceOf[Array[Short]], FastTypeTag.Short)),
      FastTypeTag.ArrayChar.key -> ((picklee: Any) => pickleArray(picklee.asInstanceOf[Array[Char]], FastTypeTag.Char)),
      FastTypeTag.ArrayInt.key -> ((picklee: Any) => pickleArray(picklee.asInstanceOf[Array[Int]], FastTypeTag.Int)),
      FastTypeTag.ArrayLong.key -> ((picklee: Any) => pickleArray(picklee.asInstanceOf[Array[Long]], FastTypeTag.Long)),
      FastTypeTag.ArrayBoolean.key -> ((picklee: Any) => pickleArray(picklee.asInstanceOf[Array[Boolean]], FastTypeTag.Boolean)),
      FastTypeTag.ArrayFloat.key -> ((picklee: Any) => pickleArray(picklee.asInstanceOf[Array[Float]], FastTypeTag.Float)),
      FastTypeTag.ArrayDouble.key -> ((picklee: Any) => pickleArray(picklee.asInstanceOf[Array[Double]], FastTypeTag.Double))
    )
    override def beginEntry(picklee: Any, tag: FastTypeTag[_]): PBuilder = withHints { hints =>
      indent()
      // We add special support here for null
      val realTag =
        if(null == picklee) FastTypeTag.Null
        else tag
      if (hints.isSharedReference) {
        tags.push(FastTypeTag.Ref)
        append("{ \"$ref\": " + hints.oid + " }")
        isIgnoringFields = true
      } else {
        tags.push(realTag)
        if (primitives.contains(realTag.key)) {
          // Null always goes out raw.
          if (hints.isElidedType || realTag.key == FastTypeTag.Null.key) primitives(realTag.key)(picklee)
          else {
            appendLine("{")
            appendLine("\"$type\": \"" + tag.key + "\",")
            append("\"value\": ")
            indent()
            primitives(realTag.key)(picklee)
            unindent()
            appendLine("")
            unindent()
            append("}")
            indent()
          }
        } else {
          appendLine("{")
          if (!hints.isElidedType) {
            // quickly decide whether we should use picklee.getClass instead
            val ts =
              if (tag.key.contains("anonfun$")) picklee.getClass.getName
              else tag.key
            append("\"$type\": \"" + ts + "\"")
          }
        }
      }
      this
    }
    private def ignoringSharedRef(action: => PBuilder): PBuilder =
      if(isIgnoringFields) this
      else action
    def putField(name: String, pickler: PBuilder => Unit): PBuilder = ignoringSharedRef {
        // assert(!primitives.contains(tags.top.key), tags.top)
        if (!lastIsBrace) appendLine(",") // TODO: very inefficient, but here we don't care much about performance
        append("\"" + name + "\": ")
        pickler(this)
      this
    }
    def endEntry(): Unit = {
      unindent()
      if (primitives.contains(tags.pop().key)) () // do nothing
      else { appendLine(); append("}") }
      // Always undo this state.
      isIgnoringFields = false
    }
    def beginCollection(length: Int): PBuilder = ignoringSharedRef {
      putField("elems", b => ())
      appendLine("[")
      // indent()
      this
    }
    def putElement(pickler: PBuilder => Unit): PBuilder = ignoringSharedRef {
      if (!lastIsBracket) appendLine(",") // TODO: very inefficient, but here we don't care much about performance
      pickler(this)
      this
    }
    def endCollection(): Unit = ignoringSharedRef {
      appendLine()
      append("]")
      // unindent()
      this
    }
    def result(): JSONPickle = {
      assert(tags.isEmpty, tags)
      JSONPickle(buf.toString)
    }
  }

  class JSONPickleReader(var datum: Any, format: JSONPickleFormat) extends PReader with PickleTools {
    private var lastReadTag: String = null
    private val primitives = Map[String, () => Any](
      FastTypeTag.Unit.key -> (() => ()),
      FastTypeTag.Null.key -> (() => null),
      FastTypeTag.Ref.key -> (() => lookupUnpicklee(datum.asInstanceOf[js.Dynamic].selectDynamic("$ref").asInstanceOf[Double].toInt)),
      FastTypeTag.Int.key -> (() => datum.asInstanceOf[Double].toInt),
      FastTypeTag.Short.key -> (() => datum.asInstanceOf[Double].toShort),
      FastTypeTag.Double.key -> (() => datum.asInstanceOf[Double]),
      FastTypeTag.Float.key -> (() => datum.asInstanceOf[Double].toFloat),
      FastTypeTag.Long.key -> (() => datum.asInstanceOf[String].toLong),
      FastTypeTag.Byte.key -> (() => datum.asInstanceOf[Double].toByte),
      FastTypeTag.Boolean.key -> (() => datum.asInstanceOf[Boolean]),
      FastTypeTag.Char.key -> (() => datum.asInstanceOf[String].head),
      FastTypeTag.String.key -> (() => datum.asInstanceOf[String]),
      FastTypeTag.ArrayByte.key -> (() => datum.asInstanceOf[js.Array[Byte]].map(el => el.asInstanceOf[Double].toByte).toArray),
      FastTypeTag.ArrayShort.key -> (() => datum.asInstanceOf[js.Array[Short]].map(el => el.asInstanceOf[Double].toShort).toArray),
      FastTypeTag.ArrayChar.key -> (() => datum.asInstanceOf[js.Array[Char]].map(el => el.asInstanceOf[String].head).toArray),
      FastTypeTag.ArrayInt.key -> (() => datum.asInstanceOf[js.Array[Int]].map(el => el.asInstanceOf[Double].toInt).toArray),
      FastTypeTag.ArrayLong.key -> (() => datum.asInstanceOf[js.Array[Long]].map(el => el.asInstanceOf[String].toLong).toArray),
      FastTypeTag.ArrayBoolean.key -> (() => datum.asInstanceOf[js.Array[Boolean]].map(el => el.asInstanceOf[Boolean]).toArray),
      FastTypeTag.ArrayFloat.key -> (() => datum.asInstanceOf[js.Array[Float]].map(el => el.asInstanceOf[Double].toFloat).toArray),
      FastTypeTag.ArrayDouble.key -> (() => datum.asInstanceOf[js.Array[Double]].map(el => el.asInstanceOf[Double]).toArray)
    )
    private def mkNestedReader(datum: Any) = {
      val nested = new JSONPickleReader(datum, format)
      if (this.areHintsPinned) {
        nested.pinHints()
        nested.hints = hints
        nested.lastReadTag = lastReadTag
      }
      nested
    }
    def beginEntry(): String = withHints { hints =>
      lastReadTag = {
        if (datum == null) FastTypeTag.Null.key
        else if (hints.isElidedType) {
          val field = datum.asInstanceOf[js.Dynamic].selectDynamic("$ref")
          if (!js.isUndefined(field)) FastTypeTag.Ref.key
          else hints.elidedType.get.key
        } else {
          val fieldRef = datum.asInstanceOf[js.Dynamic].selectDynamic("$ref")
          val fieldType = datum.asInstanceOf[js.Dynamic].selectDynamic("$type")

          if (js.isUndefined(fieldRef) && js.isUndefined(fieldType)) throw LogicPicklingError(s"Could not find a type tag, and no elided type was hinted.")
          else if (!js.isUndefined(fieldRef)) FastTypeTag.Ref.key
          else if (!js.isUndefined(fieldType)) fieldType.asInstanceOf[String]
          else throw LogicPicklingError(s"Logic pickling error:  Could not find a type tag on primitive, and no elided type was hinted")
        }
      }
      lastReadTag
    }
    def atPrimitive: Boolean = primitives.contains(lastReadTag)
    def readPrimitive(): Any = {
      datum match {
        case arr: js.Array[_] if lastReadTag != FastTypeTag.ArrayByte.key &&
                                lastReadTag != FastTypeTag.ArrayShort.key &&
                                lastReadTag != FastTypeTag.ArrayChar.key &&
                                lastReadTag != FastTypeTag.ArrayInt.key &&
                                lastReadTag != FastTypeTag.ArrayLong.key &&
                                lastReadTag != FastTypeTag.ArrayBoolean.key &&
                                lastReadTag != FastTypeTag.ArrayFloat.key &&
                                lastReadTag != FastTypeTag.ArrayDouble.key =>
          arr
        case obj if lastReadTag != FastTypeTag.Ref.key =>
          mkNestedReader(obj.asInstanceOf[js.Dynamic].selectDynamic("value")).primitives(lastReadTag)()
        case _ =>
          primitives(lastReadTag)()
      }
    }
    def atObject: Boolean = 
      //TOBEFIXED
      true
    def readField(name: String): JSONPickleReader = {
        mkNestedReader(datum) //???
    }
    def endEntry(): Unit = {}
    def beginCollection(): PReader = readField("elems")
    def readLength(): Int = {
      //To be fixed 
      0
    }
    private var i = 0
    def readElement(): PReader = {
      val reader = {
        datum match {
          case list: js.Array[_] => mkNestedReader(list(i))
        }
      }
      i += 1
      reader
    }
    def endCollection(): Unit = {}
  }
}
