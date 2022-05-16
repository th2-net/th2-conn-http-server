# HTTP SERVER v1.0.0

This microservice allows performing HTTP responses and receive HTTP requests.

## Configuration

Main configuration is done via setting following properties in a custom configuration:
+ **https** - set protocol for communication, `true` if you want to use `https` (`false` by default)
+ **port** - port for HTTP requests (null by default) - if null `80` or `443` will be used depending on https property
+ **sessionAlias** - session alias for incoming/outgoing TH2 messages (e.g. `rest_api`)
+ **threads** - number of socket-processing threads
+ **terminationTime** - the specified time to wait for the executors to close (`30` by default)
+ **keystorePass** - pass to open keystore for https connection
+ **sslProtocol** - protocol for https connection (`TLSv1.3` by default)
+ **keystorePath** - path to new keystore (by default its using one from resources)
+ **keystoreType** - type of keystore (`JKS` by default)
+ **keyManagerAlgorithm** - type of keystore algorithm (`SunX509` by default)
+ **catchClientClosing** - handle as error closing of socket without agreement (`true` by default)


## USAGE NOTES

Every response must contain Content-Length header, server will produce one if wasn't passed before. According [rfc docs](https://www.rfc-editor.org/rfc/rfc7230#section-3.3.3)

Please before production usage put your personal keystore in properties, it's not safe to use default keystore, only purpose of default one is test

### Configuration example
```yaml
https: false
port: 8080
sessionAlias: api_session
threads: 24
terminationTime: 30
keystorePass: some_https_store_pass
```

### MQ pins

* input queue with `subscribe` and `send` attributes for responses
* output queue with `publish` and `first` (for responses) attributes
* output queue with `publish` and `second` (for requests) attributes

## Inputs/outputs

This section describes messages received and by produced by the service

### Inputs

This service receives HTTP responses via MQ as `MessageGroup`s containing one of:

* a single `RawMessage` containing response body `contentType` properties in its metadata, which will be used in resulting request
* a single `Message` with `Response` message type containing HTTP Response code, reason and a `RawMessage` described above

If both `Message` and `RawMessage` contain `code` and `reason` values from `Message` take precedence.  
If none of them contain these values `200` and `ok` will be used as `code` and `reason` values respectively


#### Message descriptions

* Response

|Field|Type|Description|
|:---:|:---:|:---:|
|code|String|HTTP code number (e.g. 200, 404, etc.)|
|reason|String|Reason info (e.g ok, not found, etc.)|
|headers|List\<Header>|HTTP headers (e.g. Host, Content-Length, etc.)|

* Header

|Field|Type|Description|
|:---:|:---:|:---:|
|name|String|HTTP header name|
|value|String|HTTP header value|


### Outputs

HTTP requests and responses are sent via MQ as `MessageGroup`s containing a single `RawMessage` with a raw request or response.   
`RawMessage` also has `uri`, `method`, and `contentType` properties set equal to URI, method, and content type of request 
or `code` and `reason` for response type

## Deployment via `infra-mgr`

Here's an example of `infra-mgr` config required to deploy this service

```yaml
apiVersion: th2.exactpro.com/v1
kind: Th2Box
metadata:
  name: http-server
spec:
  image-name: ghcr.io/th2-net/th2-conn-http-server
  image-version: 0.2.0
  custom-config:
    https: false
    port: 8080
    sessionAlias: some_alias
    threads: 24
  type: th2-conn
  pins:
    - name: to_send
      connection-type: mq
      attributes:
        - subscribe
        - send
    - name: out_request
      connection-type: mq
      attributes:
        - publish
        - second
        - raw
    - name: out_response
      connection-type: mq
      attributes:
        - publish
        - first
        - raw 
```


## Instruction to regenerate test keys
1) `keytool -keystore servertest -genkey -alias servertest -keyalg RSA` - to create servertest keystore

2) `keytool -export -alias servertest -storepass servertest -file servertest.cer -keystore servertest` - to export certificate file outside keystore

3) `keytool -import -file servertest.cer -alias servertest -keystore TestTrustStore` - to import certificate inside of trust store
