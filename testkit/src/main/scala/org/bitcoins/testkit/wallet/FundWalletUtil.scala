package org.bitcoins.testkit.wallet

import akka.actor.ActorSystem
import com.typesafe.config.Config
import grizzled.slf4j.Logging
import org.bitcoins.core.api.chain.ChainQueryApi
import org.bitcoins.core.api.node.NodeApi
import org.bitcoins.core.currency.CurrencyUnit
import org.bitcoins.core.hd.HDAccount
import org.bitcoins.core.protocol.BitcoinAddress
import org.bitcoins.core.protocol.transaction.TransactionOutput
import org.bitcoins.crypto.DoubleSha256DigestBE
import org.bitcoins.dlc.wallet.DLCWallet
import org.bitcoins.rpc.client.common.BitcoindRpcClient
import org.bitcoins.server.{BitcoinSAppConfig, BitcoindRpcBackendUtil}
import org.bitcoins.testkit.wallet.FundWalletUtil.{
  FundedTestWallet,
  FundedWallet
}
import org.bitcoins.testkitcore.util.TransactionTestUtil
import org.bitcoins.wallet.Wallet
import org.bitcoins.wallet.config.WalletAppConfig

import scala.concurrent.{ExecutionContext, Future}

trait FundWalletUtil extends Logging {

  def fundAccountForWallet(
      amts: Vector[CurrencyUnit],
      account: HDAccount,
      wallet: Wallet)(implicit ec: ExecutionContext): Future[Wallet] = {

    val addressesF: Future[Vector[BitcoinAddress]] = Future.sequence {
      Vector.fill(3)(wallet.getNewAddress(account))
    }

    //construct three txs that send money to these addresses
    //these are "fictional" transactions in the sense that the
    //outpoints do not exist on a blockchain anywhere
    val txsF = for {
      addresses <- addressesF
    } yield {
      addresses.zip(amts).map { case (addr, amt) =>
        val output =
          TransactionOutput(value = amt, scriptPubKey = addr.scriptPubKey)
        TransactionTestUtil.buildTransactionTo(output)
      }
    }

    val fundedWalletF =
      txsF.flatMap(txs =>
        wallet.processTransactions(txs, Some(DoubleSha256DigestBE.empty)))

    fundedWalletF.map(_.asInstanceOf[Wallet])
  }

  def fundAccountForWalletWithBitcoind(
      amts: Vector[CurrencyUnit],
      account: HDAccount,
      wallet: Wallet,
      bitcoind: BitcoindRpcClient)(implicit
      ec: ExecutionContext): Future[Wallet] = {

    val addressesF: Future[Vector[BitcoinAddress]] = Future.sequence {
      Vector.fill(3)(wallet.getNewAddress(account))
    }

    val txAndHashF = for {
      addresses <- addressesF
      addressAmountMap = addresses.zip(amts).toMap
      txId <- bitcoind.sendMany(addressAmountMap)
      tx <- bitcoind.getRawTransactionRaw(txId)
      hashes <- bitcoind.getNewAddress.flatMap(bitcoind.generateToAddress(6, _))

      _ <- wallet.processTransaction(tx, hashes.headOption)
    } yield (tx, hashes.head)

    txAndHashF.map(_ => wallet)
  }

  /** Funds a bitcoin-s wallet with 3 utxos with 1, 2 and 3 bitcoin in the utxos */
  def fundWallet(wallet: Wallet)(implicit
      ec: ExecutionContext): Future[FundedTestWallet] = {

    val defaultAccount = wallet.walletConfig.defaultAccount
    val fundedDefaultAccountWalletF = FundWalletUtil.fundAccountForWallet(
      amts = BitcoinSWalletTest.defaultAcctAmts,
      account = defaultAccount,
      wallet = wallet
    )

    val hdAccount1 = WalletTestUtil.getHdAccount1(wallet.walletConfig)
    val fundedAccount1WalletF = for {
      fundedDefaultAcct <- fundedDefaultAccountWalletF
      fundedAcct1 <- FundWalletUtil.fundAccountForWallet(
        amts = BitcoinSWalletTest.account1Amt,
        account = hdAccount1,
        fundedDefaultAcct
      )
    } yield fundedAcct1

    //sanity check to make sure we have money
    for {
      fundedWallet <- fundedAccount1WalletF
      balance <- fundedWallet.getBalance(defaultAccount)
      _ = require(
        balance == BitcoinSWalletTest.expectedDefaultAmt,
        s"Funding wallet fixture failed to fund the wallet, got balance=${balance} expected=${BitcoinSWalletTest.expectedDefaultAmt}"
      )

      account1Balance <- fundedWallet.getBalance(hdAccount1)
      _ = require(
        account1Balance == BitcoinSWalletTest.expectedAccount1Amt,
        s"Funding wallet fixture failed to fund account 1, " +
          s"got balance=${hdAccount1} expected=${BitcoinSWalletTest.expectedAccount1Amt}"
      )

    } yield FundedWallet(fundedWallet)
  }
}

object FundWalletUtil extends FundWalletUtil {

  trait FundedTestWallet {
    def wallet: Wallet
  }

  object FundedTestWallet {

    def apply(wallet: Wallet): FundedTestWallet = {
      wallet match {
        case dlc: DLCWallet =>
          FundedDLCWallet(dlc)
        case _: Wallet =>
          FundedWallet(wallet)
      }
    }
  }

  /** This is a wallet that was two funded accounts
    * Account 0 (default account) has utxos of 1,2,3 bitcoin in it (6 btc total)
    * Account 1 has a utxos of 0.2,0.3,0.5 bitcoin in it (0.6 total)
    */
  case class FundedWallet(wallet: Wallet) extends FundedTestWallet

  case class FundedDLCWallet(wallet: DLCWallet) extends FundedTestWallet

  /** This creates a wallet that was two funded accounts
    * Account 0 (default account) has utxos of 1,2,3 bitcoin in it (6 btc total)
    * Account 1 has a utxos of 0.2,0.3,0.5 bitcoin in it (1 btc total)
    */
  def createFundedWallet(
      nodeApi: NodeApi,
      chainQueryApi: ChainQueryApi,
      bip39PasswordOpt: Option[String],
      extraConfig: Option[Config] = None)(implicit
      config: WalletAppConfig,
      system: ActorSystem): Future[FundedWallet] = {

    import system.dispatcher
    for {
      wallet <- BitcoinSWalletTest.createWallet2Accounts(
        nodeApi = nodeApi,
        chainQueryApi = chainQueryApi,
        bip39PasswordOpt = bip39PasswordOpt,
        extraConfig = extraConfig)
      funded <- FundWalletUtil.fundWallet(wallet)
    } yield FundedWallet(funded.wallet)
  }

  def createFundedDLCWallet(
      nodeApi: NodeApi,
      chainQueryApi: ChainQueryApi,
      bip39PasswordOpt: Option[String],
      extraConfig: Option[Config] = None)(implicit
      config: BitcoinSAppConfig,
      system: ActorSystem): Future[FundedDLCWallet] = {
    import system.dispatcher
    for {
      wallet <- BitcoinSWalletTest.createDLCWallet2Accounts(
        nodeApi = nodeApi,
        chainQueryApi = chainQueryApi,
        bip39PasswordOpt = bip39PasswordOpt,
        extraConfig = extraConfig)
      funded <- FundWalletUtil.fundWallet(wallet)
    } yield FundedDLCWallet(funded.wallet.asInstanceOf[DLCWallet])
  }

  def createFundedDLCWalletWithBitcoind(
      bitcoind: BitcoindRpcClient,
      bip39PasswordOpt: Option[String],
      extraConfig: Option[Config] = None)(implicit
      config: BitcoinSAppConfig,
      system: ActorSystem): Future[FundedDLCWallet] = {
    import system.dispatcher
    for {
      tmp <- BitcoinSWalletTest.createDLCWallet2Accounts(
        nodeApi = bitcoind,
        chainQueryApi = bitcoind,
        bip39PasswordOpt = bip39PasswordOpt,
        extraConfig = extraConfig)

      wallet = BitcoindRpcBackendUtil.createDLCWalletWithBitcoindCallbacks(
        bitcoind,
        tmp)

      funded1 <- fundAccountForWalletWithBitcoind(
        BitcoinSWalletTest.defaultAcctAmts,
        wallet.walletConfig.defaultAccount,
        wallet,
        bitcoind)

      hdAccount1 = WalletTestUtil.getHdAccount1(wallet.walletConfig)
      funded <- fundAccountForWalletWithBitcoind(BitcoinSWalletTest.account1Amt,
                                                 hdAccount1,
                                                 funded1,
                                                 bitcoind)
    } yield FundedDLCWallet(funded.asInstanceOf[DLCWallet])
  }
}
