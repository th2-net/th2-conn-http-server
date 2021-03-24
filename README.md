# HTTP SERVER v0.0.1

This microservice allows performing HTTP responses and receive HTTP requests.

## Configuration

Main configuration is done via setting following properties in a custom configuration:
+ **https** - set protocol for communication, `true` if you want to use `https` (`false` by default)
+ **port** - port for HTTP requests (`80` by default)
+ **sessionAlias** - session alias for incoming/outgoing TH2 messages (e.g. `rest_api`)
+ **threads** - number of socket-processing threads


### Configuration example

```yaml
https: false
port: 334
sessionAlias: api_session
threads: 24
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
  image-version: 0.0.1
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