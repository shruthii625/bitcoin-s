package org.bitcoins.eclair.rpc

import com.typesafe.config.ConfigFactory
import org.bitcoins.core.config.RegTest
import org.bitcoins.core.protocol.ln.LnPolicy
import org.bitcoins.eclair.rpc.client.EclairRpcClient
import org.bitcoins.eclair.rpc.config.{EclairAuthCredentialsRemote, EclairInstanceRemote}
import org.bitcoins.testkit.util.BitcoinSAsyncTest

import java.io.File
import java.net.URI

class EclairRemoteInstanceTest extends BitcoinSAsyncTest {
  val homeVar = sys.env("HOME");

  val config = ConfigFactory
    .parseFile(new File(homeVar + "/.eclair/eclair.conf"))
    .resolve()

  behavior of "EclairRpcClient"

  it should "be able to get channel stats with remote instance" in {

    val remoteInstance = EclairInstanceRemote(
      RegTest,
      new URI(s"http://0.0.0.0:${LnPolicy.DEFAULT_LN_P2P_PORT}"),
      new URI(s"http://127.0.0.1:${LnPolicy.DEFAULT_ECLAIR_API_PORT}"),
      EclairAuthCredentialsRemote(config.getString("eclair.api.password"),
                                  config.getInt("eclair.api.port"),
                                  None),
      None,
      None
    )

    val eclairClient = new EclairRpcClient(remoteInstance)

    for {

      res <- eclairClient.channelStats()
    } yield {
      assert(res.nonEmpty)
    }
  }
}
