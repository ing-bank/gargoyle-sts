package com.ing.wbaa.gargoyle.sts.data.aws

import java.time.Instant

case class AwsAccessKey(value: String) extends AnyVal

case class AwsSecretKey(value: String) extends AnyVal

case class AwsCredential(
    accessKey: AwsAccessKey,
    secretKey: AwsSecretKey
)

case class AwsSessionToken(value: String) extends AnyVal

case class AwsSessionTokenExpiration(value: Instant) extends AnyVal

case class AwsSession(sessionToken: AwsSessionToken, expiration: AwsSessionTokenExpiration)

case class AwsCredentialWithToken(
    awsCredential: AwsCredential,
    session: AwsSession
)
