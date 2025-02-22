package org.bitcoins.cli

import org.bitcoins.cli.CliCommand._
import org.bitcoins.cli.CliReaders._
import org.bitcoins.commons.jsonmodels.bitcoind.RpcOpts.LockUnspentOutputParameter
import org.bitcoins.commons.serializers.Picklers._
import org.bitcoins.core.api.wallet.CoinSelectionAlgo
import org.bitcoins.core.config.NetworkParameters
import org.bitcoins.core.crypto._
import org.bitcoins.core.currency._
import org.bitcoins.core.hd.AddressType
import org.bitcoins.core.hd.AddressType.SegWit
import org.bitcoins.core.number.UInt32
import org.bitcoins.core.protocol.tlv._
import org.bitcoins.core.protocol.transaction.{
  EmptyTransaction,
  Transaction,
  TransactionOutPoint
}
import org.bitcoins.core.protocol.{BitcoinAddress, BlockStamp}
import org.bitcoins.core.psbt.PSBT
import org.bitcoins.core.util.EnvUtil
import org.bitcoins.core.wallet.fee.SatoshisPerVirtualByte
import org.bitcoins.core.wallet.utxo.AddressLabelTag
import org.bitcoins.crypto._
import scodec.bits.ByteVector
import scopt.OParser
import ujson._
import upickle.{default => up}

import java.io.File
import java.net.InetSocketAddress
import java.nio.file.Path
import java.time.Instant
import java.util.Date
import scala.collection.mutable
import scala.util.{Failure, Success, Try}

object ConsoleCli {

  def parser: OParser[Unit, Config] = {
    val builder = OParser.builder[Config]

    import builder._
    OParser.sequence(
      programName("bitcoin-s-cli"),
      opt[NetworkParameters]('n', "network")
        .action((np, conf) => conf.copy(network = Some(np)))
        .text("Select the active network."),
      opt[Unit]("debug")
        .action((_, conf) => conf.copy(debug = true))
        .text("Print debugging information"),
      opt[Int]("rpcport")
        .action((port, conf) => conf.copy(rpcPortOpt = Some(port)))
        .text(s"The port to send our rpc request to on the server"),
      opt[Unit]("version")
        .action((_, conf) => conf.copy(command = GetVersion))
        .hidden(),
      help('h', "help").text("Display this help message and exit"),
      note(sys.props("line.separator") + "Commands:"),
      note(sys.props("line.separator") + "===Blockchain ==="),
      cmd("getinfo")
        .action((_, conf) => conf.copy(command = GetInfo))
        .text(s"Returns basic info about the current chain"),
      cmd("getblockcount")
        .action((_, conf) => conf.copy(command = GetBlockCount))
        .text(s"Get the block height"),
      cmd("getfiltercount")
        .action((_, conf) => conf.copy(command = GetFilterCount))
        .text(s"Get the number of filters"),
      cmd("getfilterheadercount")
        .action((_, conf) => conf.copy(command = GetFilterHeaderCount))
        .text(s"Get the number of filter headers"),
      cmd("getbestblockhash")
        .action((_, conf) => conf.copy(command = GetBestBlockHash))
        .text(s"Get the best block hash"),
      cmd("getblockheader")
        .action((_, conf) =>
          conf.copy(command = GetBlockHeader(DoubleSha256DigestBE.empty)))
        .text("Returns information about block header <hash>")
        .children(
          arg[DoubleSha256DigestBE]("hash")
            .text("The block hash")
            .required()
            .action((hash, conf) =>
              conf.copy(command = conf.command match {
                case gbh: GetBlockHeader => gbh.copy(hash = hash)
                case other               => other
              }))),
      cmd("decoderawtransaction")
        .action((_, conf) =>
          conf.copy(command = DecodeRawTransaction(EmptyTransaction)))
        .text(s"Decode the given raw hex transaction")
        .children(
          arg[Transaction]("tx")
            .text("Transaction encoded in hex to decode")
            .required()
            .action((tx, conf) =>
              conf.copy(command = conf.command match {
                case decode: DecodeRawTransaction =>
                  decode.copy(transaction = tx)
                case other => other
              }))),
      note(sys.props("line.separator") + "=== Wallet ==="),
      cmd("rescan")
        .action((_, conf) =>
          conf.copy(
            command = Rescan(addressBatchSize = Option.empty,
                             startBlock = Option.empty,
                             endBlock = Option.empty,
                             force = false,
                             ignoreCreationTime = false)))
        .text(s"Rescan for wallet UTXOs")
        .children(
          opt[Unit]("force")
            .text("Clears existing wallet records. Warning! Use with caution!")
            .optional()
            .action((_, conf) =>
              conf.copy(command = conf.command match {
                case rescan: Rescan =>
                  rescan.copy(force = true)
                case other => other
              })),
          opt[Int]("batch-size")
            .text("Number of filters that can be matched in one batch")
            .optional()
            .action((batchSize, conf) =>
              conf.copy(command = conf.command match {
                case rescan: Rescan =>
                  rescan.copy(addressBatchSize = Option(batchSize))
                case other => other
              })),
          opt[BlockStamp]("start")
            .text("Start height")
            .optional()
            .action((start, conf) =>
              conf.copy(command = conf.command match {
                case rescan: Rescan =>
                  // Need to ignoreCreationTime so we try to call
                  // rescan with rescanNeutrinoWallet with a block
                  // and a creation time
                  rescan.copy(startBlock = Option(start),
                              ignoreCreationTime = true)
                case other => other
              })),
          opt[BlockStamp]("end")
            .text("End height")
            .optional()
            .action((end, conf) =>
              conf.copy(command = conf.command match {
                case rescan: Rescan =>
                  rescan.copy(endBlock = Option(end))
                case other => other
              })),
          opt[Unit]("ignorecreationtime")
            .text("Ignores the wallet creation date and will instead do a full rescan")
            .optional()
            .action((_, conf) =>
              conf.copy(command = conf.command match {
                case rescan: Rescan =>
                  rescan.copy(ignoreCreationTime = true)
                case other => other
              }))
        ),
      cmd("isempty")
        .action((_, conf) => conf.copy(command = IsEmpty))
        .text("Checks if the wallet contains any data"),
      cmd("walletinfo")
        .action((_, conf) => conf.copy(command = WalletInfo))
        .text("Returns data about the current wallet being used"),
      cmd("getdlchostaddress")
        .action((_, conf) => conf.copy(command = GetDLCHostAddress))
        .text("Returns the public listening address of the DLC Node"),
      cmd("createdlcoffer")
        .action((_, conf) =>
          conf.copy(
            command = CreateDLCOffer(ContractInfoV0TLV.dummy,
                                     Satoshis.zero,
                                     None,
                                     UInt32.zero,
                                     UInt32.zero)))
        .text("Creates a DLC offer that another party can accept")
        .children(
          arg[ContractInfoV0TLV]("contractInfo")
            .required()
            .action((info, conf) =>
              conf.copy(command = conf.command match {
                case offer: CreateDLCOffer =>
                  offer.copy(contractInfo = info)
                case other => other
              })),
          arg[Satoshis]("collateral")
            .required()
            .action((collateral, conf) =>
              conf.copy(command = conf.command match {
                case offer: CreateDLCOffer =>
                  offer.copy(collateral = collateral)
                case other => other
              })),
          arg[SatoshisPerVirtualByte]("feerate")
            .optional()
            .action((feeRate, conf) =>
              conf.copy(command = conf.command match {
                case offer: CreateDLCOffer =>
                  offer.copy(feeRateOpt = Some(feeRate))
                case other => other
              })),
          arg[UInt32]("locktime")
            .required()
            .action((locktime, conf) =>
              conf.copy(command = conf.command match {
                case offer: CreateDLCOffer =>
                  offer.copy(locktime = locktime)
                case other => other
              })),
          arg[UInt32]("refundlocktime")
            .required()
            .action((refundLT, conf) =>
              conf.copy(command = conf.command match {
                case offer: CreateDLCOffer =>
                  offer.copy(refundLT = refundLT)
                case other => other
              }))
        ),
      cmd("acceptdlc")
        .action((_, conf) =>
          conf.copy(command =
            AcceptDLC(null,
                      InetSocketAddress.createUnresolved("localhost", 0))))
        .text("Accepts a DLC offer given from another party")
        .children(
          arg[LnMessage[DLCOfferTLV]]("offer")
            .required()
            .action((offer, conf) =>
              conf.copy(command = conf.command match {
                case accept: AcceptDLC =>
                  accept.copy(offer = offer)
                case other => other
              })),
          arg[InetSocketAddress]("peer")
            .required()
            .action((peer, conf) =>
              conf.copy(command = conf.command match {
                case accept: AcceptDLC =>
                  accept.copy(peerAddr = peer)
                case other => other
              }))
        ),
      cmd("acceptdlcoffer")
        .action((_, conf) => conf.copy(command = AcceptDLCOffer(null)))
        .text("Accepts a DLC offer given from another party")
        .children(
          arg[LnMessage[DLCOfferTLV]]("offer")
            .required()
            .action((offer, conf) =>
              conf.copy(command = conf.command match {
                case accept: AcceptDLCOffer =>
                  accept.copy(offer = offer)
                case other => other
              }))
        ),
      cmd("acceptdlcofferfromfile")
        .action((_, conf) =>
          conf.copy(command =
            AcceptDLCOfferFromFile(new File("").toPath, None)))
        .text("Accepts a DLC offer given from another party")
        .children(
          arg[Path]("path")
            .required()
            .action((path, conf) =>
              conf.copy(command = conf.command match {
                case accept: AcceptDLCOfferFromFile =>
                  accept.copy(path = path)
                case other => other
              })),
          arg[Path]("destination")
            .optional()
            .action((dest, conf) =>
              conf.copy(command = conf.command match {
                case accept: AcceptDLCOfferFromFile =>
                  accept.copy(destination = Some(dest))
                case other => other
              }))
        ),
      cmd("signdlc")
        .action((_, conf) => conf.copy(command = SignDLC(null)))
        .text("Signs a DLC")
        .children(
          arg[LnMessage[DLCAcceptTLV]]("accept")
            .required()
            .action((accept, conf) =>
              conf.copy(command = conf.command match {
                case signDLC: SignDLC =>
                  signDLC.copy(accept = accept)
                case other => other
              }))
        ),
      cmd("signdlcfromfile")
        .action((_, conf) =>
          conf.copy(command = SignDLCFromFile(new File("").toPath, None)))
        .text("Signs a DLC")
        .children(
          arg[Path]("path")
            .required()
            .action((path, conf) =>
              conf.copy(command = conf.command match {
                case signDLC: SignDLCFromFile =>
                  signDLC.copy(path = path)
                case other => other
              })),
          arg[Path]("destination")
            .optional()
            .action((dest, conf) =>
              conf.copy(command = conf.command match {
                case accept: SignDLCFromFile =>
                  accept.copy(destination = Some(dest))
                case other => other
              }))
        ),
      cmd("adddlcsigs")
        .action((_, conf) => conf.copy(command = AddDLCSigs(null)))
        .text("Adds DLC Signatures into the database")
        .children(
          arg[LnMessage[DLCSignTLV]]("sigs")
            .required()
            .action((sigs, conf) =>
              conf.copy(command = conf.command match {
                case addDLCSigs: AddDLCSigs =>
                  addDLCSigs.copy(sigs = sigs)
                case other => other
              }))
        ),
      cmd("adddlcsigsfromfile")
        .action((_, conf) =>
          conf.copy(command = AddDLCSigsFromFile(new File("").toPath)))
        .text("Adds DLC Signatures into the database")
        .children(
          arg[Path]("path")
            .required()
            .action((path, conf) =>
              conf.copy(command = conf.command match {
                case addDLCSigs: AddDLCSigsFromFile =>
                  addDLCSigs.copy(path = path)
                case other => other
              }))
        ),
      cmd("adddlcsigsandbroadcast")
        .action((_, conf) => conf.copy(command = AddDLCSigsAndBroadcast(null)))
        .text("Adds DLC Signatures into the database and broadcasts the funding transaction")
        .children(
          arg[LnMessage[DLCSignTLV]]("sigs")
            .required()
            .action((sigs, conf) =>
              conf.copy(command = conf.command match {
                case addDLCSigs: AddDLCSigsAndBroadcast =>
                  addDLCSigs.copy(sigs = sigs)
                case other => other
              }))
        ),
      cmd("adddlcsigsandbroadcastfromfile")
        .action((_, conf) =>
          conf.copy(command =
            AddDLCSigsAndBroadcastFromFile(new File("").toPath)))
        .text("Adds DLC Signatures into the database and broadcasts the funding transaction")
        .children(
          arg[Path]("path")
            .required()
            .action((path, conf) =>
              conf.copy(command = conf.command match {
                case addDLCSigs: AddDLCSigsAndBroadcastFromFile =>
                  addDLCSigs.copy(path = path)
                case other => other
              }))
        ),
      cmd("getdlcfundingtx")
        .action((_, conf) => conf.copy(command = GetDLCFundingTx(null)))
        .text("Returns the Funding Tx corresponding to the DLC with the given contractId")
        .children(
          opt[ByteVector]("contractId")
            .required()
            .action((contractId, conf) =>
              conf.copy(command = conf.command match {
                case getDLCFundingTx: GetDLCFundingTx =>
                  getDLCFundingTx.copy(contractId = contractId)
                case other => other
              }))
        ),
      cmd("broadcastdlcfundingtx")
        .action((_, conf) => conf.copy(command = BroadcastDLCFundingTx(null)))
        .text("Broadcasts the funding Tx corresponding to the DLC with the given contractId")
        .children(
          arg[ByteVector]("contractId")
            .required()
            .action((contractId, conf) =>
              conf.copy(command = conf.command match {
                case broadcastDLCFundingTx: BroadcastDLCFundingTx =>
                  broadcastDLCFundingTx.copy(contractId = contractId)
                case other => other
              }))
        ),
      cmd("executedlc")
        .action((_, conf) =>
          conf.copy(command =
            ExecuteDLC(ByteVector.empty, Vector.empty, noBroadcast = false)))
        .text("Executes the DLC with the given contractId")
        .children(
          arg[ByteVector]("contractId")
            .required()
            .action((contractId, conf) =>
              conf.copy(command = conf.command match {
                case executeDLC: ExecuteDLC =>
                  executeDLC.copy(contractId = contractId)
                case other => other
              })),
          arg[Seq[OracleAttestmentTLV]]("oraclesigs")
            .required()
            .action((sigs, conf) =>
              conf.copy(command = conf.command match {
                case executeDLC: ExecuteDLC =>
                  executeDLC.copy(oracleSigs = sigs.toVector)
                case other => other
              })),
          opt[Unit]("noBroadcast")
            .optional()
            .action((_, conf) =>
              conf.copy(command = conf.command match {
                case executeDLC: ExecuteDLC =>
                  executeDLC.copy(noBroadcast = true)
                case other => other
              }))
        ),
      cmd("executedlcrefund")
        .action((_, conf) =>
          conf.copy(command = ExecuteDLCRefund(null, noBroadcast = false)))
        .text("Executes the Refund transaction for the given DLC")
        .children(
          arg[ByteVector]("contractId")
            .required()
            .action((contractId, conf) =>
              conf.copy(command = conf.command match {
                case executeDLCRefund: ExecuteDLCRefund =>
                  executeDLCRefund.copy(contractId = contractId)
                case other => other
              })),
          opt[Unit]("noBroadcast")
            .optional()
            .action((_, conf) =>
              conf.copy(command = conf.command match {
                case executeDLCRefund: ExecuteDLCRefund =>
                  executeDLCRefund.copy(noBroadcast = true)
                case other => other
              }))
        ),
      cmd("canceldlc")
        .action((_, conf) => conf.copy(command = CancelDLC(Sha256Digest.empty)))
        .text("Cancels a DLC and unreserves used utxos")
        .children(
          arg[Sha256Digest]("dlcId")
            .required()
            .action((dlcId, conf) =>
              conf.copy(command = conf.command match {
                case cancelDLC: CancelDLC =>
                  cancelDLC.copy(dlcId = dlcId)
                case other => other
              }))
        ),
      cmd("getdlcs")
        .action((_, conf) => conf.copy(command = GetDLCs))
        .text("Returns all dlcs in the wallet"),
      cmd("getdlc")
        .action((_, conf) => conf.copy(command = GetDLC(Sha256Digest.empty)))
        .text("Gets a specific dlc in the wallet")
        .children(arg[Sha256Digest]("dlcId")
          .required()
          .action((dlcId, conf) =>
            conf.copy(command = conf.command match {
              case _: GetDLC => GetDLC(dlcId)
              case other     => other
            }))),
      cmd("getbalance")
        .action((_, conf) => conf.copy(command = GetBalance(false)))
        .text("Get the wallet balance")
        .children(
          opt[Unit]("sats")
            .optional()
            .text("Display balance in satoshis")
            .action((_, conf) =>
              conf.copy(command = conf.command match {
                case getBalance: GetBalance =>
                  getBalance.copy(isSats = true)
                case other => other
              }))
        ),
      cmd("getconfirmedbalance")
        .action((_, conf) => conf.copy(command = GetConfirmedBalance(false)))
        .text("Get the wallet balance of confirmed utxos")
        .children(
          opt[Unit]("sats")
            .optional()
            .text("Display balance in satoshis")
            .action((_, conf) =>
              conf.copy(command = conf.command match {
                case getBalance: GetConfirmedBalance =>
                  getBalance.copy(isSats = true)
                case other => other
              }))
        ),
      cmd("getunconfirmedbalance")
        .action((_, conf) => conf.copy(command = GetUnconfirmedBalance(false)))
        .text("Get the wallet balance of unconfirmed utxos")
        .children(
          opt[Unit]("sats")
            .optional()
            .text("Display balance in satoshis")
            .action((_, conf) =>
              conf.copy(command = conf.command match {
                case getBalance: GetUnconfirmedBalance =>
                  getBalance.copy(isSats = true)
                case other => other
              }))
        ),
      cmd("getbalances")
        .action((_, conf) => conf.copy(command = GetBalances(false)))
        .text("Get the wallet balance by utxo state")
        .children(
          opt[Unit]("sats")
            .optional()
            .text("Display balance in satoshis")
            .action((_, conf) =>
              conf.copy(command = conf.command match {
                case getBalances: GetBalances =>
                  getBalances.copy(isSats = true)
                case other => other
              }))
        ),
      cmd("getutxos")
        .action((_, conf) => conf.copy(command = GetUtxos))
        .text("Returns list of all wallet utxos"),
      cmd("listreservedutxos")
        .action((_, conf) => conf.copy(command = ListReservedUtxos))
        .text("Returns list of all reserved wallet utxos"),
      cmd("getaddresses")
        .action((_, conf) => conf.copy(command = GetAddresses))
        .text("Returns list of all wallet addresses currently being watched"),
      cmd("getspentaddresses")
        .action((_, conf) => conf.copy(command = GetSpentAddresses))
        .text(
          "Returns list of all wallet addresses that have received funds and been spent"),
      cmd("getfundedaddresses")
        .action((_, conf) => conf.copy(command = GetFundedAddresses))
        .text("Returns list of all wallet addresses that are holding funds"),
      cmd("getunusedaddresses")
        .action((_, conf) => conf.copy(command = GetUnusedAddresses))
        .text("Returns list of all wallet addresses that have not been used"),
      cmd("getaccounts")
        .action((_, conf) => conf.copy(command = GetAccounts))
        .text("Returns list of all wallet accounts"),
      cmd("createnewaccount")
        .action((_, conf) => conf.copy(command = CreateNewAccount))
        .text("Creates a new wallet account"),
      cmd("getaddressinfo")
        .action((_, conf) => conf.copy(command = GetAddressInfo(null)))
        .text("Returns list of all wallet accounts")
        .children(
          arg[BitcoinAddress]("address")
            .text("Address to get information about")
            .required()
            .action((addr, conf) =>
              conf.copy(command = conf.command match {
                case getAddressInfo: GetAddressInfo =>
                  getAddressInfo.copy(address = addr)
                case other => other
              }))
        ),
      cmd("getnewaddress")
        .action((_, conf) => conf.copy(command = GetNewAddress(None)))
        .text("Get a new address")
        .children(
          opt[AddressLabelTag]("label")
            .text("The label name for the address to be linked to")
            .action((label, conf) =>
              conf.copy(command = conf.command match {
                case getNewAddress: GetNewAddress =>
                  getNewAddress.copy(labelOpt = Some(label))
                case other => other
              }))
        ),
      cmd("labeladdress")
        .action((_, conf) =>
          conf.copy(command = LabelAddress(null, AddressLabelTag(""))))
        .text("Add a label to the wallet address")
        .children(
          arg[BitcoinAddress]("address")
            .text("The address to label")
            .required()
            .action((addr, conf) =>
              conf.copy(command = conf.command match {
                case labelAddress: LabelAddress =>
                  labelAddress.copy(address = addr)
                case other => other
              })),
          arg[AddressLabelTag]("label")
            .text("The label name for the address to be linked to")
            .required()
            .action((label, conf) =>
              conf.copy(command = conf.command match {
                case labelAddress: LabelAddress =>
                  labelAddress.copy(label = label)
                case other => other
              }))
        ),
      cmd("getaddresstags")
        .action((_, conf) => conf.copy(command = GetAddressTags(null)))
        .text("Get all the tags associated with this address")
        .children(
          arg[BitcoinAddress]("address")
            .text("The address to get with the associated tags")
            .required()
            .action((addr, conf) =>
              conf.copy(command = conf.command match {
                case getAddressTags: GetAddressTags =>
                  getAddressTags.copy(address = addr)
                case other => other
              }))
        ),
      cmd("getaddresslabels")
        .action((_, conf) => conf.copy(command = GetAddressLabels(null)))
        .text("Get all the labels associated with this address")
        .children(
          arg[BitcoinAddress]("address")
            .text("The address to get with the associated labels")
            .required()
            .action((addr, conf) =>
              conf.copy(command = conf.command match {
                case getAddressLabels: GetAddressLabels =>
                  getAddressLabels.copy(address = addr)
                case other => other
              }))
        ),
      cmd("dropaddresslabels")
        .action((_, conf) => conf.copy(command = DropAddressLabels(null)))
        .text("Drop all the labels associated with this address")
        .children(
          arg[BitcoinAddress]("address")
            .text("The address to drop the associated labels of")
            .required()
            .action((addr, conf) =>
              conf.copy(command = conf.command match {
                case dropAddressLabels: DropAddressLabels =>
                  dropAddressLabels.copy(address = addr)
                case other => other
              }))
        ),
      cmd("sendtoaddress")
        .action(
          // TODO how to handle null here?
          (_, conf) =>
            conf.copy(command =
              SendToAddress(null, 0.bitcoin, None, noBroadcast = false)))
        .text("Send money to the given address")
        .children(
          arg[BitcoinAddress]("address")
            .text("Address to send to")
            .required()
            .action((addr, conf) =>
              conf.copy(command = conf.command match {
                case send: SendToAddress =>
                  send.copy(destination = addr)
                case other => other
              })),
          arg[Bitcoins]("amount")
            .text("amount to send in BTC")
            .required()
            .action((btc, conf) =>
              conf.copy(command = conf.command match {
                case send: SendToAddress =>
                  send.copy(amount = btc)
                case other => other
              })),
          opt[SatoshisPerVirtualByte]("feerate")
            .text("Fee rate in sats per virtual byte")
            .optional()
            .action((feeRate, conf) =>
              conf.copy(command = conf.command match {
                case send: SendToAddress =>
                  send.copy(satoshisPerVirtualByte = Some(feeRate))
                case other => other
              })),
          opt[Unit]("noBroadcast")
            .optional()
            .action((_, conf) =>
              conf.copy(command = conf.command match {
                case send: SendToAddress =>
                  send.copy(noBroadcast = true)
                case other => other
              }))
        ),
      cmd("sendfromoutpoints")
        .action((_, conf) =>
          conf.copy(
            command = SendFromOutPoints(Vector.empty, null, 0.bitcoin, None)))
        .text("Send money to the given address")
        .children(
          arg[Seq[TransactionOutPoint]]("outpoints")
            .text("Out Points to send from")
            .required()
            .action((outPoints, conf) =>
              conf.copy(command = conf.command match {
                case send: SendFromOutPoints =>
                  send.copy(outPoints = outPoints.toVector)
                case other => other
              })),
          arg[BitcoinAddress]("address")
            .text("Address to send to")
            .required()
            .action((addr, conf) =>
              conf.copy(command = conf.command match {
                case send: SendFromOutPoints =>
                  send.copy(destination = addr)
                case other => other
              })),
          arg[Bitcoins]("amount")
            .text("amount to send in BTC")
            .required()
            .action((btc, conf) =>
              conf.copy(command = conf.command match {
                case send: SendFromOutPoints =>
                  send.copy(amount = btc)
                case other => other
              })),
          opt[SatoshisPerVirtualByte]("feerate")
            .text("Fee rate in sats per virtual byte")
            .optional()
            .action((feeRate, conf) =>
              conf.copy(command = conf.command match {
                case send: SendFromOutPoints =>
                  send.copy(feeRateOpt = Some(feeRate))
                case other => other
              }))
        ),
      cmd("sendwithalgo")
        .action((_, conf) =>
          conf.copy(command = SendWithAlgo(null, 0.bitcoin, None, null)))
        .text(
          "Send money to the given address using a specific coin selection algo")
        .children(
          arg[BitcoinAddress]("address")
            .text("Address to send to")
            .required()
            .action((addr, conf) =>
              conf.copy(command = conf.command match {
                case send: SendWithAlgo =>
                  send.copy(destination = addr)
                case other => other
              })),
          arg[Bitcoins]("amount")
            .text("amount to send in BTC")
            .required()
            .action((btc, conf) =>
              conf.copy(command = conf.command match {
                case send: SendWithAlgo =>
                  send.copy(amount = btc)
                case other => other
              })),
          arg[CoinSelectionAlgo]("algo")
            .text("Coin selection algo")
            .optional()
            .action((algo, conf) =>
              conf.copy(command = conf.command match {
                case send: SendWithAlgo =>
                  send.copy(algo = algo)
                case other => other
              })),
          opt[SatoshisPerVirtualByte]("feerate")
            .text("Fee rate in sats per virtual byte")
            .optional()
            .action((feeRate, conf) =>
              conf.copy(command = conf.command match {
                case send: SendWithAlgo =>
                  send.copy(feeRateOpt = Some(feeRate))
                case other => other
              }))
        ),
      cmd("sweepwallet")
        .action((_, conf) => conf.copy(command = SweepWallet(null, None)))
        .text("Sends the entire wallet balance to the given address")
        .children(
          arg[BitcoinAddress]("address")
            .text("Address to send to")
            .required()
            .action((addr, conf) =>
              conf.copy(command = conf.command match {
                case sweep: SweepWallet =>
                  sweep.copy(destination = addr)
                case other => other
              })),
          opt[SatoshisPerVirtualByte]("feerate")
            .text("Fee rate in sats per virtual byte")
            .optional()
            .action((feeRate, conf) =>
              conf.copy(command = conf.command match {
                case sweep: SweepWallet =>
                  sweep.copy(feeRateOpt = Some(feeRate))
                case other => other
              }))
        ),
      cmd("signpsbt")
        .action((_, conf) => conf.copy(command = SignPSBT(PSBT.empty)))
        .text("Signs the PSBT's inputs with keys that are associated with the wallet")
        .children(
          arg[PSBT]("psbt")
            .text("PSBT to sign")
            .required()
            .action((psbt, conf) =>
              conf.copy(command = conf.command match {
                case signPSBT: SignPSBT =>
                  signPSBT.copy(psbt = psbt)
                case other => other
              }))
        ),
      cmd("opreturncommit")
        .action((_, conf) =>
          conf.copy(command = OpReturnCommit("", hashMessage = false, None)))
        .text("Creates OP_RETURN commitment transaction")
        .children(
          arg[String]("message")
            .text("message to put into OP_RETURN commitment")
            .required()
            .action((message, conf) =>
              conf.copy(command = conf.command match {
                case opReturnCommit: OpReturnCommit =>
                  opReturnCommit.copy(message = message)
                case other => other
              })),
          opt[Unit]("hashMessage")
            .text("should the message be hashed before commitment")
            .optional()
            .action((_, conf) =>
              conf.copy(command = conf.command match {
                case opReturnCommit: OpReturnCommit =>
                  opReturnCommit.copy(hashMessage = true)
                case other => other
              })),
          opt[SatoshisPerVirtualByte]("feerate")
            .text("Fee rate in sats per virtual byte")
            .optional()
            .action((feeRate, conf) =>
              conf.copy(command = conf.command match {
                case opReturnCommit: OpReturnCommit =>
                  opReturnCommit.copy(feeRateOpt = Some(feeRate))
                case other => other
              }))
        ),
      cmd("bumpfeecpfp")
        .action((_, conf) =>
          conf.copy(command = BumpFeeCPFP(DoubleSha256DigestBE.empty,
                                          SatoshisPerVirtualByte.zero)))
        .text("Bump the fee of the given transaction id with a child tx using the given fee rate")
        .children(
          arg[DoubleSha256DigestBE]("txid")
            .text("Id of transaction to bump fee")
            .required()
            .action((txid, conf) =>
              conf.copy(command = conf.command match {
                case cpfp: BumpFeeCPFP =>
                  cpfp.copy(txId = txid)
                case other => other
              })),
          arg[SatoshisPerVirtualByte]("feerate")
            .text("Fee rate in sats per virtual byte of the child transaction")
            .required()
            .action((feeRate, conf) =>
              conf.copy(command = conf.command match {
                case cpfp: BumpFeeCPFP =>
                  cpfp.copy(feeRate = feeRate)
                case other => other
              }))
        ),
      cmd("bumpfeerbf")
        .action((_, conf) =>
          conf.copy(command = BumpFeeRBF(DoubleSha256DigestBE.empty,
                                         SatoshisPerVirtualByte.zero)))
        .text("Replace given transaction with one with the new fee rate")
        .children(
          arg[DoubleSha256DigestBE]("txid")
            .text("Id of transaction to bump fee")
            .required()
            .action((txid, conf) =>
              conf.copy(command = conf.command match {
                case rbf: BumpFeeRBF =>
                  rbf.copy(txId = txid)
                case other => other
              })),
          arg[SatoshisPerVirtualByte]("feerate")
            .text("New fee rate in sats per virtual byte")
            .required()
            .action((feeRate, conf) =>
              conf.copy(command = conf.command match {
                case rbf: BumpFeeRBF =>
                  rbf.copy(feeRate = feeRate)
                case other => other
              }))
        ),
      cmd("gettransaction")
        .action((_, conf) =>
          conf.copy(command = GetTransaction(DoubleSha256DigestBE.empty)))
        .text("Get detailed information about in-wallet transaction <txid>")
        .children(
          arg[DoubleSha256DigestBE]("txid")
            .text("The transaction id")
            .required()
            .action((txid, conf) =>
              conf.copy(command = conf.command match {
                case getTx: GetTransaction =>
                  getTx.copy(txId = txid)
                case other => other
              }))
        ),
      cmd("lockunspent")
        .action((_, conf) =>
          conf.copy(command = LockUnspent(unlock = false, Vector.empty)))
        .text("Temporarily lock (unlock=false) or unlock (unlock=true) specified transaction outputs." +
          "\nIf no transaction outputs are specified when unlocking then all current locked transaction outputs are unlocked.")
        .children(
          arg[Boolean]("unlock")
            .text("Whether to unlock (true) or lock (false) the specified transactions")
            .required()
            .action((unlock, conf) =>
              conf.copy(command = conf.command match {
                case lockUnspent: LockUnspent =>
                  lockUnspent.copy(unlock = unlock)
                case other => other
              })),
          arg[Vector[LockUnspentOutputParameter]]("transactions")
            .text("The transaction outpoints to unlock/lock, empty to apply to all utxos")
            .optional()
            .action((outPoints, conf) =>
              conf.copy(command = conf.command match {
                case lockUnspent: LockUnspent =>
                  lockUnspent.copy(outPoints = outPoints)
                case other => other
              }))
        ),
      cmd("importseed")
        .action((_, conf) => conf.copy(command = ImportSeed("", null, None)))
        .text("Imports a mnemonic seed as a new seed file")
        .children(
          arg[String]("walletname")
            .text("Name to associate with this seed")
            .required()
            .action((walletName, conf) =>
              conf.copy(command = conf.command match {
                case is: ImportSeed =>
                  is.copy(walletName = walletName)
                case other => other
              })),
          arg[MnemonicCode]("words")
            .text("Mnemonic seed words, space separated")
            .required()
            .action((mnemonic, conf) =>
              conf.copy(command = conf.command match {
                case is: ImportSeed =>
                  is.copy(mnemonic = mnemonic)
                case other => other
              })),
          arg[AesPassword]("passphrase")
            .text("Passphrase to encrypt the seed with")
            .action((password, conf) =>
              conf.copy(command = conf.command match {
                case is: ImportSeed =>
                  is.copy(passwordOpt = Some(password))
                case other => other
              }))
        ),
      cmd("importxprv")
        .action((_, conf) => conf.copy(command = ImportXprv("", null, None)))
        .text("Imports a xprv as a new seed file")
        .children(
          arg[String]("walletname")
            .text("What name to associate with this seed")
            .required()
            .action((walletName, conf) =>
              conf.copy(command = conf.command match {
                case ix: ImportXprv =>
                  ix.copy(walletName = walletName)
                case other => other
              })),
          arg[ExtPrivateKey]("xprv")
            .text("base58 encoded extended private key")
            .required()
            .action((xprv, conf) =>
              conf.copy(command = conf.command match {
                case ix: ImportXprv =>
                  ix.copy(xprv = xprv)
                case other => other
              })),
          arg[AesPassword]("passphrase")
            .text("Passphrase to encrypt this seed with")
            .action((password, conf) =>
              conf.copy(command = conf.command match {
                case ix: ImportXprv =>
                  ix.copy(passwordOpt = Some(password))
                case other => other
              }))
        ),
      cmd("keymanagerpassphrasechange")
        .action((_, conf) =>
          conf.copy(command = KeyManagerPassphraseChange(null, null)))
        .text("Changes the wallet passphrase")
        .children(
          arg[AesPassword]("oldpassphrase")
            .text("The current passphrase")
            .required()
            .action((oldPass, conf) =>
              conf.copy(command = conf.command match {
                case wpc: KeyManagerPassphraseChange =>
                  wpc.copy(oldPassword = oldPass)
                case other => other
              })),
          arg[AesPassword]("newpassphrase")
            .text("The new passphrase")
            .required()
            .action((newPass, conf) =>
              conf.copy(command = conf.command match {
                case wpc: KeyManagerPassphraseChange =>
                  wpc.copy(newPassword = newPass)
                case other => other
              }))
        ),
      cmd("keymanagerpassphraseset")
        .action((_, conf) => conf.copy(command = KeyManagerPassphraseSet(null)))
        .text("Encrypts the wallet with the given passphrase")
        .children(
          arg[AesPassword]("passphrase")
            .text("The passphrase to encrypt the wallet with")
            .required()
            .action((pass, conf) =>
              conf.copy(command = conf.command match {
                case wps: KeyManagerPassphraseSet =>
                  wps.copy(password = pass)
                case other => other
              }))
        ),
      note(sys.props("line.separator") + "=== Network ==="),
      cmd("getpeers")
        .action((_, conf) => conf.copy(command = GetPeers))
        .text(s"List the connected peers"),
      cmd("stop")
        .action((_, conf) => conf.copy(command = Stop))
        .text("Request a graceful shutdown of Bitcoin-S"),
      cmd("sendrawtransaction")
        .action((_, conf) =>
          conf.copy(command = SendRawTransaction(EmptyTransaction)))
        .text("Broadcasts the raw transaction")
        .children(
          arg[Transaction]("tx")
            .text("Transaction serialized in hex")
            .required()
            .action((tx, conf) =>
              conf.copy(command = conf.command match {
                case sendRawTransaction: SendRawTransaction =>
                  sendRawTransaction.copy(tx = tx)
                case other => other
              }))
        ),
      note(sys.props("line.separator") + "=== PSBT ==="),
      cmd("decodepsbt")
        .action((_, conf) => conf.copy(command = DecodePSBT(PSBT.empty)))
        .text("Return a JSON object representing the serialized, base64-encoded partially signed Bitcoin transaction.")
        .children(
          arg[PSBT]("psbt")
            .text("PSBT serialized in hex or base64 format")
            .required()
            .action((psbt, conf) =>
              conf.copy(command = conf.command match {
                case decode: DecodePSBT =>
                  decode.copy(psbt = psbt)
                case other => other
              }))),
      cmd("analyzepsbt")
        .action((_, conf) => conf.copy(command = AnalyzePSBT(PSBT.empty)))
        .text("Analyzes and provides information about the current status of a PSBT and its inputs")
        .children(
          arg[PSBT]("psbt")
            .text("PSBT serialized in hex or base64 format")
            .required()
            .action((psbt, conf) =>
              conf.copy(command = conf.command match {
                case analyzePSBT: AnalyzePSBT =>
                  analyzePSBT.copy(psbt = psbt)
                case other => other
              }))
        ),
      cmd("combinepsbts")
        .action((_, conf) => conf.copy(command = CombinePSBTs(Seq.empty)))
        .text("Combines all the given PSBTs")
        .children(
          arg[Seq[PSBT]]("psbts")
            .text("PSBTs serialized in hex or base64 format")
            .required()
            .action((seq, conf) =>
              conf.copy(command = conf.command match {
                case combinePSBTs: CombinePSBTs =>
                  combinePSBTs.copy(psbts = seq)
                case other => other
              }))
        ),
      cmd("joinpsbts")
        .action((_, conf) => conf.copy(command = JoinPSBTs(Seq.empty)))
        .text("Combines all the given PSBTs")
        .children(
          arg[Seq[PSBT]]("psbts")
            .text("PSBTs serialized in hex or base64 format")
            .required()
            .action((seq, conf) =>
              conf.copy(command = conf.command match {
                case joinPSBTs: JoinPSBTs =>
                  joinPSBTs.copy(psbts = seq)
                case other => other
              }))
        ),
      cmd("finalizepsbt")
        .action((_, conf) => conf.copy(command = FinalizePSBT(PSBT.empty)))
        .text("Finalizes the given PSBT if it can")
        .children(
          arg[PSBT]("psbt")
            .text("PSBT serialized in hex or base64 format")
            .required()
            .action((psbt, conf) =>
              conf.copy(command = conf.command match {
                case finalizePSBT: FinalizePSBT =>
                  finalizePSBT.copy(psbt = psbt)
                case other => other
              }))
        ),
      cmd("extractfrompsbt")
        .action((_, conf) => conf.copy(command = ExtractFromPSBT(PSBT.empty)))
        .text("Extracts a transaction from the given PSBT if it can")
        .children(
          arg[PSBT]("psbt")
            .text("PSBT serialized in hex or base64 format")
            .required()
            .action((psbt, conf) =>
              conf.copy(command = conf.command match {
                case extractFromPSBT: ExtractFromPSBT =>
                  extractFromPSBT.copy(psbt = psbt)
                case other => other
              }))
        ),
      cmd("converttopsbt")
        .action((_, conf) =>
          conf.copy(command = ConvertToPSBT(EmptyTransaction)))
        .text("Creates an empty psbt from the given transaction")
        .children(
          arg[Transaction]("unsignedTx")
            .text("serialized unsigned transaction in hex")
            .required()
            .action((tx, conf) =>
              conf.copy(command = conf.command match {
                case convertToPSBT: ConvertToPSBT =>
                  convertToPSBT.copy(transaction = tx)
                case other => other
              }))
        ),
      note(sys.props("line.separator") + "=== Oracle ==="),
      cmd("getpublickey")
        .action((_, conf) => conf.copy(command = GetPublicKey))
        .text(s"Get oracle's public key"),
      cmd("getstakingaddress")
        .action((_, conf) => conf.copy(command = GetStakingAddress))
        .text(s"Get oracle's staking address"),
      cmd("listevents")
        .action((_, conf) => conf.copy(command = ListEvents))
        .text(s"Lists all event names"),
      cmd("createenumevent")
        .action((_, conf) =>
          conf.copy(command = CreateEnumEvent("", new Date(), Seq.empty)))
        .text("Registers an oracle enum event")
        .children(
          arg[String]("label")
            .text("Label for this event")
            .required()
            .action((label, conf) =>
              conf.copy(command = conf.command match {
                case createEvent: CreateEnumEvent =>
                  createEvent.copy(label = label)
                case other => other
              })),
          arg[Date]("maturationtime")
            .text("The earliest expected time an outcome will be signed, given in ISO 8601 format")
            .required()
            .action((date, conf) =>
              conf.copy(command = conf.command match {
                case createEvent: CreateEnumEvent =>
                  createEvent.copy(maturationTime = date)
                case other => other
              })),
          arg[Seq[String]]("outcomes")
            .text("Possible outcomes for this event")
            .required()
            .action((outcomes, conf) =>
              conf.copy(command = conf.command match {
                case createEvent: CreateEnumEvent =>
                  createEvent.copy(outcomes = outcomes)
                case other => other
              }))
        ),
      cmd("createnumericevent")
        .action((_, conf) =>
          conf.copy(command = CreateNumericEvent(eventName = "",
                                                 maturationTime = new Date(),
                                                 minValue = 0,
                                                 maxValue = 0,
                                                 unit = "",
                                                 precision = 0)))
        .text("Registers an oracle event that uses digit decomposition when signing the number")
        .children(
          arg[String]("name")
            .text("Name for this event")
            .required()
            .action((name, conf) =>
              conf.copy(command = conf.command match {
                case createNumericEvent: CreateNumericEvent =>
                  createNumericEvent.copy(eventName = name)
                case other => other
              })),
          arg[Date]("maturationtime")
            .text("The earliest expected time an outcome will be signed, given in ISO 8601 format")
            .required()
            .action((date, conf) =>
              conf.copy(command = conf.command match {
                case createNumericEvent: CreateNumericEvent =>
                  createNumericEvent.copy(maturationTime = date)
                case other => other
              })),
          arg[Long]("minvalue")
            .text("Minimum value of this event")
            .required()
            .action((min, conf) =>
              conf.copy(command = conf.command match {
                case createNumericEvent: CreateNumericEvent =>
                  createNumericEvent.copy(minValue = min)
                case other => other
              })),
          arg[Long]("maxvalue")
            .text("Maximum value of this event")
            .required()
            .action((max, conf) =>
              conf.copy(command = conf.command match {
                case createNumericEvent: CreateNumericEvent =>
                  createNumericEvent.copy(maxValue = max)
                case other => other
              })),
          arg[String]("unit")
            .text("The unit denomination of the outcome value")
            .action((unit, conf) =>
              conf.copy(command = conf.command match {
                case createNumericEvent: CreateNumericEvent =>
                  createNumericEvent.copy(unit = unit)
                case other => other
              })),
          arg[Int]("precision")
            .text("The precision of the outcome representing the " +
              "base exponent by which to multiply the number represented by " +
              "the composition of the digits to obtain the actual outcome value.")
            .action((precision, conf) =>
              conf.copy(command = conf.command match {
                case createNumericEvent: CreateNumericEvent =>
                  createNumericEvent.copy(precision = precision)
                case other => other
              }))
        ),
      cmd("createdigitdecompevent")
        .action((_, conf) =>
          conf.copy(command = CreateDigitDecompEvent("",
                                                     Instant.MIN,
                                                     0,
                                                     isSigned = false,
                                                     0,
                                                     "",
                                                     0)))
        .text("Registers an oracle event that uses digit decomposition when signing the number")
        .children(
          arg[String]("name")
            .text("Name for this event")
            .required()
            .action((name, conf) =>
              conf.copy(command = conf.command match {
                case createLargeRangedEvent: CreateDigitDecompEvent =>
                  createLargeRangedEvent.copy(eventName = name)
                case other => other
              })),
          arg[Instant]("maturationtime")
            .text("The earliest expected time an outcome will be signed, given in epoch second")
            .required()
            .action((time, conf) =>
              conf.copy(command = conf.command match {
                case createLargeRangedEvent: CreateDigitDecompEvent =>
                  createLargeRangedEvent.copy(maturationTime = time)
                case other => other
              })),
          arg[Int]("base")
            .text("The base in which the outcome value is decomposed")
            .required()
            .action((base, conf) =>
              conf.copy(command = conf.command match {
                case createLargeRangedEvent: CreateDigitDecompEvent =>
                  createLargeRangedEvent.copy(base = base)
                case other => other
              })),
          arg[Int]("numdigits")
            .text("The max number of digits the outcome can have")
            .action((num, conf) =>
              conf.copy(command = conf.command match {
                case createLargeRangedEvent: CreateDigitDecompEvent =>
                  createLargeRangedEvent.copy(numDigits = num)
                case other => other
              })),
          opt[Unit]("signed")
            .text("Whether the outcomes can be negative")
            .action((_, conf) =>
              conf.copy(command = conf.command match {
                case createLargeRangedEvent: CreateDigitDecompEvent =>
                  createLargeRangedEvent.copy(isSigned = true)
                case other => other
              })),
          arg[String]("unit")
            .text("The unit denomination of the outcome value")
            .action((unit, conf) =>
              conf.copy(command = conf.command match {
                case createRangedEvent: CreateDigitDecompEvent =>
                  createRangedEvent.copy(unit = unit)
                case other => other
              })),
          arg[Int]("precision")
            .text("The precision of the outcome representing the " +
              "base exponent by which to multiply the number represented by " +
              "the composition of the digits to obtain the actual outcome value.")
            .action((precision, conf) =>
              conf.copy(command = conf.command match {
                case createLargeRangedEvent: CreateDigitDecompEvent =>
                  createLargeRangedEvent.copy(precision = precision)
                case other => other
              }))
        ),
      cmd("getevent")
        .action((_, conf) => conf.copy(command = GetEvent("")))
        .text("Get an event's details")
        .children(
          arg[String]("eventName")
            .text("The event's name")
            .required()
            .action((eventName, conf) =>
              conf.copy(command = conf.command match {
                case getEvent: GetEvent =>
                  getEvent.copy(eventName = eventName)
                case other => other
              }))
        ),
      cmd("signevent")
        .action((_, conf) => conf.copy(command = SignEvent("", "")))
        .text("Signs an event")
        .children(
          arg[String]("eventName")
            .text("The event's name")
            .required()
            .action((eventName, conf) =>
              conf.copy(command = conf.command match {
                case signEvent: SignEvent =>
                  signEvent.copy(eventName = eventName)
                case other => other
              })),
          arg[String]("outcome")
            .text("Outcome to sign for this event")
            .required()
            .action((outcome, conf) =>
              conf.copy(command = conf.command match {
                case signEvent: SignEvent =>
                  signEvent.copy(outcome = outcome)
                case other => other
              }))
        ),
      cmd("signdigits")
        .action((_, conf) => conf.copy(command = SignDigits("", 0)))
        .text("Signs a large range event")
        .children(
          arg[String]("eventName")
            .text("The event's name")
            .required()
            .action((eventName, conf) =>
              conf.copy(command = conf.command match {
                case signDigits: SignDigits =>
                  signDigits.copy(eventName = eventName)
                case other => other
              })),
          arg[Long]("outcome")
            .text("The number to sign")
            .required()
            .action((num, conf) =>
              conf.copy(command = conf.command match {
                case signDigits: SignDigits =>
                  signDigits.copy(num = num)
                case other => other
              }))
        ),
      cmd("getsignatures")
        .action((_, conf) => conf.copy(command = GetSignatures("")))
        .text("Get the signatures from a signed event")
        .children(
          arg[String]("eventName")
            .text("The event's name")
            .required()
            .action((eventName, conf) =>
              conf.copy(command = conf.command match {
                case getSignature: GetSignatures =>
                  getSignature.copy(eventName = eventName)
                case other => other
              }))
        ),
      cmd("signmessage")
        .action((_, conf) => conf.copy(command = SignMessage("")))
        .text("Signs the SHA256 hash of the given string using the oracle's signing key")
        .children(
          arg[String]("message")
            .text("Message to hash and sign")
            .required()
            .action((msg, conf) =>
              conf.copy(command = conf.command match {
                case signMessage: SignMessage =>
                  signMessage.copy(message = msg)
                case other => other
              }))
        ),
      note(sys.props("line.separator") + "=== Util ==="),
      cmd("createmultisig")
        .action((_, conf) =>
          conf.copy(command = CreateMultisig(0, Vector.empty, SegWit)))
        .text("Creates a multi-signature address with n signature of m keys required.")
        .children(
          arg[Int]("nrequired")
            .text("The number of required signatures out of the n keys.")
            .required()
            .action((nRequired, conf) =>
              conf.copy(command = conf.command match {
                case createMultisig: CreateMultisig =>
                  createMultisig.copy(requiredKeys = nRequired)
                case other => other
              })),
          arg[Seq[ECPublicKey]]("keys")
            .text("The hex-encoded public keys.")
            .required()
            .action((keys, conf) =>
              conf.copy(command = conf.command match {
                case createMultisig: CreateMultisig =>
                  createMultisig.copy(keys = keys.toVector)
                case other => other
              })),
          arg[AddressType]("address_type")
            .text("The address type to use. Options are \"legacy\", \"p2sh-segwit\", and \"bech32\"")
            .optional()
            .action((addrType, conf) =>
              conf.copy(command = conf.command match {
                case createMultisig: CreateMultisig =>
                  createMultisig.copy(addressType = addrType)
                case other => other
              }))
        ),
      cmd("estimatefee")
        .action((_, conf) => conf.copy(command = EstimateFee))
        .text("Returns the recommended fee rate using the fee provider"),
      cmd("zipdatadir")
        .action((_, conf) =>
          conf.copy(command = ZipDataDir(new File("").toPath)))
        .text(
          "zips the bitcoin-s datadir and places the file at the given path")
        .children(
          arg[Path]("path")
            .text("Location of final zip file")
            .required()
            .action((path, conf) =>
              conf.copy(command = conf.command match {
                case zipDataDir: ZipDataDir =>
                  zipDataDir.copy(path = path)
                case other => other
              }))),
      checkConfig {
        case Config(NoCommand, _, _, _) =>
          failure("You need to provide a command!")
        case _ => success
      }
    )
  }

  def exec(args: String*): Try[String] = {
    val config = OParser.parse(parser, args.toVector, Config()) match {
      case None       => sys.exit(1)
      case Some(conf) => conf
    }

    exec(config.command, config)
  }

  def exec(command: CliCommand, config: Config): Try[String] = {
    import System.err.{println => printerr}

    /** Prints the given message to stderr if debug is set */
    def debug(message: Any): Unit = {
      if (config.debug) {
        printerr(s"DEBUG: $message")
      }
    }

    /** Prints the given message to stderr and exist */
    def error[T](message: String): Failure[T] = {
      Failure(new RuntimeException(message))
    }

    val requestParam: RequestParam = command match {
      case GetInfo =>
        RequestParam("getinfo")
      case GetUtxos =>
        RequestParam("getutxos")
      case ListReservedUtxos =>
        RequestParam("listreservedutxos")
      case GetAddresses =>
        RequestParam("getaddresses")
      case GetSpentAddresses =>
        RequestParam("getspentaddresses")
      case GetFundedAddresses =>
        RequestParam("getfundedaddresses")
      case GetUnusedAddresses =>
        RequestParam("getunusedaddresses")
      case GetAccounts =>
        RequestParam("getaccounts")
      case CreateNewAccount =>
        RequestParam("createnewaccount")
      case IsEmpty =>
        RequestParam("isempty")
      case WalletInfo =>
        RequestParam("walletinfo")
      // DLCs
      case GetDLCHostAddress => RequestParam("getdlchostaddress")
      case GetDLCs           => RequestParam("getdlcs")
      case GetDLC(dlcId) =>
        RequestParam("getdlc", Seq(up.writeJs(dlcId)))
      case CreateDLCOffer(contractInfo,
                          collateral,
                          feeRateOpt,
                          locktime,
                          refundLT) =>
        RequestParam(
          "createdlcoffer",
          Seq(
            up.writeJs(contractInfo),
            up.writeJs(collateral),
            up.writeJs(feeRateOpt),
            up.writeJs(locktime),
            up.writeJs(refundLT)
          )
        )
      case AcceptDLC(offer, address) =>
        RequestParam("acceptdlc", Seq(up.writeJs(offer), up.writeJs(address)))
      case AcceptDLCOffer(offer) =>
        RequestParam("acceptdlcoffer", Seq(up.writeJs(offer)))
      case AcceptDLCOfferFromFile(path, dest) =>
        RequestParam("acceptdlcofferfromfile",
                     Seq(up.writeJs(path), up.writeJs(dest)))
      case SignDLC(accept) =>
        RequestParam("signdlc", Seq(up.writeJs(accept)))
      case SignDLCFromFile(path, dest) =>
        RequestParam("signdlcfromfile", Seq(up.writeJs(path), up.writeJs(dest)))
      case AddDLCSigs(sigs) =>
        RequestParam("adddlcsigs", Seq(up.writeJs(sigs)))
      case AddDLCSigsFromFile(path) =>
        RequestParam("adddlcsigsfromfile", Seq(up.writeJs(path)))
      case AddDLCSigsAndBroadcast(sigs) =>
        RequestParam("adddlcsigsandbroadcast", Seq(up.writeJs(sigs)))
      case AddDLCSigsAndBroadcastFromFile(path) =>
        RequestParam("adddlcsigsandbroadcastfromfile", Seq(up.writeJs(path)))
      case ExecuteDLC(contractId, oracleSigs, noBroadcast) =>
        RequestParam("executedlc",
                     Seq(up.writeJs(contractId),
                         up.writeJs(oracleSigs),
                         up.writeJs(noBroadcast)))
      case GetDLCFundingTx(contractId) =>
        RequestParam("getdlcfundingtx", Seq(up.writeJs(contractId)))
      case BroadcastDLCFundingTx(contractId) =>
        RequestParam("broadcastdlcfundingtx", Seq(up.writeJs(contractId)))
      case ExecuteDLCRefund(contractId, noBroadcast) =>
        RequestParam("executedlcrefund",
                     Seq(up.writeJs(contractId), up.writeJs(noBroadcast)))
      case CancelDLC(dlcId) =>
        RequestParam("canceldlc", Seq(up.writeJs(dlcId)))
      // Wallet
      case GetBalance(isSats) =>
        RequestParam("getbalance", Seq(up.writeJs(isSats)))
      case GetBalances(isSats) =>
        RequestParam("getbalances", Seq(up.writeJs(isSats)))
      case GetConfirmedBalance(isSats) =>
        RequestParam("getconfirmedbalance", Seq(up.writeJs(isSats)))
      case GetUnconfirmedBalance(isSats) =>
        RequestParam("getunconfirmedbalance", Seq(up.writeJs(isSats)))
      case GetAddressInfo(address) =>
        RequestParam("getaddressinfo", Seq(up.writeJs(address)))
      case GetNewAddress(labelOpt) =>
        RequestParam("getnewaddress", Seq(up.writeJs(labelOpt)))
      case LockUnspent(unlock, outPoints) =>
        RequestParam("lockunspent",
                     Seq(up.writeJs(unlock), up.writeJs(outPoints)))
      case LabelAddress(address, label) =>
        RequestParam("labeladdress",
                     Seq(up.writeJs(address), up.writeJs(label)))
      case GetAddressTags(address) =>
        RequestParam("getaddresstags", Seq(up.writeJs(address)))
      case GetAddressLabels(address) =>
        RequestParam("getaddresslabels", Seq(up.writeJs(address)))
      case DropAddressLabels(address) =>
        RequestParam("dropaddresslabels", Seq(up.writeJs(address)))
      case Rescan(addressBatchSize,
                  startBlock,
                  endBlock,
                  force,
                  ignoreCreationTime) =>
        RequestParam("rescan",
                     Seq(up.writeJs(addressBatchSize),
                         up.writeJs(startBlock),
                         up.writeJs(endBlock),
                         up.writeJs(force),
                         up.writeJs(ignoreCreationTime)))

      case GetTransaction(txId) =>
        RequestParam("gettransaction", Seq(up.writeJs(txId)))

      case SendToAddress(address,
                         bitcoins,
                         satoshisPerVirtualByte,
                         noBroadcast) =>
        RequestParam("sendtoaddress",
                     Seq(up.writeJs(address),
                         up.writeJs(bitcoins),
                         up.writeJs(satoshisPerVirtualByte),
                         up.writeJs(noBroadcast)))
      case SendFromOutPoints(outPoints, address, bitcoins, feeRateOpt) =>
        RequestParam("sendfromoutpoints",
                     Seq(up.writeJs(outPoints),
                         up.writeJs(address),
                         up.writeJs(bitcoins),
                         up.writeJs(feeRateOpt)))
      case SweepWallet(address, feeRateOpt) =>
        RequestParam("sweepwallet",
                     Seq(up.writeJs(address), up.writeJs(feeRateOpt)))
      case SendWithAlgo(address, bitcoins, feeRateOpt, algo) =>
        RequestParam("sendwithalgo",
                     Seq(up.writeJs(address),
                         up.writeJs(bitcoins),
                         up.writeJs(feeRateOpt),
                         up.writeJs(algo)))
      case BumpFeeCPFP(txId, feeRate) =>
        RequestParam("bumpfeecpfp", Seq(up.writeJs(txId), up.writeJs(feeRate)))
      case BumpFeeRBF(txId, feeRate) =>
        RequestParam("bumpfeerbf", Seq(up.writeJs(txId), up.writeJs(feeRate)))
      case OpReturnCommit(message, hashMessage, satoshisPerVirtualByte) =>
        RequestParam("opreturncommit",
                     Seq(up.writeJs(message),
                         up.writeJs(hashMessage),
                         up.writeJs(satoshisPerVirtualByte)))
      case SignPSBT(psbt) =>
        RequestParam("signpsbt", Seq(up.writeJs(psbt)))

      case KeyManagerPassphraseChange(oldPassword, newPassword) =>
        RequestParam("keymanagerpassphrasechange",
                     Seq(up.writeJs(oldPassword), up.writeJs(newPassword)))

      case KeyManagerPassphraseSet(password) =>
        RequestParam("keymanagerpassphraseset", Seq(up.writeJs(password)))

      case ImportSeed(walletName, mnemonic, passwordOpt) =>
        RequestParam("importseed",
                     Seq(up.writeJs(walletName),
                         up.writeJs(mnemonic),
                         up.writeJs(passwordOpt)))

      case ImportXprv(walletName, xprv, passwordOpt) =>
        RequestParam("importxprv",
                     Seq(up.writeJs(walletName),
                         up.writeJs(xprv),
                         up.writeJs(passwordOpt)))

      case GetBlockHeader(hash) =>
        RequestParam("getblockheader", Seq(up.writeJs(hash)))
      // height
      case GetBlockCount => RequestParam("getblockcount")
      // filter count
      case GetFilterCount => RequestParam("getfiltercount")
      // filter header count
      case GetFilterHeaderCount => RequestParam("getfilterheadercount")
      // besthash
      case GetBestBlockHash => RequestParam("getbestblockhash")
      // peers
      case GetPeers => RequestParam("getpeers")
      case Stop     => RequestParam("stop")
      case SendRawTransaction(tx) =>
        RequestParam("sendrawtransaction", Seq(up.writeJs(tx)))
      // PSBTs
      case DecodePSBT(psbt) =>
        RequestParam("decodepsbt", Seq(up.writeJs(psbt)))
      case CombinePSBTs(psbts) =>
        RequestParam("combinepsbts", Seq(up.writeJs(psbts)))
      case JoinPSBTs(psbts) =>
        RequestParam("joinpsbts", Seq(up.writeJs(psbts)))
      case FinalizePSBT(psbt) =>
        RequestParam("finalizepsbt", Seq(up.writeJs(psbt)))
      case ExtractFromPSBT(psbt) =>
        RequestParam("extractfrompsbt", Seq(up.writeJs(psbt)))
      case ConvertToPSBT(tx) =>
        RequestParam("converttopsbt", Seq(up.writeJs(tx)))

      case DecodeRawTransaction(tx) =>
        RequestParam("decoderawtransaction", Seq(up.writeJs(tx)))

      case AnalyzePSBT(psbt) =>
        RequestParam("analyzepsbt", Seq(up.writeJs(psbt)))

      // Oracle
      case GetPublicKey =>
        RequestParam("getpublickey")
      case GetStakingAddress =>
        RequestParam("getstakingaddress")
      case ListEvents =>
        RequestParam("listevents")
      case GetEvent(eventName) =>
        RequestParam("getevent", Seq(up.writeJs(eventName)))
      case CreateEnumEvent(label, time, outcomes) =>
        RequestParam(
          "createenumevent",
          Seq(up.writeJs(label), up.writeJs(time), up.writeJs(outcomes)))
      case CreateNumericEvent(eventName,
                              time,
                              minValue,
                              maxValue,
                              unit,
                              precision) =>
        RequestParam(
          "createnumericevent",
          Seq(up.writeJs(eventName),
              up.writeJs(time),
              up.writeJs(minValue),
              up.writeJs(maxValue),
              up.writeJs(unit),
              up.writeJs(precision))
        )
      case CreateDigitDecompEvent(eventName,
                                  time,
                                  base,
                                  isSigned,
                                  numDigits,
                                  unit,
                                  precision) =>
        RequestParam(
          "createdigitdecompevent",
          Seq(up.writeJs(eventName),
              up.writeJs(time),
              up.writeJs(base),
              up.writeJs(isSigned),
              up.writeJs(numDigits),
              up.writeJs(unit),
              up.writeJs(precision))
        )
      case SignEvent(eventName, outcome) =>
        RequestParam("signevent",
                     Seq(up.writeJs(eventName), up.writeJs(outcome)))
      case SignDigits(eventName, num) =>
        RequestParam("signdigits", Seq(up.writeJs(eventName), up.writeJs(num)))
      case GetSignatures(eventName) =>
        RequestParam("getsignatures", Seq(up.writeJs(eventName)))

      case SignMessage(message) =>
        RequestParam("signmessage", Seq(up.writeJs(message)))

      case CreateMultisig(requiredKeys, keys, addressType) =>
        RequestParam("createmultisig",
                     Seq(up.writeJs(requiredKeys),
                         up.writeJs(keys),
                         up.writeJs(addressType)))
      case EstimateFee => RequestParam("estimatefee")
      case ZipDataDir(path) =>
        RequestParam("zipdatadir", Seq(up.writeJs(path)))

      case GetDLCWalletAccounting =>
        RequestParam("getdlcwalletaccounting")
      case GetVersion =>
        // skip sending to server and just return version number of cli
        return Success(EnvUtil.getVersion)

      case NoCommand => ???
    }

    Try {
      import com.softwaremill.sttp._
      implicit val backend: SttpBackend[Id, Nothing] =
        HttpURLConnectionBackend()
      val request =
        sttp
          .post(uri"http://$host:${config.rpcPort}/")
          .contentType("application/json")
          .body {
            val uuid = java.util.UUID.randomUUID.toString
            val paramsWithID: Map[String, ujson.Value] =
              requestParam.toJsonMap + ("id" -> up
                .writeJs(uuid))
            up.write(paramsWithID)
          }
      debug(s"HTTP request: $request")
      val response = request.send()

      debug(s"HTTP response:")
      debug(response)

      // in order to mimic Bitcoin Core we always send
      // an object looking like {"result": ..., "error": ...}
      val rawBody = response.body match {
        case Left(err)       => err
        case Right(response) => response
      }

      val jsObjT =
        Try(ujson.read(rawBody).obj)
          .transform[mutable.LinkedHashMap[String, ujson.Value]](
            Success(_),
            _ => error(s"Response was not a JSON object! Got: $rawBody"))

      /** Gets the given key from jsObj if it exists
        * and is not null
        */
      def getKey(key: String): Option[ujson.Value] = {
        jsObjT.toOption.flatMap(_.get(key).flatMap(result =>
          if (result.isNull) None else Some(result)))
      }

      /** Converts a `ujson.Value` to String, making an
        * effort to avoid preceding and trailing `"`s
        */
      def jsValueToString(value: ujson.Value) =
        value match {
          case Str(string)             => string
          case Num(num) if num.isWhole => num.toLong.toString
          case Num(num)                => num.toString
          case rest: ujson.Value       => rest.render(2)
        }

      (getKey("result"), getKey("error")) match {
        case (Some(result), None) =>
          Success(jsValueToString(result))
        case (None, Some(err)) =>
          val msg = jsValueToString(err)
          error(msg)
        case (None, None) => Success("")
        case (None, None) | (Some(_), Some(_)) =>
          error(s"Got unexpected response: $rawBody")
      }
    }.flatten
  }

  def host = "localhost"

  case class RequestParam(
      method: String,
      params: Seq[ujson.Value.Value] = Nil) {

    lazy val toJsonMap: Map[String, ujson.Value] = {
      if (params.isEmpty)
        Map("method" -> method)
      else
        Map("method" -> method, "params" -> params)
    }
  }
}

case class Config(
    command: CliCommand = CliCommand.NoCommand,
    network: Option[NetworkParameters] = None,
    debug: Boolean = false,
    rpcPortOpt: Option[Int] = None) {

  val rpcPort: Int = rpcPortOpt match {
    case Some(port) => port
    case None       => command.defaultPort
  }
}

object Config {
  val empty: Config = Config()
}

sealed abstract class CliCommand {
  def defaultPort: Int
}

object CliCommand {

  case object NoCommand extends CliCommand {
    override def defaultPort: Int = 9999
  }

  trait Broadcastable {
    def noBroadcast: Boolean
  }

  sealed trait ServerlessCliCommand extends CliCommand {
    override def defaultPort: Int = 9999
  }

  sealed trait AppServerCliCommand extends CliCommand {
    override def defaultPort: Int = 9999
  }

  sealed trait OracleServerCliCommand extends CliCommand {
    override def defaultPort: Int = 9998
  }

  case object GetVersion extends ServerlessCliCommand

  case object GetInfo extends AppServerCliCommand

  // DLC
  case object GetDLCHostAddress extends AppServerCliCommand

  case class CreateDLCOffer(
      contractInfo: ContractInfoV0TLV,
      collateral: Satoshis,
      feeRateOpt: Option[SatoshisPerVirtualByte],
      locktime: UInt32,
      refundLT: UInt32)
      extends AppServerCliCommand

  sealed trait AcceptDLCCliCommand extends AppServerCliCommand

  case class AcceptDLC(
      offer: LnMessage[DLCOfferTLV],
      peerAddr: InetSocketAddress)
      extends AcceptDLCCliCommand

  case class AcceptDLCOffer(offer: LnMessage[DLCOfferTLV])
      extends AcceptDLCCliCommand

  case class AcceptDLCOfferFromFile(path: Path, destination: Option[Path])
      extends AcceptDLCCliCommand

  sealed trait SignDLCCliCommand extends AppServerCliCommand

  case class SignDLC(accept: LnMessage[DLCAcceptTLV]) extends SignDLCCliCommand

  case class SignDLCFromFile(path: Path, destination: Option[Path])
      extends SignDLCCliCommand

  sealed trait AddDLCSigsCliCommand extends AppServerCliCommand

  case class AddDLCSigs(sigs: LnMessage[DLCSignTLV])
      extends AddDLCSigsCliCommand

  case class AddDLCSigsFromFile(path: Path) extends AddDLCSigsCliCommand

  sealed trait AddDLCSigsAndBroadcastCliCommand extends AddDLCSigsCliCommand

  case class AddDLCSigsAndBroadcast(sigs: LnMessage[DLCSignTLV])
      extends AddDLCSigsAndBroadcastCliCommand

  case class AddDLCSigsAndBroadcastFromFile(path: Path)
      extends AddDLCSigsAndBroadcastCliCommand

  case class GetDLCFundingTx(contractId: ByteVector) extends AppServerCliCommand

  case class BroadcastDLCFundingTx(contractId: ByteVector)
      extends AppServerCliCommand

  case class ExecuteDLC(
      contractId: ByteVector,
      oracleSigs: Vector[OracleAttestmentTLV],
      noBroadcast: Boolean)
      extends AppServerCliCommand
      with Broadcastable

  case class ExecuteDLCRefund(contractId: ByteVector, noBroadcast: Boolean)
      extends AppServerCliCommand
      with Broadcastable

  case class CancelDLC(dlcId: Sha256Digest) extends AppServerCliCommand

  case object GetDLCs extends AppServerCliCommand
  case class GetDLC(dlcId: Sha256Digest) extends AppServerCliCommand

  sealed trait SendCliCommand extends AppServerCliCommand {
    def destination: BitcoinAddress
  }

  // Wallet
  case class SendToAddress(
      destination: BitcoinAddress,
      amount: Bitcoins,
      satoshisPerVirtualByte: Option[SatoshisPerVirtualByte],
      noBroadcast: Boolean)
      extends SendCliCommand
      with Broadcastable

  case class SendFromOutPoints(
      outPoints: Vector[TransactionOutPoint],
      destination: BitcoinAddress,
      amount: Bitcoins,
      feeRateOpt: Option[SatoshisPerVirtualByte])
      extends SendCliCommand

  case class SweepWallet(
      destination: BitcoinAddress,
      feeRateOpt: Option[SatoshisPerVirtualByte])
      extends SendCliCommand

  case class SendWithAlgo(
      destination: BitcoinAddress,
      amount: Bitcoins,
      feeRateOpt: Option[SatoshisPerVirtualByte],
      algo: CoinSelectionAlgo)
      extends SendCliCommand

  case class OpReturnCommit(
      message: String,
      hashMessage: Boolean,
      feeRateOpt: Option[SatoshisPerVirtualByte])
      extends AppServerCliCommand

  case class BumpFeeCPFP(
      txId: DoubleSha256DigestBE,
      feeRate: SatoshisPerVirtualByte)
      extends AppServerCliCommand

  case class BumpFeeRBF(
      txId: DoubleSha256DigestBE,
      feeRate: SatoshisPerVirtualByte)
      extends AppServerCliCommand

  case class SignPSBT(psbt: PSBT) extends AppServerCliCommand

  case class LockUnspent(
      unlock: Boolean,
      outPoints: Vector[LockUnspentOutputParameter])
      extends AppServerCliCommand

  case class LabelAddress(address: BitcoinAddress, label: AddressLabelTag)
      extends AppServerCliCommand

  case class GetAddressTags(address: BitcoinAddress) extends AppServerCliCommand

  case class GetAddressLabels(address: BitcoinAddress)
      extends AppServerCliCommand

  case class DropAddressLabels(address: BitcoinAddress)
      extends AppServerCliCommand

  case class GetNewAddress(labelOpt: Option[AddressLabelTag])
      extends AppServerCliCommand
  case object GetUtxos extends AppServerCliCommand
  case object ListReservedUtxos extends AppServerCliCommand
  case object GetAddresses extends AppServerCliCommand
  case object GetSpentAddresses extends AppServerCliCommand
  case object GetFundedAddresses extends AppServerCliCommand
  case object GetUnusedAddresses extends AppServerCliCommand
  case object GetAccounts extends AppServerCliCommand
  case object CreateNewAccount extends AppServerCliCommand
  case object IsEmpty extends AppServerCliCommand
  case object WalletInfo extends AppServerCliCommand
  case class GetBalance(isSats: Boolean) extends AppServerCliCommand
  case class GetConfirmedBalance(isSats: Boolean) extends AppServerCliCommand
  case class GetUnconfirmedBalance(isSats: Boolean) extends AppServerCliCommand
  case class GetBalances(isSats: Boolean) extends AppServerCliCommand
  case class GetAddressInfo(address: BitcoinAddress) extends AppServerCliCommand
  case object GetDLCWalletAccounting extends AppServerCliCommand

  case class GetTransaction(txId: DoubleSha256DigestBE)
      extends AppServerCliCommand

  case class KeyManagerPassphraseChange(
      oldPassword: AesPassword,
      newPassword: AesPassword)
      extends AppServerCliCommand

  case class KeyManagerPassphraseSet(password: AesPassword)
      extends AppServerCliCommand

  case class ImportSeed(
      walletName: String,
      mnemonic: MnemonicCode,
      passwordOpt: Option[AesPassword])
      extends AppServerCliCommand

  case class ImportXprv(
      walletName: String,
      xprv: ExtPrivateKey,
      passwordOpt: Option[AesPassword])
      extends AppServerCliCommand

  // Node
  case object GetPeers extends AppServerCliCommand
  case object Stop extends AppServerCliCommand
  case class SendRawTransaction(tx: Transaction) extends AppServerCliCommand

  // Chain
  case object GetBestBlockHash extends AppServerCliCommand
  case object GetBlockCount extends AppServerCliCommand
  case object GetFilterCount extends AppServerCliCommand
  case object GetFilterHeaderCount extends AppServerCliCommand

  case class GetBlockHeader(hash: DoubleSha256DigestBE)
      extends AppServerCliCommand

  case class DecodeRawTransaction(transaction: Transaction)
      extends AppServerCliCommand

  case class Rescan(
      addressBatchSize: Option[Int],
      startBlock: Option[BlockStamp],
      endBlock: Option[BlockStamp],
      force: Boolean,
      ignoreCreationTime: Boolean)
      extends AppServerCliCommand

  // PSBT
  case class DecodePSBT(psbt: PSBT) extends AppServerCliCommand
  case class CombinePSBTs(psbts: Seq[PSBT]) extends AppServerCliCommand
  case class JoinPSBTs(psbts: Seq[PSBT]) extends AppServerCliCommand
  case class FinalizePSBT(psbt: PSBT) extends AppServerCliCommand
  case class ExtractFromPSBT(psbt: PSBT) extends AppServerCliCommand
  case class ConvertToPSBT(transaction: Transaction) extends AppServerCliCommand
  case class AnalyzePSBT(psbt: PSBT) extends AppServerCliCommand

  // Util
  case class CreateMultisig(
      requiredKeys: Int,
      keys: Vector[ECPublicKey],
      addressType: AddressType)
      extends AppServerCliCommand

  case class ZipDataDir(path: Path) extends AppServerCliCommand

  case object EstimateFee extends AppServerCliCommand

  // Oracle
  case object GetPublicKey extends OracleServerCliCommand
  case object GetStakingAddress extends OracleServerCliCommand
  case object ListEvents extends OracleServerCliCommand

  case class GetEvent(eventName: String) extends OracleServerCliCommand

  case class CreateEnumEvent(
      label: String,
      maturationTime: Date,
      outcomes: Seq[String])
      extends OracleServerCliCommand

  case class CreateNumericEvent(
      eventName: String,
      maturationTime: Date,
      minValue: Long,
      maxValue: Long,
      unit: String,
      precision: Int)
      extends OracleServerCliCommand

  case class CreateDigitDecompEvent(
      eventName: String,
      maturationTime: Instant,
      base: Int,
      isSigned: Boolean,
      numDigits: Int,
      unit: String,
      precision: Int)
      extends OracleServerCliCommand

  case class SignEvent(eventName: String, outcome: String)
      extends OracleServerCliCommand

  case class SignDigits(eventName: String, num: Long)
      extends OracleServerCliCommand

  case class GetSignatures(eventName: String) extends OracleServerCliCommand

  case class SignMessage(message: String) extends OracleServerCliCommand
}
