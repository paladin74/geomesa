/*
 * Copyright 2014 Commonwealth Computer Research, Inc.
 *
 * Licensed under the Apache License, Version 2.0 (the License);
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 * http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an AS IS BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package org.locationtech.geomesa.convert.avro

import org.apache.avro.generic.{GenericData, GenericRecord}
import org.apache.avro.util.Utf8

import scala.collection.JavaConversions._
import scala.util.parsing.combinator.RegexParsers

object AvroPath {
  def apply(s: String) = new AvroPath(s)
}

class AvroPath(s: String) extends RegexParsers {
  val path = build(s)

  def eval(r: AnyRef) = path.eval(r)

  sealed trait Expr {
    def eval(r: AnyRef): Option[AnyRef]
  }

  case class PathExpr(field: String, pred: GenericRecord => Boolean = _ => true) extends Expr {
    override def eval(r: AnyRef) = r match {
      case gr: GenericRecord =>
        gr.get(field) match {
          case s: Utf8          => Some(s.toString)
          case x: GenericRecord => if(pred(x)) Some(x) else None
          case x                => Some(x)
        }
      case _                 => Some(r)
    }
  }

  type Predicate[T] = T => Boolean
  case class UnionTypeFilter(n: String) extends Predicate[GenericRecord] {
    override def apply(v1: GenericRecord): Boolean = v1.getSchema.getName.equals(n)
  }

  case class ArrayRecordExpr(pred: Predicate[GenericRecord]) extends Expr {
    override def eval(r: AnyRef): Option[AnyRef] = r match {
      case a: GenericData.Array[GenericRecord] => a.find(pred)
    }
  }

  case class CompositeExpr(se: Seq[Expr]) extends Expr {
    override def eval(r: AnyRef): Option[AnyRef] = r match {
      case gr: GenericRecord => se.foldLeft[Option[AnyRef]](Some(gr))((acc, expr) => acc.flatMap(expr.eval))
      case _ => None
    }
  }

  def fieldName = "[A-Za-z0-9_]*".r
  def field = "$" ~> fieldName
  def pathExpr = "/" ~> fieldName ^^ {
    case f => PathExpr(f)
  }

  def schemaTypeName = (pathExpr <~ "$type=") ~ "[A-Za-z0-9_]+".r ^^ {
    case pe ~ stn => pe.copy(pred = UnionTypeFilter(stn))
  }

  def arrayRecord = ("[$" ~> fieldName) ~ ("=" ~> "[A-Za-z0-9_]+".r) <~ "]" ^^ {
    case fn ~ fv =>
      ArrayRecordExpr(
        (gr) => gr.get(fn) match {
          case utf8string: Utf8 => utf8string.toString.equals(fv)
          case x => x.equals(fv)
        })
    case _ => ArrayRecordExpr(_ => false)
  }

  def expr = schemaTypeName | arrayRecord | pathExpr

  def build(s: String) = parse(rep1(expr), s) match {
    case Success(t, _) => CompositeExpr(t)
    case _ => null
  }

}
