package scala.collection
package mutable

import org.junit.Assert._
import org.junit.Test
import org.junit.runner.RunWith
import org.junit.runners.JUnit4

@RunWith(classOf[JUnit4])
class HashMapTest {

  @Test
  def getOrElseUpdate_mutationInCallback(): Unit = {
    val hm = new mutable.HashMap[String, String]()
    // add enough elements to resize the hash table in the callback
    def add() = 1 to 100000 foreach (i => hm(i.toString) = "callback")
    hm.getOrElseUpdate("0", {
      add()
      ""
    })
    assertEquals(Some(""), hm.get("0"))
  }

  @Test
  def getOrElseUpdate_evalOnce(): Unit = {
    var i = 0
    val hm = new mutable.HashMap[Int, Int]()
    hm.getOrElseUpdate(0, {i += 1; i})
    assertEquals(1, hm(0))
  }

  @Test
  def getOrElseUpdate_noEval(): Unit = {
    val hm = new mutable.HashMap[Int, Int]()
    hm.put(0, 0)
    hm.getOrElseUpdate(0, throw new AssertionError())
  }

  def getOrElseUpdate_keyIdempotence(): Unit = {
    val map = mutable.HashMap[String, String]()

    val key = "key"
    map.getOrElseUpdate(key, {
      map.getOrElseUpdate(key, "value1")
      "value2"
    })

    assertEquals(List((key, "value2")), map.toList)
  }

  @Test
  def customGet(): Unit = {
    val gotItAll = new mutable.HashMap[String, String] {
      override def get(key: String): Option[String] = Some(key)
    }
    assertEquals("a", gotItAll.getOrElse("a", "b"))
    assertEquals("a", gotItAll.getOrElseUpdate("a", "b"))
  }
  @Test
  def testWithDefaultValue: Unit = {
    val m1 = mutable.HashMap(1 -> "a", 2 -> "b")
    val m2 = m1.withDefaultValue("")

    assertEquals(m2(1), "a")
    assertEquals(m2(3), "")

    m2 += (3 -> "c")
    assertEquals(m2(3), "c")
    assertEquals(m2(4), "")

    m2 ++= List(4 -> "d", 5 -> "e", 6 -> "f")
    assertEquals(m2(3), "c")
    assertEquals(m2(4), "d")
    assertEquals(m2(5), "e")
    assertEquals(m2(6), "f")
    assertEquals(m2(7), "")

    m2 --= List(3, 4, 5)
    assertEquals(m2(3), "")
    assertEquals(m2(4), "")
    assertEquals(m2(5), "")
    assertEquals(m2(6), "f")
    assertEquals(m2(7), "")

    val m3 = m2 ++ List(3 -> "333")
    assertEquals(m2(3), "")
    assertEquals(m3(3), "333")
  }
  @Test
  def testWithDefault: Unit = {
    val m1 = mutable.HashMap(1 -> "a", 2 -> "b")

    val m2: mutable.Map.WithDefault[Int, String] = m1.withDefault(i => (i + 1).toString)
    m2.update(1, "aa")
    m2.update(100, "bb")
    m2.addAll(List(500 -> "c", 501 -> "c"))

    assertEquals(m2(1), "aa")
    assertEquals(m2(2), "b")
    assertEquals(m2(3), "4")
    assertEquals(m2(4), "5")
    assertEquals(m2(500), "c")
    assertEquals(m2(501), "c")
    assertEquals(m2(502), "503")

    val m3: mutable.Map.WithDefault[Int, String] = m2 - 1
    assertEquals(m3(1), "2")

    val m4: mutable.Map.WithDefault[Int, String] = m3 -- List(2, 100)
    assertEquals(m4(2), "3")
    assertEquals(m4(100), "101")
  }
  @Test
  def testUpdateWith(): Unit = {
    val insertIfAbsent: Option[String] => Option[String] = _.orElse(Some("b"))
    val hashMap1 = mutable.HashMap(1 -> "a")
    assertEquals(hashMap1.updateWith(1)(insertIfAbsent), Some("a"))
    assertEquals(hashMap1, mutable.HashMap(1 -> "a"))
    val hashMap2 = mutable.HashMap(1 -> "a")
    assertEquals(hashMap2.updateWith(2)(insertIfAbsent), Some("b"))
    assertEquals(hashMap2, mutable.HashMap(1 -> "a", 2 -> "b"))

    val noneAnytime: Option[String] => Option[String] =  _ => None
    val hashMap3 = mutable.HashMap(1 -> "a")
    assertEquals(hashMap3.updateWith(1)(noneAnytime), None)
    assertEquals(hashMap3, mutable.HashMap())
    val hashMap4 = mutable.HashMap(1 -> "a")
    assertEquals(hashMap4.updateWith(2)(noneAnytime), None)
    assertEquals(hashMap4, mutable.HashMap(1 -> "a"))
  }
}
