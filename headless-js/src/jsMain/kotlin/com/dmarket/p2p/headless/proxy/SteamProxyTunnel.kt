package com.dmarket.p2p.headless.proxy

import com.dmarket.p2p.headless.node.nodeNet
import com.dmarket.p2p.tracker.net.STEAM_PROXY_UNREACHABLE_CODE
import com.dmarket.p2p.tracker.net.SteamProxy
import kotlin.js.Promise

/**
 * Opens a raw TCP tunnel to `host:port` through [proxy]: a connection to the proxy, `CONNECT host:port`, and its
 * `200`. Resolves the Node socket, paused, past the proxy's answer and ready for TLS; rejects with an error coded
 * [STEAM_PROXY_UNREACHABLE_CODE] when the tunnel cannot be opened — no connection to the proxy, no answer within
 * [timeoutMs], or a `CONNECT` it refused. The only way either Steam transport reaches Steam while a proxy is set:
 * nothing here, or above it, ever falls back to a direct connection.
 *
 * The error's message is written here, from fixed words, a Node error code (`ECONNREFUSED`) and the status the proxy
 * answered: never the proxy's address or credentials, which Node's own messages (`connect ECONNREFUSED 10.0.0.1:3128`)
 * would carry.
 */
internal fun openSteamTunnel(proxy: SteamProxy, host: String, port: Int, timeoutMs: Int = TUNNEL_TIMEOUT_MS): Promise<dynamic> =
    tunnel(nodeNet, proxy.host.trim('[', ']'), proxy.port, proxy.authorization(), host, port, timeoutMs, STEAM_PROXY_UNREACHABLE_CODE)
        .unsafeCast<Promise<dynamic>>()

/** How long a tunnel may take to open, the proxy's answer included: under the prover's 15 s dial and the 30 s request. */
internal const val TUNNEL_TIMEOUT_MS: Int = 10_000

private val tunnel: dynamic = js(
    """
    (function (net, proxyHost, proxyPort, authorization, host, port, timeoutMs, code) {
      return new Promise((resolve, reject) => {
        const failure = (detail) => {
          const error = new Error(detail);
          error.code = code;
          return error;
        };
        if (typeof host !== 'string' || !/^[A-Za-z0-9.:\[\]-]+$/.test(host) || !(port > 0 && port < 65536)) {
          reject(failure('the tunnel target is not a host and a port'));
          return;
        }
        const target = (host.includes(':') && !host.startsWith('[') ? '[' + host + ']' : host) + ':' + port;
        const socket = net.connect({ host: proxyHost, port: proxyPort, noDelay: true, allowHalfOpen: true });
        let settled = false;
        let head = Buffer.alloc(0);
        const finish = () => {
          settled = true;
          clearTimeout(timer);
          socket.removeListener('error', onError);
          socket.removeListener('close', onClose);
          socket.removeListener('end', onClose);
          socket.removeListener('readable', onReadable);
        };
        const fail = (detail) => {
          if (settled) return;
          finish();
          // An error after this one has nobody to tell; without a listener Node would throw it.
          socket.on('error', () => {});
          socket.destroy();
          reject(failure(detail));
        };
        const timer = setTimeout(() => fail('no answer from the proxy within ' + timeoutMs + ' ms'), timeoutMs);
        const onError = (error) => {
          const name = error && typeof error.code === 'string' && /^[A-Z0-9_]{2,40}$/.test(error.code) ? error.code : null;
          fail(name ? 'could not connect to the proxy: ' + name : 'could not connect to the proxy');
        };
        const onClose = () => fail('the proxy closed the connection before the tunnel opened');
        const onReadable = () => {
          let chunk;
          while ((chunk = socket.read()) !== null) head = Buffer.concat([head, chunk]);
          const end = head.indexOf('\r\n\r\n');
          if (end < 0) {
            if (head.length > 16384) fail('the proxy answered CONNECT with an oversized head');
            return;
          }
          const match = /^HTTP\/1\.[01] (\d{3})/.exec(head.subarray(0, end).toString('latin1'));
          if (match === null) return fail('the proxy answered CONNECT with no HTTP status');
          const status = Number(match[1]);
          if (status !== 200) return fail('the proxy answered CONNECT with HTTP ' + status);
          // Before the client has spoken TLS nothing may come through the tunnel; bytes here are not Steam's.
          if (head.length > end + 4) return fail('the proxy sent data before the tunnel was used');
          finish();
          socket.pause();
          resolve(socket);
        };
        socket.on('error', onError);
        socket.on('close', onClose);
        // Half-open sockets see the proxy's FIN as 'end' and no 'close': a proxy that hangs up must not wait out the timer.
        socket.on('end', onClose);
        socket.once('connect', () => {
          const lines = ['CONNECT ' + target + ' HTTP/1.1', 'Host: ' + target];
          if (authorization) lines.push('Proxy-Authorization: ' + authorization);
          socket.write(lines.join('\r\n') + '\r\n\r\n');
          socket.on('readable', onReadable);
        });
      });
    })
    """,
)
