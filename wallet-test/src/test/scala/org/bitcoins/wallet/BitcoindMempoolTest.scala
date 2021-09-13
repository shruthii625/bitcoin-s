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

  it must "send txs present in both txoutpointlist and bitcoind mempool for processing" in {
    fundedWallet: WalletWithBitcoind =>
      val wallet = fundedWallet.wallet
      val bitcoind = fundedWallet.bitcoind
      for {
        utxoList <- wallet.listUtxos()
        txList = utxoList.map(info => info.txid)
        txidUtxoMap = utxoList.map(info => info.txid -> info).toMap
        txFromTxOutpoints <- wallet.spendingInfoDAO.findAllOutpoints()
        _ <- BitcoindRpcBackendUtil.processBitcoindMempoolTransactions(wallet,
                                                                       bitcoind)
      } yield {
        val finalList = txFromTxOutpoints.map(op => op.txIdBE).intersect(txList)

        val stateList = finalList.map(tx =>
          txidUtxoMap
            .get(tx)
            .map(info => info.state) match {
            case Some(value) => value
            case scala.None  =>
          })

        assert(
          stateList.equals(
            Vector.fill(stateList.size)(TxoState.BroadcastSpent)))

      }

  }

}
