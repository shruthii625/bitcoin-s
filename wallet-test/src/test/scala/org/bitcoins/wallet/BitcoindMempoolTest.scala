package org.bitcoins.wallet

import org.bitcoins.core.wallet.utxo.TxoState
import org.bitcoins.server.BitcoindRpcBackendUtil
import org.bitcoins.testkit.wallet.{
  BitcoinSWalletTestCachedBitcoindNewest,
  WalletWithBitcoind
}
import org.scalatest.{FutureOutcome, Outcome}

import scala.concurrent.Future

class BitcoindMempoolTest extends BitcoinSWalletTestCachedBitcoindNewest {

  override type FixtureParam = WalletWithBitcoind

  override def withFixture(test: OneArgAsyncTest): FutureOutcome = {
    val f: Future[Outcome] = for {
      bitcoind <- cachedBitcoindWithFundsF
      futOutcome = withFundedWalletAndBitcoindCached(
        test,
        getBIP39PasswordOpt(),
        bitcoind)(getFreshWalletAppConfig)
      fut <- futOutcome.toFuture
    } yield fut
    new FutureOutcome(f)
  }

  it must "check if the state of spending tx has been changed after processing" in {
    fundedWallet: WalletWithBitcoind =>
      val wallet = fundedWallet.wallet
      val bitcoind = fundedWallet.bitcoind
      for {
        txFromTxOutpoints <- wallet.spendingInfoDAO.findAllOutpoints()
        utxolist <- wallet.listUtxos(txFromTxOutpoints)
        utxostate = utxolist.map(utxo => utxo.state)
        _ <- BitcoindRpcBackendUtil.processBitcoindMempoolTransactions(wallet,
                                                                       bitcoind)
      } yield {
        val stateList = utxolist.map(utxo => utxo.state)

        assert(
          stateList.equals(
            Vector.fill(stateList.size)(TxoState.BroadcastSpent)),
          s"\nState before calling processBitcoindMempoolTransactions(): ${utxostate} \n State after calling processBitcoindMempoolTransacctions : ${stateList}"
        )

      }

  }

}
