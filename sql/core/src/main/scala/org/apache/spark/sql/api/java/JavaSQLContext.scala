/*
* Licensed to the Apache Software Foundation (ASF) under one or more
* contributor license agreements.  See the NOTICE file distributed with
* this work for additional information regarding copyright ownership.
* The ASF licenses this file to You under the Apache License, Version 2.0
* (the "License"); you may not use this file except in compliance with
* the License.  You may obtain a copy of the License at
*
*    http://www.apache.org/licenses/LICENSE-2.0
*
* Unless required by applicable law or agreed to in writing, software
* distributed under the License is distributed on an "AS IS" BASIS,
* WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
* See the License for the specific language governing permissions and
* limitations under the License.
*/

package org.apache.spark.sql.api.java

import java.beans.Introspector

import org.apache.hadoop.conf.Configuration

import org.apache.spark.annotation.Experimental
import org.apache.spark.api.java.{JavaRDD, JavaSparkContext}
import org.apache.spark.sql.csv.CsvRDD
import org.apache.spark.sql.json.JsonRDD
import org.apache.spark.sql.SQLContext
import org.apache.spark.sql.catalyst.expressions.{AttributeReference, GenericRow, Row => ScalaRow}
import org.apache.spark.sql.catalyst.types._
import org.apache.spark.sql.parquet.ParquetRelation
import org.apache.spark.sql.execution.{ExistingRdd, SparkLogicalPlan}
import org.apache.spark.util.Utils

/**
 * The entry point for executing Spark SQL queries from a Java program.
 */
class JavaSQLContext(val sqlContext: SQLContext) {

  def this(sparkContext: JavaSparkContext) = this(new SQLContext(sparkContext.sc))

  /**
   * Executes a query expressed in SQL, returning the result as a JavaSchemaRDD
   */
  def sql(sqlQuery: String): JavaSchemaRDD =
    new JavaSchemaRDD(sqlContext, sqlContext.parseSql(sqlQuery))

  /**
   * :: Experimental ::
   * Creates an empty parquet file with the schema of class `beanClass`, which can be registered as
   * a table. This registered table can be used as the target of future `insertInto` operations.
   *
   * {{{
   *   JavaSQLContext sqlCtx = new JavaSQLContext(...)
   *
   *   sqlCtx.createParquetFile(Person.class, "path/to/file.parquet").registerAsTable("people")
   *   sqlCtx.sql("INSERT INTO people SELECT 'michael', 29")
   * }}}
   *
   * @param beanClass A java bean class object that will be used to determine the schema of the
   *                  parquet file.
   * @param path The path where the directory containing parquet metadata should be created.
   *             Data inserted into this table will also be stored at this location.
   * @param allowExisting When false, an exception will be thrown if this directory already exists.
   * @param conf A Hadoop configuration object that can be used to specific options to the parquet
   *             output format.
   */
  @Experimental
  def createParquetFile(
      beanClass: Class[_],
      path: String,
      allowExisting: Boolean = true,
      conf: Configuration = new Configuration()): JavaSchemaRDD = {
    new JavaSchemaRDD(
      sqlContext,
      ParquetRelation.createEmpty(path, getSchema(beanClass), allowExisting, conf))
  }

  /**
   * Applies a schema to an RDD of Java Beans.
   */
  def applySchema(rdd: JavaRDD[_], beanClass: Class[_]): JavaSchemaRDD = {
    val schema = getSchema(beanClass)
    val className = beanClass.getName
    val rowRdd = rdd.rdd.mapPartitions { iter =>
      // BeanInfo is not serializable so we must rediscover it remotely for each partition.
      val localBeanInfo = Introspector.getBeanInfo(
        Class.forName(className, true, Utils.getContextOrSparkClassLoader))
      val extractors =
        localBeanInfo.getPropertyDescriptors.filterNot(_.getName == "class").map(_.getReadMethod)

      iter.map { row =>
        new GenericRow(extractors.map(e => e.invoke(row)).toArray[Any]): ScalaRow
      }
    }
    new JavaSchemaRDD(sqlContext, SparkLogicalPlan(ExistingRdd(schema, rowRdd)))
  }

  /**
   * Loads a parquet file, returning the result as a [[JavaSchemaRDD]].
   */
  def parquetFile(path: String): JavaSchemaRDD =
    new JavaSchemaRDD(
      sqlContext,
      ParquetRelation(path, Some(sqlContext.sparkContext.hadoopConfiguration)))

  /**
   * Loads a JSON file (one object per line), returning the result as a [[JavaSchemaRDD]].
   * It goes through the entire dataset once to determine the schema.
   *
   * @group userf
   */
  def jsonFile(path: String): JavaSchemaRDD =
    jsonRDD(sqlContext.sparkContext.textFile(path))

  /**
   * Loads an RDD[String] storing JSON objects (one object per record), returning the result as a
   * [[JavaSchemaRDD]].
   * It goes through the entire dataset once to determine the schema.
   *
   * @group userf
   */
  def jsonRDD(json: JavaRDD[String]): JavaSchemaRDD =
    new JavaSchemaRDD(sqlContext, JsonRDD.inferSchema(json, 1.0))

   /**
    * Loads a CSV file (according to RFC 4180) and returns the result as a [[JavaSchemaRDD]].
    *
    * NOTE: If there are new line characters inside quoted fields this method may fail to
    * parse correctly, because the two lines may be in different partitions. Use
    * [[SQLContext#csvRDD]] to parse such files.
    *
    * @param path path to input file
    * @param delimiter Optional delimiter (default is comma)
    * @param quote Optional quote character or string (default is '"')
    * @param schema optional StructType object to specify schema (field names and types). This will
    *               override field names if header is used
    * @param header Optional flag to indicate first line of each file is the header
    *               (default is false)
    */
  def csvFile(
      path: String,
      delimiter: String = ",",
      quote: Char = '"',
      schema: StructType = null,
      header: Boolean = false): JavaSchemaRDD = {
    val csv = sqlContext.sparkContext.textFile(path)
    csvRDD(csv, delimiter, quote, schema, header)
  }

  /**
   * Parses an RDD of String as a CSV (according to RFC 4180) and returns the result as a
   * [[JavaSchemaRDD]].
   *
   * NOTE: If there are new line characters inside quoted fields, use
   * [[JavaSparkContext#wholeTextFiles]] to read each file into a single partition.
   *
   * @param csv input RDD
   * @param delimiter Optional delimiter (default is comma)
   * @param quote Optional quote character of strig (default is '"')
   * @param schema optional StructType object to specify schema (field names and types). This will
   *               override field names if header is used
   * @param header Optional flag to indicate first line of each file is the hader
   *               (default is false)
   */
  def csvRDD(
      csv: JavaRDD[String],
      delimiter: String = ",",
      quote: Char = '"',
      schema: StructType = null,
      header: Boolean = false): JavaSchemaRDD = {
    new JavaSchemaRDD(sqlContext, CsvRDD.inferSchema(csv, delimiter, quote, schema, header))
  }

  /**
   * Registers the given RDD as a temporary table in the catalog.  Temporary tables exist only
   * during the lifetime of this instance of SQLContext.
   */
  def registerRDDAsTable(rdd: JavaSchemaRDD, tableName: String): Unit = {
    sqlContext.registerRDDAsTable(rdd.baseSchemaRDD, tableName)
  }

  /** Returns a Catalyst Schema for the given java bean class. */
  protected def getSchema(beanClass: Class[_]): Seq[AttributeReference] = {
    // TODO: All of this could probably be moved to Catalyst as it is mostly not Spark specific.
    val beanInfo = Introspector.getBeanInfo(beanClass)

    val fields = beanInfo.getPropertyDescriptors.filterNot(_.getName == "class")
    fields.map { property =>
      val (dataType, nullable) = property.getPropertyType match {
        case c: Class[_] if c == classOf[java.lang.String] => (StringType, true)
        case c: Class[_] if c == java.lang.Short.TYPE => (ShortType, false)
        case c: Class[_] if c == java.lang.Integer.TYPE => (IntegerType, false)
        case c: Class[_] if c == java.lang.Long.TYPE => (LongType, false)
        case c: Class[_] if c == java.lang.Double.TYPE => (DoubleType, false)
        case c: Class[_] if c == java.lang.Byte.TYPE => (ByteType, false)
        case c: Class[_] if c == java.lang.Float.TYPE => (FloatType, false)
        case c: Class[_] if c == java.lang.Boolean.TYPE => (BooleanType, false)

        case c: Class[_] if c == classOf[java.lang.Short] => (ShortType, true)
        case c: Class[_] if c == classOf[java.lang.Integer] => (IntegerType, true)
        case c: Class[_] if c == classOf[java.lang.Long] => (LongType, true)
        case c: Class[_] if c == classOf[java.lang.Double] => (DoubleType, true)
        case c: Class[_] if c == classOf[java.lang.Byte] => (ByteType, true)
        case c: Class[_] if c == classOf[java.lang.Float] => (FloatType, true)
        case c: Class[_] if c == classOf[java.lang.Boolean] => (BooleanType, true)
      }
      AttributeReference(property.getName, dataType, nullable)()
    }
  }
}
