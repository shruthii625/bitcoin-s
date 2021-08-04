package org.bitcoins.rpc.config


import grizzled.slf4j.Logging
import org.bitcoins.core.api.commons.InstanceFactory
import org.bitcoins.core.config.NetworkParameters

import java.io.{File, FileNotFoundException}
import java.net.URI
import java.nio.file.{Files, Path, Paths}
import scala.util.Properties

/** Created by chris on 4/29/17.
  */
sealed trait BitcoindInstance extends Logging {
  def network: NetworkParameters
  def uri: URI
  def rpcUri: URI
  def authCredentials: BitcoindAuthCredentials
  def zmqConfig: ZmqConfig
  def p2pPort: Int = uri.getPort

}


object BitcoindInstanceLocal extends InstanceFactory[BitcoindInstance]{

  case class BitcoindInstanceLocal(        network: NetworkParameters,
                                           uri: URI,
                                           rpcUri: URI,
                                           authCredentials: BitcoindAuthCredentials,
                                           zmqConfig: ZmqConfig,
                                           binary: File,
                                           datadir: File
                                  ) extends BitcoindInstance

  def apply(
             network: NetworkParameters,
             uri: URI,
             rpcUri: URI,
             authCredentials: BitcoindAuthCredentials,
             zmqConfig: ZmqConfig = ZmqConfig(),
             binary: File = DEFAULT_BITCOIND_LOCATION,
             datadir: File = BitcoindConfig.DEFAULT_DATADIR,
           ): BitcoindInstance = {
    BitcoindInstanceLocal(network,
      uri,
      rpcUri,
      authCredentials,
      zmqConfig = zmqConfig,
      binary = binary,
      datadir = datadir)
  }

  lazy val DEFAULT_BITCOIND_LOCATION: File = {

    def findExecutableOnPath(name: String): Option[File] =
      sys.env
        .getOrElse("PATH", "")
        .split(File.pathSeparator)
        .map(directory => new File(directory, name))
        .find(file => file.isFile && file.canExecute)

    val cmd =
      if (Properties.isWin) {
        findExecutableOnPath("bitcoind.exe")
      } else {
        findExecutableOnPath("bitcoind")
      }

    cmd.getOrElse(
      throw new FileNotFoundException("Cannot find a path to bitcoind"))
  }

  lazy val remoteFilePath: File = {
    Files.createTempFile("dummy", "").toFile
  }

  /** Constructs a `bitcoind` instance from the given datadir, using the
    * `bitcoin.conf` found within (if any)
    *
    * @throws IllegalArgumentException if the given datadir does not exist
    */
  def fromDatadir(
                   datadir: File = BitcoindConfig.DEFAULT_DATADIR,
                   binary: File = DEFAULT_BITCOIND_LOCATION
                 ): BitcoindInstance = {
    require(datadir.exists, s"${datadir.getPath} does not exist!")
    require(datadir.isDirectory, s"${datadir.getPath} is not a directory!")

    val configPath = Paths.get(datadir.getAbsolutePath, "bitcoin.conf")
    if (Files.exists(configPath)) {

      val file = configPath.toFile()
      fromConfFile(file, binary)
    } else {
      fromConfig(BitcoindConfig.empty, binary)
    }
  }

  override def fromDataDir(dir: File): BitcoindInstance = {
    fromDatadir(dir, DEFAULT_BITCOIND_LOCATION)
  }

  /** Construct a `bitcoind` from the given config file. If no `datadir` setting
    * is found, the parent directory to the given file is used.
    *
    * @throws  IllegalArgumentException if the given config file does not exist
    */
  def fromConfFile(
                    file: File = BitcoindConfig.DEFAULT_CONF_FILE,
                    binary: File = DEFAULT_BITCOIND_LOCATION
                  ): BitcoindInstance = {
    require(file.exists, s"${file.getPath} does not exist!")
    require(file.isFile, s"${file.getPath} is not a file!")

    val conf = BitcoindConfig(file, file.getParentFile)

    fromConfig(conf, binary)
  }

  override def fromConfigFile(file: File): BitcoindInstance = {
    fromConfFile(file, DEFAULT_BITCOIND_LOCATION)
  }

  /** Constructs a `bitcoind` instance from the given config */
  def fromConfig(
                  config: BitcoindConfig,
                  binary: File = DEFAULT_BITCOIND_LOCATION
                ): BitcoindInstance = {

    val authCredentials = BitcoindAuthCredentials.fromConfig(config)
    BitcoindInstanceLocal(config.network,
      config.uri,
      config.rpcUri,
      authCredentials,
      zmqConfig = ZmqConfig.fromConfig(config),
      binary = binary,
      datadir = config.datadir)
  }

  override val DEFAULT_DATADIR: Path = BitcoindConfig.DEFAULT_DATADIR.toPath

  override val DEFAULT_CONF_FILE: Path = BitcoindConfig.DEFAULT_CONF_FILE.toPath


}

object BitcoindInstanceRemote  {

  case class BitcoindInstanceRemote( network: NetworkParameters,
                                     uri: URI,
                                     rpcUri: URI,
                                     authCredentials: BitcoindAuthCredentials,
                                     zmqConfig: ZmqConfig) extends BitcoindInstance

  def apply(
             network: NetworkParameters,
             uri: URI,
             rpcUri: URI,
             authCredentials: BitcoindAuthCredentials,
             zmqConfig: ZmqConfig = ZmqConfig(),
           ): BitcoindInstanceRemote = {
    BitcoindInstanceRemote(network,
      uri,
      rpcUri,
      authCredentials,
      zmqConfig = zmqConfig)
  }


}