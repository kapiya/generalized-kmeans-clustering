package com.massivedatascience.clusterer.base

import org.apache.spark.SparkContext._
import org.apache.spark.rdd.RDD

import scala.collection.mutable.ArrayBuffer
import scala.reflect.ClassTag

/**
 * A K-Means clustering implementation that performs multiple K-means clusterings simultaneously, returning the
 * one with the lowest cost.
 *
 */

class GeneralizedKMeans[P <: FP : ClassTag, C <: FP : ClassTag](
                                                                 pointOps: PointOps[P, C], maxIterations: Int) extends MultiKMeansClusterer[P, C] {

  def cluster(data: RDD[P], centers: Array[Array[C]]): (Double, GeneralizedKMeansModel[P, C]) = {
    val runs = centers.length
    val active = Array.fill(runs)(true)
    val costs = Array.fill(runs)(Zero)
    var activeRuns = new ArrayBuffer[Int] ++ (0 until runs)
    var iteration = 0

    /*
     * Execute iterations of Lloyd's algorithm until all runs have converged.
     */

    while (iteration < maxIterations && activeRuns.nonEmpty) {
      // remove the empty clusters
      log.info("iteration {}", iteration)

      val activeCenters = activeRuns.map(r => centers(r)).toArray

      if (log.isInfoEnabled) {
        for (r <- 0 until activeCenters.length)
          log.info("run {} has {} centers", activeRuns(r), activeCenters(r).length)
      }

      // Find the sum and count of points mapping to each center
      val (centroids, runDistortion) = getCentroids(data, activeCenters)

      if (log.isInfoEnabled) {
        for (run <- activeRuns) log.info("run {} distortion {}", run, runDistortion(run))
      }

      for (run <- activeRuns) active(run) = false

      for (((runIndex: Int, clusterIndex: Int), cn: Centroid) <- centroids) {
        val run = activeRuns(runIndex)
        if (cn.isEmpty) {
          active(run) = true
          centers(run)(clusterIndex) = null.asInstanceOf[C]
        } else {
          val centroid = pointOps.centroidToPoint(cn)
          active(run) = active(run) || pointOps.centerMoved(centroid, centers(run)(clusterIndex))
          centers(run)(clusterIndex) = pointOps.pointToCenter(centroid)
        }
      }

      // filter out null centers
      for (r <- activeRuns) centers(r) = centers(r).filter(_ != null)

      // update distortions and print log message if run completed during this iteration
      for ((run, runIndex) <- activeRuns.zipWithIndex) {
        costs(run) = runDistortion(runIndex)
        if (!active(run)) log.info("run {} finished in {} iterations", run, iteration + 1)
      }
      activeRuns = activeRuns.filter(active(_))
      iteration += 1
    }

    val best = costs.zipWithIndex.min._2
    (costs(best), new GeneralizedKMeansModel(pointOps, centers(best)))
  }

  def getCentroids(data: RDD[P], activeCenters: Array[Array[C]])
  : (Array[((Int, Int), Centroid)], Array[Double]) = {
    val runDistortion = activeCenters.map(_ => data.sparkContext.accumulator(Zero))
    val bcActiveCenters = data.sparkContext.broadcast(activeCenters)
    val result = data.mapPartitions { points =>
      val bcCenters = bcActiveCenters.value
      val centers = bcCenters.map { c => Array.fill(c.length)(new Centroid)}

      for (
        point <- points;
        (clusters: Array[C], run) <- bcCenters.zipWithIndex
      ) {
        val (cluster, cost) = pointOps.findClosest(clusters, point)
        runDistortion(run) += cost
        centers(run)(cluster).add(point)
      }

      val contribution =
        for (
          (clusters, run) <- bcCenters.zipWithIndex;
          (contrib, cluster) <- clusters.zipWithIndex
        ) yield {
          ((run, cluster), centers(run)(cluster))
        }

      contribution.iterator
    }.reduceByKey { (x, y) => x.add(y)}.collect()
    bcActiveCenters.unpersist()
    (result, runDistortion.map(x => x.localValue))
  }
}
