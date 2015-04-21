package ch.epfl.data
package dblab.legobase
package tpch

import sc.pardis.annotations.{ deep, needs }
import utils.Utilities._
import sc.pardis.shallow.{ CaseClassRecord, OptimalString }

@needs[OptimalString] @deep case class LINEITEMRecord(
  val L_ORDERKEY: Int,
  val L_PARTKEY: Int,
  val L_SUPPKEY: Int,
  val L_LINENUMBER: Int,
  val L_QUANTITY: Int,
  val L_EXTENDEDPRICE: Double,
  val L_DISCOUNT: Double,
  val L_TAX: Double,
  val L_RETURNFLAG: Char,
  val L_LINESTATUS: Char,
  val L_SHIPDATE: Int,
  val L_COMMITDATE: Int,
  val L_RECEIPTDATE: Int,
  val L_SHIPINSTRUCT: LBString,
  val L_SHIPMODE: LBString,
  val L_COMMENT: LBString) extends CaseClassRecord

@needs[OptimalString] @deep case class ORDERSRecord(
  val O_ORDERKEY: Int,
  val O_CUSTKEY: Int,
  val O_ORDERSTATUS: Char,
  val O_TOTALPRICE: Double,
  val O_ORDERDATE: Int,
  val O_ORDERPRIORITY: LBString,
  val O_CLERK: LBString,
  val O_SHIPPRIORITY: Int,
  val O_COMMENT: LBString) extends CaseClassRecord

@needs[OptimalString] @deep case class CUSTOMERRecord(
  val C_CUSTKEY: Int,
  val C_NAME: LBString,
  val C_ADDRESS: LBString,
  val C_NATIONKEY: Int,
  val C_PHONE: LBString,
  val C_ACCTBAL: Double,
  val C_MKTSEGMENT: LBString,
  val C_COMMENT: LBString) extends CaseClassRecord

@needs[OptimalString] @deep case class SUPPLIERRecord(
  val S_SUPPKEY: Int,
  val S_NAME: LBString,
  val S_ADDRESS: LBString,
  val S_NATIONKEY: Int,
  val S_PHONE: LBString,
  val S_ACCTBAL: Double,
  val S_COMMENT: LBString) extends CaseClassRecord

@needs[OptimalString] @deep case class PARTSUPPRecord(
  val PS_PARTKEY: Int,
  val PS_SUPPKEY: Int,
  val PS_AVAILQTY: Int,
  val PS_SUPPLYCOST: Double,
  val PS_COMMENT: LBString) extends CaseClassRecord

@needs[OptimalString] @deep case class REGIONRecord(
  val R_REGIONKEY: Int,
  val R_NAME: LBString,
  val R_COMMENT: LBString) extends CaseClassRecord

@needs[OptimalString] @deep case class NATIONRecord(
  val N_NATIONKEY: Int,
  val N_NAME: LBString,
  val N_REGIONKEY: Int,
  val N_COMMENT: LBString) extends CaseClassRecord

@needs[OptimalString] @deep case class PARTRecord(
  val P_PARTKEY: Int,
  val P_NAME: LBString,
  val P_MFGR: LBString,
  val P_BRAND: LBString,
  val P_TYPE: LBString,
  val P_SIZE: Int,
  val P_CONTAINER: LBString,
  val P_RETAILPRICE: Double,
  val P_COMMENT: LBString) extends CaseClassRecord

object TPCHSchema {
  import sc.pardis.types._
  import schema._
  def getSchema(folderLocation: String, scalingFactor: Double): Schema = {
    val lineItemTable = {
      val ok: Attribute = "L_ORDERKEY" -> IntType
      val ln: Attribute = "L_LINENUMBER" -> IntType

      new Table("lineitem", List(
        ok,
        "L_PARTKEY" -> IntType,
        "L_SUPPKEY" -> IntType,
        ln,
        "L_QUANTITY" -> DoubleType,
        "L_EXTENDEDPRICE" -> DoubleType,
        "L_DISCOUNT" -> DoubleType,
        "L_TAX" -> DoubleType,
        "L_RETURNFLAG" -> CharType,
        "L_LINESTATUS" -> CharType,
        "L_SHIPDATE" -> DateType,
        "L_COMMITDATE" -> DateType,
        "L_RECEIPTDATE" -> DateType,
        ("L_SHIPINSTRUCT", StringType, 25),
        ("L_SHIPMODE", StringType, 10),
        ("L_COMMENT", StringType, 44)),
        List(
          PrimaryKey(List(ok, ln)),
          ForeignKey("lineitem", "orders", List(("L_ORDERKEY", "O_ORDERKEY"))),
          ForeignKey("lineitem", "partsupp", List(("L_PARTKEY", "PS_PARTKEY"), ("L_SUPPKEY", "PS_SUPPKEY")))),
        folderLocation + "/lineitem.tbl", (scalingFactor * 6000000).toLong)
    }

    val regionTable = {
      val rk: Attribute = "R_REGIONKEY" -> IntType

      new Table("region", List(
        rk,
        ("R_NAME", StringType, 25),
        ("R_COMMENT", StringType, 152)),
        List(PrimaryKey(List(rk))),
        folderLocation + "/region.tbl", 5)
    }

    val nationTable = {
      val nk: Attribute = "N_NATIONKEY" -> IntType

      new Table("nation", List(
        nk,
        ("N_NAME", StringType, 25),
        "N_REGIONKEY" -> IntType,
        ("N_COMMENT", StringType, 152)),
        List(
          PrimaryKey(List(nk)),
          ForeignKey("nation", "region", List(("N_REGIONKEY", "R_REGIONKEY")))),
        folderLocation + "/nation.tbl", 25)
    }

    val supplierTable = {
      val sk: Attribute = "S_SUPPKEY" -> IntType

      new Table("supplier", List(
        sk,
        ("S_NAME", StringType, 25),
        ("S_ADDRESS", StringType, 40),
        "S_NATIONKEY" -> IntType,
        ("S_PHONE", StringType, 15),
        "S_ACCTBAL" -> DoubleType,
        ("S_COMMENT", StringType, 101)),
        List(
          PrimaryKey(List(sk)),
          ForeignKey("supplier", "nation", List(("S_NATIONKEY", "N_NATIONKEY")))),
        folderLocation + "/supplier.tbl", (scalingFactor * 10000).toLong)
    }

    val partTable = {
      val pk: Attribute = "P_PARTKEY" -> IntType

      new Table("part", List(
        pk,
        ("P_NAME", StringType, 55),
        ("P_MFGR", StringType, 25),
        ("P_BRAND", StringType, 10),
        ("P_TYPE", StringType, 25),
        "P_SIZE" -> IntType,
        ("P_CONTAINER", StringType, 10),
        "P_RETAILPRICE" -> DoubleType,
        ("P_COMMENT", StringType, 23)),
        List(
          PrimaryKey(List(pk))),
        folderLocation + "/part.tbl", (scalingFactor * 200000).toLong)
    }

    val partsuppTable = {
      val pk: Attribute = "PS_PARTKEY" -> IntType
      val sk: Attribute = "PS_SUPPKEY" -> IntType

      new Table("partsupp", List(
        pk,
        sk,
        "PS_AVAILQTY" -> IntType,
        "PS_SUPPLYCOST" -> DoubleType,
        ("PS_COMMENT", StringType, 199)),
        List(
          PrimaryKey(List(pk, sk)),
          ForeignKey("partsupp", "part", List(("PS_PARTKEY", "P_PARTKEY"))),
          ForeignKey("partsupp", "supplier", List(("PS_SUPPKEY", "S_SUPPKEY")))),
        folderLocation + "/partsupp.tbl", (scalingFactor * 800000).toLong)
    }

    val customerTable = {
      val ck: Attribute = "C_CUSTKEY" -> IntType

      new Table("customer", List(
        ck,
        ("C_NAME", StringType, 25),
        ("C_ADDRESS", StringType, 40),
        "C_NATIONKEY" -> IntType,
        ("C_PHONE", StringType, 15),
        "C_ACCTBAL" -> DoubleType,
        ("C_MKTSEGMENT", StringType, 10),
        ("C_COMMENT", StringType, 117)),
        List(
          PrimaryKey(List(ck)),
          ForeignKey("customer", "nation", List(("C_NATIONKEY", "N_NATIONKEY")))),
        folderLocation + "/customer.tbl", (scalingFactor * 150000).toLong)
    }

    val ordersTable = {
      val ok: Attribute = "O_ORDERKEY" -> IntType

      new Table("orders", List(
        ok,
        "O_CUSTKEY" -> IntType,
        "O_ORDERSTATUS" -> CharType,
        "O_TOTALPRICE" -> DoubleType,
        "O_ORDERDATE" -> DateType,
        ("O_ORDERPRIORITY", StringType, 15),
        ("O_CLERK", StringType, 15),
        "O_SHIPPRIORITY" -> IntType,
        ("O_COMMENT", StringType, 79)),
        List(
          PrimaryKey(List(ok)),
          ForeignKey("orders", "customer", List(("O_CUSTKEY", "C_CUSTKEY")))),
        folderLocation + "/orders.tbl", (scalingFactor * 1500000).toLong)
    }

    new Schema(List(lineItemTable, regionTable, nationTable, supplierTable, partTable, partsuppTable, customerTable, ordersTable))
  }
}
