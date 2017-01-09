package akkacrdt

import com.typesafe.config.ConfigFactory
import akka.actor.ActorSystem
import akka.actor.ActorRef
import akka.actor.Props
import akka.actor.AddressFromURIString
import akka.cluster.Cluster

import akka.http.scaladsl.Http
import akka.http.scaladsl.model._
import akka.http.scaladsl.server.Directives._
import akka.stream.ActorMaterializer
import scala.io.StdIn

import akka.cluster.ddata.DistributedData
import akka.cluster.ddata.ORSet
import akka.cluster.ddata.ORSetKey
import akka.cluster.ddata.PNCounter
import akka.cluster.ddata.PNCounterKey
import akka.cluster.ddata.Replicator
import akka.cluster.ddata.Replicator._

import scala.concurrent.ExecutionContext;
import scala.concurrent.Future;
import scala.concurrent.Await
import scala.concurrent.Promise
import scala.concurrent.duration._
import akka.util.Timeout
import akka.pattern.ask

object SimpleClusterApp {
  def main(args: Array[String]): Unit = {
    println("ARGUMENTS:")
    args.map { println _ }
    if (args.isEmpty) {
      println("DEFAULTING:")
      val (system, cluster, replicator) = joinCluster(Seq("127.0.0.1", "127.0.0.1", "2551", "akka.tcp://ClusterSystem@127.0.0.1:2551"))
      val bindingFuture = startServer(system,cluster,replicator)
    }
    else {
      println("MANUAL:")
      val (system, cluster, replicator) = joinCluster(args)
      val bindingFuture = startServer(system,cluster,replicator)
    }
    println("Serving on port 8080...")
    StdIn.readLine()
  }

  def joinCluster(args: Seq[String]): (ActorSystem,Cluster,ActorRef) = args match {
    case Seq(hostname,bind,port,seed) => {
      // Override the configuration of the port

      val config = ConfigFactory.parseString(
          "akka.remote.netty.tcp.hostname=" + hostname + "\n" +
          "akka.remote.netty.tcp.bind-hostname=" + bind + "\n" +
          "akka.remote.netty.tcp.port=" + port)
        .withFallback(ConfigFactory.load())
      // Create an Akka system
      val system = ActorSystem("ClusterSystem", config)
      // Create an actor that handles cluster domain events
      system.actorOf(Props[SimpleClusterListener], name = "clusterListener")
      // Join the cluster
      val cluster = Cluster(system)
      println("JOINING CLUSTER: " + seed)
      cluster.join(AddressFromURIString(seed))

      val replicator = DistributedData(system).replicator

      return (system,cluster,replicator)
    }
  }

  def startServer(actorSystem:ActorSystem, mycluster:Cluster, replicator:ActorRef) = {
    import de.heikoseeberger.akkahttpcirce.CirceSupport._
    import io.circe.generic.auto._
    import collection.JavaConversions._

    implicit val system = actorSystem
    implicit val cluster = mycluster
    implicit val materializer = ActorMaterializer()
    implicit val executionContext = system.dispatcher
    implicit val timeout = Timeout(5 seconds)

    val counterKey = PNCounterKey("counter")
    val route =
      path("cluster") {
        get {
          complete {
            for ( member <- cluster.state.getMembers) 
              yield member.toString()
          }
        }
      } ~
      path("counter") {
        get {
          val request = replicator ? Get(counterKey,ReadLocal)
          onSuccess(request) { response => complete( response match {
            case g @ GetSuccess(_,_) =>
              g.get(counterKey) match {
                case c:PNCounter => Map("count" -> c.value.longValue)
              }
            case NotFound(_,_) => Map("count" -> 0)
          })}
        }
      } ~
      path("increment") {
        get {
          val request = replicator ? Update(counterKey, PNCounter(), WriteLocal)(_ + 1)
          onSuccess(request) { response => complete( response match {
            case UpdateSuccess(_,_) => Map("status" -> "success")
            case UpdateTimeout(_,_) => Map("status" -> "timeout")
          })}
        }
      }
    Http().bindAndHandle(route, "0.0.0.0", 8080)
  }
}

