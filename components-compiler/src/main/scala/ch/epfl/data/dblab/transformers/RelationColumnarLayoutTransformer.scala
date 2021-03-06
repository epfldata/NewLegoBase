package ch.epfl.data
package dblab
package transformers

import ch.epfl.data.sc.pardis.quasi.TypeParameters._
import utils.Logger
import scala.language.implicitConversions
import sc.pardis.ir._
import deep.dsls.QueryEngineExp
import reflect.runtime.universe.{ TypeTag, Type }
import sc.pardis.optimization._
import deep._
import sc.pardis.types._
import sc.pardis.types.PardisTypeImplicits._
import sc.pardis.shallow.utils.DefaultValue
import quasi._

/**
 * Transforms row layout representation of the source relations to columnar layout representation.
 *
 * Example:
 * {{{
 *    // RecordA { fieldA: Int, fieldB: String }
 *    val array = new Array[RecordA](size)
 *    for(i <- 0 until size) {
 *      val elem = array(i)
 *      process(elem.fieldA, elem.fieldB)
 *    }
 * }}}
 * is converted to:
 * {{{
 *    val arrayOfFieldA = new Array[Int](size)
 *    val arrayOfFieldB = new Array[String](size)
 *    for(i <- 0 until size) {
 *      val elem = RecordA {
 *        val fieldA = arrayOfFieldA(i)
 *        val fieldB = arrayOfFieldB(i)
 *      }
 *      process(elem.fieldA, elem.fieldB)
 *    }
 * }}}
 *
 * If this optimization is combined with ParameterPromotion, the abstraction of
 * `ColumnStoreOfRecordA` and `RowOfRecordA` will be removed.
 * The previous example is converted to the following program:
 * {{{
 *    val arrayOfFieldA = new Array[Int](size)
 *    val arrayOfFieldB = new Array[String](size)
 *    for(i <- 0 until size) {
 *      val elemFieldA = arrayOfFieldA(i)
 *      val elemFieldB = arrayOfFieldB(i)
 *      process(elemFieldA, elemFieldB)
 *    }
 * }}}
 *
 * Precondition:
 * The arrays should correspond to source relations.
 *
 *
 * @param IR the polymorphic embedding trait which contains the reified program.
 */
class RelationColumnarLayoutTransformer(override val IR: QueryEngineExp)
  extends RuleBasedTransformer[QueryEngineExp](IR)
  with StructCollector[QueryEngineExp] {
  import IR._

  val logger = Logger[RelationColumnarLayoutTransformer]

  case class ColumnarRecord[T](arraySymbol: Rep[Array[T]], recordSymbol: Rep[T], index: Rep[Int])

  case class ColumnarArray(originalSymbol: Rep[_], fields: List[(String, Rep[Array[_]])]) {
    def getField(field: String)(idx: Rep[Int]): Rep[Any] = {
      val arr = fields.find(_._1 == field).get._2.asInstanceOf[Rep[Array[Any]]]
      implicit val tp: TypeRep[Any] = arr.tp.typeArguments(0).asInstanceOf[TypeRep[Any]]
      val res = arrayApply(arr, idx)(tp)
      logger.debug(s"get field of $res with tp ${res.tp}")
      res
    }
    def setField(field: String)(idx: Rep[Int])(value: Rep[Any]): Rep[Unit] = {
      val arr = fields.find(_._1 == field).get._2.asInstanceOf[Rep[Array[Any]]]
      implicit val tp: TypeRep[Any] = arr.tp.typeArguments(0).asInstanceOf[TypeRep[Any]]
      arr.update(idx, value)
    }
  }

  val relationArrays = scala.collection.mutable.Set[Rep[_]]()
  val relationRecords = scala.collection.mutable.Set[ColumnarRecord[_]]()
  val relationColumnarArrays = scala.collection.mutable.Set[ColumnarArray]()

  analysis += statement {
    case sym -> dsl"new Array[Any](${ Def(LoaderFileLineCountObject(_)) })" =>
      val innerType = sym.tp.typeArguments(0)
      relationArrays += sym
      ()
  }

  analysis += statement {
    case sym -> dsl"($arr: Array[Any])($index)" if relationArrays.contains(arr) =>
      relationRecords += ColumnarRecord(arr, sym, index)
      ()
  }

  override def postAnalyseProgram[T: TypeRep](node: Block[T]): Unit = {
    logger.debug(s"Relation arrays: ${relationArrays}")
    logger.debug(s"Relation records: ${relationRecords}")
  }

  rewrite += statement {
    case sym -> dsl"new Array[Any]($size)" if relationArrays.contains(sym) => {
      val elemType = sym.tp.typeArguments(0).asInstanceOf[PardisType[Any]]
      val structDef = getStructDef(elemType).get
      val fields = structDef.fields.map(el =>
        el.name -> arrayNew(size)(el.tpe)).toList
      relationColumnarArrays += ColumnarArray(sym, fields)
      logger.debug(s"fields: $fields")
      unit()
    }
  }

  rewrite += statement {
    case sym -> (aa @ dsl"($arr: Array[Any])($idx)") if relationArrays.contains(arr) =>
      val elemType = sym.tp.asInstanceOf[PardisType[Any]]
      val columnarArray = relationColumnarArrays.find(_.originalSymbol == arr).get
      // val newElems = List(PardisStructArg(COLUMN_STORE_POINTER, false, apply(arr)), PardisStructArg(INDEX, false, apply(idx)))
      // val rTpe = elemColumnStoreType(tpe.tag)
      logger.debug(s"apply elemType: $elemType --> $columnarArray")
      val newElems = columnarArray.fields.map(f => PardisStructArg(f._1, false, columnarArray.getField(f._1)(idx)))
      struct[Any](newElems: _*)(elemType)
    // unit()
  }

  rewrite += rule {
    case dsl"(($arr: Array[Any])($idx) = $value)" if relationArrays.contains(arr) =>
      val elemType = arr.tp.typeArguments(0).asInstanceOf[PardisType[Any]]
      val columnarArray = relationColumnarArrays.find(_.originalSymbol == arr).get
      // val newElems = List(PardisStructArg(COLUMN_STORE_POINTER, false, apply(arr)), PardisStructArg(INDEX, false, apply(idx)))
      // val rTpe = elemColumnStoreType(tpe.tag)
      for ((fieldName, fieldArray) <- columnarArray.fields) {
        columnarArray.setField(fieldName)(idx)(field(apply(value), fieldName))
      }
      logger.debug(s"update elemType: $elemType --> $columnarArray")
      // val newElems = columnarArray.fields.map(f => PardisStructArg(f._1, false, columnarArray.getField(f._1)(idx)))
      // struct[Any](newElems: _*)(elemType)
      unit()
  }

  rewrite += statement {
    case sym -> StructImmutableField(s, f) if relationRecords.exists(_.recordSymbol == s) =>
      val ColumnarRecord(arr, rec, idx) = relationRecords.find(_.recordSymbol == s).get
      logger.debug(s"struct field access: $s.$f: ${sym.tp}")
      val columnarArray = relationColumnarArrays.find(_.originalSymbol == arr).get
      columnarArray.getField(f)(idx)
  }
}
