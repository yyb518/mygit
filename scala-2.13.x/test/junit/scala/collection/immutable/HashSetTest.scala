package scala.collection.immutable

import org.junit.Assert.{assertEquals, assertSame}
import org.junit.Test
import org.junit.runner.RunWith
import org.junit.runners.JUnit4
import scala.tools.testing.AssertUtil._

@RunWith(classOf[JUnit4])
class HashSetTest {

  @Test
  def factoryReuse(): Unit = {
    assertSame(HashSet.empty, HashSet.empty)
    assertSame(HashSet.empty, HashSet())
    val m = HashSet("a")
    assertSame(m, HashSet.from(m))
  }

  @Test
  def earlyReturnWhenRemoveAllIterator(): Unit = {
    val xs = (1 to 10).to(HashSet)
    def iter(n: Int) = (1 to n).iterator.concat(new Iterator[Int] {
      override def hasNext = true
      override def next() = throw new RuntimeException("This iterator should not be evaluated")
    })

    assertSame(HashSet.empty, xs.removedAll(iter(10)))
    assertSame(HashSet.empty, xs.removedAll(iter(100)))
    assertThrows[RuntimeException](xs.removedAll(iter(9)))
    assertThrows[RuntimeException](xs.removedAll(iter(1)))
  }
}
