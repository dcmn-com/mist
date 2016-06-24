package io.hydrosphere.mist.jobs

import io.hydrosphere.mist.master.{JobCompleted, JobStarted}
import io.hydrosphere.mist.{Repository, Specification, MistConfig, Constants, Master}
import org.apache.commons.lang.SerializationUtils
import org.mapdb.{DBMaker, Serializer}

import scala.collection.mutable.ArrayBuffer

private[mist] trait JobRepository extends Repository[Job]

private[mist] object InMemoryJobRepository extends JobRepository {

  private val _collection = ArrayBuffer.empty[Job]

  override def add(job: Job): Unit = {
    _collection += job
  }

  override def get(specification: Specification[Job]): Option[Job] = {
    val predicate: Job => Boolean = x => specification.specified(x)
    _collection.find(predicate)
  }

  override def filter(specification: Specification[Job]): List[Job] = {
    val predicate: Job => Boolean = x => specification.specified(x)
    _collection.filter(predicate).toList
  }

  override def remove(job: Job): Unit = {
    _collection -= job
  }
}

private[mist] trait ConfigurationRepository {
  def add(job: Job): Unit = ???
  def remove(job: Job): Unit = ???
  def getAll: ArrayBuffer[JobConfiguration] = ???
  def clear(): Unit = ???
}

private[mist] object InMemoryJobConfigurationRepository extends ConfigurationRepository {

  private val _collection = ArrayBuffer.empty[JobConfiguration]

  override def add(job: Job): Unit = {
    _collection += job.configuration
  }

  override def remove(job: Job): Unit = {
    _collection -= job.configuration
  }

  override def getAll: ArrayBuffer[JobConfiguration] = _collection

  override def clear(): Unit = {
    for(jobConf <- getAll){
      _collection -= jobConf
    }
  }
}

private[mist] object InMapDbJobConfigurationRepository extends ConfigurationRepository {
  // Db
  private lazy val db  =  DBMaker
    .fileDB(MistConfig.Recovery.recoveryDbFileName)
    .fileLockDisable
    .closeOnJvmShutdown
    .make

  // Map
  private lazy val map = db
    .hashMap("map", Serializer.STRING, Serializer.BYTE_ARRAY)
    .createOrOpen

  // Json formats
  private implicit val formats = org.json4s.DefaultFormats

  override def add(job: Job): Unit = {
    try {
      val w_job = SerializationUtils.serialize(job.configuration)
      map.put(job.id, w_job)
      println(s"${job.id} saved in MapDb")
    } catch {
      case e: Exception => println(e)
    }
  }

  def addById(jobId: String, jobConfiguration: JobConfiguration): Unit = {
    try {
      val w_job = SerializationUtils.serialize(jobConfiguration)
      map.put(jobId, w_job)
      println(s"${jobId} saved in MapDb")
    } catch {
      case e: Exception => println(e)
    }
  }

  override def remove(job: Job): Unit = {
    try {
      map.remove(job.id)
      println(s"${job.id} removed from MapDb")
    } catch{
      case e: Exception => println(e)
    }
  }

  def removeById(jobId: String): Unit = {
    try {
      map.remove(jobId)
      println(s"${jobId} removed from MapDb")
    } catch{
      case e: Exception => println(e)
    }
  }

  override def getAll: ArrayBuffer[JobConfiguration] = {
    try{
      val keys = map.getKeys.toArray()
      var _collection = ArrayBuffer.empty[JobConfiguration]
      for(key <- keys){
        _collection += SerializationUtils.deserialize(map.get(key.toString)).asInstanceOf[JobConfiguration]
      }
      println(s"${_collection.size} loaded from MapDb")
      _collection
    }
    catch {
      case e: Exception =>
        println(e)
        ArrayBuffer.empty[JobConfiguration]
    }
  }

 override def clear(): Unit = {
   try {
     map.clear()
   } catch {
     case e: Exception => println(e)
   }
 }
}

private[mist] object RecoveryJobRepository extends JobRepository {

  private val _collection = ArrayBuffer.empty[Job]

  lazy val configurationRepository = InMapDbJobConfigurationRepository
  /*
  lazy val configurationRepository: ConfigurationRepository = MistConfig.Recovery.recoveryTypeDb match {
    case "MapDb" => InMapDbJobConfigurationRepository
    case _ => InMemoryJobConfigurationRepository

  }*/

  override def add(job: Job): Unit = {
    _collection += job
    if(job.jobRunnerName == Constants.Actors.asyncJobRunnerName) {
      configurationRepository.add(job)
      Master.recoveryActor ! JobStarted
    }
  }

  def addById(jobId: String, jobConfiguration: JobConfiguration): Unit = {
    configurationRepository.addById(jobId, jobConfiguration)
    Master.recoveryActor ! JobStarted
  }

  def removeById(jobId: String): Unit = {
    configurationRepository.removeById(jobId)
    Master.recoveryActor ! JobCompleted
  }

  override def get(specification: Specification[Job]): Option[Job] = {
    val predicate: Job => Boolean = x => specification.specified(x)
    _collection.find(predicate)
  }

  override def filter(specification: Specification[Job]): List[Job] = {
    val predicate: Job => Boolean = x => specification.specified(x)
    _collection.filter(predicate).toList
  }

  override def remove(job: Job): Unit = {
    _collection -= job
  }

  def removeFromRecovery(job: Job): Unit = {
    if(job.jobRunnerName == Constants.Actors.asyncJobRunnerName) {
      configurationRepository.remove(job)
      Master.recoveryActor ! JobCompleted
    }
  }
}
