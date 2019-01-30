/*
 * Scala (https://www.scala-lang.org)
 *
 * Copyright EPFL and Lightbend, Inc.
 *
 * Licensed under Apache License 2.0
 * (http://www.apache.org/licenses/LICENSE-2.0).
 *
 * See the NOTICE file distributed with this work for
 * additional information regarding copyright ownership.
 */

package scala
package collection.immutable

import java.lang.Integer.bitCount
import java.lang.System.arraycopy

import scala.annotation.unchecked.{uncheckedVariance => uV}
import scala.collection.Hashing.improve
import scala.collection.mutable.{Builder, ReusableBuilder}
import scala.collection.{Iterator, MapFactory, StrictOptimizedIterableOps, mutable}
import scala.util.hashing.MurmurHash3
import scala.runtime.Statics.releaseFence

/** This class implements immutable maps using a Compressed Hash-Array Mapped Prefix-tree.
  * See paper https://michael.steindorfer.name/publications/oopsla15.pdf for more details.
  *
  *  @tparam K      the type of the keys contained in this hash set.
  *  @tparam V      the type of the values associated with the keys in this hash map.
  *
  *  @author  Michael J. Steindorfer
  *  @since   2.13
  *  @define Coll `immutable.HashMap`
  *  @define coll immutable champ hash map
  */

final class HashMap[K, +V] private[immutable] (private[immutable] val rootNode: MapNode[K, V])
  extends AbstractMap[K, V]
    with StrictOptimizedMapOps[K, V, HashMap, HashMap[K, V]] {

  def this() = this(MapNode.empty)

  releaseFence()

  override def mapFactory: MapFactory[HashMap] = HashMap

  override def knownSize: Int = rootNode.size

  override def size: Int = rootNode.size

  override def isEmpty: Boolean = rootNode.size == 0

  def iterator: Iterator[(K, V)] = {
    if (isEmpty) Iterator.empty
    else new MapKeyValueTupleIterator[K, V](rootNode)
  }

  override def keysIterator: Iterator[K] = {
    if (isEmpty) Iterator.empty
    else new MapKeyIterator[K, V](rootNode)
  }
  override def valuesIterator: Iterator[V] = {
    if (isEmpty) Iterator.empty
    else new MapValueIterator[K, V](rootNode)
  }

  protected[immutable] def reverseIterator: Iterator[(K, V)] = {
    if (isEmpty) Iterator.empty
    else new MapKeyValueTupleReverseIterator[K, V](rootNode)
  }

  override final def contains(key: K): Boolean = {
    val keyUnimprovedHash = key.##
    val keyHash = improve(keyUnimprovedHash)
    rootNode.containsKey(key, keyUnimprovedHash, keyHash, 0)
  }

  override def apply(key: K): V = {
    val keyUnimprovedHash = key.##
    val keyHash = improve(keyUnimprovedHash)
    rootNode.apply(key, keyUnimprovedHash, keyHash, 0)
  }

  def get(key: K): Option[V] = {
    val keyUnimprovedHash = key.##
    val keyHash = improve(keyUnimprovedHash)
    rootNode.get(key, keyUnimprovedHash, keyHash, 0)
  }

  override def getOrElse[V1 >: V](key: K, default: => V1): V1 = {
    val keyUnimprovedHash = key.##
    val keyHash = improve(keyUnimprovedHash)
    rootNode.getOrElse(key, keyUnimprovedHash, keyHash, 0, default)
  }

  def updated[V1 >: V](key: K, value: V1): HashMap[K, V1] = {
    val keyUnimprovedHash = key.##
    val keyHash = improve(keyUnimprovedHash)
    val newRootNode = rootNode.updated(key, value, keyUnimprovedHash, keyHash, 0)
    if (newRootNode ne rootNode) new HashMap(newRootNode) else this
  }

  def removed(key: K): HashMap[K, V] = {
    val keyUnimprovedHash = key.##
    val keyHash = improve(keyUnimprovedHash)
    val newRootNode = rootNode.removed(key, keyUnimprovedHash, keyHash, 0)
    if (newRootNode ne rootNode) new HashMap(newRootNode) else this
  }

  override def concat[V1 >: V](that: scala.IterableOnce[(K, V1)]): HashMap[K, V1] = that match {
    case hm: HashMap[K, V1] =>
      if (hm.rootNode eq rootNode) this else {
        val newRootNode = rootNode.concat(hm.rootNode, 0)
        if (newRootNode eq rootNode) this
        else if (newRootNode eq hm.rootNode) hm
        else new HashMap(newRootNode)
      }
    case _ =>
      mapFactory.newBuilder[K, V1].addAll(this).addAll(that).result()
  }


  override def tail: HashMap[K, V] = this - head._1

  override def init: HashMap[K, V] = this - last._1

  override def head: (K, V) = iterator.next()

  override def last: (K, V) = reverseIterator.next()

  override def foreach[U](f: ((K, V)) => U): Unit = rootNode.foreach(f)

  override def equals(that: Any): Boolean =
    that match {
      case map: HashMap[K, V] => (this eq map) || (this.rootNode == map.rootNode)
      case _ => super.equals(that)
    }

  override def hashCode(): Int = {
    val hashIterator = new MapKeyValueTupleHashIterator(rootNode)
    val hash = MurmurHash3.unorderedHash(hashIterator, MurmurHash3.mapSeed)
    // assert(hash == super.hashCode())
    hash
  }

  override protected[this] def className = "HashMap"

  private type MergeFunction[A1, B1] = ((A1, B1), (A1, B1)) => (A1, B1)

  //TODO optimize (https://github.com/scala/bug/issues/11077)
  def merged[V1 >: V](that: HashMap[K, V1])(mergef: MergeFunction[K, V1]): HashMap[K, V1] = {
    val thisKeys = this.keySet
    if(mergef eq null)
      that.removedAll(thisKeys) ++ this
    else {
      val thatKeys = that.keySet
      that.removedAll(thisKeys) ++ this.removedAll(thatKeys) ++ thisKeys.intersect(thatKeys).map { case k => mergef((k, this(k)), (k, that(k))) }
    }
  }

  override def transform[W](f: (K, V) => W) = {
    val transformed = rootNode.transform(f)
    if (transformed eq rootNode) this.asInstanceOf[HashMap[K, W]]
    else new HashMap(transformed)
  }

  override protected[collection] def filterImpl(pred: ((K, V)) => Boolean, isFlipped: Boolean): HashMap[K, V] = {
    val newRootNode = rootNode.filterImpl(pred, isFlipped)
    if (newRootNode eq rootNode) this
    else if (newRootNode.size == 0) HashMap.empty
    else new HashMap(newRootNode)
  }

  override def removedAll(keys: IterableOnce[K]): HashMap[K, V] = {
    if (isEmpty) {
      this
    } else {
      keys match {
        case hashSet: HashSet[K] =>
          if (hashSet.isEmpty) {
            this
          } else {
            // TODO: Remove all keys from the hashSet in a sub-linear fashion by only visiting the nodes in the tree
            // This can be a direct port of the implementation of `SetNode[A]#diff(SetNode[A])`
            val newRootNode = new MapNodeRemoveAllSetNodeIterator(hashSet.rootNode).removeAll(rootNode)
            if (newRootNode eq rootNode) this
            else if (newRootNode.size <= 0) HashMap.empty
            else new HashMap(newRootNode)
          }
        case _ =>
          val iter = keys.iterator
          var curr = rootNode
          while (iter.hasNext) {
            val next = iter.next()
            val originalHash = next.##
            val improved = improve(originalHash)
            curr = curr.removed(next, originalHash, improved, 0)
            if (curr.size <= 0) {
              return HashMap.empty
            }
          }

          if (curr eq rootNode) this else new HashMap(curr)
      }
    }
  }

  override def partition(p: ((K, V)) => Boolean): (HashMap[K, V], HashMap[K, V]) = {
    // This method has been preemptively overridden in order to ensure that an optimizing implementation may be included
    // in a minor release without breaking binary compatibility.
    //
    // In particular, `partition` could be optimized to traverse the trie node-by-node, splitting each node into two,
    // based on the result of applying `p` to its elements and subnodes.
    super.partition(p)
  }

  override def take(n: Int): HashMap[K, V] = {
    // This method has been preemptively overridden in order to ensure that an optimizing implementation may be included
    // in a minor release without breaking binary compatibility.
    //
    // In particular, `take` could be optimized to construct a new trie structure by visiting each node, and including
    // those nodes in the resulting trie, until `n` total elements have been included.
    super.take(n)
  }

  override def takeRight(n: Int): HashMap[K, V] = {
    // This method has been preemptively overridden in order to ensure that an optimizing implementation may be included
    // in a minor release without breaking binary compatibility.
    //
    // In particular, `take` could be optimized to construct a new trie structure by visiting each node in reverse, and
    // and including those nodes in the resulting trie, until `n` total elements have been included.
    super.takeRight(n)
  }

  override def takeWhile(p: ((K, V)) => Boolean): HashMap[K, V] = {
    // This method has been preemptively overridden in order to ensure that an optimizing implementation may be included
    // in a minor release without breaking binary compatibility.
    //
    // In particular, `takeWhile` could be optimized to construct a new trie structure by visiting each node, and
    // including those nodes in the resulting trie, until `p` returns `false`
    super.takeWhile(p)
  }

  override def dropWhile(p: ((K, V)) => Boolean): HashMap[K, V] = {
    // This method has been preemptively overridden in order to ensure that an optimizing implementation may be included
    // in a minor release without breaking binary compatibility.
    //
    // In particular, `dropWhile` could be optimized to construct a new trie structure by visiting each node, and
    // dropping those nodes in the resulting trie, until `p` returns `true`
    super.dropWhile(p)
  }

  override def dropRight(n: Int): HashMap[K, V] = {
    // This method has been preemptively overridden in order to ensure that an optimizing implementation may be included
    // in a minor release without breaking binary compatibility.
    //
    // In particular, `dropRight` could be optimized to construct a new trie structure by visiting each node, in reverse
    // order, and dropping all nodes until `n` elements have been dropped
    super.dropRight(n)
  }

  override def drop(n: Int): HashMap[K, V] = {
    // This method has been preemptively overridden in order to ensure that an optimizing implementation may be included
    // in a minor release without breaking binary compatibility.
    //
    // In particular, `dropRight` could be optimized to construct a new trie structure by visiting each node, and
    // dropping all nodes until `n` elements have been dropped
    super.drop(n)
  }

  override def span(p: ((K, V)) => Boolean): (HashMap[K, V], HashMap[K, V]) = {
    // This method has been preemptively overridden in order to ensure that an optimizing implementation may be included
    // in a minor release without breaking binary compatibility.
    //
    // In particular, `scan` could be optimized to construct a new trie structure by visiting each node, and
    // keeping each node and element until `p` returns false, then including the remaining nodes in the second result.
    // This would avoid having to rebuild most of the trie, and would eliminate the need to perform hashing and equality
    // checks.
    super.span(p)
  }

}

private[immutable] object MapNode {

  private final val EmptyMapNode = new BitmapIndexedMapNode(0, 0, Array.empty, Array.empty, 0, 0)

  def empty[K, V]: MapNode[K, V] =
    EmptyMapNode.asInstanceOf[MapNode[K, V]]

  final val TupleLength = 2

}


private[immutable] sealed abstract class MapNode[K, +V] extends Node[MapNode[K, V @uV]] {
  def apply(key: K, originalHash: Int, hash: Int, shift: Int): V

  def get(key: K, originalHash: Int, hash: Int, shift: Int): Option[V]

  def getOrElse[V1 >: V](key: K, originalHash: Int, hash: Int, shift: Int, f: => V1): V1

  def containsKey(key: K, originalHash: Int, hash: Int, shift: Int): Boolean

  def updated[V1 >: V](key: K, value: V1, originalHash: Int, hash: Int, shift: Int): MapNode[K, V1]

  def removed[V1 >: V](key: K, originalHash: Int, hash: Int, shift: Int): MapNode[K, V1]

  def hasNodes: Boolean

  def nodeArity: Int

  def getNode(index: Int): MapNode[K, V]

  def hasPayload: Boolean

  def payloadArity: Int

  def getKey(index: Int): K

  def getValue(index: Int): V

  def getPayload(index: Int): (K, V)

  def sizePredicate: Int

  def size: Int

  def foreach[U](f: ((K, V)) => U): Unit

  def transform[W](f: (K, V) => W): MapNode[K, W]

  def copy(): MapNode[K, V]

  def concat[V1 >: V](that: MapNode[K, V1], shift: Int): MapNode[K, V1]

  def filterImpl(pred: ((K, V)) => Boolean, isFlipped: Boolean): MapNode[K, V]
}

private final class BitmapIndexedMapNode[K, +V](
  var dataMap: Int,
  var nodeMap: Int,
  var content: Array[Any],
  var originalHashes: Array[Int],
  var size: Int,
  var cachedJavaKeySetHashCode: Int) extends MapNode[K, V] {

  releaseFence()

  import MapNode._
  import Node._

  /*
  assert(checkInvariantContentIsWellTyped())
  assert(checkInvariantSubNodesAreCompacted())

  private final def checkInvariantSubNodesAreCompacted(): Boolean =
    new MapKeyValueTupleIterator[K, V](this).size - payloadArity >= 2 * nodeArity

  private final def checkInvariantContentIsWellTyped(): Boolean = {
    val predicate1 = TupleLength * payloadArity + nodeArity == content.length

    val predicate2 = Range(0, TupleLength * payloadArity)
      .forall(i => content(i).isInstanceOf[MapNode[_, _]] == false)

    val predicate3 = Range(TupleLength * payloadArity, content.length)
      .forall(i => content(i).isInstanceOf[MapNode[_, _]] == true)

    predicate1 && predicate2 && predicate3
  }
  */

  def getKey(index: Int): K = content(TupleLength * index).asInstanceOf[K]
  def getValue(index: Int): V = content(TupleLength * index + 1).asInstanceOf[V]

  def getPayload(index: Int) = Tuple2(
    content(TupleLength * index).asInstanceOf[K],
    content(TupleLength * index + 1).asInstanceOf[V])

  override def getHash(index: Int): Int = originalHashes(index)

  def getNode(index: Int) =
    content(content.length - 1 - index).asInstanceOf[MapNode[K, V]]

  def apply(key: K, originalHash: Int, keyHash: Int, shift: Int): V = {
    val mask = maskFrom(keyHash, shift)
    val bitpos = bitposFrom(mask)

    if ((dataMap & bitpos) != 0) {
      val index = indexFrom(dataMap, mask, bitpos)
      val key0 = this.getKey(index)
      return if (key == key0) this.getValue(index) else throw new NoSuchElementException
    }

    if ((nodeMap & bitpos) != 0) {
      val index = indexFrom(nodeMap, mask, bitpos)
      return this.getNode(index).apply(key, originalHash, keyHash, shift + BitPartitionSize)
    }

    throw new NoSuchElementException
  }

  def get(key: K, originalHash: Int, keyHash: Int, shift: Int): Option[V] = {
    val mask = maskFrom(keyHash, shift)
    val bitpos = bitposFrom(mask)

    if ((dataMap & bitpos) != 0) {
      val index = indexFrom(dataMap, mask, bitpos)
      val key0 = this.getKey(index)
      return if (key == key0) Some(this.getValue(index)) else None
    }

    if ((nodeMap & bitpos) != 0) {
      val index = indexFrom(nodeMap, mask, bitpos)
      return this.getNode(index).get(key, originalHash, keyHash, shift + BitPartitionSize)
    }

    None
  }
  def getOrElse[V1 >: V](key: K, originalHash: Int, keyHash: Int, shift: Int, f: => V1): V1 = {
    val mask = maskFrom(keyHash, shift)
    val bitpos = bitposFrom(mask)

    if ((dataMap & bitpos) != 0) {
      val index = indexFrom(dataMap, mask, bitpos)
      val key0 = this.getKey(index)
      return if (key == key0) this.getValue(index) else f
    }

    if ((nodeMap & bitpos) != 0) {
      val index = indexFrom(nodeMap, mask, bitpos)
      return this.getNode(index).getOrElse(key, originalHash, keyHash, shift + BitPartitionSize, f)
    }
    f
  }

  override def containsKey(key: K, originalHash: Int, keyHash: Int, shift: Int): Boolean = {
    val mask = maskFrom(keyHash, shift)
    val bitpos = bitposFrom(mask)

    if ((dataMap & bitpos) != 0) {
      val index = indexFrom(dataMap, mask, bitpos)
      // assert(hashes(index) == computeHash(this.getKey(index)), (hashes.toSeq, content.toSeq, index, key, keyHash, shift))
      return (originalHashes(index) == originalHash) && key == this.getKey(index)
    }

    if ((nodeMap & bitpos) != 0) {
      val index = indexFrom(nodeMap, mask, bitpos)
      return this.getNode(index).containsKey(key, originalHash, keyHash, shift + BitPartitionSize)
    }

    false
  }

  def updated[V1 >: V](key: K, value: V1, originalHash: Int, keyHash: Int, shift: Int): MapNode[K, V1] = {
    val mask = maskFrom(keyHash, shift)
    val bitpos = bitposFrom(mask)

    if ((dataMap & bitpos) != 0) {
      val index = indexFrom(dataMap, mask, bitpos)
      val key0 = getKey(index)
      val key0UnimprovedHash = getHash(index)
      if (key0UnimprovedHash == originalHash && key0 == key) {
        val value0 = this.getValue(index)
        return (
          if ((key0.asInstanceOf[AnyRef] eq key.asInstanceOf[AnyRef]) && (value0.asInstanceOf[AnyRef] eq value.asInstanceOf[AnyRef]))
            this
          else copyAndSetValue(bitpos, key, value)
        )
      } else {
        val value0 = this.getValue(index)
        val key0Hash = improve(key0UnimprovedHash)

        val subNodeNew = mergeTwoKeyValPairs(key0, value0, key0UnimprovedHash, key0Hash, key, value, originalHash, keyHash, shift + BitPartitionSize)
        return copyAndMigrateFromInlineToNode(bitpos, key0Hash, subNodeNew)
      }
    }

    if ((nodeMap & bitpos) != 0) {
      val index = indexFrom(nodeMap, mask, bitpos)
      val subNode = this.getNode(index)

      val subNodeNew = subNode.updated(key, value, originalHash, keyHash, shift + BitPartitionSize)
      if (subNodeNew eq subNode) {
        return this
      } else {
        return copyAndSetNode(bitpos, subNode, subNodeNew)
      }
    }

    copyAndInsertValue(bitpos, key, originalHash, keyHash, value)
  }

  def removed[V1 >: V](key: K, originalHash: Int, keyHash: Int, shift: Int): MapNode[K, V1] = {
    val mask = maskFrom(keyHash, shift)
    val bitpos = bitposFrom(mask)

    if ((dataMap & bitpos) != 0) {
      val index = indexFrom(dataMap, mask, bitpos)
      val key0 = this.getKey(index)

      if (key0 == key) {
        if (this.payloadArity == 2 && this.nodeArity == 0) {
          /*
           * Create new node with remaining pair. The new node will a) either become the new root
           * returned, or b) unwrapped and inlined during returning.
           */
          val newDataMap = if (shift == 0) (dataMap ^ bitpos) else bitposFrom(maskFrom(keyHash, 0))
          if (index == 0)
            return new BitmapIndexedMapNode[K, V1](newDataMap, 0, Array(getKey(1), getValue(1)), Array(originalHashes(1)), 1, improve(getHash(1)))
          else
            return new BitmapIndexedMapNode[K, V1](newDataMap, 0, Array(getKey(0), getValue(0)), Array(originalHashes(0)), 1, improve(getHash(0)))
        }
        else return copyAndRemoveValue(bitpos, keyHash)
      } else return this
    }

    if ((nodeMap & bitpos) != 0) {
      val index = indexFrom(nodeMap, mask, bitpos)
      val subNode = this.getNode(index)

      val subNodeNew = subNode.removed(key, originalHash, keyHash, shift + BitPartitionSize)
      // assert(subNodeNew.sizePredicate != SizeEmpty, "Sub-node must have at least one element.")

      if (subNodeNew eq subNode) return this
      subNodeNew.sizePredicate match {
        case SizeOne =>
          if (this.payloadArity == 0 && this.nodeArity == 1) { // escalate (singleton or empty) result
            return subNodeNew
          }
          else { // inline value (move to front)
            return copyAndMigrateFromNodeToInline(bitpos, originalHash, keyHash, subNode, subNodeNew)
          }

        case SizeMoreThanOne =>
          // modify current node (set replacement node)
          return copyAndSetNode(bitpos, subNode, subNodeNew)
      }
    }

    this
  }

  def mergeTwoKeyValPairs[V1 >: V](key0: K, value0: V1, originalHash0: Int, keyHash0: Int, key1: K, value1: V1, originalHash1: Int, keyHash1: Int, shift: Int): MapNode[K, V1] = {
    // assert(key0 != key1)

    if (shift >= HashCodeLength) {
      new HashCollisionMapNode[K, V1](originalHash0, keyHash0, Vector((key0, value0), (key1, value1)))
    } else {
      val mask0 = maskFrom(keyHash0, shift)
      val mask1 = maskFrom(keyHash1, shift)
      val newCachedHash = keyHash0 + keyHash1

      if (mask0 != mask1) {
        // unique prefixes, payload fits on same level
        val dataMap = bitposFrom(mask0) | bitposFrom(mask1)

        if (mask0 < mask1) {
          new BitmapIndexedMapNode[K, V1](dataMap, 0, Array(key0, value0, key1, value1), Array(originalHash0, originalHash1), 2, newCachedHash)
        } else {
          new BitmapIndexedMapNode[K, V1](dataMap, 0, Array(key1, value1, key0, value0), Array(originalHash1, originalHash0), 2, newCachedHash)
        }
      } else {
        // identical prefixes, payload must be disambiguated deeper in the trie
        val nodeMap = bitposFrom(mask0)
        val node = mergeTwoKeyValPairs(key0, value0, originalHash0, keyHash0, key1, value1, originalHash1, keyHash1, shift + BitPartitionSize)
        new BitmapIndexedMapNode[K, V1](0, nodeMap, Array(node), Array(), node.size, node.cachedJavaKeySetHashCode)
      }
    }
  }
  
  def sizePredicate: Int =
    if (nodeArity == 0) payloadArity match {
      case 0 => SizeEmpty
      case 1 => SizeOne
      case _ => SizeMoreThanOne
    } else SizeMoreThanOne

  def hasNodes: Boolean = nodeMap != 0

  def nodeArity: Int = bitCount(nodeMap)

  def hasPayload: Boolean = dataMap != 0

  def payloadArity: Int = bitCount(dataMap)

  def dataIndex(bitpos: Int) = bitCount(dataMap & (bitpos - 1))

  def nodeIndex(bitpos: Int) = bitCount(nodeMap & (bitpos - 1))

  def copyAndSetValue[V1 >: V](bitpos: Int, newKey: K, newValue: V1): MapNode[K, V1] = {
    val dataIx = dataIndex(bitpos)
    val idx = TupleLength * dataIx

    val src = this.content
    val dst = new Array[Any](src.length)

    // copy 'src' and set 1 element(s) at position 'idx'
    arraycopy(src, 0, dst, 0, src.length)
    //dst(idx) = newKey
    dst(idx + 1) = newValue
    new BitmapIndexedMapNode[K, V1](dataMap, nodeMap, dst, originalHashes, size, cachedJavaKeySetHashCode)
  }

  def copyAndSetNode[V1 >: V](bitpos: Int, oldNode: MapNode[K, V1], newNode: MapNode[K, V1]): MapNode[K, V1] = {
    val idx = this.content.length - 1 - this.nodeIndex(bitpos)

    val src = this.content
    val dst = new Array[Any](src.length)

    // copy 'src' and set 1 element(s) at position 'idx'
    arraycopy(src, 0, dst, 0, src.length)
    dst(idx) = newNode
    new BitmapIndexedMapNode[K, V1](
      dataMap,
      nodeMap,
      dst,
      originalHashes,
      size - oldNode.size + newNode.size,
      cachedJavaKeySetHashCode - oldNode.cachedJavaKeySetHashCode + newNode.cachedJavaKeySetHashCode
    )
  }

  def copyAndInsertValue[V1 >: V](bitpos: Int, key: K, originalHash: Int, keyHash: Int, value: V1): BitmapIndexedMapNode[K, V1] = {
    val dataIx = dataIndex(bitpos)
    val idx = TupleLength * dataIx

    val src = this.content
    val dst = new Array[Any](src.length + TupleLength)

    // copy 'src' and insert 2 element(s) at position 'idx'
    arraycopy(src, 0, dst, 0, idx)
    dst(idx) = key
    dst(idx + 1) = value
    arraycopy(src, idx, dst, idx + TupleLength, src.length - idx)

    val dstHashes = insertElement(originalHashes, dataIx, originalHash)

    new BitmapIndexedMapNode[K, V1](dataMap | bitpos, nodeMap, dst, dstHashes, size + 1, cachedJavaKeySetHashCode + keyHash)
  }

  def copyAndRemoveValue(bitpos: Int, keyHash: Int): BitmapIndexedMapNode[K, V] = {
    val dataIx = dataIndex(bitpos)
    val idx = TupleLength * dataIx

    val src = this.content
    val dst = new Array[Any](src.length - TupleLength)

    // copy 'src' and remove 2 element(s) at position 'idx'
    arraycopy(src, 0, dst, 0, idx)
    arraycopy(src, idx + TupleLength, dst, idx, src.length - idx - TupleLength)

    val dstHashes = removeElement(originalHashes, dataIx)

    new BitmapIndexedMapNode[K, V](dataMap ^ bitpos, nodeMap, dst, dstHashes, size - 1, cachedJavaKeySetHashCode - keyHash)
  }

  def copyAndMigrateFromInlineToNode[V1 >: V](bitpos: Int, keyHash: Int, node: MapNode[K, V1]): BitmapIndexedMapNode[K, V1] = {
    val dataIx = dataIndex(bitpos)
    val idxOld = TupleLength * dataIx
    val idxNew = this.content.length - TupleLength - nodeIndex(bitpos)

    val src = this.content
    val dst = new Array[Any](src.length - TupleLength + 1)

    // copy 'src' and remove 2 element(s) at position 'idxOld' and
    // insert 1 element(s) at position 'idxNew'
    // assert(idxOld <= idxNew)
    arraycopy(src, 0, dst, 0, idxOld)
    arraycopy(src, idxOld + TupleLength, dst, idxOld, idxNew - idxOld)
    dst(idxNew) = node
    arraycopy(src, idxNew + TupleLength, dst, idxNew + 1, src.length - idxNew - TupleLength)

    val dstHashes = removeElement(originalHashes, dataIx)

    new BitmapIndexedMapNode[K, V1](
      dataMap = dataMap ^ bitpos,
      nodeMap = nodeMap | bitpos,
      content = dst,
      originalHashes = dstHashes,
      size = size - 1 + node.size,
      cachedJavaKeySetHashCode = cachedJavaKeySetHashCode - keyHash + node.cachedJavaKeySetHashCode
    )
  }

  def copyAndMigrateFromNodeToInline[V1 >: V](bitpos: Int, originalHash: Int, keyHash: Int, oldNode: MapNode[K, V1], node: MapNode[K, V1]): BitmapIndexedMapNode[K, V1] = {
    val idxOld = this.content.length - 1 - nodeIndex(bitpos)
    val dataIxNew = dataIndex(bitpos)
    val idxNew = TupleLength * dataIxNew

    val key = node.getKey(0)
    val value = node.getValue(0)
    val src = this.content
    val dst = new Array[Any](src.length - 1 + TupleLength)

    // copy 'src' and remove 1 element(s) at position 'idxOld' and
    // insert 2 element(s) at position 'idxNew'
    // assert(idxOld >= idxNew)
    arraycopy(src, 0, dst, 0, idxNew)
    dst(idxNew) = key
    dst(idxNew + 1) = value
    arraycopy(src, idxNew, dst, idxNew + TupleLength, idxOld - idxNew)
    arraycopy(src, idxOld + 1, dst, idxOld + TupleLength, src.length - idxOld - 1)
    val hash = node.getHash(0)
    val dstHashes = insertElement(originalHashes, dataIxNew, hash)
    new BitmapIndexedMapNode[K, V1](
      dataMap = dataMap | bitpos,
      nodeMap = nodeMap ^ bitpos,
      content = dst,
      originalHashes = dstHashes,
      size = size - oldNode.size + 1,
      cachedJavaKeySetHashCode = cachedJavaKeySetHashCode - oldNode.cachedJavaKeySetHashCode + node.cachedJavaKeySetHashCode
    )
  }

  override def foreach[U](f: ((K, V)) => U): Unit = {
    var i = 0
    while (i < payloadArity) {
      f(getPayload(i))
      i += 1
    }

    var j = 0
    while (j < nodeArity) {
      getNode(j).foreach(f)
      j += 1
    }
  }

  override def transform[W](f: (K, V) => W): BitmapIndexedMapNode[K, W] = {
    var newContent: Array[Any] = null
    val _payloadArity = payloadArity
    val _nodeArity = nodeArity
    val newContentLength = content.length
    var i = 0
    while (i < _payloadArity) {
      val key = getKey(i)
      val value = getValue(i)
      val newValue = f(key, value)
      if (newContent eq null) {
        if (newValue.asInstanceOf[AnyRef] ne value.asInstanceOf[AnyRef]) {
          newContent = content.clone()
          newContent(TupleLength * i + 1) = newValue
        }
      } else {
        newContent(TupleLength * i + 1) = newValue
      }
      i += 1
    }

    var j = 0
    while (j < _nodeArity) {
      val node = getNode(j)
      val newNode = node.transform(f)
      if (newContent eq null) {
        if (newNode ne node) {
          newContent = content.clone()
          newContent(newContentLength - j - 1) = newNode
        }
      } else
        newContent(newContentLength - j - 1) = newNode
      j += 1
    }
    if (newContent eq null) this.asInstanceOf[BitmapIndexedMapNode[K, W]]
    else new BitmapIndexedMapNode[K, W](dataMap, nodeMap, newContent, originalHashes, size, cachedJavaKeySetHashCode)
  }

  override def equals(that: Any): Boolean =
    that match {
      case node: BitmapIndexedMapNode[K, V] =>
        (this eq node) ||
          (this.cachedJavaKeySetHashCode == node.cachedJavaKeySetHashCode) &&
          (this.nodeMap == node.nodeMap) &&
            (this.dataMap == node.dataMap) &&
              (this.size == node.size) &&
                java.util.Arrays.equals(this.originalHashes, node.originalHashes) &&
                  deepContentEquality(this.content, node.content, content.length)
      case _ => false
    }

  @`inline` private def deepContentEquality(a1: Array[Any], a2: Array[Any], length: Int): Boolean = {
    if (a1 eq a2)
      true
    else {
      var isEqual = true
      var i = 0

      while (isEqual && i < length) {
        isEqual = a1(i) == a2(i)
        i += 1
      }

      isEqual
    }
  }

  override def hashCode(): Int =
    throw new UnsupportedOperationException("Trie nodes do not support hashing.")

  override def concat[V1 >: V](that: MapNode[K, V1], shift: Int): MapNode[K, V1] = that match {
    case bm: BitmapIndexedMapNode[K, V] if bm eq this => this
    case bm: BitmapIndexedMapNode[K, V] =>
      // if we go through the merge and the result does not differ from `bm`, we can just return `bm`, to improve sharing
      // So, `anyChangesMadeSoFar` will be set to `true` as soon as we encounter a difference between the
      // currently-being-computed result, and `bm`
      var anyChangesMadeSoFar = false

      val allMap = dataMap | bm.dataMap | nodeMap | bm.nodeMap

      // minimumIndex is inclusive -- it is the first index for which there is data or nodes
      val minimumBitPos: Int = Node.bitposFrom(Integer.numberOfTrailingZeros(allMap))
      // maximumIndex is inclusive -- it is the last index for which there is data or nodes
      // it could not be exclusive, because then upper bound in worst case (Node.BranchingFactor) would be out-of-bound
      // of int bitposition representation
      val maximumBitPos: Int = Node.bitposFrom(Node.BranchingFactor - Integer.numberOfLeadingZeros(allMap) - 1)

      var leftNodeRightNode = 0
      var leftDataRightNode = 0
      var leftNodeRightData = 0
      var leftDataOnly = 0
      var rightDataOnly = 0
      var leftNodeOnly = 0
      var rightNodeOnly = 0
      var leftDataRightDataMigrateToNode = 0
      var leftDataRightDataRightOverwrites = 0

      var dataToNodeMigrationTargets = 0

      {
        var bitpos = minimumBitPos
        var leftIdx = 0
        var rightIdx = 0
        var finished = false

        while (!finished) {

          if ((bitpos & dataMap) != 0) {
            if ((bitpos & bm.dataMap) != 0) {
              if (getKey(leftIdx) == bm.getKey(rightIdx)) {
                leftDataRightDataRightOverwrites |= bitpos
              } else {
                leftDataRightDataMigrateToNode |= bitpos
                dataToNodeMigrationTargets |= Node.bitposFrom(Node.maskFrom(improve(getHash(leftIdx)), shift))
              }
              rightIdx += 1
            } else if ((bitpos & bm.nodeMap) != 0) {
              leftDataRightNode |= bitpos
            } else {
              leftDataOnly |= bitpos
            }
            leftIdx += 1
          } else if ((bitpos & nodeMap) != 0) {
            if ((bitpos & bm.dataMap) != 0) {
              leftNodeRightData |= bitpos
              rightIdx += 1
            } else if ((bitpos & bm.nodeMap) != 0) {
              leftNodeRightNode |= bitpos
            } else {
              leftNodeOnly |= bitpos
            }
          } else if ((bitpos & bm.dataMap) != 0) {
            rightDataOnly |= bitpos
            rightIdx += 1
          } else if ((bitpos & bm.nodeMap) != 0) {
            rightNodeOnly |= bitpos
          }

          if (bitpos == maximumBitPos) {
            finished = true
          } else {
            bitpos = bitpos << 1
          }
        }
      }


      val newDataMap = leftDataOnly | rightDataOnly | leftDataRightDataRightOverwrites

      val newNodeMap =
        leftNodeRightNode |
          leftDataRightNode |
          leftNodeRightData |
          leftNodeOnly |
          rightNodeOnly |
          dataToNodeMigrationTargets


      if ((newDataMap == (rightDataOnly | leftDataRightDataRightOverwrites)) && (newNodeMap == rightNodeOnly)) {
        // nothing from `this` will make it into the result -- return early
        return bm
      }

      val newDataSize = bitCount(newDataMap)
      val newContentSize = (MapNode.TupleLength * newDataSize) + bitCount(newNodeMap)

      val newContent = new Array[Any](newContentSize)
      val newOriginalHashes = new Array[Int](newDataSize)
      var newSize = 0
      var newCachedHashCode = 0

      {
        var leftDataIdx = 0
        var rightDataIdx = 0
        var leftNodeIdx = 0
        var rightNodeIdx = 0

        val nextShift = shift + Node.BitPartitionSize

        var compressedDataIdx = 0
        var compressedNodeIdx = 0

        var bitpos = minimumBitPos
        var finished = false

        while (!finished) {

          if ((bitpos & leftNodeRightNode) != 0) {
            val rightNode = bm.getNode(rightNodeIdx)
            val newNode = getNode(leftNodeIdx).concat(rightNode, nextShift)
            if (rightNode ne newNode) {
              anyChangesMadeSoFar = true
            }
            newContent(newContentSize - compressedNodeIdx - 1) = newNode
            compressedNodeIdx += 1
            rightNodeIdx += 1
            leftNodeIdx += 1
            newSize += newNode.size
            newCachedHashCode += newNode.cachedJavaKeySetHashCode

          } else if ((bitpos & leftDataRightNode) != 0) {
            val newNode = {
              val n = bm.getNode(rightNodeIdx)
              val leftKey = getKey(leftDataIdx)
              val leftValue = getValue(leftDataIdx)
              val leftOriginalHash = getHash(leftDataIdx)
              val leftImproved = improve(leftOriginalHash)

              // TODO: Implement MapNode#updatedIfNotContains
              val updated = if (n.containsKey(leftKey, leftOriginalHash, leftImproved, nextShift)) {
                n
              } else {
                n.updated(leftKey, leftValue, leftOriginalHash, leftImproved, nextShift)
              }

              if (updated ne n) {
                anyChangesMadeSoFar = true
              }

              updated
            }

            newContent(newContentSize - compressedNodeIdx - 1) = newNode
            compressedNodeIdx += 1
            rightNodeIdx += 1
            leftDataIdx += 1
            newSize += newNode.size
            newCachedHashCode += newNode.cachedJavaKeySetHashCode
          }
          else if ((bitpos & leftNodeRightData) != 0) {
            anyChangesMadeSoFar = true
            val newNode = {
              val rightOriginalHash = bm.getHash(rightDataIdx)
              getNode(leftNodeIdx).updated(
                key = bm.getKey(rightDataIdx),
                value = bm.getValue(rightDataIdx),
                originalHash = bm.getHash(rightDataIdx),
                hash = improve(rightOriginalHash),
                shift = nextShift
              )
            }

            newContent(newContentSize - compressedNodeIdx - 1) = newNode
            compressedNodeIdx += 1
            leftNodeIdx += 1
            rightDataIdx += 1
            newSize += newNode.size
            newCachedHashCode += newNode.cachedJavaKeySetHashCode

          } else if ((bitpos & leftDataOnly) != 0) {
            anyChangesMadeSoFar = true
            val originalHash = originalHashes(leftDataIdx)
            newContent(MapNode.TupleLength * compressedDataIdx) = getKey(leftDataIdx).asInstanceOf[AnyRef]
            newContent(MapNode.TupleLength * compressedDataIdx + 1) = getValue(leftDataIdx).asInstanceOf[AnyRef]
            newOriginalHashes(compressedDataIdx) = originalHash

            compressedDataIdx += 1
            leftDataIdx += 1
            newSize += 1
            newCachedHashCode += improve(originalHash)
          } else if ((bitpos & rightDataOnly) != 0) {
            val originalHash = bm.originalHashes(rightDataIdx)
            newContent(MapNode.TupleLength * compressedDataIdx) = bm.getKey(rightDataIdx).asInstanceOf[AnyRef]
            newContent(MapNode.TupleLength * compressedDataIdx + 1) = bm.getValue(rightDataIdx).asInstanceOf[AnyRef]
            newOriginalHashes(compressedDataIdx) = originalHash

            compressedDataIdx += 1
            rightDataIdx += 1
            newSize += 1
            newCachedHashCode += improve(originalHash)
          } else if ((bitpos & leftNodeOnly) != 0) {
            anyChangesMadeSoFar = true
            val newNode = getNode(leftNodeIdx)
            newContent(newContentSize - compressedNodeIdx - 1) = newNode
            compressedNodeIdx += 1
            leftNodeIdx += 1
            newSize += newNode.size
            newCachedHashCode += newNode.cachedJavaKeySetHashCode
          } else if ((bitpos & rightNodeOnly) != 0) {
            val newNode = bm.getNode(rightNodeIdx)
            newContent(newContentSize - compressedNodeIdx - 1) = newNode
            compressedNodeIdx += 1
            rightNodeIdx += 1
            newSize += newNode.size
            newCachedHashCode += newNode.cachedJavaKeySetHashCode
          } else if ((bitpos & leftDataRightDataMigrateToNode) != 0) {
            anyChangesMadeSoFar = true
            val newNode = {
              val leftOriginalHash = getHash(leftDataIdx)
              val rightOriginalHash = bm.getHash(rightDataIdx)

              bm.mergeTwoKeyValPairs(
                getKey(leftDataIdx), getValue(leftDataIdx), leftOriginalHash, improve(leftOriginalHash),
                bm.getKey(rightDataIdx), bm.getValue(rightDataIdx), rightOriginalHash, improve(rightOriginalHash),
                nextShift
              )
            }

            newContent(newContentSize - compressedNodeIdx - 1) = newNode
            compressedNodeIdx += 1
            leftDataIdx += 1
            rightDataIdx += 1
            newSize += newNode.size
            newCachedHashCode += newNode.cachedJavaKeySetHashCode
          } else if ((bitpos & leftDataRightDataRightOverwrites) != 0) {
            val originalHash = bm.originalHashes(rightDataIdx)
            newContent(MapNode.TupleLength * compressedDataIdx) = bm.getKey(rightDataIdx).asInstanceOf[AnyRef]
            newContent(MapNode.TupleLength * compressedDataIdx + 1) = bm.getValue(rightDataIdx).asInstanceOf[AnyRef]
            newOriginalHashes(compressedDataIdx) = originalHash

            compressedDataIdx += 1
            rightDataIdx += 1
            newSize += 1
            newCachedHashCode += improve(originalHash)
            leftDataIdx += 1
          }

          if (bitpos == maximumBitPos) {
            finished = true
          } else {
            bitpos = bitpos << 1
          }
        }
      }

      if (anyChangesMadeSoFar)
        new BitmapIndexedMapNode(
          dataMap = newDataMap,
          nodeMap = newNodeMap,
          content = newContent,
          originalHashes = newOriginalHashes,
          size = newSize,
          cachedJavaKeySetHashCode = newCachedHashCode
        )
      else bm

    case _ =>
      // should never happen -- hash collisions are never at the same level as bitmapIndexedMapNodes
      throw new UnsupportedOperationException("Cannot concatenate a HashCollisionMapNode with a BitmapIndexedMapNode")
  }

  override def copy(): BitmapIndexedMapNode[K, V] = {
    val contentClone = new Array[Any](content.length)
    val dataIndices = bitCount(dataMap) * TupleLength
    Array.copy(content, 0, contentClone, 0, dataIndices)
    var i = dataIndices
    while (i < content.length) {
      contentClone(i) = content(i).asInstanceOf[MapNode[K, V]].copy()
      i += 1
    }
    new BitmapIndexedMapNode[K, V](dataMap, nodeMap, contentClone, originalHashes.clone(), size, cachedJavaKeySetHashCode)
  }

  override def filterImpl(pred: ((K, V)) => Boolean, flipped: Boolean): MapNode[K, V] = {
    if (size == 0) this else if (size == 1) {
      if (pred(getPayload(0)) != flipped) this else MapNode.empty
    } else {
      val allMap = dataMap | nodeMap
      val minimumIndex: Int = Integer.numberOfTrailingZeros(allMap)
      val maximumIndex: Int = Node.BranchingFactor - Integer.numberOfLeadingZeros(allMap)

      var oldDataPassThrough = 0

      // bitmap of nodes which, when filtered, returned a single-element node. These must be migrated to data
      var nodeMigrateToDataTargetMap = 0
      // the queue of single-element, post-filter nodes
      var nodesToMigrateToData: mutable.Queue[MapNode[K, V]] = null

      // bitmap of all nodes which, when filtered, returned themselves. They are passed forward to the returned node
      var nodesToPassThroughMap = 0

      // bitmap of any nodes which, after being filtered, returned a node that is not empty, but also not `eq` itself
      // These are stored for later inclusion into the final `content` array
      // not named `newNodesMap` (plural) to avoid confusion with `newNodeMap` (singular)
      var mapOfNewNodes = 0
      // each bit in `mapOfNewNodes` corresponds to one element in this queue
      var newNodes: mutable.Queue[MapNode[K, V]] = null

      var newDataMap = 0
      var newNodeMap = 0
      var newSize = 0
      var newCachedHashCode = 0

      var dataIndex = 0
      var nodeIndex = 0

      var i = minimumIndex
      while (i < maximumIndex) {
        val bitpos = bitposFrom(i)

        if ((bitpos & dataMap) != 0) {
          val payload = getPayload(dataIndex)
          val passed = pred(payload) != flipped

          if (passed) {
            newDataMap |= bitpos
            oldDataPassThrough |= bitpos
            newSize += 1
            newCachedHashCode += improve(getHash(dataIndex))
          }

          dataIndex += 1
        } else if ((bitpos & nodeMap) != 0) {
          val oldSubNode = getNode(nodeIndex)
          val newSubNode = oldSubNode.filterImpl(pred, flipped)

          newSize += newSubNode.size
          newCachedHashCode += newSubNode.cachedJavaKeySetHashCode

          // if (newSubNode.size == 0) do nothing (drop it)
          if (newSubNode.size > 1) {
            newNodeMap |= bitpos
            if (oldSubNode eq newSubNode) {
              nodesToPassThroughMap |= bitpos
            } else {
              mapOfNewNodes |= bitpos
              if (newNodes eq null) {
                newNodes = mutable.Queue.empty
              }
              newNodes += newSubNode
            }
          } else if (newSubNode.size == 1) {
            newDataMap |= bitpos
            nodeMigrateToDataTargetMap |= bitpos
            if (nodesToMigrateToData eq null) {
              nodesToMigrateToData = mutable.Queue()
            }
            nodesToMigrateToData += newSubNode
          }

          nodeIndex += 1
        }

        i += 1
      }

      if (newSize == 0) {
        MapNode.empty
      } else if (newSize == size) {
        this
      } else {
        val newDataSize = bitCount(newDataMap)
        val newContentSize = (MapNode.TupleLength * newDataSize) + bitCount(newNodeMap)
        val newContent = new Array[Any](newContentSize)
        val newOriginalHashes = new Array[Int](newDataSize)

        val newAllMap = newDataMap | newNodeMap
        val maxIndex = Node.BranchingFactor - Integer.numberOfLeadingZeros(newAllMap)

        // note: We MUST start from the minimum index in the old (`this`) node, otherwise `old{Node,Data}Index` will
        // not be incremented properly. Otherwise we could have started at Integer.numberOfTrailingZeroes(newAllMap)
        var i = minimumIndex

        var oldDataIndex = 0
        var oldNodeIndex = 0

        var newDataIndex = 0
        var newNodeIndex = 0

        while (i < maxIndex) {
          val bitpos = bitposFrom(i)

          if ((bitpos & oldDataPassThrough) != 0) {
            newContent(newDataIndex * TupleLength) = getKey(oldDataIndex)
            newContent(newDataIndex * TupleLength + 1) = getValue(oldDataIndex)
            newOriginalHashes(newDataIndex) = getHash(oldDataIndex)
            newDataIndex += 1
            oldDataIndex += 1
          } else if ((bitpos & nodesToPassThroughMap) != 0) {
            newContent(newContentSize - newNodeIndex - 1) = getNode(oldNodeIndex)
            newNodeIndex += 1
            oldNodeIndex += 1
          } else if ((bitpos & nodeMigrateToDataTargetMap) != 0) {
            // we need not check for null here. If nodeMigrateToDataTargetMap != 0, then nodesMigrateToData must not be null
            val node = nodesToMigrateToData.dequeue()
            newContent(TupleLength * newDataIndex) = node.getKey(0)
            newContent(TupleLength * newDataIndex + 1) = node.getValue(0)
            newOriginalHashes(newDataIndex) = node.getHash(0)
            newDataIndex += 1
            oldNodeIndex += 1
          } else if ((bitpos & mapOfNewNodes) != 0) {
            newContent(newContentSize - newNodeIndex - 1) = newNodes.dequeue()
            newNodeIndex += 1
            oldNodeIndex += 1
          } else if ((bitpos & dataMap) != 0) {
            oldDataIndex += 1
          } else if ((bitpos & nodeMap) != 0) {
            oldNodeIndex += 1
          }

          i += 1
        }

        new BitmapIndexedMapNode[K, V](newDataMap, newNodeMap, newContent, newOriginalHashes, newSize, newCachedHashCode)
      }
    }
  }
}

private final class HashCollisionMapNode[K, +V ](
  val originalHash: Int,
  val hash: Int,
  var content: Vector[(K, V @uV)]
  ) extends MapNode[K, V] {

  import Node._

  require(content.length >= 2)

  releaseFence()

  private[immutable] def indexOf(key: K): Int = {
    val iter = content.iterator
    var i = 0
    while (iter.hasNext) {
      if (iter.next()._1 == key) return i
      i += 1
    }
    -1
  }

  def size = content.length

  def apply(key: K, originalHash: Int, hash: Int, shift: Int): V = get(key, originalHash, hash, shift).getOrElse(throw new NoSuchElementException)

  def get(key: K, originalHash: Int, hash: Int, shift: Int): Option[V] =
    if (this.hash == hash) {
      val index = indexOf(key)
      if (index >= 0) Some(content(index)._2) else None
    } else None

  def getOrElse[V1 >: V](key: K, originalHash: Int, hash: Int, shift: Int, f: => V1): V1 = {
    if (this.hash == hash) {
      indexOf(key) match {
        case -1 => f
        case other => content(other)._2
      }
    } else f
  }

  override def containsKey(key: K, originalHash: Int, hash: Int, shift: Int): Boolean =
    this.hash == hash && indexOf(key) >= 0

  def contains[V1 >: V](key: K, value: V1, hash: Int, shift: Int): Boolean =
    this.hash == hash && {
      val index = indexOf(key)
      index >= 0 && (content(index)._2.asInstanceOf[AnyRef] eq value.asInstanceOf[AnyRef])
    }

  def updated[V1 >: V](key: K, value: V1, originalHash: Int, hash: Int, shift: Int): MapNode[K, V1] = {
    val index = indexOf(key)
    if (index >= 0) {

      if (content(index)._2.asInstanceOf[AnyRef] eq value.asInstanceOf[AnyRef]) {
        this
      } else {
        new HashCollisionMapNode[K, V1](originalHash, hash, content.updated[(K, V1)](index, (key, value)))
      }
    } else {
      new HashCollisionMapNode[K, V1](originalHash, hash, content.appended[(K, V1)]((key, value)))
    }
  }

  def removed[V1 >: V](key: K, originalHash: Int, hash: Int, shift: Int): MapNode[K, V1] = {
    if (!this.containsKey(key, originalHash, hash, shift)) {
      this
    } else {
      val updatedContent = content.filterNot(keyValuePair => keyValuePair._1 == key)
      // assert(updatedContent.size == content.size - 1)

      updatedContent.size match {
        case 1 =>
          val (k, v) = updatedContent(0)
          new BitmapIndexedMapNode[K, V1](bitposFrom(maskFrom(hash, 0)), 0, Array(k, v), Array(originalHash), 1, hash)
        case _ => new HashCollisionMapNode[K, V1](originalHash, hash, updatedContent)
      }
    }
  }

  def hasNodes: Boolean = false

  def nodeArity: Int = 0

  def getNode(index: Int): MapNode[K, V] =
    throw new IndexOutOfBoundsException("No sub-nodes present in hash-collision leaf node.")

  def hasPayload: Boolean = true

  def payloadArity: Int = content.length

  def getKey(index: Int): K = getPayload(index)._1
  def getValue(index: Int): V = getPayload(index)._2

  def getPayload(index: Int): (K, V) = content(index)

  override def getHash(index: Int): Int = originalHash
  def sizePredicate: Int = SizeMoreThanOne

  def foreach[U](f: ((K, V)) => U): Unit = content.foreach(f)

  override def transform[W](f: (K, V) => W): HashCollisionMapNode[K, W] = {
    val newContent = Vector.newBuilder[(K, W)]
    val contentIter = content.iterator
    // true if any values have been transformed to a different value via `f`
    var anyChanges = false
    while(contentIter.hasNext) {
      val (k, v) = contentIter.next()
      val newValue = f(k, v)
      newContent.addOne((k, newValue))
      anyChanges ||= (v.asInstanceOf[AnyRef] ne newValue.asInstanceOf[AnyRef])
    }
    if (anyChanges) new HashCollisionMapNode(originalHash, hash, newContent.result())
    else this.asInstanceOf[HashCollisionMapNode[K, W]]
  }

  override def equals(that: Any): Boolean =
    that match {
      case node: HashCollisionMapNode[K, V] =>
        (this eq node) ||
          (this.hash == node.hash) &&
            (this.content.length == node.content.length) && {
              val iter = content.iterator
              while (iter.hasNext) {
                val (key, value) = iter.next()
                val index = node.indexOf(key)
                if (index < 0 || value != node.content(index)._2) {
                  return false
                }
              }
              true
            }
      case _ => false
    }

  override def concat[V1 >: V](that: MapNode[K, V1], shift: Int) = that match {
    case hc: HashCollisionMapNode[K, V1] =>
      if (hc eq this) {
        this
      } else {
        var newContent: VectorBuilder[(K, V1)] = null
        val iter = content.iterator
        while (iter.hasNext) {
          val nextPayload = iter.next()
          if (hc.indexOf(nextPayload._1) < 0) {
            if (newContent eq null) {
              newContent = new VectorBuilder[(K, V1)]()
              newContent.addAll(hc.content)
            }
            newContent.addOne(nextPayload)
          }
        }
        if (newContent eq null) hc else new HashCollisionMapNode(originalHash, hash, newContent.result())
      }
    case _: BitmapIndexedMapNode[K, V1] =>
      // should never happen -- hash collisions are never at the same level as bitmapIndexedMapNodes
      throw new UnsupportedOperationException("Cannot concatenate a HashCollisionMapNode with a BitmapIndexedMapNode")
  }

  override def filterImpl(pred: ((K, V)) => Boolean, flipped: Boolean) = {
    val newContent = content.filterImpl(pred, flipped)
    val newContentLength = newContent.length
    if (newContentLength == 0) {
      MapNode.empty
    } else if (newContentLength == 1) {
      val (k, v) = newContent.head
      new BitmapIndexedMapNode[K, V](bitposFrom(maskFrom(hash, 0)), 0, Array(k, v), Array(originalHash), 1, hash)
    } else if (newContentLength == content.length) this
    else new HashCollisionMapNode(originalHash, hash, newContent)
  }

  override def copy(): HashCollisionMapNode[K, V] = new HashCollisionMapNode[K, V](originalHash, hash, content)

  override def hashCode(): Int =
    throw new UnsupportedOperationException("Trie nodes do not support hashing.")

  override def cachedJavaKeySetHashCode: Int = size * hash

}

private final class MapKeyIterator[K, V](rootNode: MapNode[K, V])
  extends ChampBaseIterator[MapNode[K, V]](rootNode) with Iterator[K] {

  def next() = {
    if (!hasNext)
      throw new NoSuchElementException

    val key = currentValueNode.getKey(currentValueCursor)
    currentValueCursor += 1

    key
  }

}

private final class MapValueIterator[K, V](rootNode: MapNode[K, V])
  extends ChampBaseIterator[MapNode[K, V]](rootNode) with Iterator[V] {

  def next() = {
    if (!hasNext)
      throw new NoSuchElementException

    val value = currentValueNode.getValue(currentValueCursor)
    currentValueCursor += 1

    value
  }
}

private final class MapKeyValueTupleIterator[K, V](rootNode: MapNode[K, V])
  extends ChampBaseIterator[MapNode[K, V]](rootNode) with Iterator[(K, V)] {

  def next() = {
    if (!hasNext)
      throw new NoSuchElementException

    val payload = currentValueNode.getPayload(currentValueCursor)
    currentValueCursor += 1

    payload
  }

}

private final class MapKeyValueTupleReverseIterator[K, V](rootNode: MapNode[K, V])
  extends ChampBaseReverseIterator[MapNode[K, V]](rootNode) with Iterator[(K, V)] {

  def next() = {
    if (!hasNext)
      throw new NoSuchElementException

    val payload = currentValueNode.getPayload(currentValueCursor)
    currentValueCursor -= 1

    payload
  }
}

private final class MapKeyValueTupleHashIterator[K, V](rootNode: MapNode[K, V])
  extends ChampBaseReverseIterator[MapNode[K, V]](rootNode) with Iterator[Product2[AnyRef, V]] with Product2[AnyRef, V] {
  private[this] var hash = 0
  private[this] var value: V = _
  private[this] val key = new Object {
    override def hashCode(): Int = hash
  }
  override def hashCode(): Int = MurmurHash3.productHash(this)
  override def _1: AnyRef = key
  override def _2: V = value
  override def canEqual(that: Any): Boolean = false
  override def productPrefix: String = "Tuple2"
  def next() = {
    if (!hasNext)
      throw new NoSuchElementException

    hash = currentValueNode.getHash(currentValueCursor)
    value = currentValueNode.getValue(currentValueCursor)
    currentValueCursor -= 1
    this
  }
}

/** Used in HashMap[K, V]#removeAll(HashSet[K]) */
private final class MapNodeRemoveAllSetNodeIterator[K](rootSetNode: SetNode[K]) extends ChampBaseIterator(rootSetNode) {
  /** Returns the result of immutably removing all keys in `rootSetNode` from `rootMapNode` */
  def removeAll[V](rootMapNode: MapNode[K, V]): MapNode[K, V] = {
    var curr = rootMapNode
    while (curr.size > 0 && hasNext) {
      val originalHash = currentValueNode.getHash(currentValueCursor)
      curr = curr.removed(
        key = currentValueNode.getPayload(currentValueCursor),
        hash = improve(originalHash),
        originalHash = originalHash,
        shift = 0
      )
      currentValueCursor += 1
    }
    curr
  }
}

/**
  * $factoryInfo
  *
  * @define Coll `immutable.HashMap`
  * @define coll immutable champ hash map
  */
@SerialVersionUID(3L)
object HashMap extends MapFactory[HashMap] {

  private[HashMap] def apply[K, V](rootNode: MapNode[K, V]) =
    new HashMap[K, V](rootNode)

  private final val EmptyMap = new HashMap(MapNode.empty)

  def empty[K, V]: HashMap[K, V] =
    EmptyMap.asInstanceOf[HashMap[K, V]]

  def from[K, V](source: collection.IterableOnce[(K, V)]): HashMap[K, V] =
    source match {
      case hs: HashMap[K, V] => hs
      case _ => (newBuilder[K, V] ++= source).result()
    }

  /** Create a new Builder which can be reused after calling `result()` without an
    * intermediate call to `clear()` in order to build multiple related results.
    */
  def newBuilder[K, V]: ReusableBuilder[(K, V), HashMap[K, V]] = new HashMapBuilder[K, V]
}


/** A Builder for a HashMap.
  * $multipleResults
  */
private[immutable] final class HashMapBuilder[K, V] extends ReusableBuilder[(K, V), HashMap[K, V]] {
  import Node._
  import MapNode._


  private def newEmptyRootNode = new BitmapIndexedMapNode[K, V](0, 0, Array(), Array(), 0, 0)

  /** The last given out HashMap as a return value of `result()`, if any, otherwise null.
    * Indicates that on next add, the elements should be copied to an identical structure, before continuing
    * mutations. */
  private var aliased: HashMap[K, V] = _

  private def isAliased: Boolean = aliased != null

  /** The root node of the partially build hashmap */
  private var rootNode: MapNode[K, V] = newEmptyRootNode

  private[immutable] def getOrElse[V0 >: V](key: K, value: V0): V0 =
    if (rootNode.size == 0) value
    else {
      val originalHash = key.##
      rootNode.getOrElse(key, originalHash, improve(originalHash), 0, value)
    }

  /** Inserts element `elem` into array `as` at index `ix`, shifting right the trailing elems */
  private def insertElement(as: Array[Int], ix: Int, elem: Int): Array[Int] = {
    if (ix < 0) throw new ArrayIndexOutOfBoundsException
    if (ix > as.length) throw new ArrayIndexOutOfBoundsException
    val result = new Array[Int](as.length + 1)
    arraycopy(as, 0, result, 0, ix)
    result(ix) = elem
    arraycopy(as, ix, result, ix + 1, as.length - ix)
    result
  }

  /** Inserts key-value into the bitmapIndexMapNode. Requires that this is a new key-value pair */
  private def insertValue[V1 >: V](bm: BitmapIndexedMapNode[K, V],bitpos: Int, key: K, originalHash: Int, keyHash: Int, value: V1): Unit = {
    val dataIx = bm.dataIndex(bitpos)
    val idx = TupleLength * dataIx

    val src = bm.content
    val dst = new Array[Any](src.length + TupleLength)

    // copy 'src' and insert 2 element(s) at position 'idx'
    arraycopy(src, 0, dst, 0, idx)
    dst(idx) = key
    dst(idx + 1) = value
    arraycopy(src, idx, dst, idx + TupleLength, src.length - idx)

    val dstHashes = insertElement(bm.originalHashes, dataIx, originalHash)

    bm.dataMap |= bitpos
    bm.content = dst
    bm.originalHashes = dstHashes
    bm.size += 1
    bm.cachedJavaKeySetHashCode += keyHash
  }

  /** Removes element at index `ix` from array `as`, shifting the trailing elements right */
  private def removeElement(as: Array[Int], ix: Int): Array[Int] = {
    if (ix < 0) throw new ArrayIndexOutOfBoundsException
    if (ix > as.length - 1) throw new ArrayIndexOutOfBoundsException
    val result = new Array[Int](as.length - 1)
    arraycopy(as, 0, result, 0, ix)
    arraycopy(as, ix + 1, result, ix, as.length - ix - 1)
    result
  }

  /** Mutates `bm` to replace inline data at bit position `bitpos` with node `node` */
  private def migrateFromInlineToNode(bm: BitmapIndexedMapNode[K, V], keyHash: Int, bitpos: Int, node: MapNode[K, V]): Unit = {
    val dataIx = bm.dataIndex(bitpos)
    val idxOld = TupleLength * dataIx
    val idxNew = bm.content.length - TupleLength - bm.nodeIndex(bitpos)

    val src = bm.content
    val dst = new Array[Any](src.length - TupleLength + 1)

    // copy 'src' and remove 2 element(s) at position 'idxOld' and
    // insert 1 element(s) at position 'idxNew'
    // assert(idxOld <= idxNew)
    arraycopy(src, 0, dst, 0, idxOld)
    arraycopy(src, idxOld + TupleLength, dst, idxOld, idxNew - idxOld)
    dst(idxNew) = node
    arraycopy(src, idxNew + TupleLength, dst, idxNew + 1, src.length - idxNew - TupleLength)

    val dstHashes = removeElement(bm.originalHashes, dataIx)

    bm.dataMap ^= bitpos
    bm.nodeMap |= bitpos
    bm.content = dst
    bm.originalHashes = dstHashes
    bm.size = bm.size - 1 + node.size
    bm.cachedJavaKeySetHashCode += node.cachedJavaKeySetHashCode - keyHash
  }

  /** Mutates `bm` to replace inline data at bit position `bitpos` with updated key/value */
  private def setValue[V1 >: V](bm: BitmapIndexedMapNode[K, V], bitpos: Int, newKey: K, newValue: V1): Unit = {
    val dataIx = bm.dataIndex(bitpos)
    val idx = TupleLength * dataIx
    //bm.content(idx) = newKey
    bm.content(idx + 1) = newValue
  }

  /** Upserts a key/value pair into mapNode, mutably */
  private[immutable] def update(mapNode: MapNode[K, V], key: K, value: V, originalHash: Int, keyHash: Int, shift: Int): Unit = {
    mapNode match {
      case bm: BitmapIndexedMapNode[K, V] =>
        val mask = maskFrom(keyHash, shift)
        val bitpos = bitposFrom(mask)
        if ((bm.dataMap & bitpos) != 0) {
          val index = indexFrom(bm.dataMap, mask, bitpos)
          val key0 = bm.getKey(index)
          val key0UnimprovedHash = bm.getHash(index)

          if (key0UnimprovedHash == originalHash && key0 == key) {
            setValue(bm, bitpos, key, value)
          } else {
            val value0 = bm.getValue(index)
            val key0Hash = improve(key0UnimprovedHash)

            val subNodeNew: MapNode[K, V] =
              bm.mergeTwoKeyValPairs(key0, value0, key0UnimprovedHash, key0Hash, key, value, originalHash, keyHash, shift + BitPartitionSize)

            migrateFromInlineToNode(bm, key0Hash, bitpos, subNodeNew)
          }

        } else if ((bm.nodeMap & bitpos) != 0) {
          val index = indexFrom(bm.nodeMap, mask, bitpos)
          val subNode = bm.getNode(index)
          val beforeSize = subNode.size
          val beforeHash = subNode.cachedJavaKeySetHashCode
          update(subNode, key, value, originalHash, keyHash, shift + BitPartitionSize)
          bm.size += subNode.size - beforeSize
          bm.cachedJavaKeySetHashCode += subNode.cachedJavaKeySetHashCode - beforeHash
        } else {
          insertValue(bm, bitpos, key, originalHash, keyHash, value)
        }
      case hc: HashCollisionMapNode[K, V] =>
        val index = hc.indexOf(key)
        if (index < 0) {
          hc.content = hc.content.appended((key, value))
        } else {
          hc.content = hc.content.updated(index, (key, value))
        }
    }
  }

  /** If currently referencing aliased structure, copy elements to new mutable structure */
  private def ensureUnaliased() = {
    if (isAliased) copyElems()
    aliased = null
  }

  /** Copy elements to new mutable structure */
  private def copyElems(): Unit = {
    rootNode = rootNode.copy()
  }

  override def result(): HashMap[K, V] =
    if (rootNode.size == 0) {
      HashMap.empty
    } else if (aliased != null) {
      aliased
    } else {
      aliased = new HashMap(rootNode)
      releaseFence()
      aliased
    }

  override def addOne(elem: (K, V)): this.type = {
    ensureUnaliased()
    val h = elem._1.##
    val im = improve(h)
    update(rootNode, elem._1, elem._2, h, im, 0)
    this
  }

  def addOne(key: K, value: V): this.type = {
    ensureUnaliased()
    val originalHash = key.##
    update(rootNode, key, value, originalHash, improve(originalHash), 0)
    this
  }

  override def addAll(xs: IterableOnce[(K, V)]) = {
    ensureUnaliased()
    xs match {
      case hm: HashMap[K, V] =>
        new ChampBaseIterator(hm.rootNode) {
          while(hasNext) {
            val originalHash = currentValueNode.getHash(currentValueCursor)
            update(
              mapNode = rootNode,
              key = currentValueNode.getKey(currentValueCursor),
              value = currentValueNode.getValue(currentValueCursor),
              originalHash = originalHash,
              keyHash = improve(originalHash),
              shift = 0
            )
            currentValueCursor += 1
          }
        }
      case other =>
        val it = other.iterator
        while(it.hasNext) addOne(it.next())
    }

    this
  }

  override def clear(): Unit = {
    aliased = null
    if (rootNode.size > 0) {
      rootNode = newEmptyRootNode
    }
  }

  private[collection] def size: Int = rootNode.size
}