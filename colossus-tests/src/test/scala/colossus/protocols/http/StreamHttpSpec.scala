package colossus
package protocols.http

import core._
import testkit._
import stream._

import akka.util.ByteString

class StreamHttpSpec extends ColossusSpec {


  "streamhttp codec" must {
    "decode a standard request into parts" in {
      val requestBytes = DataBuffer("GET /foo HTTP/1.1\r\ncontent-length: 10\r\nsomething-else: bleh\r\n\r\n0123456789")
      
      val codec = new StreamHttpServerCodec

      codec.decode(requestBytes) mustBe Some(Head(HttpRequestHead(HttpMethod.Get, "/foo", HttpVersion.`1.1`, HttpHeaders.fromString(
        "content-length" -> "10", "something-else" -> "bleh"
      ))))
      codec.decode(requestBytes) mustBe Some(BodyData(DataBlock("0123456789")))
      codec.decode(requestBytes) mustBe Some(End())
      codec.decode(requestBytes) mustBe None
    }

    "decode a chunked request into chunks" in {
      val requestBytes1 = DataBuffer("GET /foo HTTP/1.1\r\ntransfer-encoding: chunked\r\nsomething-else: bleh\r\n\r\n5\r\nhel")
      val requestBytes2 = DataBuffer("lo\r\n2\r\nok\r\n0\r\n\r\n")
      val codec = new StreamHttpServerCodec

      codec.decode(requestBytes1) mustBe Some(Head(HttpRequestHead(HttpMethod.Get, "/foo", HttpVersion.`1.1`, HttpHeaders.fromString(
        "transfer-encoding" -> "chunked", "something-else" -> "bleh"
      ))))

      codec.decode(requestBytes1) mustBe None
      codec.decode(requestBytes2) mustBe Some(BodyData(DataBlock("hello")))
      codec.decode(requestBytes2) mustBe Some(BodyData(DataBlock("ok")))
      codec.decode(requestBytes2) mustBe Some(End())
      codec.decode(requestBytes2) mustBe None
    }

    "encode standard response parts" in {
      val codec = new StreamHttpServerCodec
      val out = new DynamicOutBuffer(100)
      val resp = HttpResponseHead(HttpVersion.`1.1`, HttpCodes.OK, HttpHeaders.fromString("foo" -> "bar", "content-length" -> "10"))
      val expected = "HTTP/1.1 200 OK\r\nfoo: bar\r\ncontent-length: 10\r\n\r\n0123456789"
      codec.encode(Head(resp), out)
      codec.encode(BodyData(DataBlock("0123456789")), out)
      codec.encode(End(), out)
      out.data.asByteString.utf8String mustBe expected
    }

    "encode chunked response parts" in {
      val codec = new StreamHttpServerCodec
      val out = new DynamicOutBuffer(100)
      val resp = HttpResponseHead(HttpVersion.`1.1`, HttpCodes.OK, HttpHeaders.fromString("foo" -> "bar", "transfer-encoding" -> "chunked"))
      val expected = "HTTP/1.1 200 OK\r\nfoo: bar\r\ntransfer-encoding: chunked\r\n\r\n5\r\nhello\r\n6\r\nworld!\r\n0\r\n\r\n"
      codec.encode(Head(resp), out)
      codec.encode(BodyData(DataBlock("hello")), out)
      codec.encode(BodyData(DataBlock("world!")), out)
      codec.encode(End(), out)
      out.data.asByteString.utf8String mustBe expected
    }

    "not allow sending a new head until current message fully sent" in {
      val codec = new StreamHttpServerCodec
      val out = new DynamicOutBuffer(100)
      codec.encode(Head(HttpResponse.ok("foo").head), out)
      intercept[StreamHttpException] {
        codec.encode(Head(HttpResponse.ok("foo").head), out)
      }
      codec.encode(End(), out)
      //now it should work
      codec.encode(Head(HttpResponse.ok("foo").head), out)
    }


      

    "properly reset" in {
      val codec = new StreamHttpServerCodec
      codec.decode(DataBuffer("GET /foo")) mustBe None
      codec.reset()
      codec.decode(DataBuffer("GET /foo HTTP/1.1\r\n\r\n")).isEmpty mustBe false
      
    }

  }

  "StreamHttpServerHandler" must {
    class MyHandler(ctx: ServerContext) extends StreamServerHandler(ctx) {
      
      def processMessage(msg: StreamHttpMessage[HttpRequestHead]) = msg match {
        case Head(h) => {
          pushCompleteMessage(HttpResponse.ok("hello"))(_ => ())
        }
        case _ => {}
      }
    }
    
    "push a full response" in {
      val con = MockConnection.server(new MyHandler(_))
      con.typedHandler.connected(con)
      con.typedHandler.receivedData(DataBuffer(HttpRequest.get("/foo").bytes))
      con.iterate()
      con.iterate()
      val expected = ByteString("HTTP/1.1 200 OK\r\ncontent-length: 5\r\nContent-Type: text/plain\r\n\r\nhello")
      con.expectOneWrite(expected)
    }
  }

}
