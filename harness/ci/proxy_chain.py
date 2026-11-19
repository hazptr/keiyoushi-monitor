#!/usr/bin/env python3
"""A local, unauthenticated HTTP proxy in front of an upstream proxy that wants a login.

    CHAIN_UPSTREAM=http://user:pass@host:port   proxy_chain.py <listen-port>
    CHAIN_UPSTREAM=socks5://user:pass@host:port proxy_chain.py <listen-port>

Chrome's --proxy-server and the JVM's proxy properties can't carry proxy credentials (and the JVM's
https.proxyHost only speaks HTTP), so the recheck points both at this instead (127.0.0.1 only).
For an http:// upstream it adds the Proxy-Authorization header on the way through; for a socks5://
upstream it opens each connection with a SOCKS5 username/password handshake (RFC 1928/1929), host
names resolved by the upstream. CONNECT tunnels and plain HTTP requests both work; plain HTTP is
forced to one request per connection. The upstream comes from the environment, never argv, so it
doesn't show up in a process list.
"""
import asyncio
import base64
import os
import struct
import sys
from urllib.parse import unquote, urlsplit

UP = urlsplit(os.environ["CHAIN_UPSTREAM"])
SOCKS = UP.scheme in ("socks5", "socks5h")
if not (UP.scheme == "http" or SOCKS) or not UP.hostname:
    sys.exit("CHAIN_UPSTREAM must be http:// or socks5://[user:pass@]host:port")
UP_HOST, UP_PORT = UP.hostname, UP.port or (1080 if SOCKS else 8080)
USER, PASS = unquote(UP.username or ""), unquote(UP.password or "")
AUTH = "Basic " + base64.b64encode(f"{USER}:{PASS}".encode()).decode() if UP.username else None
DROP = ("proxy-authorization:", "proxy-connection:", "connection:", "keep-alive:")


class UpstreamError(Exception):
    pass


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


async def socks_connect(host: str, port: int):
    r, w = await asyncio.wait_for(asyncio.open_connection(UP_HOST, UP_PORT), 20)
    w.write(b"\x05\x01\x02" if USER else b"\x05\x01\x00")
    await w.drain()
    ver, method = await asyncio.wait_for(r.readexactly(2), 20)
    if ver != 5 or method not in (0, 2):
        raise UpstreamError("socks: no acceptable auth method")
    if method == 2:
        u, p = USER.encode(), PASS.encode()
        w.write(bytes([1, len(u)]) + u + bytes([len(p)]) + p)
        await w.drain()
        if (await asyncio.wait_for(r.readexactly(2), 20))[1] != 0:
            raise UpstreamError("socks: login refused")
    h = host.encode("idna")
    w.write(b"\x05\x01\x00\x03" + bytes([len(h)]) + h + struct.pack(">H", port))
    await w.drain()
    rep = await asyncio.wait_for(r.readexactly(4), 30)
    if rep[1] != 0:
        raise UpstreamError(f"socks: connect failed ({rep[1]})")
    skip = {1: 4, 4: 16}.get(rep[3])
    if skip is None:
        skip = (await r.readexactly(1))[0]
    await r.readexactly(skip + 2)
    return r, w


async def handle(client_r, client_w):
    try:
        head = await asyncio.wait_for(client_r.readuntil(b"\r\n\r\n"), 30)
    except (asyncio.TimeoutError, asyncio.IncompleteReadError, asyncio.LimitOverrunError, ConnectionError):
        client_w.close()
        return
    lines = head.decode("latin-1").split("\r\n")
    method, target, version = (lines[0].split(" ", 2) + ["", ""])[:3]
    headers = [h for h in lines[1:] if h and not h.lower().startswith(DROP)]
    connect = method.upper() == "CONNECT"
    try:
        if SOCKS:
            if connect:
                host, _, port = target.rpartition(":")
                up_r, up_w = await socks_connect(host.strip("[]"), int(port or 443))
                client_w.write(b"HTTP/1.1 200 Connection established\r\n\r\n")
                await client_w.drain()
            else:
                u = urlsplit(target)
                up_r, up_w = await socks_connect(u.hostname, u.port or 80)
                path = (u.path or "/") + (f"?{u.query}" if u.query else "")
                up_w.write(("\r\n".join([f"{method} {path} {version}", *headers, "Connection: close"])
                            + "\r\n\r\n").encode("latin-1"))
                await up_w.drain()
        else:
            up_r, up_w = await asyncio.wait_for(asyncio.open_connection(UP_HOST, UP_PORT), 20)
            if AUTH:
                headers.append(f"Proxy-Authorization: {AUTH}")
            if not connect:
                headers += ["Connection: close", "Proxy-Connection: close"]
            up_w.write(("\r\n".join([lines[0], *headers]) + "\r\n\r\n").encode("latin-1"))
            await up_w.drain()
    except (asyncio.TimeoutError, asyncio.IncompleteReadError, OSError, UpstreamError, ValueError):
        client_w.write(b"HTTP/1.1 502 Bad Gateway\r\nContent-Length: 0\r\n\r\n")
        await client_w.drain()
        client_w.close()
        return
    await asyncio.gather(pipe(client_r, up_w), pipe(up_r, client_w))


async def main():
    server = await asyncio.start_server(handle, "127.0.0.1", int(sys.argv[1]))
    async with server:
        await server.serve_forever()


if __name__ == "__main__":
    asyncio.run(main())
