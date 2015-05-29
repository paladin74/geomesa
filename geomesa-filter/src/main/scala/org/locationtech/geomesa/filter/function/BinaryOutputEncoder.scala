/*
 * Copyright 2015 Commonwealth Computer Research, Inc.
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

package org.locationtech.geomesa.filter.function

import java.io.OutputStream
import java.util.Date
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.{CountDownLatch, Executors, TimeUnit}

import com.google.common.collect.Queues
import com.typesafe.scalalogging.slf4j.Logging
import com.vividsolutions.jts.geom.{Geometry, LineString, Point}
import org.geotools.data.simple.SimpleFeatureCollection
import org.locationtech.geomesa.utils.geotools.SimpleFeatureTypes
import org.locationtech.geomesa.utils.geotools.SimpleFeatureTypes.ListAttributeSpec
import org.opengis.feature.simple.{SimpleFeature, SimpleFeatureType}

import scala.collection.JavaConversions._

object BinaryOutputEncoder extends Logging {

  import org.locationtech.geomesa.filter.function.AxisOrder._
  import org.locationtech.geomesa.utils.geotools.Conversions._

  case class ValuesToEncode(lat: Float, lon: Float, dtg: Long, track: Option[String], label: Option[Long])

  implicit val ordering = new Ordering[ValuesToEncode]() {
    override def compare(x: ValuesToEncode, y: ValuesToEncode) = x.dtg.compareTo(y.dtg)
  }

  /**
   * Encodes a feature collection to bin format. Features are written to the output stream.
   *
   * @param fc
   * @param output
   * @param dtgField
   * @param trackIdField
   * @param labelField
   * @param latLon
   * @param axisOrder
   * @param sort
   */
  def encodeFeatureCollection(
      fc: SimpleFeatureCollection,
      output: OutputStream,
      dtgField: String,
      trackIdField: Option[String],
      labelField: Option[String] = None,
      latLon: Option[(String, String)] = None,
      axisOrder: AxisOrder = LatLon,
      sort: Boolean = false): Unit = {

    val sft = fc.getSchema
    val isPoint = sft.getGeometryDescriptor.getType.getBinding == classOf[Point]
    val isLineString = !isPoint && sft.getGeometryDescriptor.getType.getBinding == classOf[LineString]

    // validate our input data
    validateDateAttribute(dtgField, sft, isLineString)
    validateLabels(trackIdField, labelField, sft)
    validateLatLong(latLon, sft)

    val sysTime = System.currentTimeMillis
    val dtgIndex = sft.indexOf(dtgField)

    // get date function based
    val getDtg: (SimpleFeature) => Long = (f) => {
      val date = f.getAttribute(dtgIndex).asInstanceOf[Date]
      if (date == null) sysTime else date.getTime
    }
    // for line strings, we need an array of dates corresponding to the points in the line
    val getLineDtg: (SimpleFeature) => Array[Long] = (f) => {
      val dates = f.getAttribute(dtgIndex).asInstanceOf[java.util.List[Date]]
      if (dates == null) Array.empty else dates.map(_.getTime).toArray
    }

    // get lat/lon as floats
    val getLatLon: (SimpleFeature) => (Float, Float) =
      latLon.map { case (lat, lon) =>
        // if requested, export arbitrary fields as lat/lon
        val latIndex = sft.indexOf(lat)
        val lonIndex = sft.indexOf(lon)
        val binding = sft.getType(latIndex).getBinding
        if (binding == classOf[java.lang.Float]) {
          (f: SimpleFeature) =>
            (f.getAttribute(latIndex).asInstanceOf[Float], f.getAttribute(lonIndex).asInstanceOf[Float])
        } else if (binding == classOf[java.lang.Double]) {
          (f: SimpleFeature) =>
            (f.getAttribute(latIndex).asInstanceOf[Double].toFloat, f.getAttribute(lonIndex).asInstanceOf[Double].toFloat)
        } else {
          (f: SimpleFeature) =>
            (f.getAttribute(latIndex).toString.toFloat, f.getAttribute(lonIndex).toString.toFloat)
        }
      }.getOrElse {
        // default is to use the geometry
        // depending on srs requested and wfs versions, axis order can be flipped
        axisOrder match {
          case LatLon =>
            if (isPoint) {
              (f) => pointToXY(f.getDefaultGeometry.asInstanceOf[Point])
            } else {
              (f) => pointToXY(f.getDefaultGeometry.asInstanceOf[Geometry].getInteriorPoint)
            }
          case LonLat =>
            if (isPoint) {
              (f) => pointToXY(f.getDefaultGeometry.asInstanceOf[Point]).swap
            } else {
              (f) => pointToXY(f.getDefaultGeometry.asInstanceOf[Geometry].getInteriorPoint).swap  
            }
        }
      }
    // for linestrings, we return each point - use an array so we get constant-time lookup
    // depending on srs requested and wfs versions, axis order can be flipped
    val getLineLatLon: (SimpleFeature) => Array[(Float, Float)] = axisOrder match {
      case LatLon => (f) => {
        val geom = f.getDefaultGeometry.asInstanceOf[LineString]
        val points = (0 until geom.getNumPoints).map(geom.getPointN)
        points.map(pointToXY).toArray
      }
      case LonLat => (f) => {
        val geom = f.getDefaultGeometry.asInstanceOf[LineString]
        val points = (0 until geom.getNumPoints).map(geom.getPointN)
        points.map(pointToXY(_).swap).toArray
      }
    }

    // gets the track id from a feature
    val getTrackId: (SimpleFeature) => Option[String] = trackIdField match {
      case Some(trackId) if (trackId == "id") => (f) => Some(f.getID)

      case Some(trackId) =>
        val trackIndex  = sft.indexOf(trackId)
        (f) => Option(f.getAttribute(trackIndex)).map(_.toString)

      case None => (_) => None
    }

    // gets the label from a feature
    val getLabel: (SimpleFeature) => Option[Long] = labelField match {
      case Some(label) =>
        val labelIndex = sft.indexOf(label)
        (f) => Option(f.getAttribute(labelIndex).asInstanceOf[Long])

      case None => (_) => None
    }

    // encodes the values in either 16 or 24 bytes
    val encode: (ValuesToEncode) => Unit = labelField match {
      case Some(label) => (v) => {
        val toEncode = ExtendedValues(v.lat, v.lon, v.dtg, v.track, v.label)
        Convert2ViewerFunction.encode(toEncode, output)
      }
      case None => (v) => {
        val toEncode = BasicValues(v.lat, v.lon, v.dtg, v.track)
        Convert2ViewerFunction.encode(toEncode, output)
      }
    }

    val iter = if (isLineString) {
      // expand the line string into individual points to encode
      fc.features().flatMap { sf =>
        val points = getLineLatLon(sf)
        val dates = getLineDtg(sf)
        if (points.size != dates.size) {
          logger.warn(s"Mismatched geometries and dates for simple feature ${sf.getID} - skipping")
          Seq.empty
        } else {
          val trackId = getTrackId(sf)
          val label = getLabel(sf)
          (0 until points.size).map { case i =>
            val (lat, lon) = points(i)
            ValuesToEncode(lat, lon, dates(i), trackId, label)
          }
        }
      }
    } else {
      val encoderQ = Queues.newLinkedBlockingQueue[SimpleFeature]()
      val outQ     = Queues.newLinkedBlockingQueue[ValuesToEncode]()
      val numThreads = sys.props.getOrElse("geomesa.encode.threads", "8").toInt
      val encodingThreads = Executors.newFixedThreadPool(numThreads)
      val done = new AtomicBoolean(false)
      val latch = new CountDownLatch(numThreads)
      (0 until numThreads).foreach { _ =>
        encodingThreads.submit(new Runnable {
          override def run(): Unit = {
            var sf = encoderQ.poll(500, TimeUnit.MILLISECONDS)
            while(sf != null || !done.get()) {
              if(sf != null) {
                val (lat, lon) = getLatLon(sf)
                val dtg = getDtg(sf)
                val trackId = getTrackId(sf)
                val label = getLabel(sf)
                val v = ValuesToEncode(lat, lon, dtg, trackId, label)
                outQ.put(v)
              }
              sf = encoderQ.poll(500, TimeUnit.MILLISECONDS)
            }
            latch.countDown()
          }
        })
      }

      fc.features.foreach { sf => encoderQ.put(sf) }
      done.set(true)
      encodingThreads.shutdown()
      new Iterator[ValuesToEncode] {
        var staged: ValuesToEncode = null

        def stageNext = {
          while(staged == null && !outQ.isEmpty) {
            staged = outQ.poll(1, TimeUnit.SECONDS)
          }
        }
        override def hasNext: Boolean = {
          stageNext
          staged != null
        }

        override def next(): ValuesToEncode = {
          val ret = staged
          staged = null
          ret
        }
      }
    }

    if (sort) {
      iter.toList.sorted.foreach(encode)
    } else {
      iter.foreach(encode)
    }
    // Feature collection has already been closed by SelfClosingIterator
  }

  private def pointToXY(p: Point) = (p.getX.toFloat, p.getY.toFloat)

  private def validateDateAttribute(dtgField: String, sft: SimpleFeatureType, isLineString: Boolean) = {
    val sftAttributes = SimpleFeatureTypes.parse(SimpleFeatureTypes.encodeType(sft)).attributes
    val dateAttribute = sftAttributes.find(_.name == dtgField)
    val ok = dateAttribute.exists { spec =>
      if (isLineString) {
        spec.isInstanceOf[ListAttributeSpec] && spec.asInstanceOf[ListAttributeSpec].subClass == classOf[Date]
      } else {
        spec.clazz == classOf[Date]
      }
    }
    if (!ok) throw new RuntimeException(s"Invalid date field $dtgField requested for feature type $sft")
  }

  private def validateLatLong(latLon: Option[(String, String)], sft: SimpleFeatureType) = {
    val ok = latLon.forall { case (lat, lon) => Seq(lat, lon).forall(sft.indexOf(_) != -1) }
    if (!ok) throw new RuntimeException(s"Invalid lat/lon fields ${latLon.get} requested for feature type $sft")
  }

  private def validateLabels(trackId: Option[String], label: Option[String], sft: SimpleFeatureType) = {
    val ok = Seq(trackId, label).flatten.forall(attr => attr == "id" || sft.indexOf(attr) != -1)
    if (!ok) throw new RuntimeException(s"Invalid fields $trackId and/or $label requested for feature type $sft")
  }
}

object AxisOrder extends Enumeration {
  type AxisOrder = Value
  val LatLon, LonLat = Value
}
