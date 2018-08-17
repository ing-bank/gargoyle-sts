package com.ing.wbaa.gargoyle.sts.service

import java.time.Instant

import akka.http.scaladsl.marshallers.xml.ScalaXmlSupport
import com.ing.wbaa.gargoyle.sts.data.{ AssumeRoleWithWebIdentityResponse, AssumedRoleUser, CredentialsResponse }

import scala.xml._

trait TokenXML extends ScalaXmlSupport {

  protected def getSessionTokenResponseToXML(credentials: CredentialsResponse): NodeSeq = {
    <GetSessionTokenResponse>
      <GetSessionTokenResult>{ credentialToXml(credentials) }</GetSessionTokenResult>
      <ResponseMetadata>
        <RequestId>{ credentials.requestId }</RequestId>
      </ResponseMetadata>
    </GetSessionTokenResponse>
  }

  protected def assumeRoleWithWebIdentityResponseToXML(aRWWIResponse: AssumeRoleWithWebIdentityResponse): NodeSeq = {
    <AssumeRoleWithWebIdentityResponse>
      <AssumeRoleWithWebIdentityResult>
        <SubjectFromWebIdentityToken>{ aRWWIResponse.subjectFromWebIdentityToken }</SubjectFromWebIdentityToken>
        <Audience>{ aRWWIResponse.audience }</Audience>
        { assumedRoleUserToXml(aRWWIResponse.assumedRoleUser) }
        { credentialToXml(aRWWIResponse.credentialsResponse) }
        <Provider>{ aRWWIResponse.provider }</Provider>
      </AssumeRoleWithWebIdentityResult>
      <ResponseMetadata>
        <RequestId>{ aRWWIResponse.credentialsResponse.requestId }</RequestId>
      </ResponseMetadata>
    </AssumeRoleWithWebIdentityResponse>
  }

  private def assumedRoleUserToXml(assumedRoleUser: AssumedRoleUser): NodeSeq = {
    <AssumedRoleUser>
      <Arn>{ assumedRoleUser.arn }</Arn>
      <AssumedRoleId>{ assumedRoleUser.assumedRoleId }</AssumedRoleId>
    </AssumedRoleUser>
  }

  private def credentialToXml(credentials: CredentialsResponse): NodeSeq = {
    <Credentials>
      <SessionToken>{ credentials.sessionToken }</SessionToken>
      <SecretAccessKey>{ credentials.secretAccessKey }</SecretAccessKey>
      <Expiration>{ Instant.ofEpochMilli(credentials.expiration) }</Expiration>
      <AccessKeyId>{ credentials.accessKeyId }</AccessKeyId>
    </Credentials>
  }
}

