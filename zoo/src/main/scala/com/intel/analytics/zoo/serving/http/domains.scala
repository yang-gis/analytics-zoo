/*
 * Copyright 2018 Analytics Zoo Authors.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package com.intel.analytics.zoo.serving.http

import java.io.{ByteArrayInputStream, ByteArrayOutputStream}
import java.util
import java.util.{HashMap, UUID}

import akka.actor.ActorRef
import com.codahale.metrics.Timer
import com.google.common.collect.ImmutableList
import org.apache.arrow.memory.RootAllocator
import org.apache.arrow.vector.complex.{DenseUnionVector, FixedSizeListVector, StructVector, UnionVector}
import org.apache.arrow.vector.dictionary.DictionaryProvider
import org.apache.arrow.vector.ipc.{ArrowStreamReader, ArrowStreamWriter}
import org.apache.arrow.vector.types.{FloatingPointPrecision, UnionMode}
import org.apache.arrow.vector.types.Types.MinorType
import org.apache.arrow.vector.types.pojo.ArrowType.ArrowTypeID
import org.apache.arrow.vector.types.pojo.{ArrowType, Field, FieldType, Schema}
import org.apache.arrow.vector.{FieldVector, Float4Vector, IntVector, VarBinaryVector, VectorSchemaRoot}

import scala.collection.mutable
import scala.collection.mutable.ArrayBuffer

sealed trait ServingMessage

case class PredictionInputMessage(inputs: Seq[PredictionInput]) extends ServingMessage

case class PredictionInputFlushMessage() extends ServingMessage

case class PredictionQueryMessage(ids: Seq[String]) extends ServingMessage

case class PredictionQueryWithTargetMessage(query: PredictionQueryMessage, target: ActorRef)
  extends ServingMessage

object PredictionInputMessage {
  def apply(input: PredictionInput): PredictionInputMessage =
    PredictionInputMessage(Seq(input))
}

sealed trait PredictionInput {
  def getId(): String

  def toHash(): HashMap[String, String]
}

case class BytesPredictionInput(uuid: String, bytesStr: String) extends PredictionInput {
  override def getId(): String = this.uuid

  def toMap(): Map[String, String] = Map("uuid" -> uuid, "bytesStr" -> bytesStr)

  override def toHash(): HashMap[String, String] = {
    val hash = new HashMap[String, String]()
    hash.put("uri", uuid)
    hash.put("image", bytesStr)
    hash
  }
}

object BytesPredictionInput {
  def apply(str: String): BytesPredictionInput =
    BytesPredictionInput(UUID.randomUUID().toString, str)
}

case class PredictionOutput[Type](uuid: String, result: Type)

class ImageFeature(val b64: String)

object Conventions {
  val ARROW_INT = new ArrowType.Int(32, true)
  val ARROW_FLOAT = new ArrowType.FloatingPoint(FloatingPointPrecision.SINGLE)
  val ARROW_BINARY = new ArrowType.Binary()
}

case class SparseTensor[T](shape: Array[Int], indices: Array[Array[Int]], values: Array[T])

case class Instances(instances: List[mutable.LinkedHashMap[String, Any]]) {
  def constructTensors(): Seq[mutable.LinkedHashMap[String, (mutable.ArrayBuffer[Int], Any)]] = {
    instances.map(instance => {
      instance.map(i => {
        val key = i._1
        val value = i._2
        val tensor =
          if (value.isInstanceOf[List[_]]) {
            Instances.transferListToTensor(value)
          } else {
            (new mutable.ArrayBuffer[Int](0), value)
          }
        key -> tensor
      })
    })
  }

  def makeSchema(
      tensors: Seq[mutable.LinkedHashMap[String, (mutable.ArrayBuffer[Int], Any)]]): Schema = {
    assert(instances.size > 0, "must have instances, and each should have the same schema")
    val sample = tensors(0)
    val key_fields = sample.map(s => (s._1, s._2))
    val childrenBuilder = ImmutableList.builder[Field]()
    key_fields.map(key_field => {
      val key = key_field._1
      val shape = key_field._2._1
      val data = key_field._2._2
      val (isList, fieldSampe) = data.isInstanceOf[ArrayBuffer[_]] match {
        case true => (true, data.asInstanceOf[ArrayBuffer[_]](0))
        case false => (false, data)
      }
      if (fieldSampe.isInstanceOf[Int]) {
        val field = if (isList) {
          val shapeSize = shape.asInstanceOf[ArrayBuffer[_]].size
          val shapeList = new util.ArrayList[Field]()
          shapeList.add(new Field("", FieldType.nullable(Conventions.ARROW_INT), null))
          val shapeField =
            new Field("shape",
              FieldType.nullable(new ArrowType.FixedSizeList(shapeSize)), shapeList)
          val dataSize = data.asInstanceOf[ArrayBuffer[_]].size
          val dataList = new util.ArrayList[Field]()
          dataList.add(new Field("", FieldType.nullable(Conventions.ARROW_INT), null))
          val dataField =
            new Field("data",
              FieldType.nullable(new ArrowType.FixedSizeList(dataSize)), dataList)
          val tensorFieldList = new util.ArrayList[Field]()
          tensorFieldList.add(shapeField)
          tensorFieldList.add(dataField)
          new Field(key, FieldType.nullable(new ArrowType.Struct()), tensorFieldList)
        } else {
          new Field(key, FieldType.nullable(Conventions.ARROW_INT), null)
        }
        childrenBuilder.add(field)
      } else if (fieldSampe.isInstanceOf[Float] || fieldSampe.isInstanceOf[Double]) {
        val field = if (isList) {
          val shapeSize = shape.asInstanceOf[ArrayBuffer[_]].size
          val shapeList = new util.ArrayList[Field]()
          shapeList.add(new Field("", FieldType.nullable(Conventions.ARROW_INT), null))
          val shapeField =
            new Field("shape",
              FieldType.nullable(new ArrowType.FixedSizeList(shapeSize)), shapeList)
          val dataSize = data.asInstanceOf[ArrayBuffer[_]].size
          val dataList = new util.ArrayList[Field]()
          dataList.add(new Field("", FieldType.nullable(Conventions.ARROW_FLOAT), null))
          val dataField =
            new Field("data",
              FieldType.nullable(new ArrowType.FixedSizeList(dataSize)), dataList)
          val tensorFieldList = new util.ArrayList[Field]()
          tensorFieldList.add(shapeField)
          tensorFieldList.add(dataField)
          new Field(key, FieldType.nullable(new ArrowType.Struct()), tensorFieldList)
        } else {
          new Field(key, FieldType.nullable(Conventions.ARROW_FLOAT), null)
        }
        childrenBuilder.add(field)
      } else if (fieldSampe.isInstanceOf[String]) {
        val field = if (isList) {
          val shapeSize = shape.asInstanceOf[ArrayBuffer[_]].size
          val shapeList = new util.ArrayList[Field]()
          shapeList.add(new Field("", FieldType.nullable(Conventions.ARROW_INT), null))
          val shapeField =
            new Field("shape",
              FieldType.nullable(new ArrowType.FixedSizeList(shapeSize)), shapeList)
          val dataSize = data.asInstanceOf[ArrayBuffer[_]].size
          val dataList = new util.ArrayList[Field]()
          dataList.add(new Field("", FieldType.nullable(Conventions.ARROW_BINARY), null))
          val dataField =
            new Field("data",
              FieldType.nullable(new ArrowType.FixedSizeList(dataSize)), dataList)
          val tensorFieldList = new util.ArrayList[Field]()
          tensorFieldList.add(shapeField)
          tensorFieldList.add(dataField)
          new Field(key, FieldType.nullable(new ArrowType.Struct()), tensorFieldList)
        } else {
          new Field(key, FieldType.nullable(Conventions.ARROW_BINARY), null)
        }
        childrenBuilder.add(field)
      }
    })
    new Schema(childrenBuilder.build(), null)
  }

  def toArrow(): Array[Byte] = {
    val tensors = constructTensors()
    val schema = makeSchema(tensors)
    val vectorSchemaRoot = VectorSchemaRoot.create(schema, new RootAllocator(Integer.MAX_VALUE))
    val provider = new DictionaryProvider.MapDictionaryProvider
    val byteArrayOutputStream = new ByteArrayOutputStream()
    val arrowStreamWriter = new ArrowStreamWriter(vectorSchemaRoot, provider, byteArrayOutputStream)

    arrowStreamWriter.start()
    for (i <- 0 until tensors.size) {
      val map = tensors(i)
      vectorSchemaRoot.setRowCount(1)
      map.map(sample => {
        val key = sample._1
        val tensor = sample._2
        val fieldVector = vectorSchemaRoot.getVector(key)
        fieldVector.setInitialCapacity(1)
        fieldVector.allocateNew()
        val minorType = fieldVector.getMinorType()
        minorType match {
          case MinorType.INT =>
            fieldVector.asInstanceOf[IntVector].setSafe(0, tensor._2.asInstanceOf[Int])
            fieldVector.setValueCount(1)
          case MinorType.FLOAT4 =>
            tensor._2.isInstanceOf[Float] match {
              case true => fieldVector.asInstanceOf[Float4Vector]
                .setSafe(0, tensor._2.asInstanceOf[Float])
              case false => fieldVector.asInstanceOf[Float4Vector]
                .setSafe(0, tensor._2.asInstanceOf[Double].toFloat)
            }
            fieldVector.setValueCount(1)
          case MinorType.VARBINARY =>
            val varBinaryVector = fieldVector.asInstanceOf[VarBinaryVector]
            val bytes = tensor._2.asInstanceOf[String].getBytes
            varBinaryVector.setIndexDefined(0)
            varBinaryVector.setValueLengthSafe(0, bytes.length)
            varBinaryVector.setSafe(0, bytes)
            fieldVector.setValueCount(1)
          case MinorType.STRUCT =>
            val shape = tensor._1
            val data = tensor._2
            val structVector = fieldVector.asInstanceOf[StructVector]
            val shapeVector = structVector.getChild("shape")
            val dataVector = structVector.getChild("data")
            val shapeDataVector = shapeVector.asInstanceOf[FixedSizeListVector].getDataVector
            val dataDataVector = dataVector.asInstanceOf[FixedSizeListVector].getDataVector
            val shapeSize = shape.size
            val shapeIntVector = shapeDataVector.asInstanceOf[IntVector]
            for(j <- 0 until shapeSize) {
              shapeIntVector.setSafe(j, shape(j))
            }
            shapeIntVector.setValueCount(shapeSize)
            dataDataVector.getMinorType match {
              case MinorType.INT =>
                val dataIntVector = dataDataVector.asInstanceOf[IntVector]
                val datas = data.asInstanceOf[ArrayBuffer[Int]]
                val dataSize = datas.size
                for (j <- 0 until dataSize) {
                  dataIntVector.setSafe(j, datas(j))
                }
                dataIntVector.setValueCount(dataSize)
              case MinorType.FLOAT4 =>
                val dataFloatVector = dataDataVector.asInstanceOf[Float4Vector]
                val dataBuffer = data.asInstanceOf[ArrayBuffer[_]]
                dataBuffer.size > 0 match {
                  case true =>
                    val dataSample = dataBuffer(0)
                    val dataSize = dataBuffer.size
                    for (j <- 0 until dataSize) {
                      dataBuffer(j).isInstanceOf[Float] match {
                        case true =>
                          dataFloatVector.setSafe(j, dataBuffer(j).asInstanceOf[Float])
                          dataFloatVector.setValueCount(dataSize)
                        case false =>
                          dataFloatVector.setSafe(j, dataBuffer(j).asInstanceOf[Double].toFloat)
                          dataFloatVector.setValueCount(dataSize)
                      }
                    }
                  case false =>
                }

              case MinorType.VARBINARY =>
                val varBinaryVector = dataDataVector.asInstanceOf[VarBinaryVector]
                val datas = data.asInstanceOf[ArrayBuffer[String]]
                val dataSize = datas.size
                for (j <- 0 until dataSize) {
                  val bytes = datas(j).asInstanceOf[String].getBytes
                  varBinaryVector.setIndexDefined(j)
                  varBinaryVector.setValueLengthSafe(j, bytes.length)
                  varBinaryVector.setSafe(j, bytes)
                }
                varBinaryVector.setValueCount(dataSize)
            }
          case _ =>
        }
      })
      arrowStreamWriter.writeBatch()
    }
    arrowStreamWriter.end()
    arrowStreamWriter.close()
    byteArrayOutputStream.flush()
    byteArrayOutputStream.close()
    byteArrayOutputStream.toByteArray
  }

}

object Instances {
  def apply(instance: mutable.LinkedHashMap[String, Any]): Instances = {
    Instances(List(instance))
  }

  def apply(instances: mutable.LinkedHashMap[String, Any]*): Instances = {
    Instances(instances.toList)
  }

  def fromArrow(arrowBytes: Array[Byte]): Instances = {
    val instances = new mutable.ArrayBuffer[mutable.LinkedHashMap[String, Any]]()

    val byteArrayInputStream = new ByteArrayInputStream(arrowBytes)
    val rootAllocator = new RootAllocator(Integer.MAX_VALUE)
    val arrowStreamReader = new ArrowStreamReader(byteArrayInputStream, rootAllocator)
    val root = arrowStreamReader.getVectorSchemaRoot()
    val fieldVectors: util.List[FieldVector] = root.getFieldVectors

    while(arrowStreamReader.loadNextBatch()) {
      val map = new mutable.LinkedHashMap[String, Any]()
      fieldVectors.toArray().map(fieldVector => {
        val (name, value) =
          if (fieldVector.isInstanceOf[IntVector]) {
          val vector = fieldVector.asInstanceOf[IntVector]
          (vector.getName, vector.getObject(0))
        } else if (fieldVector.isInstanceOf[Float4Vector]) {
            val vector = fieldVector.asInstanceOf[Float4Vector]
            (vector.getName, vector.getObject(0))
          } else if (fieldVector.isInstanceOf[VarBinaryVector]) {
            val vector = fieldVector.asInstanceOf[VarBinaryVector]
            (vector.getName, new String(vector.getObject(0).asInstanceOf[Array[Byte]]))
          } else if (fieldVector.isInstanceOf[StructVector]) {
            val structVector = fieldVector.asInstanceOf[StructVector]
            val shapeVector = structVector.getChild("shape")
            val dataVector = structVector.getChild("data")
            val shapeDataVector = shapeVector.asInstanceOf[FixedSizeListVector].getDataVector
            val dataDataVector = dataVector.asInstanceOf[FixedSizeListVector].getDataVector
            val shape = new ArrayBuffer[Int]()
            val shapeIntVector = shapeDataVector.asInstanceOf[IntVector]
            for(i <- 0 until shapeDataVector.getValueCount) {
              shape.append(shapeDataVector.getObject(i).asInstanceOf[Int])
            }
            val data = dataDataVector.getMinorType match {
              case MinorType.INT =>
                val data = new ArrayBuffer[Int]()
                val dataIntVector = dataDataVector.asInstanceOf[IntVector]
                for(i <- 0 until dataIntVector.getValueCount) {
                  data.append(dataIntVector.getObject(i).asInstanceOf[Int])
                }
                data
              case MinorType.FLOAT4 =>
                val data = new ArrayBuffer[Float]()
                val dataFloatVector = dataDataVector.asInstanceOf[Float4Vector]
                for(i <- 0 until dataFloatVector.getValueCount) {
                  data.append(dataFloatVector.getObject(i).asInstanceOf[Float])
                }
                data
              case MinorType.VARBINARY =>
                val data = new ArrayBuffer[String]()
                val dataVarBinaryVector = dataDataVector.asInstanceOf[VarBinaryVector]
                for(i <- 0 until dataVarBinaryVector.getValueCount) {
                  data.append(
                    new String(dataVarBinaryVector.getObject(i).asInstanceOf[Array[Byte]]))
                }
                data
            }
            (structVector.getName, (shape, data))
          } else {
            (null, null)
          }
        if(null != name) {
          map.put(name, value)
        }
      })
      instances.append(map)
    }
    new Instances(instances.toList)
  }

  def transferListToTensor(value: Any): (mutable.ArrayBuffer[Int], mutable.ArrayBuffer[Any]) = {
    val shape = mutable.ArrayBuffer[Int]()
    val data = mutable.ArrayBuffer[Any]()
    transferListToTensor(value, shape, data)
    val real = shape.take(shape.indexOf(-1))
    (real, data)
  }

  private def transferListToTensor(
      source: Any,
      shape: mutable.ArrayBuffer[Int],
      data: mutable.ArrayBuffer[Any]): Unit = {
    if (source.isInstanceOf[List[_]]) {
      val list = source.asInstanceOf[List[_]]
      shape.append(list.size)
      list.map(i => {
        transferListToTensor(i, shape, data)
      })
    } else {
      shape.append(-1)
      data.append(source)
    }
  }
}

case class Predictions[Type](predictions: Array[Type]) {
  override def toString: String = JsonUtil.toJson(this)
}

object Predictions {
  def apply[T](output: PredictionOutput[T])(implicit m: Manifest[T]): Predictions[T] = {
    Predictions(Array(output.result))
  }

  def apply[T](outputs: List[PredictionOutput[T]])(implicit m: Manifest[T]): Predictions[T] = {
    Predictions(outputs.map(_.result).toArray)
  }

  def apply[T](outputs: Seq[PredictionOutput[T]])(implicit m: Manifest[T]): Predictions[T] = {
    Predictions(outputs.map(_.result).toArray)
  }
}


case class ServingResponse[Type](statusCode: Int, entity: Type) {
  def this(tuple: (Int, Type)) = this(tuple._1, tuple._2)

  override def toString: String = s"[$statusCode, $entity]"

  def isSuccessful: Boolean = statusCode / 100 == 2
}

case class ServingRuntimeException(message: String = null, cause: Throwable = null)
  extends RuntimeException(message, cause) {
  def this(response: ServingResponse[String]) = this(JsonUtil.toJson(response), null)
}

case class ServingError(error: String) {
  override def toString: String = JsonUtil.toJson(this)
}

case class ServingTimerMetrics(
    name: String,
    count: Long,
    meanRate: Double,
    min: Long,
    max: Long,
    mean: Double,
    median: Double,
    stdDev: Double,
    _75thPercentile: Double,
    _95thPercentile: Double,
    _98thPercentile: Double,
    _99thPercentile: Double,
    _999thPercentile: Double
)

object ServingTimerMetrics {
  def apply(name: String, timer: Timer): ServingTimerMetrics =
    ServingTimerMetrics(
      name,
      timer.getCount,
      timer.getMeanRate,
      timer.getSnapshot.getMin / 1000000,
      timer.getSnapshot.getMax / 1000000,
      timer.getSnapshot.getMean / 1000000,
      timer.getSnapshot.getMedian / 1000000,
      timer.getSnapshot.getStdDev / 1000000,
      timer.getSnapshot.get75thPercentile() / 1000000,
      timer.getSnapshot.get95thPercentile() / 1000000,
      timer.getSnapshot.get98thPercentile() / 1000000,
      timer.getSnapshot.get99thPercentile() / 1000000,
      timer.getSnapshot.get999thPercentile() / 1000000
    )
}
