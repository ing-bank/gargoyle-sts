package com.ing.wbaa.rokku.sts.service

import java.time.Instant
import java.util.concurrent.TimeUnit

import akka.actor.ActorSystem
import com.ing.wbaa.rokku.sts.config.StsSettings
import com.ing.wbaa.rokku.sts.data.aws._
import com.ing.wbaa.rokku.sts.data._
import org.scalatest.wordspec.AsyncWordSpec
import org.scalatest.diagrams.Diagrams

import scala.concurrent.duration.Duration
import scala.concurrent.{ ExecutionContext, Future }
import scala.util.Random

class UserTokenDbServiceTest extends AsyncWordSpec with Diagrams {

  trait UserTokenDbServiceTest extends UserTokenDbService {
    val testSystem: ActorSystem = ActorSystem.create("test-system")
    override implicit def executionContext: ExecutionContext = testSystem.dispatcher
    override protected[this] def stsSettings: StsSettings = StsSettings(testSystem)

    override protected[this] def getAwsCredentialAndStatus(userName: UserName): Future[(Option[AwsCredential], AccountStatus)] =
      Future.successful((Some(AwsCredential(AwsAccessKey("a"), AwsSecretKey("s"))), AccountStatus(true)))

    override protected[this] def getUserSecretWithExtInfo(awsAccessKey: AwsAccessKey): Future[Option[(UserName, AwsSecretKey, NPA, AccountStatus, Set[UserGroup])]] =
      Future.successful(Some((UserName("u"), AwsSecretKey("s"), NPA(false), AccountStatus(true), Set(UserGroup("testGroup")))))

    override protected[this] def insertAwsCredentials(username: UserName, awsCredential: AwsCredential, isNpa: Boolean): Future[Boolean] =
      Future.successful(true)

    override protected[this] def getToken(awsSessionToken: AwsSessionToken, userName: UserName): Future[Option[(UserName, UserAssumeRole, AwsSessionTokenExpiration)]] =
      Future.successful(Some((UserName("u"), UserAssumeRole(""), AwsSessionTokenExpiration(Instant.now().plusSeconds(20)))))

    override protected[this] def insertToken(awsSessionToken: AwsSessionToken, username: UserName, expirationDate: AwsSessionTokenExpiration): Future[Boolean] =
      Future.successful(true)

    override protected[this] def insertToken(awsSessionToken: AwsSessionToken, username: UserName, role: UserAssumeRole, expirationDate: AwsSessionTokenExpiration): Future[Boolean] =
      Future.successful(true)

    override protected[this] def doesUsernameNotExistAndAccessKeyExist(userName: UserName, awsAccessKey: AwsAccessKey): Future[Boolean] =
      Future.successful(false)

    override protected[this] def insertUserGroups(userName: UserName, userGroups: Set[UserGroup]): Future[Boolean] =
      Future.successful(true)
  }

  private class TestObject {
    val userName: UserName = UserName(Random.alphanumeric.take(32).mkString)
    val duration: Duration = Duration(2, TimeUnit.HOURS)
  }

  "User token service" should {
    "get credentials and token" that {
      def assertExpirationValid(expiration: AwsSessionTokenExpiration, durationFromNow: Duration, allowedOffsetMillis: Long = 28800000) = {
        val diff = Instant.now().toEpochMilli + durationFromNow.toMillis - expiration.value.toEpochMilli
        assert(diff >= 0 && diff < allowedOffsetMillis)
      }

      "are new credentials and a new token with specified duration" in {
        val testObject = new TestObject
        new UserTokenDbServiceTest {}.getAwsCredentialWithToken(testObject.userName, Set.empty[UserGroup], Some(testObject.duration)).map { c =>
          assertExpirationValid(c.session.expiration, testObject.duration)
        }
      }

      "are new credentials" in {
        val testObject = new TestObject
        new UserTokenDbServiceTest {
          override protected[this] def getAwsCredentialAndStatus(userName: UserName): Future[(Option[AwsCredential], AccountStatus)] = Future.successful((None, AccountStatus(false)))
        }.getAwsCredentialWithToken(testObject.userName, Set.empty[UserGroup], Some(testObject.duration)).map { c =>
          assertExpirationValid(c.session.expiration, testObject.duration)
        }
      }

      "are new credentials but token generated exists" in {
        val testObject = new TestObject

        val utdst = new UserTokenDbServiceTest {
          override protected[this] def getAwsCredentialAndStatus(userName: UserName): Future[(Option[AwsCredential], AccountStatus)] = Future.successful((None, AccountStatus(false)))

          override protected[this] def insertAwsCredentials(username: UserName, awsCredential: AwsCredential, isNpa: Boolean): Future[Boolean] =
            Future.successful(false)
        }

        recoverToSucceededIf[Exception] {
          utdst.getAwsCredentialWithToken(testObject.userName, Set.empty[UserGroup], Some(testObject.duration)).map { c =>
            assertExpirationValid(c.session.expiration, testObject.duration)
          }
        }

      }

      "have existing credentials and a new token" in {
        val testObject = new TestObject
        val utds = new UserTokenDbServiceTest {}
        utds.getAwsCredentialWithToken(testObject.userName, Set.empty[UserGroup], None).flatMap { firstReturn =>
          utds.getAwsCredentialWithToken(testObject.userName, Set.empty[UserGroup], None).map { secondReturn =>
            assert(firstReturn.awsCredential == secondReturn.awsCredential)
            assert(firstReturn.session != secondReturn.session)
          }
        }
      }
    }

    "check if credentials are active" that {
      "has valid accesskey and sessiontoken is active" in {
        val t = new TestObject
        val utds = new UserTokenDbServiceTest {}
        utds.getAwsCredentialWithToken(t.userName, Set.empty[UserGroup], Some(t.duration)).flatMap { awsCredWithToken =>
          utds.isCredentialActive(awsCredWithToken.awsCredential.accessKey, Some(awsCredWithToken.session.sessionToken)).map { u =>
            assert(u.map(_.userName).contains(UserName("u")))
            assert(u.map(_.awsAccessKey).contains(AwsAccessKey("a")))
            assert(u.map(_.awsSecretKey).contains(AwsSecretKey("s")))
            assert(u.map(_.userGroup).contains(Set(UserGroup("testGroup"))))
            assert(u.map(_.userRole).contains(None))
          }
        }
      }

      "has valid accesskey and sessiontoken is inactive" in {
        val t = new TestObject
        val utds = new UserTokenDbServiceTest {
          override protected[this] def getToken(awsSessionToken: AwsSessionToken, userName: UserName): Future[Option[(UserName, UserAssumeRole, AwsSessionTokenExpiration)]] =
            Future.successful(Some((UserName("u"), UserAssumeRole("testGroup"), AwsSessionTokenExpiration(Instant.now().minusSeconds(20)))))
        }
        utds.getAwsCredentialWithToken(t.userName, Set.empty[UserGroup], Some(Duration(-1, TimeUnit.HOURS)))
          .flatMap { awsCredWithToken =>
            utds.isCredentialActive(awsCredWithToken.awsCredential.accessKey, Some(awsCredWithToken.session.sessionToken))
              .map(b => assert(b.isEmpty))
          }
      }

      "has valid accesskey and sessiontoken is active for a role" in {
        val t = new TestObject
        val utds = new UserTokenDbServiceTest {
          override protected[this] def getToken(awsSessionToken: AwsSessionToken, userName: UserName): Future[Option[(UserName, UserAssumeRole, AwsSessionTokenExpiration)]] =
            Future.successful(Some((UserName("u"), UserAssumeRole("testRole"), AwsSessionTokenExpiration(Instant.now().plusSeconds(20)))))
        }
        utds.getAwsCredentialWithToken(t.userName, Set.empty[UserGroup], Some(t.duration))
          .flatMap { awsCredWithToken =>
            utds.isCredentialActive(awsCredWithToken.awsCredential.accessKey, Some(awsCredWithToken.session.sessionToken)).map { u =>
              assert(u.map(_.userName).contains(UserName("u")))
              assert(u.map(_.awsAccessKey).contains(AwsAccessKey("a")))
              assert(u.map(_.awsSecretKey).contains(AwsSecretKey("s")))
              assert(u.map(_.userGroup).contains(Set.empty))
              assert(u.map(_.userRole).contains(Some(UserAssumeRole("testRole"))))
            }
          }
      }

      "has invalid accesskey" in {
        val utds = new UserTokenDbServiceTest {}
        utds.isCredentialActive(AwsAccessKey("nonexistent"), None).map(a => assert(a.isEmpty))
      }

      "has valid accesskey, no sessiontoken and is not an NPA" in {
        val t = new TestObject
        val utds = new UserTokenDbServiceTest {}
        utds.getAwsCredentialWithToken(t.userName, Set.empty[UserGroup], Some(t.duration)).flatMap { awsCredWithToken =>
          utds.isCredentialActive(awsCredWithToken.awsCredential.accessKey, None).map(a => assert(a.isEmpty))
        }
      }

      "has valid accesskey, no sessiontoken and is an NPA" in {
        val t = new TestObject
        val utds = new UserTokenDbServiceTest {
          override protected[this] def getUserSecretWithExtInfo(awsAccessKey: AwsAccessKey): Future[Option[(UserName, AwsSecretKey, NPA, AccountStatus, Set[UserGroup])]] =
            Future.successful(Some((UserName("u"), AwsSecretKey("s"), NPA(true), AccountStatus(true), Set.empty[UserGroup])))
        }
        utds.getAwsCredentialWithToken(t.userName, Set.empty[UserGroup], Some(t.duration)).flatMap { awsCredWithToken =>
          utds.isCredentialActive(awsCredWithToken.awsCredential.accessKey, None).map(a => assert(a.isDefined))
        }
      }

      "not provide user credentials with account disabled" in {
        val t = new TestObject
        val utds = new UserTokenDbServiceTest {
          override protected[this] def getUserSecretWithExtInfo(awsAccessKey: AwsAccessKey): Future[Option[(UserName, AwsSecretKey, NPA, AccountStatus, Set[UserGroup])]] =
            Future.successful(Some((UserName("u"), AwsSecretKey("s"), NPA(false), AccountStatus(false), Set.empty[UserGroup])))
        }
        utds.getAwsCredentialWithToken(t.userName, Set.empty[UserGroup], Some(t.duration)).flatMap { awsCredWithToken =>
          utds.isCredentialActive(awsCredWithToken.awsCredential.accessKey, Some(awsCredWithToken.session.sessionToken)).map(a => assert(!a.isDefined))
        }
      }
    }
  }
}
