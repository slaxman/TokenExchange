TokenExchange
=============

TokenExchange is a NRS add-on that automates the process of exchanging Nxt currency for bitcoins.  The add-on watches for transfer transactions of the specified currency.  If the transfer is to the redemption Nxt address, a Bitcoin transaction will be initiated to send the equivalent amount of bitcoins to the bitcoin address specified as a message attached to the transfer transaction.  The attached message must be a prunable plain message.

The token-exchange.properties configuration file controls the operation of the TokenExchange add-on.  The access controls for this file should restrict access since it contains passwords used to access bitcoind and send bitcoins.  The configuration file contains the following fields:    

- exchangeRate=nnn.nnn    
    This specifies the value of a single currency unit in bitcoins.  For example, a value of 0.001 indicates that 1 currency unit is equal to 0.001 bitcoins.
 
- currency=xxxxx    
    This specifies the 3-5 character Nxt currency code that will be used for the token exchange.

- redemptionAccount=NXT-xxxx-xxxx-xxxx-xxxxx    
    This specifies the NXT redemption account.  Currency transfers to this address will be processed by the TokenExchange add-on.
    
- confirmations=n    
    This specifies the number of Nxt confirmations before the bitcoins will be sent to the user.
    
- bitcoindAddress=host-name:port    
    This specifies the host name and port of the bitcoind server that will be used to send bitcoins to the user.  Since bitcoind no longer supports SSL connections, the bitcoind server should be running on the same system as the NRS server to avoid security leaks.
    
- bitcoindUser=user-name   
    This specifies the user for RPC requests to the bitcoind server
    
- bitcoindPassword=password    
    This specifies the password for RPC requests to the bitcoind server
    
- bitcoindTxFee=n.nnnn    
    This specifies the bitcoind transaction fee as BTC/KB.  A SEND transaction with one input and two output is around 226 bytes.  If the bitcoindTxFee=0.0004, then the transaction fee calculated by the current version of bitcoind would be 0.0004 * .226 = 0.0000904.  Note that older versions of bitcoind would round the size up to 1KB, resulting in a transaction fee of 0.0004.
    
- bitcoindWalletPassphrase=passphrase    
    This specifies the passphrase needed to unlock an encrypted bitcoind wallet.  It should be omitted if the wallet is not encrypted.


TokenExchange API
=================

TokenExchange provides an NRS API under the ADDONS tag with the request type 'tokenExchange'.  The NRS test page (http://localhost:7876/test) can be used to issue requests or an application can issue its own HTTP requests.  The following functions are available:

  - Get the current status of the TokenExchange add-on.  Specify 'requestType=tokenExchange&function=status' in the HTTP request.
  - List currency tokens that have been redeemed.  Specify 'requestType=tokenExchange&function=list&height=n&exchanged=true/false' in the HTTP request.  This will return all tokens redeemed after the specified height.  The height defaults to 0 if it is not specified.  The 'exchanged' parameter is 'true' to return exchanged tokens in addition to tokens that have not been exchanged.  Only unexchanged tokens are returned if 'false' is specified or the parameter is omitted.
  - Delete an entry in the token exchange database.  Specify 'requestType=tokenExchange&function=delete&id=string&adminPassword=xxxxxx' in the HTTP request.
  - Stop sending bitcoins for redeemed tokens.  Specify 'requestType=tokenExchange&adminPassword=xxxxxx' in the HTTP request.  Redeemed tokens will still be added to the database but bitcoins will not be sent until sending is resumed or the NRS server is restarted.
  - Resume sending bitcoins for redeemed tokens.  Specify 'requestType=tokenExchange&adminPassword=xxxxx' in the HTTP request.  Bitcoins will be sent for pending confirmed tokens and normal processing will resume.

    
Installation
============

- Extract the files to the Nxt installation directory    

- Copy sample.token-exchange.properties to the 'conf' subdirectory of the NRS application data directory and rename it to token-exchange.properties.  Fill in the configuration parameters for your installation.

- Add 'addons/lib/*' to the java classpath when starting NRS    
    - Linux and Mac: -cp classes:lib/*:conf:addons/classes:addons/lib/*    
    - Windows: -cp classes;lib\*conf;addons\classes;addons\lib\*    

- Add 'nxt.addOns=org.ScripterRon.TokenExchange.TokenAddon' to nxt.properties. If you have multiple add-ons, separate each add-on name by a semi-colon

    
Build
=====

I use the Netbeans IDE but any build environment with Maven and the Java compiler available should work.  The documentation is generated from the source code using javadoc.

Here are the steps for a manual build.  You will need to install Maven 3 and Java SE Development Kit 8 if you don't already have them.

  - Create the executable: mvn clean package    
  - [Optional] Create the documentation: mvn javadoc:javadoc    
