package master

import zio._
import zio.stream._
import java.time.Duration

import org.rogach.scallop._
import scalapb.zio_grpc
import scalapb.zio_grpc.ZManagedChannel
import io.grpc.ManagedChannelBuilder
import proto.common.ZioCommon.WorkerServiceClient
import proto.common.Entity
import proto.common.Pivots
import proto.common.ShuffleRequest
import io.grpc.ServerBuilder
import io.grpc.protobuf.services.ProtoReflectionService
import proto.common.ZioCommon.MasterService
import io.grpc.StatusException
import proto.common.{WorkerData, WorkerDataResponse}
import common.AddressParser
import proto.common.SampleRequest
import io.grpc.Status

class Config(args: Seq[String]) extends ScallopConf(args) {
  val workerNum = trailArg[Int](required = true, descr = "Number of workers", default = Some(1))
  verify()
}

object Main extends ZIOAppDefault {
  def port: Int = 7080

  override def run: ZIO[Environment with ZIOAppArgs with Scope,Any,Any] = (for {
    _ <- zio.Console.printLine(s"Master is running on port ${port}")
    result <- serverLive.launch
  } yield result).provideSomeLayer[ZIOAppArgs](
    ZLayer.fromZIO( for {
        args <- getArgs
        config = new Config(args)
      } yield config
    ) >>> ZLayer.fromFunction {config: Config => new MasterLogic(config)}
  )

  def builder = ServerBuilder
    .forPort(port)
    .addService(ProtoReflectionService.newInstance())

  def serverLive: ZLayer[MasterLogic, Throwable, zio_grpc.Server] = for {
    service <- ZLayer.service[MasterLogic]
    result <- zio_grpc.ServerLayer.fromServiceList(builder, zio_grpc.ServiceList.add(new ServiceImpl(service.get)))
  } yield result

  class ServiceImpl(service: MasterLogic) extends MasterService {
    def sendWorkerData(request: WorkerData): IO[StatusException,WorkerDataResponse] = {
      val result = for {
        addClient <- service.addClient(request.workerAddress, request.fileSize)
        // TODO: Map addClient
        result <- ZIO.succeed(WorkerDataResponse())
      } yield result

      result.mapError(e => e match {
        case e: StatusException => {e} 
        case _ => new StatusException(Status.INTERNAL)
      })
    }
  }
}

class MasterLogic(config: Config) {
  case class WorkerClient(val client: Layer[Throwable, WorkerServiceClient], val size: Long)
  
  var workerIPList: List[String] = List()
  var clients: List[WorkerClient] = List()

  lazy val offset: Int = List(1, (clients.map(_.size).sum / (1024 * 1024)).toInt).max

  /** Add new client connection to MasterLogic
    *
    * @param clientAddress address of client
    */
  def addClient(workerAddress: String, workerSize: Long): IO[Throwable, Any] = {
    if (clients.size >= config.workerNum.toOption.get) {
      println(s"Worker attached: ${workerAddress} but rejected")
      ZIO.fail(new StatusException(Status.UNAVAILABLE))
    } else {
      println(s"New worker[${clients.size}] attached: ${workerAddress}, Size: ${workerSize} Bytes")
      val address = AddressParser.parse(workerAddress).get
      clients = clients :+ WorkerClient(WorkerServiceClient.live(
        ZManagedChannel(
          ManagedChannelBuilder.forAddress(address._1, address._2).usePlaintext()
        )
      ), workerSize)
      workerIPList = workerIPList :+ workerAddress

      if (clients.size == config.workerNum.toOption.get) this.run()
      else ZIO.succeed(())
    }
  }

  def run(): IO[Throwable, Any] = {
    println("All worker connected")

    for {
      _ <- zio.Console.printLine("Requests samples to each workers")
      pivotCandicateList <- ZIO.foreachPar(clients.map(_.client)) { layer =>
        collectSample.provideLayer(layer)
      }

      selectedPivots = selectPivots(pivotCandicateList)
      _ <- zio.Console.printLine(selectedPivots.pivots)

    } yield pivotCandicateList

    // clients.foreach(client => sendPartitionToWorker(client.client, selectedPivots))
  }
  
  def collectSample: ZIO[WorkerServiceClient, Throwable, Pivots] =
    ZIO.serviceWithZIO[WorkerServiceClient] { workerServiceClient =>
      workerServiceClient.getSamples(SampleRequest(offset))
  }

  def selectPivots(pivotCandidateListOriginal: List[Pivots]): Pivots = {
    assert { !pivotCandidateListOriginal.isEmpty }
    assert { !clients.isEmpty }
    assert { pivotCandidateListOriginal.length == clients.length }

    val pivotCandidateList: List[String] = pivotCandidateListOriginal.flatMap(_.pivots)

    val pivotCandidateListSize: Long = pivotCandidateList.size
    val totalDataSize: Long = clients.map(_.size).sum

    assert { totalDataSize != 0 }

    val clientSizes = clients.map(_.size)
    val pivotIndices: List[Int] = clientSizes match {
      case _ :: Nil => Nil
      case _ => clientSizes.init.scanLeft(0) { (acc, workerSize) =>
        acc + (pivotCandidateListSize * (workerSize.toDouble / totalDataSize.toDouble)).toInt
      }.tail
    }

    Pivots(pivotIndices.map(idx => pivotCandidateList(idx)))
  }

  def sendPartitionToWorker(
    client: Layer[Throwable, WorkerServiceClient],
    pivots: ZIO[Any, Throwable, Pivots]): ZIO[Any, Throwable, Unit] = for {
      pivotsData <- pivots
      workerServiceClient <- ZIO.scoped(client.build).map(_.get) // ZEnvironment에서 WorkerServiceClient 추출
      shuffleRequest = ShuffleRequest(pivots = Some(pivotsData), workerAddresses = workerIPList)
      _ <- workerServiceClient.startShuffle(shuffleRequest).mapError(e => new RuntimeException(s"Failed to send ShuffleRequest: ${e.getMessage}"))
    } yield ()
}
