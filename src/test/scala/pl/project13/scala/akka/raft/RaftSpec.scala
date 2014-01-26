package pl.project13.scala.akka.raft

import org.scalatest._
import akka.testkit.{TestProbe, TestFSMRef, TestKit}
import akka.actor._
import scala.concurrent.duration._
import java.util.concurrent.TimeUnit

abstract class RaftSpec extends TestKit(ActorSystem("raft-test")) with FlatSpecLike with Matchers
  with BeforeAndAfterAll with BeforeAndAfterEach {

  import protocol._

  // notice the EventStreamAllMessages, thanks to this we're able to wait for messages like "leader elected" etc.
  private var _members: Vector[TestFSMRef[RaftState, Metadata, WordConcatRaftStateMachineActor with EventStreamAllMessages]] = _

  def memberCount: Int

  val config = system.settings.config
  val electionTimeoutMin = config.getDuration("akka.raft.election-timeout.min", TimeUnit.MILLISECONDS).millis
  val electionTimeoutMax = config.getDuration("akka.raft.election-timeout.max", TimeUnit.MILLISECONDS).millis

  implicit var probe: TestProbe = _

  override def beforeAll() {
    super.beforeAll()

    _members = (1 to memberCount).toVector map { i =>
      TestFSMRef(
        new WordConcatRaftStateMachineActor with EventStreamAllMessages,
        name = s"member-$i"
      )
    }
    _members foreach { _ ! MembersChanged(_members) }
  }

  override def beforeEach() {
    super.beforeEach()

    probe = TestProbe()
  }

  override def afterEach() {
    system.eventStream.unsubscribe(probe.ref)
    super.afterEach()
  }

  def maybeLeader() = members.find(_.stateName == Leader)

  def leader() = maybeLeader getOrElse {
    infoMemberStates()
    throw new RuntimeException("Unable to find leader!")
  }

  def members() = _members.filterNot(_.isTerminated)

  def followers() = _members.filter(m => m.stateName == Follower && !m.isTerminated)

  def candidates() = _members.filter(m => m.stateName == Candidate && !m.isTerminated)

  def simpleName(ref: ActorRef) = {
    import collection.JavaConverters._
    ref.path.getElements.asScala.last
  }

  def infoMemberStates() {
    info(s"Members: ${members.map(simpleName).mkString(", ")}; Leader is: ${simpleName(leader)}")
  }

  // cluster management

  def killLeader() = {
    val leaderToStop = leader
    leaderToStop.stop()
    info(s"Killed leader: ${simpleName(leaderToStop)}")
    leaderToStop
  }

  def suspendMember(member: TestFSMRef[_, _, _]) = {
    member.suspend()
    info(s"Killed member: ${simpleName(member)}")
    member
  }

  def restartMember(member: TestFSMRef[_, _, _]) = {
    member.restart(new Throwable)
    info(s"Restarted member: ${simpleName(member)}")
    member
  }

  // await stuff -------------------------------------------------------------------------------------------------------

  def subscribeElectedLeader()(implicit probe: TestProbe): Unit =
    system.eventStream.subscribe(probe.ref, classOf[ElectedAsLeader])

  def awaitElectedLeader()(implicit probe: TestProbe): Unit =
    probe.expectMsgClass(max = 3.seconds, classOf[ElectedAsLeader])

  def subscribeBeginElection()(implicit probe: TestProbe): Unit =
    system.eventStream.subscribe(probe.ref, BeginElection.getClass)

  def awaitBeginElection()(implicit probe: TestProbe): Unit =
    probe.expectMsgClass(max = 3.seconds, BeginElection.getClass)

  // end of await stuff ------------------------------------------------------------------------------------------------

  override def afterAll() {
    super.afterAll()
    shutdown(system)
  }

}
