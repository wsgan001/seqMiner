import java.io._
import java.net.URI
import java.util.Properties

import org.apache.hadoop.conf.Configuration
import org.apache.hadoop.fs.{FileSystem, Path}
import org.apache.spark.SparkContext._
import org.apache.spark.rdd.RDD
import org.apache.spark.{SparkConf, SparkContext}

import scala.collection.mutable.ArrayBuffer

/**
 * Created by jiaqige on 4/1/15.
 */


class SeqMiner(val support: Double, val confidence: Double, val minGap: Double, val maxGap: Double) extends Serializable {

  var C:Long = 100

  def seqStart() = {

  }

  /**
   *
   * @param sc
   * @param inputPath
   * @return
   */
  def readSeqFile(sc: SparkContext, inputPath: String, splitNum:Int): RDD[(String, List[Occurrences])] = {
    val data = sc.textFile(inputPath, splitNum)

    //transform data format
    // from: <sid, tid, item, pe>
    // to: <sid, item, tid, Pc, Pe>

    data.map(line => {
      val units = line.split(" ")
      (units.head, units.tail)
    }).groupByKey()
      .map({ seq =>
      val sid = seq._1
      val occurrences = new ArrayBuffer[Occurrences]()

      seq._2.groupBy(x => x(1)).foreach(x => {
        val candidate = x._1
        val occs = new ArrayBuffer[Occurrence]()

        x._2.toList.foreach(x => {
          val occurrence = new Occurrence(x(0).toDouble, x(2).toDouble, x(2).toDouble)
          occs.append(occurrence)
        })
        val occurrencesOfOnePattern = new Occurrences(new Pattern(candidate), occs.toList)
        occurrences.append(occurrencesOfOnePattern)
      })
      (sid, occurrences.toList)
    })
  }


  /**
   *
   * @param data
   * @param k
   */
  def seqMine(sc: SparkContext, data: RDD[(String, List[Occurrences])], k: Int = 4): RDD[(Pattern, Double)] = {

    C = data.count()
    var frequentPatterns: RDD[(Pattern, Double)] = null
    var round = 1

    var vData = data
    vData.persist()

    while (round <= k) {

      val largePatterns = vData.flatMap(x => {
        val patternCount = new ArrayBuffer[(Pattern, Double)]()
        val sequence = x._2
        sequence.foreach(occurrencesOfOnePattern => {
          //compute support probability
          patternCount.append((occurrencesOfOnePattern.getCandidate, occurrencesOfOnePattern.getSupport))
        })
        patternCount.toIterable
      }).reduceByKey(_ + _)
        .filter(_._2 >= support*C)

      if (largePatterns.count() == 0) {
        return frequentPatterns
      }

      if (frequentPatterns == null)
        frequentPatterns = largePatterns
      else
        frequentPatterns = frequentPatterns.union(largePatterns)

      //generate candidates
      // val candidates = selfJoin(largePatterns).collect
      val kPatterns = largePatterns.map(_._1).collect
      if (kPatterns.isEmpty)
        return frequentPatterns

      //construct the new vertical data set using the new candidates
      vData = updateData(sc, kPatterns, vData)
///      vData.persist()
      round += 1
    }

    frequentPatterns

  }


  /**
   * update the vertical data
   * @param sc
   * @param kPatterns
   * @param vData
   * @return
   */
  def updateData(sc: SparkContext, kPatterns: Array[Pattern],
                 vData: RDD[(String, List[Occurrences])]): RDD[(String, List[Occurrences])] = {
    val bcKPatterns = sc.broadcast(kPatterns)

    vData.mapPartitions(sequences => {
      val kPatterns = bcKPatterns.value.toSet

      val buffer = new ArrayBuffer[(String, List[Occurrences])]()
      sequences.foreach(sequence => {
        val sid = sequence._1
        val patternOccurrences = sequence._2.sortBy(x => x.candidate.getPattern())

        //build new ListOfOccurrences
        val listOfOccurrence = new ArrayBuffer[Occurrences]()

        for (i <- 0 until patternOccurrences.size) {
          val s1: Pattern = patternOccurrences(i).getCandidate()
          for (j <- (i + 1) until patternOccurrences.size) {
            val s2: Pattern = patternOccurrences(j).getCandidate()

            if (kPatterns.contains(s1) && kPatterns.contains(s2)) {

              if (s1.removeFirst().compareTo(s2.removeLast())) {
                //generate a possible candidate
                listOfOccurrence.appendAll(updateCandidateOccurrence(s1.seqJoin(s2), patternOccurrences(i),
                  patternOccurrences(j)))
              }

              if (s2.removeFirst().compareTo(s1.removeLast())) {
                listOfOccurrence.appendAll(updateCandidateOccurrence(s2.seqJoin(s1), patternOccurrences(j),
                  patternOccurrences(i)))
              }

              if (s1.removeFirst().getPattern() == null || s1.removeFirst().getPattern().isEmpty) {
                if (s1.itemJoin(s2) != null)
                  listOfOccurrence.appendAll(updateCandidateOccurrence(s1.itemJoin(s2), patternOccurrences(i), patternOccurrences(j)))
              }

            }
          }
        }
        buffer.append((sid, listOfOccurrence.toList))
      })

      buffer.toIterator
    })
  }

  /**
   *
   * @param pattern
   * @param first
   * @param second
   * @return
   */
  def updateCandidateOccurrence(pattern: Pattern, first: Occurrences, second: Occurrences): Traversable[Occurrences] = {

    val buffer = new ArrayBuffer[Occurrences]()

    if (pattern.getPattern().equals("a,d")) {
      println(pattern)
    }

    val listOfPatternOcc = new ArrayBuffer[Occurrence]()

    val listOfOcc1 = first.getListOfOccurrence()
    val listOfOcc2 = second.getListOfOccurrence()

    // check if a seqeuence extension of item extension
    val isSeqExt = pattern.isSeqExtend()

    for (i <- 0 until listOfOcc2.size) {
      val tid = listOfOcc2(i).getTid()

      var probOfPattern: Double = 0

      for (j <- 0 until listOfOcc1.size) {
        //check if satisfies gap-constraints
        if (isSatisfy(isSeqExt, listOfOcc1(j).getTid(), tid)) {
          probOfPattern += listOfOcc1(j).getPatternProb()
        }
      }

      val itemProb = listOfOcc2(i).getItemProb()
      probOfPattern *= itemProb

      if (probOfPattern > 0)
        listOfPatternOcc.append(new Occurrence(tid, probOfPattern, itemProb))

    }

    //generate an occurrences
    if (!listOfPatternOcc.isEmpty)
      buffer.append(new Occurrences(pattern, listOfPatternOcc.toList))



    buffer.toList
  }


  /**
   *
   * @param isSeqExt
   * @param head
   * @param tail
   * @return
   */
  def isSatisfy(isSeqExt: Boolean, head: Double, tail: Double): Boolean = {
    if (isSeqExt) {
      (tail - head <= maxGap) && (tail - head >= minGap)
    } else {
      Math.abs(tail - head) <= Double.MinPositiveValue
    }
  }


  def buildRules(frequentPatterns: RDD[(Pattern, Double)]): Array[String] = {

    if(frequentPatterns == null)
      return null

    val patterns = frequentPatterns.repartition(1).collect().toMap

    val rules = patterns.filter(x => isARule(x._1)).flatMap(x => {
      buildRulesFromPattern(x._1, patterns)
    })

    //save to file

    val fw = new PrintWriter(new File("/tmp/rules"))
    rules.foreach(x => fw.write(x.toRuleString()+"\n"))
    fw.close()

    rules.map(x => x.toRuleString()).toArray
  }


  def buildRulesFromPattern(pattern: Pattern, patterns: Map[Pattern, Double]): Iterable[Rule] = {
    val rules = new ArrayBuffer[Rule]()
    val patStr = pattern.getPattern()
    val support: Double = patterns.get(pattern).get
    var index = 0

    while (index < patStr.size) {
      if (patStr.charAt(index) == '|') {
        val left = patStr.substring(0, index)
        val right = patStr.substring(index + 1)
        val name = left + " => " + right
        val conf = support / patterns.get(new Pattern(left)).get
        val lift = (C*support) / (patterns.get(new Pattern(left)).get
          * patterns.get(new Pattern(right)).get)

//        if(conf > this.confidence)
          rules.append(new Rule(name, support, conf, lift))
      }
      index += 1
    }

    rules.toIterable

  }

  def isARule(pattern: Pattern): Boolean = {
    pattern.contains('|')
  }
}

object SeqMiner {


  def init(support:Double, confidence:Double, minGap:Double, maxGap:Double) = {
    new SeqMiner(support, confidence, minGap, maxGap)
  }

  def main(args: Array[String]) = {

    val propertiesFile = new File(args(0))
    val properties = new Properties()
    properties.loadFromXML(new FileInputStream(propertiesFile))

    val mode = properties.getProperty("mode")
    val inputPath = properties.getProperty("inputPath")
    val outputPath = properties.getProperty("outputPath")
    val splitNum = properties.getProperty("minPartition").toInt

    val support:Double = properties.getProperty("support").toDouble
    val confidence = properties.getProperty("confidence").toDouble
    val minGap = properties.getProperty("minGap").toDouble
    val maxGap = properties.getProperty("maxGap").toDouble

    val jarPath = properties.getProperty("jarPath")
    val jarNames = new Array[String](1)
    jarNames(0) = jarPath

    val hadoopConf = new Configuration()
    val hdfs = FileSystem.get(new URI(outputPath), hadoopConf)

    try {
      hdfs.delete(new Path(outputPath), true)
    } catch {
      case _: Throwable => {}
    }


    // configure the spark context
    //todo: add jar from property
    val conf = new SparkConf().setAppName("SPM").setMaster(mode).setJars(jarNames)

    val sc = new SparkContext(conf)

//    if(mode != "local")
//      sc.addJar("seqminer.jar")


    //read data to RDD and transform it to the vertical format

    val seqMiner = new SeqMiner(support, confidence, minGap, maxGap)

    val data = seqMiner.readSeqFile(sc, inputPath, splitNum)

    //mine sequential patterns with quality measurements
    val frequentPatternsRDD = seqMiner.seqMine(sc, data)

    //save output to hdfs

    if(frequentPatternsRDD != null){
      frequentPatternsRDD.repartition(1).saveAsTextFile(outputPath)

      //generate rules
      //val rules = seqMiner.buildRules(frequentPatternsRDD)
      //println(rules)
    }

//    sc.stop()
  }
}
