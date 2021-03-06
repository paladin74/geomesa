/*
 * Copyright 2014 Commonwealth Computer Research, Inc.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 * http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package org.locationtech.geomesa.utils.geotools

import java.util.{Date, Locale, UUID}

import com.typesafe.config.{Config, ConfigFactory}
import com.vividsolutions.jts.geom._
import org.geotools.feature.AttributeTypeBuilder
import org.geotools.feature.simple.SimpleFeatureTypeBuilder
import org.locationtech.geomesa.utils.geotools.SimpleFeatureTypes.SpecParser.{ListAttributeType, MapAttributeType, SimpleAttributeType}
import org.locationtech.geomesa.utils.stats.Cardinality.Cardinality
import org.locationtech.geomesa.utils.stats.IndexCoverage.IndexCoverage
import org.locationtech.geomesa.utils.stats.{Cardinality, IndexCoverage}
import org.opengis.feature.`type`.{AttributeDescriptor, GeometryDescriptor}
import org.opengis.feature.simple.SimpleFeatureType

import scala.collection.JavaConversions._
import scala.util.Try
import scala.util.parsing.combinator.JavaTokenParsers

object SimpleFeatureTypes {

  import org.locationtech.geomesa.utils.geotools.RichAttributeDescriptors._

  val TABLE_SPLITTER           = "table.splitter.class"
  val TABLE_SPLITTER_OPTIONS   = "table.splitter.options"
  val DEFAULT_DATE_FIELD       = "geomesa_index_start_time"

  val OPT_INDEX_VALUE          = "index-value"
  val OPT_INDEX                = "index"
  val OPT_CARDINALITY          = "cardinality"

  val USER_DATA_LIST_TYPE      = "subtype"
  val USER_DATA_MAP_KEY_TYPE   = "keyclass"
  val USER_DATA_MAP_VALUE_TYPE = "valueclass"

  def createType(conf: Config): SimpleFeatureType = {
    val nameSpec = conf.getString("type-name")
    val (namespace, name) = buildTypeName(nameSpec)
    val specParser = new SpecParser

    val fields = getFieldConfig(conf).map { fc => buildField(fc, specParser) }
    createType(namespace, name, fields, Seq())
  }

  def getFieldConfig(conf: Config): Seq[Config] =
    if(conf.hasPath("fields")) conf.getConfigList("fields")
    else conf.getConfigList("attributes")

  def buildField(conf: Config, specParser: SpecParser): AttributeSpec = conf.getString("type") match {
    case t if simpleTypeMap.contains(t)   => SimpleAttributeSpec(conf)
    case t if geometryTypeMap.contains(t) => GeomAttributeSpec(conf)

    case t if specParser.parse(specParser.listType, t).successful =>
      ListAttributeSpec(conf)

    case t if specParser.parse(specParser.mapType, t).successful =>
      MapAttributeSpec(conf)
  }

  def createType(nameSpec: String, spec: String): SimpleFeatureType = {
    val (namespace, name) = buildTypeName(nameSpec)
    val FeatureSpec(attributeSpecs, opts) = parse(spec)
    createType(namespace, name, attributeSpecs, opts)
  }

  def createType(namespace: String, name: String,spec: String): SimpleFeatureType = {
    createType(namespace + ":" + name, spec)
  }

  def buildTypeName(nameSpec: String): (String, String) = {
    val nsIndex = nameSpec.lastIndexOf(':')
    val (namespace, name) = if (nsIndex == -1 || nsIndex == nameSpec.length - 1) {
      (null, nameSpec)
    } else {
      (nameSpec.substring(0, nsIndex), nameSpec.substring(nsIndex + 1))
    }
    (namespace, name)
  }

  def createType(namespace: String, name: String, attributeSpecs: Seq[AttributeSpec], opts: Seq[FeatureOption]): SimpleFeatureType = {
    val geomAttributes = attributeSpecs.collect { case g: GeomAttributeSpec => g}
    val defaultGeom = geomAttributes.find(_.default).orElse(geomAttributes.headOption)
    val dateAttributes = attributeSpecs.collect {
      case s: SimpleAttributeSpec if s.clazz == classOf[Date] => s
    }
    val defaultDate = dateAttributes.headOption // TODO GEOMESA-594 allow for setting default date field
    val b = new SimpleFeatureTypeBuilder()
    b.setNamespaceURI(namespace)
    b.setName(name)
    b.addAll(attributeSpecs.map(_.toAttribute))
    defaultGeom.foreach { dg => b.setDefaultGeometry(dg.name)}
    val sft = b.buildFeatureType()
    defaultDate.foreach(dt => sft.getUserData.put(DEFAULT_DATE_FIELD, dt.name))
    opts.map(_.decorateSFT(sft))
    sft
  }

  def encodeType(sft: SimpleFeatureType): String =
    sft.getAttributeDescriptors.map { ad => AttributeSpecFactory.fromAttributeDescriptor(sft, ad).toSpec }.mkString(",")

  def getSecondaryIndexedAttributes(sft: SimpleFeatureType): Seq[AttributeDescriptor] =
    sft.getAttributeDescriptors.filter(ad => ad.isIndexed && !ad.isInstanceOf[GeometryDescriptor])

  object AttributeSpecFactory {
    def fromAttributeDescriptor(sft: SimpleFeatureType, ad: AttributeDescriptor) = ad.getType match {
      case t if simpleTypeMap.contains(t.getBinding.getSimpleName) =>
        SimpleAttributeSpec(
          ad.getLocalName,
          ad.getType.getBinding,
          ad.getIndexCoverage(),
          ad.isIndexValue(),
          ad.getCardinality()
        )

      case t if geometryTypeMap.contains(t.getBinding.getSimpleName) =>
        val srid = Option(ad.asInstanceOf[GeometryDescriptor].getCoordinateReferenceSystem)
            .flatMap(crs => Try(crs.getName.getCode.toInt).toOption)
            .getOrElse(4326)
        GeomAttributeSpec(
          ad.getLocalName,
          ad.getType.getBinding,
          srid,
          sft.getGeometryDescriptor.equals(ad)
        )

      case t if t.getBinding.equals(classOf[java.util.List[_]]) =>
        ListAttributeSpec(
          ad.getLocalName,
          ad.getCollectionType().get,
          ad.getIndexCoverage(),
          ad.getCardinality()
        )

      case t if t.getBinding.equals(classOf[java.util.Map[_, _]]) =>
        val Some((keyType, valueType)) = ad.getMapTypes()
        MapAttributeSpec(
          ad.getLocalName,
          keyType,
          valueType,
          ad.getIndexCoverage(),
          ad.getCardinality()
        )
    }
  }

  sealed trait AttributeSpec {

    def name: String

    def clazz: Class[_]

    def index: IndexCoverage

    def indexValue: Boolean

    def cardinality: Cardinality

    def toAttribute: AttributeDescriptor

    def toSpec: String

    protected def getIndexSpec = {
      val builder = new StringBuilder()
      index match {
        case IndexCoverage.NONE => // don't append
        case _ => builder.append(s":$OPT_INDEX=$index")
      }
      if (indexValue) {
        builder.append(s":$OPT_INDEX_VALUE=$indexValue")
      }
      if (cardinality == Cardinality.LOW || cardinality == Cardinality.HIGH) {
        builder.append(s":$OPT_CARDINALITY=$cardinality")
      }
      builder.toString()
    }
  }

  implicit class AttributeCopyable(val attrSpec: AttributeSpec) extends AnyVal {
    def copy(): AttributeSpec = attrSpec match {
      case o: SimpleAttributeSpec => o.copy()
      case o: GeomAttributeSpec   => o.copy()
      case o: ListAttributeSpec   => o.copy()
      case o: MapAttributeSpec    => o.copy()
    }
  }

  object AttributeSpec {
    val defaults = Map[String, AnyRef](
      OPT_INDEX       -> IndexCoverage.NONE.toString,
      OPT_INDEX_VALUE -> java.lang.Boolean.FALSE,
      OPT_CARDINALITY -> Cardinality.UNKNOWN.toString,
      "srid"          -> Integer.valueOf(4326),
      "default"       -> java.lang.Boolean.FALSE
    )
    val fallback = ConfigFactory.parseMap(defaults)

    // back compatible with string or boolean
    def getIndexCoverage(conf: Config) =
      Try(conf.getString(OPT_INDEX)).flatMap(o => Try(IndexCoverage.withName(o.toLowerCase(Locale.US))))
        .orElse(Try(if (conf.getBoolean(OPT_INDEX)) IndexCoverage.JOIN else IndexCoverage.NONE))
        .getOrElse(IndexCoverage.NONE)
  }

  sealed trait NonGeomAttributeSpec extends AttributeSpec

  object SimpleAttributeSpec {
    def apply(in: Config): SimpleAttributeSpec = {
      val conf = in.withFallback(AttributeSpec.fallback)

      val name        = conf.getString("name")
      val attrType    = conf.getString("type")
      val index       = AttributeSpec.getIndexCoverage(conf)
      val indexValue  = conf.getBoolean(OPT_INDEX_VALUE)
      val cardinality = Cardinality.withName(conf.getString(OPT_CARDINALITY).toLowerCase(Locale.US))
      SimpleAttributeSpec(name, simpleTypeMap(attrType), index, indexValue, cardinality)
    }
  }

  case class SimpleAttributeSpec(name: String,
                                 clazz: Class[_],
                                 index: IndexCoverage,
                                 indexValue: Boolean,
                                 cardinality: Cardinality) extends NonGeomAttributeSpec {

    override def toAttribute: AttributeDescriptor =
      new AttributeTypeBuilder()
          .binding(clazz)
          .indexCoverage(index)
          .indexValue(indexValue)
          .cardinality(cardinality)
          .buildDescriptor(name)

    override def toSpec = s"$name:${typeEncode(clazz)}$getIndexSpec"
  }

  object ListAttributeSpec {
    private val specParser = new SpecParser
    def apply(in: Config): ListAttributeSpec = {
      val conf          = in.withFallback(AttributeSpec.fallback)
      val name          = conf.getString("name")
      val attributeType = specParser.parse(specParser.listType, conf.getString("type")).getOrElse(ListAttributeType(SimpleAttributeType("string")))
      val index         = AttributeSpec.getIndexCoverage(conf)
      val cardinality   = Cardinality.withName(conf.getString(OPT_CARDINALITY).toLowerCase(Locale.US))
      ListAttributeSpec(name, simpleTypeMap(attributeType.p.t), index, cardinality)
    }
  }

  case class ListAttributeSpec(name: String,
                               subClass: Class[_],
                               index: IndexCoverage,
                               cardinality: Cardinality) extends NonGeomAttributeSpec {

    val clazz = classOf[java.util.List[_]]
    // currently we only allow simple types in the ST IDX for simplicity - revisit if it becomes a use-case
    val indexValue = false

    override def toAttribute: AttributeDescriptor = {
      new AttributeTypeBuilder()
          .binding(clazz)
          .indexCoverage(index)
          .indexValue(indexValue)
          .cardinality(cardinality)
          .collectionType(subClass)
          .buildDescriptor(name)
    }

    override def toSpec = s"$name:List[${subClass.getSimpleName}]$getIndexSpec"
  }

  object MapAttributeSpec {
    private val specParser = new SpecParser
    private val defaultType = MapAttributeType(SimpleAttributeType("string"), SimpleAttributeType("string"))
    def apply(in: Config): MapAttributeSpec = {
      val conf          = in.withFallback(AttributeSpec.fallback)
      val name          = conf.getString("name")
      val attributeType = specParser.parse(specParser.mapType, conf.getString("type")).getOrElse(defaultType)
      val index         = AttributeSpec.getIndexCoverage(conf)
      val cardinality   = Cardinality.withName(conf.getString(OPT_CARDINALITY).toLowerCase(Locale.US))
      MapAttributeSpec(name, simpleTypeMap(attributeType.kt.t), simpleTypeMap(attributeType.vt.t), index, cardinality)
    }
  }

  case class MapAttributeSpec(name: String,
                              keyClass: Class[_],
                              valueClass: Class[_],
                              index: IndexCoverage,
                              cardinality: Cardinality) extends NonGeomAttributeSpec {
    val clazz = classOf[java.util.Map[_, _]]
    // currently we only allow simple types in the ST IDX for simplicity - revisit if it becomes a use-case
    val indexValue = false

    override def toAttribute: AttributeDescriptor = {
      new AttributeTypeBuilder()
          .binding(clazz)
          .indexCoverage(index)
          .indexValue(indexValue)
          .cardinality(cardinality)
          .mapTypes(keyClass, valueClass)
          .buildDescriptor(name)
    }

    override def toSpec =
      s"$name:Map[${keyClass.getSimpleName},${valueClass.getSimpleName}]$getIndexSpec"
  }

  object GeomAttributeSpec {
    def apply(in: Config): GeomAttributeSpec = {
      val conf = in.withFallback(AttributeSpec.fallback)

      val name       = conf.getString("name")
      val attrType   = conf.getString("type")
      val srid       = conf.getInt("srid")
      val default    = conf.getBoolean("default")
      GeomAttributeSpec(name, geometryTypeMap(attrType), srid, default)
    }
  }

  case class GeomAttributeSpec(name: String, clazz: Class[_], srid: Int, default: Boolean)
      extends AttributeSpec {
    val index = if (default) IndexCoverage.FULL else IndexCoverage.NONE
    val indexValue = default
    val cardinality = Cardinality.UNKNOWN

    override def toAttribute: AttributeDescriptor = {
      if (!(srid == 4326 || srid == -1)) {
        throw new IllegalArgumentException(s"Invalid SRID '$srid'. Only 4326 is supported.")
      }
      val b = new AttributeTypeBuilder()
      b.binding(clazz)
          .indexCoverage(index)
          .indexValue(indexValue)
          .cardinality(cardinality)
          .crs(CRS_EPSG_4326)
          .buildDescriptor(name)
    }

    override def toSpec = {
      val star = if (default) "*" else ""
      s"$star$name:${typeEncode(clazz)}:srid=$srid$getIndexSpec"
    }
  }

  sealed trait FeatureOption {
    def decorateSFT(sft: SimpleFeatureType): Unit
  }

  case class Splitter(splitterClazz: String, options: Map[String, String]) extends FeatureOption {
    override def decorateSFT(sft: SimpleFeatureType): Unit = {
      sft.getUserData.put(TABLE_SPLITTER, splitterClazz)
      sft.getUserData.put(TABLE_SPLITTER_OPTIONS, options)
    }
  }

  case class FeatureSpec(attributes: Seq[AttributeSpec], opts: Seq[FeatureOption])

  private val typeEncode: Map[Class[_], String] = Map(
    classOf[java.lang.String]    -> "String",
    classOf[java.lang.Integer]   -> "Integer",
    classOf[java.lang.Double]    -> "Double",
    classOf[java.lang.Long]      -> "Long",
    classOf[java.lang.Float]     -> "Float",
    classOf[java.lang.Boolean]   -> "Boolean",
    classOf[UUID]                -> "UUID",
    classOf[Geometry]            -> "Geometry",
    classOf[Point]               -> "Point",
    classOf[LineString]          -> "LineString",
    classOf[Polygon]             -> "Polygon",
    classOf[MultiPoint]          -> "MultiPoint",
    classOf[MultiLineString]     -> "MultiLineString",
    classOf[MultiPolygon]        -> "MultiPolygon",
    classOf[GeometryCollection]  -> "GeometryCollection",
    classOf[Date]                -> "Date",
    classOf[java.util.List[_]]   -> "List",
    classOf[java.util.Map[_, _]] -> "Map"
  )

  private val simpleTypeMap = Map(
    "String"            -> classOf[java.lang.String],
    "java.lang.String"  -> classOf[java.lang.String],
    "string"            -> classOf[java.lang.String],
    "Integer"           -> classOf[java.lang.Integer],
    "java.lang.Integer" -> classOf[java.lang.Integer],
    "int"               -> classOf[java.lang.Integer],
    "Int"               -> classOf[java.lang.Integer],
    "0"                 -> classOf[java.lang.Integer],
    "Long"              -> classOf[java.lang.Long],
    "java.lang.Long"    -> classOf[java.lang.Long],
    "long"              -> classOf[java.lang.Long],
    "Double"            -> classOf[java.lang.Double],
    "java.lang.Double"  -> classOf[java.lang.Double],
    "double"            -> classOf[java.lang.Double],
    "0.0"               -> classOf[java.lang.Double],
    "Float"             -> classOf[java.lang.Float],
    "java.lang.Float"   -> classOf[java.lang.Float],
    "float"             -> classOf[java.lang.Float],
    "0.0f"              -> classOf[java.lang.Float],
    "Boolean"           -> classOf[java.lang.Boolean],
    "java.lang.Boolean" -> classOf[java.lang.Boolean],
    "true"              -> classOf[java.lang.Boolean],
    "false"             -> classOf[java.lang.Boolean],
    "UUID"              -> classOf[UUID],
    "Date"              -> classOf[Date]
  )

  private val geometryTypeMap = Map(
    "Geometry"           -> classOf[Geometry],
    "Point"              -> classOf[Point],
    "LineString"         -> classOf[LineString],
    "Polygon"            -> classOf[Polygon],
    "MultiPoint"         -> classOf[MultiPoint],
    "MultiLineString"    -> classOf[MultiLineString],
    "MultiPolygon"       -> classOf[MultiPolygon],
    "GeometryCollection" -> classOf[GeometryCollection]
  )

  private val listTypeMap = Seq("list", "List", "java.util.List").map { n => (n, classOf[java.util.List[_]]) }.toMap
  private val mapTypeMap  = Seq("map", "Map", "java.util.Map").map { n => (n, classOf[java.util.Map[_, _]]) }.toMap

  object SpecParser {
    case class Name(s: String, default: Boolean = false)
    sealed trait AttributeType
    case class GeometryAttributeType(t: String) extends AttributeType
    case class SimpleAttributeType(t: String) extends AttributeType
    case class ListAttributeType(p: SimpleAttributeType) extends AttributeType
    case class MapAttributeType(kt: SimpleAttributeType, vt: SimpleAttributeType) extends AttributeType

    def optionToCardinality(options: Map[String, String]) = options.get(OPT_CARDINALITY)
        .flatMap(c => Try(Cardinality.withName(c.toLowerCase(Locale.US))).toOption)
        .getOrElse(Cardinality.UNKNOWN)

    def optionToIndexCoverage(options: Map[String, String]) = {
      val o = options.getOrElse(OPT_INDEX, IndexCoverage.NONE.toString)
      val fromName = Try(IndexCoverage.withName(o.toLowerCase(Locale.US)))
      fromName.getOrElse(if (Try(o.toBoolean).getOrElse(false)) IndexCoverage.JOIN else IndexCoverage.NONE)
    }
  }

  private class SpecParser extends JavaTokenParsers {
    import org.locationtech.geomesa.utils.geotools.SimpleFeatureTypes.SpecParser._
    /*
     Valid specs can have attributes that look like the following:
        "id:Integer:opt1=v1:opt2=v2,*geom:Geometry:srid=4326,ct:List[String]:index=true,mt:Map[String,Double]:index=false"
     */

    private val SEP = ":"

    def nonDefaultAttributeName  = """[^:,]+""".r ^^ { n => Name(n) }  // simple name
    def defaultAttributeName     = ("*" ~ nonDefaultAttributeName) ^^ {         // for *geom
      case "*" ~ Name(n, _)   => Name(n, default = true)
    }

    // matches any of the primitive types defined in simpleTypeMap
    // order matters so that Integer is matched before Int
    val simpleType =
      simpleTypeMap
        .keys
        .toList
        .sorted
        .reverse
        .map(literal)
        .reduce(_ | _) ^^ { case s => SimpleAttributeType(s) }

    // matches any of the geometry types defined in geometryTypeMap
    val geometryType =
      geometryTypeMap
        .keys
        .toList
        .sorted
        .reverse
        .map(literal)
        .reduce(_ | _) ^^ { case g => GeometryAttributeType(g) }

    // valid lists
    def listTypeOuter: Parser[String]         = listTypeMap.keys.map(literal).reduce(_ | _)

    // valid maps
    def mapTypeOuter: Parser[String]          = mapTypeMap.keys.map(literal).reduce(_ | _)

    // list type matches "List[String]" or "List" (which defaults to parameterized type String)
    def listType              = listTypeOuter ~> ("[" ~> simpleType <~ "]").? ^^ {
      case st => ListAttributeType(st.getOrElse(SimpleAttributeType("String")))
    }

    // map type matches "Map[String,String]" (defaults to Map[String,String] if parameterized types not specified
    def mapType               = mapTypeOuter ~> ("[" ~> simpleType ~ "," ~ simpleType <~ "]").? ^^ {
      case Some(kt ~ "," ~ vt) => MapAttributeType(kt, vt)
      case None                => MapAttributeType(SimpleAttributeType("String"), SimpleAttributeType("String"))
    }

    // either a list or a map
    def complexType           = listType | mapType

    // simple type or geometry type or complex types
    def attrType              = simpleType | geometryType | complexType

    // converts options into key/values
    def option                = ("[a-zA-Z_.-]+".r <~ "=") ~ "[^:,;]+".r ^^ { case k ~ v => (k, v) }

    // builds a map of key/values
    def options               = repsep(option, SEP) ^^ { kvs => kvs.toMap }

    // options map or empty map if no options specified
    def optionsOrEmptyMap     = (SEP ~> options).? ^^ {
      case Some(opts) => opts
      case None       => Map.empty[String, String]
    }

    // either a name with default prefix or regular name
    def name                  = defaultAttributeName | nonDefaultAttributeName

    // builds a GeometrySpec
    def geometryAttribute     = (name ~ SEP ~ geometryType ~ optionsOrEmptyMap) ^^ {
      case Name(n, default) ~ SEP ~ GeometryAttributeType(t) ~ options =>
        val srid = options.getOrElse("srid", "4326").toInt
        GeomAttributeSpec(n, geometryTypeMap(t), srid, default)
    }

    // builds a NonGeomAttributeSpec for primitive types
    def simpleAttribute       = (name ~ SEP ~ simpleType ~ optionsOrEmptyMap) ^^ {
      case Name(n, default) ~ SEP ~ SimpleAttributeType(t) ~ options =>
        val indexed     = optionToIndexCoverage(options)
        val stIndexed   = options.getOrElse(OPT_INDEX_VALUE, "false").toBoolean
        val cardinality = optionToCardinality(options)
        SimpleAttributeSpec(n, simpleTypeMap(t), indexed, stIndexed, cardinality)
    }

    // builds a NonGeomAttributeSpec for complex types
    def complexAttribute      = (name ~ SEP ~ complexType ~ optionsOrEmptyMap) ^^ {
      case Name(n, default) ~ SEP ~ ListAttributeType(SimpleAttributeType(t)) ~ options =>
        val indexed     = optionToIndexCoverage(options)
        val cardinality = optionToCardinality(options)
        ListAttributeSpec(n, simpleTypeMap(t), indexed, cardinality)

      case Name(n, default) ~ SEP ~ MapAttributeType(SimpleAttributeType(kt), SimpleAttributeType(vt)) ~ options =>
        val indexed     = optionToIndexCoverage(options)
        val cardinality = optionToCardinality(options)
        MapAttributeSpec(n, simpleTypeMap(kt), simpleTypeMap(vt), indexed, cardinality)
    }

    // any attribute
    def attribute             = geometryAttribute | complexAttribute | simpleAttribute

    // "table.splitter=org.locationtech.geomesa.data.DigitSplitter,table.splitter.options=fmt:%02d,"
    def splitter              = (TABLE_SPLITTER ~ "=") ~> "[^,]*".r
    def splitterOption        = ("[^,:]*".r <~ ":") ~ "[^,]*".r ^^ { case k ~ v => (k, v) }
    def splitterOptions       = (TABLE_SPLITTER_OPTIONS ~ "=") ~> repsep(splitterOption, ",") ^^ { opts => opts.toMap }

    def featureOptions        = (splitter <~ ",") ~ splitterOptions.? ^^ {
      case splitter ~ opts => Splitter(splitter, opts.getOrElse(Map.empty[String, String]))
    }

    // a full SFT spec
    def spec                  = repsep(attribute, ",") ~ (";" ~> featureOptions).? ^^ {
      case attrs ~ fOpts => FeatureSpec(attrs, fOpts.toSeq)
    }

    def strip(s: String) = s.stripMargin('|').replaceAll("\\s*", "")

    def parse(s: String): FeatureSpec = parse(spec, strip(s)) match {
      case Success(t, r)     if r.atEnd => t
      case NoSuccess(msg, r) if r.atEnd =>
        throw new IllegalArgumentException(s"Error parsing spec '$s' : $msg")
      case other =>
        throw new IllegalArgumentException(s"Error parsing spec '$s' : $other")
    }
  }

  def parse(s: String): FeatureSpec = new SpecParser().parse(s)

}