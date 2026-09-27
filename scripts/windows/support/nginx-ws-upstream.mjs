import http from "node:http";
import crypto from "node:crypto";

const port = Number(process.argv[2]);
const server = http.createServer((request, response) => {
  process.stdout.write(`HTTP ${request.url}\n`);
  response.writeHead(200, { "Content-Type": "text/plain" });
  response.end(request.url === "/ws/info" ? "{\"websocket\":true}" : "ok");
});
server.on("upgrade", (request, socket) => {
  process.stdout.write(`UPGRADE ${request.url}\n`);
  const key = request.headers["sec-websocket-key"];
  if (typeof key !== "string") {
    socket.destroy();
    return;
  }
  const accept = crypto.createHash("sha1").update(key + "258EAFA5-E914-47DA-95CA-C5AB0DC85B11").digest("base64");
  socket.write("HTTP/1.1 101 Switching Protocols\r\nUpgrade: websocket\r\nConnection: Upgrade\r\nSec-WebSocket-Accept: " + accept + "\r\n\r\n");
  socket.on("error", () => {});
});
server.listen(port, "127.0.0.1");
