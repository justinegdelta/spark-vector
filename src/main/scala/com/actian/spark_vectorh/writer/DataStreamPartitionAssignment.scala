package com.actian.spark_vectorh.writer

import scala.collection.mutable.{ ArrayBuffer, IndexedSeq => mutableISeq, Stack }
import org.apache.spark.Logging
import com.actian.spark_vectorh.Profiling

/**
 * Trait that performs a special kind of matching in a bipartite graph:
 *  - let `A` and `B` be the two classes of nodes in a bipartite graph
 *  - let `edges` be the edges (`a -> b`)
 *  - for each `b` in `B`, define as `g(b)` as the number of nodes from `A` assigned to `b`
 *  - the goal of this algorithm is to assign each node from `a` in `A` to a node `b` in `B` such that there is an edge between
 * `a` and `b` and `max(g(b))` is minimum for all `b` in `B`
 *
 * This algorithm is very similar to Hopcroft-Karp's matching algorithm in bipartite graphs:
 *  - create an initial matching
 *  - define `target` = `nA / nB` rounded up, i.e. the ideal solution
 *  - separate nodes from `B` into three classes: `b`s with `g(b) > target, = target and < target`
 *  - At each iteration try to find an alternating path starting from a node with `g(b) > target` to a node that has `g(b) < target`.
 *  By negating this alternate path, each node in `A` is still assigned to a node from `B` that it shares an edge with, but the sum of `|g(b) - target|`
 *  for every `b` in `B` is smaller.
 *  - When there is no such alternating path anymore, we have reached the optimal solution
 *  - By trying to find more than one alternating path at each iteration, the complexity of the algorithm is improved to
 *  `|edges|sqrt(nA + nB)`. Since there is usually a constant number of `B`s a node from `A` shares an edge with, e.g.
 *  each HDFS block has an affinity to a constant number(replication factor) of hosts, and `nB` is usually << `nA`, the complexity of this algorithm can be viewed as `O(nA sqrt(nA))`
 */
sealed trait BipartiteAssignment extends Logging with Profiling {
  protected val nA: Int
  protected val nB: Int
  protected val edges: IndexedSeq[IndexedSeq[Int]]
  private lazy val matchFor = mutableISeq.fill(nA)(-1)
  private lazy val numAperB = mutableISeq.fill(nB)(0)
  private lazy val allAperB = {
    for (a <- 0 until nA; b <- edges(a)) numAperB(b) += 1
    val ret = mutableISeq.tabulate(nB)(b => mutableISeq.fill(numAperB(b))(0))
    for (b <- 0 until nB) numAperB(b) = 0
    for (a <- 0 until nA; b <- edges(a)) {
      ret(b)(numAperB(b)) = a
      numAperB(b) += 1
    }
    for (b <- 0 until nB) numAperB(b) = 0
    ret
  }

  private lazy val st = Stack.empty[(Int, Int)]

  private lazy val target = nA / nB + (if (nA % nB != 0) 1 else 0)

  /**
   * Find an alternating path that respects the properties of the algorithm, i.e. starts from a node with
   *  `g(b) > target` and ends in a node with `g(b') < target`.
   *
   *  @param offset The offset index in allAperB to look for potential next A's, to mark which edges (b -> a) have already
   *  been traversed in previous calls to [[findPath]]
   *  @param visited marks which nodes from A have already been visited
   */
  private def findPath(a: Int, offset: IndexedSeq[Iterator[Int]], visited: mutableISeq[Boolean]): Boolean = {
    st.push((a, -1))
    var foundPath = false
    /** Use iterative algorithm as we might run out of stack memory if we recurse */
    while (!st.isEmpty) {
      val (a, afidx) = st.pop
      if (foundPath) matchFor(a) = edges(a)(afidx)
      if (afidx == -1) {
        visited(a) = true
        edges(a).find { b => b != matchFor(a) && numAperB(b) < target }.foreach { b =>
          foundPath = true
          numAperB(b) += 1
          matchFor(a) = b
        }
      }
      if (!foundPath && afidx + 1 < edges(a).size) {
        val b = edges(a)(afidx + 1)
        var nextA = -1
        while (offset(b).hasNext && (nextA == -1 || visited(nextA) || matchFor(nextA) != b)) {
          nextA = offset(b).next
        }
        if (nextA != -1 && !visited(nextA) && matchFor(nextA) == b) {
          st.push((a, afidx + 1))
          st.push((nextA, -1))
        }
      }
    }
    foundPath
  }

  /**
   * Get an assignment of partitions to hosts satisfying the properties described in the header
   *
   * @return One sequence of partition indexes assigned to each host
   */
  lazy val matching: IndexedSeq[IndexedSeq[Int]] = {
    implicit val accs = profileInit("first assignment", "matching", "ret")
    profile("first assignment")
    /* Start with some assignment, any assignment */
    (0 until nA).foreach { a =>
        val b = edges(a).minBy(numAperB(_))
        matchFor(a) = b
        numAperB(b) += 1
    }
    profileEnd
    val more = (0 until nB).filter(numAperB(_) > target)

    val visited = mutableISeq.fill(nA)(false)
    var repeat = true
    var iterations = 0
    val iterators = mutableISeq.fill(nB)(allAperB(0).iterator)
    profile("matching")
    while (repeat) {
      iterations += 1
      (0 until nA).foreach { visited(_) = false }
      (0 until nB).foreach { b => iterators(b) = allAperB(b).iterator }

      repeat = false
      more.foreach { b =>
          while (iterators(b).hasNext && numAperB(b) > target) {
            val a = iterators(b).next
            if (matchFor(a) == b &&
              !visited(a) && findPath(a, iterators, visited)) {
              numAperB(b) -= 1
              repeat = true
            }
          }
      }
    }
    profileEnd
    logDebug(s"Matching algorithm took $iterations iterations")
    profile("ret")
    val allBsWithAassigned = (0 until nA).view.groupBy(matchFor(_)).mapValues(_.force)
    val ret = (0 until nB).map(b => allBsWithAassigned.get(b).getOrElse(IndexedSeq.empty[Int]))
    profileEnd
    profilePrint
    ret
  }
}

/**
 * Class that contains the matching algorithm used to assign `RDD` partitions to Vector hosts, based on `affinities`.
 *
 * The algorithm used here tries to assign partitions to hosts for which they have affinity. For this reason only partitions
 * that have affinity to at least one host are matched here, the others are assigned to a random node. Also, this algorithm
 * aims to minimize the maximum number of partitions that a host will have assigned, i.e. the most data a host will process
 *
 * @param affinities Affinities of each partition to host names represented as an `Array` of `Seq[T]`
 * @param endpoints Vector end points
 */
final class DataStreamPartitionAssignment(affinities: Array[_ <: Seq[String]], endpoints: IndexedSeq[VectorEndPoint]) extends BipartiteAssignment {
  private def verifyMatching: Unit = {
    var sanity = 0
    (0 to hosts.size - 1).view.map { host =>
        matching(host).view.map(partitionsWithAffinity(_)._2).foreach { partition =>
            if (affinities(partition).find(hosts(_) == host).isEmpty) sanity += 1
        }
    }
    logDebug(s"After matching algorithm, $sanity partitions are read remotely")
  }

  private def prepareStructures = {
    profile("prepare structures")
    val (p1, p2) = (0 until affinities.size).map { part => (affinities(part), part) }.partition(!_._1.isEmpty)
    val hosts = endpoints.map(_.host).distinct.zipWithIndex.toMap
    val aff = (0 until p1.size).map { idx => p1(idx)._1.map(hosts(_)).toIndexedSeq }
    profileEnd
    (p1, p2, hosts, aff)
  }

  private def matchPartitionsWithAffinity = {
    logDebug(s"data stream partition assignment: trying to assign ${affinities.size} partitions to ${endpoints.size} endpoints")
    logDebug(s"data stream partition assignment: ${partitionsWithAffinity.size} partitions have affinity, ${partitionsWithoutAffinity.size} don't")

    profile("match partitions with affinity to hosts")
    matching
    verifyMatching
    profileEnd
  }

  private def matchingToEndpoints = {
    profile("translate matching to assignment to endpoints")
    val endPointsPerHost = (0 until endpoints.size).view.groupBy(e => hosts(endpoints(e).host))
    val numEndpointsPerHost = endPointsPerHost.mapValues(_.size)
    val partitionsAssignedPerHost = mutableISeq.tabulate(hosts.size)(matching(_).size)
    val assignment = mutableISeq((0 until hosts.size).view.flatMap { host =>
      val partitions = matching(host)
      var i = 0
      val numEndpoints = numEndpointsPerHost(host)
      (0 until numEndpoints).view.map { idx =>
        val endpoint = endPointsPerHost(host)(idx)
        val share = (partitions.size / numEndpoints) + (if (idx < partitions.size % numEndpoints) 1 else 0)
        val ret = partitions.slice(i, i + share).map(partitionsWithAffinity(_)._2)
        i += share
        ArrayBuffer(ret: _*)
      }.force
    }.force: _*)
    profileEnd
    assignment
  }

  private def matchPartitionsWithoutAffinity = {
    profile("assign partitions without affinity")
    val maxPartPerEndpoint = assignment.map(_.size).reduce(_ max _)
    var i = 0
    assignment.foreach { partitions =>
        if (partitions.size < maxPartPerEndpoint) {
          val toAdd = Math.min(partitionsWithoutAffinity.size - i, maxPartPerEndpoint - partitions.size)
          partitions ++= partitionsWithoutAffinity.slice(i, i + toAdd).map(_._2)
          i += toAdd
        }
    }
    var k = 0
    (i to partitionsWithoutAffinity.size - 1).foreach { idx =>
        assignment(k) += partitionsWithoutAffinity(idx)._2
        k = if (k + 1 >= assignment.size) 0 else k + 1
    }
    var sanity = 0
    (0 until endpoints.size).view.foreach { endpoint => assignment(endpoint).foreach { partition => if (affinities(partition).find(endpoints(endpoint).host == _).isEmpty) sanity += 1 } }
    logDebug(s"After computing assignment, $sanity partitions are read remotely")
    profileEnd
  }

  implicit val accs = profileInit("prepare structures", "match partitions with affinity to hosts", "translate matching to assignment to endpoints", "assign partitions without affinity")
  
  private val (partitionsWithAffinity, partitionsWithoutAffinity, hosts, affinitiesToHosts) = prepareStructures

  protected val nA = partitionsWithAffinity.size
  protected val nB = hosts.size
  protected val edges = affinitiesToHosts
  
  matchPartitionsWithAffinity
  /** For each end point in `endpoints`, a sequence of partition indices that are assigned to that end point */
  val assignment: IndexedSeq[ArrayBuffer[Int]] = matchingToEndpoints
  matchPartitionsWithoutAffinity
  profilePrint
}

object DataStreamPartitionAssignment {
  def apply(affinities: Array[_ <: Seq[String]], endpoints: IndexedSeq[VectorEndPoint]): IndexedSeq[IndexedSeq[Int]] = {
    (new DataStreamPartitionAssignment(affinities, endpoints)).assignment
  }
}
