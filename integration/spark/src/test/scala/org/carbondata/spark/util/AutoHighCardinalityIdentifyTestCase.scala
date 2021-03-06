/*
 * Licensed to the Apache Software Foundation (ASF) under one
 * or more contributor license agreements.  See the NOTICE file
 * distributed with this work for additional information
 * regarding copyright ownership.  The ASF licenses this file
 * to you under the Apache License, Version 2.0 (the
 * "License"); you may not use this file except in compliance
 * with the License.  You may obtain a copy of the License at
 *
 *    http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing,
 * software distributed under the License is distributed on an
 * "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY
 * KIND, either express or implied.  See the License for the
 * specific language governing permissions and limitations
 * under the License.
 */
package org.carbondata.spark.util

import java.io.File

import org.apache.spark.sql.{CarbonEnv, CarbonRelation}
import org.apache.spark.sql.common.util.CarbonHiveContext
import org.apache.spark.sql.common.util.CarbonHiveContext.sql
import org.apache.spark.sql.common.util.QueryTest

import org.carbondata.core.cache.dictionary.DictionaryColumnUniqueIdentifier
import org.carbondata.core.carbon.{CarbonDataLoadSchema, CarbonTableIdentifier}
import org.carbondata.core.constants.CarbonCommonConstants
import org.carbondata.spark.load.CarbonLoadModel
import org.carbondata.spark.load.CarbonLoaderUtil

import org.scalatest.BeforeAndAfterAll
import java.io.FileWriter
import java.io.BufferedWriter
import java.util.Random
import org.carbondata.core.carbon.metadata.encoder.Encoding
import org.carbondata.core.carbon.path.CarbonStorePath
import org.carbondata.core.carbon.path.CarbonStorePath
import org.carbondata.core.carbon.metadata.schema.table.CarbonTable
import org.carbondata.core.util.CarbonUtil

/**
  * Test Case for org.carbondata.spark.util.GlobalDictionaryUtil
  *
  * @date: Apr 10, 2016 10:34:58 PM
  * @See org.carbondata.spark.util.GlobalDictionaryUtil
  */
class AutoHighCardinalityIdentifyTestCase extends QueryTest with BeforeAndAfterAll {

  var filePath: String = _

  def buildCarbonLoadModel(relation: CarbonRelation,
    filePath: String,
    dimensionFilePath: String,
    header: String): CarbonLoadModel = {
    val carbonLoadModel = new CarbonLoadModel
    carbonLoadModel.setTableName(relation.cubeMeta.carbonTableIdentifier.getDatabaseName)
    carbonLoadModel.setDatabaseName(relation.cubeMeta.carbonTableIdentifier.getTableName)
    // carbonLoadModel.setSchema(relation.cubeMeta.schema)
    val table = relation.cubeMeta.carbonTable
    val carbonSchema = new CarbonDataLoadSchema(table)
    carbonLoadModel.setDatabaseName(table.getDatabaseName)
    carbonLoadModel.setTableName(table.getFactTableName)
    carbonLoadModel.setCarbonDataLoadSchema(carbonSchema)
    carbonLoadModel.setFactFilePath(filePath)
    carbonLoadModel.setDimFolderPath(dimensionFilePath)
    carbonLoadModel.setCsvHeader(header)
    carbonLoadModel.setCsvDelimiter(",")
    carbonLoadModel.setComplexDelimiterLevel1("\\$")
    carbonLoadModel.setComplexDelimiterLevel2("\\:")
    carbonLoadModel
  }

  override def beforeAll {
    buildTestData
    buildTable
  }

  def buildTestData() = {
    val pwd = new File(this.getClass.getResource("/").getPath + "/../../").getCanonicalPath
    filePath = pwd + "/target/highcarddata.csv"
    val file = new File(filePath)
    val writer = new BufferedWriter(new FileWriter(file))
    writer.write("hc1,c2,c3")
    writer.newLine()
    var i = 0
    val random = new Random
    for(i <- 0 until 2000000) {
      writer.write("a" + i + "," +
          "b" + i%1000 + "," +
          i%1000000 + "\n")
    }
    writer.close
  }

  def buildTable() = {
    try {
      sql("drop table if exists highcard")
      sql("""create table if not exists highcard
             (hc1 string, c2 string, c3 int)
             STORED BY 'org.apache.carbondata.format'""")
    } catch {
      case ex: Throwable => logError(ex.getMessage + "\r\n" + ex.getStackTraceString)
    }
  }

  def relation: CarbonRelation = {
    CarbonEnv.getInstance(CarbonHiveContext).carbonCatalog
        .lookupRelation1(Option("default"), "highcard")(CarbonHiveContext)
        .asInstanceOf[CarbonRelation]
  }
  
  private def checkDictFile(table: CarbonTable) = {
    val tableIdentifier = new CarbonTableIdentifier(table.getDatabaseName,
        table.getFactTableName, "1")
    val carbonTablePath = CarbonStorePath.getCarbonTablePath(CarbonHiveContext.hdfsCarbonBasePath,
        tableIdentifier)
    val newHc1 = table.getDimensionByName("highcard", "hc1")
    val newC2 = table.getDimensionByName("highcard", "c2")
    val dictFileHc1 = carbonTablePath.getDictionaryFilePath(newHc1.getColumnId)
    val dictFileC2 = carbonTablePath.getDictionaryFilePath(newC2.getColumnId)
    assert(!CarbonUtil.isFileExists(dictFileHc1))
    assert(CarbonUtil.isFileExists(dictFileC2))
  }

  private def checkMetaData(oldTable: CarbonTable, newTable: CarbonTable) = {
    val oldHc1 = oldTable.getDimensionByName("highcard", "hc1")
    val oldc2 = oldTable.getDimensionByName("highcard", "c2")
    val newHc1 = newTable.getDimensionByName("highcard", "hc1")
    val newC2 = newTable.getDimensionByName("highcard", "c2")
    assert(oldHc1.hasEncoding(Encoding.DICTIONARY))
    assert(oldc2.hasEncoding(Encoding.DICTIONARY))
    assert(!newHc1.hasEncoding(Encoding.DICTIONARY))
    assert(newC2.hasEncoding(Encoding.DICTIONARY))
  }

  test("auto identify high cardinality column in first load #396") {
    val oldTable = relation.cubeMeta.carbonTable
    sql(s"LOAD DATA LOCAL INPATH '$filePath' into table highcard")
    val newTable = relation.cubeMeta.carbonTable
    sql(s"select count(hc1) from highcard").show

    // check dictionary file
    checkDictFile(newTable)
    // check the meta data
    checkMetaData(oldTable, newTable)
  }
}
