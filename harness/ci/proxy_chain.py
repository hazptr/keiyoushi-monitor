#!/usr/bin/env python3
"""A local, unauthenticated front for an upstream HTTP proxy that wants a login.

    CHAIN_UPSTREAM=http://user:pass@host:port proxy_chain.py <listen-port>

Chrome's --proxy-server and the JVM's proxy properties can't carry proxy credentials, so the
recheck points both at this instead (127.0.0.1 only) and it adds the Proxy-Authorization header on
the way to the upstream. CONNECT tunnels and plain HTTP requests both work; plain HTTP is forced to
one request per connection so every request carries the header. The upstream comes from the
environment, never argv, so it doesn't show up in a process list.
"""
import asyncio
import base64
import os
import sys
from urllib.parse import unquote, urlsplit

UP = urlsplit(os.environ["CHAIN_UPSTREAM"])
if UP.scheme != "http" or not UP.hostname:
    sys.exit("CHAIN_UPSTREAM must be http://[user:pass@]host:port")
UP_HOST, UP_PORT = UP.hostname, UP.port or 8080
AUTH = ("Basic " + base64.b64encode(f"{unquote(UP.username)}:{unquote(UP.password or '')}".encode()).decode()
        if UP.username else None)
DROP = ("proxy-authorization:", "proxy-connection:", "connection:", "keep-alive:")


async def pipe(reader, writer):
    try:
        while data := await reader.read(65536):
            writer.write(data)
            await writer.drain()
    except (ConnectionError, asyncio.IncompleteReadError, OSError):
        pass
    finally:
        try:
            writer.close()
        except OSError:
            pass


async def handle(client_r, client_w):
    try:
        head = await asyncio.wait_for(client_r.readuntil(b"\r\n\r\n"), 30)
    except (asyncio.TimeoutError, asyncio.IncompleteReadError, asyncio.LimitOverrunError, ConnectionError):
        client_w.close()
        return
    lines = head.decode("latin-1").split("\r\n")
    headers = [h for h in lines[1:] if h and not h.lower().startswith(DROP)]
    if AUTH:
        headers.append(f"Proxy-Authorization: {AUTH}")
    if not lines[0].upper().startswith("CONNECT "):
        headers += ["Connection: close", "Proxy-Connection: close"]
    try:
        up_r, up_w = await asyncio.wait_for(asyncio.open_connection(UP_HOST, UP_PORT), 20)
    except (asyncio.TimeoutError, OSError):
        client_w.write(b"HTTP/1.1 502 Bad Gateway\r\nContent-Length: 0\r\n\r\n")
        await client_w.drain()
        client_w.close()
        return
    up_w.write(("\r\n".join([lines[0], *headers]) + "\r\n\r\n").encode("latin-1"))
    await up_w.drain()
    await asyncio.gather(pipe(client_r, up_w), pipe(up_r, client_w))


async def main():
    server = await asyncio.start_server(handle, "127.0.0.1", int(sys.argv[1]))
    async with server:
        await server.serve_forever()


if __name__ == "__main__":
    asyncio.run(main())
