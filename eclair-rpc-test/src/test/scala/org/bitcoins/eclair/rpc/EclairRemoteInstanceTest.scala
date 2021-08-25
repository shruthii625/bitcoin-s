package org.bitcoins.eclair.rpc

import org.bitcoins.eclair.rpc.client.EclairRpcClient
import org.bitcoins.eclair.rpc.config.{
  EclairAuthCredentialsRemote,
  EclairInstanceRemote
}
import org.bitcoins.testkit.util.{BitcoinSAsyncTest, EclairRpcTestClient}

class EclairRemoteInstanceTest extends BitcoinSAsyncTest {

  behavior of "EclairRpcClient"

  it should "be able to get channel stats with remote instance" in {

    val eclairTestClient =
      EclairRpcTestClient.fromSbtDownload(eclairVersionOpt = None,
                                          eclairCommitOpt = None,
                                          bitcoindRpcClientOpt = None)
    for {
      eclair <- eclairTestClient.start()
      instance = eclair.getInstance
      _ = logger.info(instance)
      remoteInstance = EclairInstanceRemote(
        network = instance.network,
        uri = instance.uri,
        rpcUri = instance.rpcUri,
        EclairAuthCredentialsRemote(password =
                                      instance.authCredentials.password,
                                    rpcPort = instance.authCredentials.rpcPort,
                                    None),
        None,
        None
      )
      eclairClient = new EclairRpcClient(remoteInstance)
      _ <- eclairClient.isStarted().map {
        case false => fail("Couldn't ping eclair")
        case true  =>
      }
    } yield succeed
  }
}
