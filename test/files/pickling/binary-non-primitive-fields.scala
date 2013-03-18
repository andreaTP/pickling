/*
  Test to directly generate a pickler for a *nested* arbitrary type (that is,
  a type that has at least one field with an arbitrary non-primitive type),
  and then use the generated pickler to pickle an instance of that type.

  This test directly invokes the implicit macro which generates picklers (it
  doesn't use the implicit class that adds the `pickle` extension method)
*/

import scala.pickling._
import binary._

case class Person(val name: String, val age: Int)
case class Philipp(val nationality: String, val weird: Boolean, val mother: Person)

object Test extends App {
  val pckl = new Philipp("German", true, new Person("Gudrun", 62)).pickle

  println(pckl.value.mkString("[", ",", "]"))
  println(pckl.unpickle[Philipp])
}