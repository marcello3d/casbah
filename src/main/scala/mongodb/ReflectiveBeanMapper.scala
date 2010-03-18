/**
 * Copyright (c) 2010, Novus Partners, Inc. <http://novus.com>
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
 *
 * NOTICE: Portions of this work are derived from the Apache License 2.0 "mongo-scala-driver" work
 * by Alexander Azarov <azarov@osinka.ru>, available from http://github.com/alaz/mongo-scala-driver
 */

package com.novus.mongodb

import collection.jcl.{LinkedHashMap, HashSet}
import reflect.Manifest
import com.mongodb.DBObject
import com.novus.util.Logging
import java.util.{Map => JMap}
import java.lang.String
import org.scala_tools.javautils.Implicits._
import java.util.Map.Entry

/**
 * The <code>ReflectiveBeanMapper</code> object provides utility functions
 * and internal caching for on-the-fly, reflective Mongo DBObjects.
 *
 * @version 1.0
 * @author Brendan W. McAdams <bmcadams@novus.com>
 * 
 */
object ReflectiveBeanMapper extends Logging {
  type ProxyMethod = (Any, java.lang.Object*) => java.lang.Object
  protected[mongodb] val getters = new scala.collection.mutable.HashMap[Tuple2[Class[_], String], ProxyMethod]
  protected[mongodb] val setters = new scala.collection.mutable.HashMap[Tuple3[Class[_], String, AnyRef], ProxyMethod]

  def apply[A](caller: ReflectiveBeanMapper, field: String)(implicit m: Manifest[A]): Option[ProxyMethod] =  {
    getters.get((caller.getClass, field)) match {
      case Some(getProxy) => Some(getProxy)
      case None => {
        try {
          log.debug("Creating new method mapping for getter %s returning %s", field, m.erasure.getName)
          val methodName = m.erasure.getName match {
            case "Boolean" | "boolean" => "is%s".format(field.capitalize)
            case _ => "get%s".format(field.capitalize)
          }
          log.debug("Looking for a method named %s", methodName)
          val getMethod = caller.getClass.getMethod(methodName)
          val getMethodProxy = getMethod.invoke _
          getters.update((caller.getClass, field), getMethodProxy)
          Some(getMethodProxy)
        } catch {
          case nsmE => None // @todo this is probably a problem.  Compile time check?
        }
      }
    }
  }

  def apply(caller: ReflectiveBeanMapper, field: String, value: Class[_]): Option[ProxyMethod] = {
    log.trace("Value: %s for %s", value.toString, "set%s".format(field.capitalize))
    // @todo make this less...wonky
    val lookupType = value.toString match {
      case "class scala.BigDecimal" =>  classOf[java.math.BigDecimal]
      case "class com.mongodb.BasicDBObject" =>  classOf[com.mongodb.DBObject]
      case unknown => value
    }
    log.trace("Lookup tuple: %s", lookupType)
    setters.get((caller.getClass, field, lookupType)) match {
      case Some(setProxy) => Some(setProxy)
      case None => {
        try {
          log.debug("Creating new method mapping for setter %s on %s", field, lookupType)
          val setMethod = caller.getClass.getMethod("set%s".format(field.capitalize), lookupType)
          val setMethodProxy = setMethod.invoke _
          setters.update((caller.getClass, field, lookupType), setMethodProxy)
          Some(setMethodProxy)
        } catch {
          case nsmE => None // @todo this is probably a problem.  Compile time check?
        }
      }
    }

  }

}

/**
 * <code>ReflectiveBeanMapper</code> trait which provides an implementation of
 * the MongoDB <code>DBObject</code> which has scala-friendly support for sitting on top of a Javabean,
 * and utilising scala-like functionality.
 *
 * Instead of treating a <code>ReflectiveBeanMapper</code> like a Map-like object as you do with a
 * <code>BasicDBObject</code>, you can define Scala methods for setters and getters
 * (see the Tests for examples) which automatically can serialize/deserialize to Mongo DBObject format.
 *
 * At the moment, any non-mapped value will get placed in an 'other_fields' hashmap.  It will be fetchable
 * directly via 'get' but will NOT be included in <code>toMap</code> calls.
 *
 * @todo preload via attributes, currently only puts out values that have been set.
 *
 * @version 1.0
 * @author Brendan W. McAdams <bmcadams@novus.com>
 */
trait ReflectiveBeanMapper extends DBObject with Logging {
  import org.scala_tools.javautils.Implicits._
  private var _partial = false

  val other_fields = new scala.collection.mutable.HashMap[String, Any]
  val serializable_other_fields = scala.collection.mutable.HashSet[String]("_id", "_ns")

  def mongoID = other_fields.get("_id")
  def mongoNS = other_fields.get("_ns")

  /**
   * Define a getter for an OPTIONAL value (one for which you want to get Option[A] instead of
   * null in empty value cases)
   * @param field A string value indicating the fieldName for the getter (e.g. "foo" maps to "getFoo")
   * @param returnType A class of the type of the object you expect to be returned for Casting
   * @param A a type automatically picked up from the classtype of returnType
    @return Option[A]
   */
  def optGetter[A](field: String, returnType: Class[A])(implicit m: Manifest[A]): Option[A] = {
    val out = getter(field, returnType)
    if (out == null) None else Some(out.asInstanceOf[A])
  }

  /**
   * Define a getter for a value ( can potentially return null but it will be nice enough to cast it to A for you)
   * @param field A string value indicating the fieldName for the getter (e.g. "foo" maps to "getFoo")
   * @param returnType A class of the type of the object you expect to be returned for Casting
   * @param A a type automatically picked up from the classtype of returnType
   * @return A
   */  
   def getter[A](field: String, returnType: Class[A])(implicit m: Manifest[A]): A = {
    log.trace("Getter lookup trying for field %s returnType %s", field, returnType)
    ReflectiveBeanMapper(this, field)(m) match {
      case Some(proxy) => {
        log.trace("Got back a getter %s", proxy)
        val ret = proxy(this)
        if (ret != null)
          log.trace("* Return value from getter invocation: %s / %s / erasure: %S / returnType: %s", ret, ret.getClass, m.erasure.toString, returnType)
        if (ret.isInstanceOf[java.math.BigDecimal]) {
          log.debug("JBigDecimal")
          val jBd = ret.asInstanceOf[java.math.BigDecimal]
          log.trace("jBD = %s", jBd)
          m.erasure.getName match {
            case "scala.BigDecimal" => {
              log.trace("Wants a scala BigDecimal {%s}!", jBd)
              val bd = new BigDecimal(jBd)
              log.trace("Flipped to a scala BigDecimal: %s", bd)
              bd.asInstanceOf[A]
            }
            case _ => {
              log.trace("Wants something else.")
              jBd.asInstanceOf[A]
            }
          }
        } else {
          log.trace("No match on the bigdecimal swap.")
          ret.asInstanceOf[A]
        }
      }
      case None => {
        log.trace("Unable to find defined getter for field " + field)
        // @todo - A is lost by erasure... put a manifest in here?
        if (other_fields.contains(field) && m.erasure.isInstance(other_fields.get(field))) {
          other_fields.get(field) match {
            case Some(v) => v.asInstanceOf[A]
            case None => null.asInstanceOf[A]
          }
        }
        else {
          null.asInstanceOf[A]
        }
      }
    }
  }



  def optSetter[A](field: String, value: Option[A])(implicit m: Manifest[A]) {
    val in = value match {
      case None => null
      case Some(value) => value
    }
    log.trace("OptSetter setting %s -> %s on %s [%s]", value, in, field, in.asInstanceOf[A])
    setter(field, in.asInstanceOf[A])
  }

  def setter[A](field: String, value: A)(implicit m: Manifest[A]) {
    log.trace("Setter lookup trying for field %s value %s (Manifest erasure type %s)", field, value, m.erasure)
    val tVal = if (value.isInstanceOf[scala.BigDecimal]) value.asInstanceOf[scala.BigDecimal].bigDecimal else value
    ReflectiveBeanMapper(this, field, m.erasure) match {
      case Some(proxy) => {
        log.trace("Got back a setter %s", proxy)
        val ret = proxy(this, tVal.asInstanceOf[Object])
        log.trace("Return value from setter: %s", ret)
        ret
      }
      case None => {
        log.trace("Unable to find defined setter for field " + field)
        other_fields.update(field, value)
      }
    }
  }


  def dataSet = {

  }
  def toMap = {
    val dataMap =  new LinkedHashMap[String, Any]()
    dataMap ++= ReflectiveBeanMapper.getters.filter(x => x._1._1 == this.getClass).map(x => x._1._2 -> x._2(this))
    log.debug("serializable_other_fields: %s", serializable_other_fields)
    val other = serializable_other_fields.map(x => (x -> other_fields.getOrElse(x, null))).filter(x => x._2 != null)
    log.debug("Adding other field output to dataMap: %s", other)
    dataMap ++= other
    dataMap.underlying
  }


  def removeField(key: String) = {
    val cur = get(key)
    for ((k, v) <- ReflectiveBeanMapper.setters.filter(x => x._1._1 == this.getClass && x._1._2 == key)) {
      v(this, null)
    }
    cur.asInstanceOf[Object]
  }

  def put(key: String, value: AnyRef) = {
    // @todo find a cleaner way to do this.
    try {
      val tVal = if (value.isInstanceOf[scala.BigDecimal]) value.asInstanceOf[scala.BigDecimal].bigDecimal else value
      if (tVal != null) {
          
        log.debug("this: %s key: %s tVal Class: %s", this, key, tVal.getClass)
        ReflectiveBeanMapper(this, key, tVal.getClass) match {
          case Some(proxy) => {
            log.trace("Proxy Method: %s", proxy)
            proxy(this, tVal.asInstanceOf[Object])
          }
          case None => {
            log.trace("Unable to find defined setter for field " + key)
            other_fields.update(key, value)
          }
        }
      }
    } catch {
      case x: NullPointerException => log.warning("Caught a NPE setting %s... Passing. [%s]", key, x)
    }
    value
  }

  def put[A](key: String, value: A)(implicit m: Manifest[A]) = {
    log.trace("Parametered put setting %s to %s[%s]", key, value, m.erasure)
    setter(key, value)
    value.asInstanceOf[Object]
  }


  def putAll(dbObj: DBObject) =  putAll(dbObj.toMap)

  def putAll(fieldMap: Map[_,_]) = {
    for ((k, v) <- fieldMap.asInstanceOf[Map[String, AnyRef]]) {
      try {
        put(k, v)
      } catch {
        case any => log.warning("Can't put %s with %s, failed... %s", k, v, any)
      }
    }
  }


  def putAll(fieldMap: JMap[_, _]) = {
    for (x <- fieldMap.entrySet) {
      try {
        put(x.getKey.toString, x.getValue.asInstanceOf[AnyRef])
      } catch {
       case any => log.warning("Can't put %s , failed... %s", x, any)
      }
    }
  }

  def get(key: String) = {
    ReflectiveBeanMapper(this, key) match {
      case Some(proxy) => {
        proxy(this)
      }
      case None => {
        log.trace("Unable to find defined getter for field " + key)
        if (other_fields.contains(key)) {
          other_fields.get(key) match {
            case Some(v) => v.asInstanceOf[AnyRef]
            case None => null
          }
        }
        else {
          null
        }
      }
    }
  }

  def containsKey(key: String): Boolean = containsField(key)

  def containsField(field: String): Boolean = ReflectiveBeanMapper.getters.contains((this.getClass, field))

  def keySet = {
    val dataSet = new HashSet[String]()
    for(n <- ReflectiveBeanMapper.getters) {
      log.trace("N: %s", n)
      if (n._1._1 == this.getClass) {
        log.trace("Class Match %s == %s", n._1._1, this.getClass)
        dataSet += n._1._2
      } else {
        log.trace("NO Class Match %s == %s", n._1._1, this.getClass)
      }
    }

    dataSet ++= serializable_other_fields.filter(x => other_fields.getOrElse(x, null) != null)

    log.trace("DataSet: %s", dataSet)
    log.trace("Getters: %s", ReflectiveBeanMapper.getters)
    dataSet.underlying
  }


  def markAsPartialObject: Unit = _partial = true

  def isPartialObject: Boolean = _partial

}

