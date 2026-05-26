const http = require('http');
const { WebSocketServer } = require('ws');

const PORT = process.env.PORT || 8080;
const HOST = process.env.HOST || '0.0.0.0';

const devices = new Map();
const deviceNames = new Map();
const pendingStreams = new Map();

const server = http.createServer((req, res) => {
  res.setHeader('Access-Control-Allow-Origin', '*');
  res.setHeader('Access-Control-Allow-Methods', 'GET, POST, OPTIONS');
  res.setHeader('Access-Control-Allow-Headers', '*');
  if (req.method === 'OPTIONS') return res.writeHead(200).end();

  const url = new URL(req.url, `http://${req.headers.host}`);
  const parts = url.pathname.split('/');

  const serveJson = (data, code = 200) => {
    res.writeHead(code, { 'Content-Type': 'application/json' });
    res.end(JSON.stringify(data));
  };

  if (parts[1] === 'device' && parts[2]) {
    const deviceId = parts[2];
    const ws = devices.get(deviceId);
    if (!ws || ws.readyState !== ws.OPEN) {
      return serveJson({ error: 'Device offline' }, 503);
    }
    const targetPath = '/' + parts.slice(3).join('/') + url.search;
    const reqId = deviceId + '-' + Date.now() + '-' + Math.random().toString(36).slice(2, 6);

    let body = [];
    req.on('data', c => body.push(c));
    req.on('end', () => {
      const bodyBuf = Buffer.concat(body);
      pendingStreams.set(reqId, { res, bodySent: false, ended: false });
      ws.send(JSON.stringify({
        type: 'request', id: reqId, method: req.method,
        path: targetPath, headers: req.headers,
        body: bodyBuf.length > 0 ? bodyBuf.toString('base64') : ''
      }));
    });
    return;
  }

  if (url.pathname === '/devices') {
    const list = [];
    for (const [id, ws] of devices) {
      list.push({ id, name: deviceNames.get(id) || 'Unknown', connected: ws.readyState === ws.OPEN });
    }
    return serveJson(list);
  }

  if (url.pathname === '/') {
    res.writeHead(200, { 'Content-Type': 'text/html' });
    res.end(`<!DOCTYPE html><html><head><meta charset="utf-8"><title>Screen Mirror Relay</title>
<meta name="viewport" content="width=device-width,initial-scale=1">
<style>body{font-family:sans-serif;background:#1a1a2e;color:#eee;padding:20px}
a{color:#4fc3f7}.device{background:#16213e;padding:12px;border-radius:8px;margin:8px 0}
.device .url{color:#4caf50;font-family:monospace;font-size:13px}
h1{color:#4fc3f7;font-size:20px}</style></head><body>
<h1>Screen Mirror Relay</h1>
<p>Connected devices: <span id="count">0</span></p>
<div id="list"></div>
<script>
async function refresh(){const r=await fetch('/devices');const d=await r.json();
document.getElementById('count').textContent=d.length;
const list=document.getElementById('list');list.innerHTML='';
if(d.length===0){list.innerHTML='<p>No devices connected</p>';return}
d.forEach(dev=>{const div=document.createElement('div');div.className='device';
div.innerHTML='<strong>'+dev.name+'</strong><br><span class="url">https://'+location.host+'/device/'+dev.id+'</span>';
list.appendChild(div)})}
refresh();setInterval(refresh,5000)
</script></body></html>`);
    return;
  }

  res.writeHead(404).end('Not found');
});

const wss = new WebSocketServer({ server });

wss.on('connection', (ws, req) => {
  try {
    const url = new URL(req.url, `http://${req.headers.host}`);
    if (url.pathname !== '/agent') { ws.close(); return; }
  } catch (_) { ws.close(); return; }

  let deviceId = null;

  ws.on('message', raw => {
    let msg;
    try { msg = JSON.parse(raw.toString()); } catch (_) { return; }

    if (msg.type === 'register') {
      deviceId = msg.device_id || 'dev-' + Math.random().toString(36).slice(2, 8);
      deviceNames.set(deviceId, msg.name || 'Unknown');
      devices.set(deviceId, ws);
      const publicUrl = (req.headers['x-forwarded-proto'] || 'http') + '://' +
        req.headers.host + '/device/' + deviceId;
      ws.send(JSON.stringify({ type: 'assigned', device_id: deviceId, url: publicUrl }));
      console.log('Device registered:', deviceId, msg.name || 'Unknown');
      return;
    }

    if (msg.type === 'response_headers') {
      const s = pendingStreams.get(msg.id);
      if (!s || s.bodySent) return;
      s.bodySent = true;
      s.streaming = !!msg.streaming;
      if (msg.streaming) {
        try { s.res.writeHead(msg.status || 200, msg.headers || {}); s.res.flushHeaders(); } catch (e) {}
      } else {
        s.status = msg.status || 200;
        s.headers = msg.headers || {};
        s.bodyParts = [];
      }
      return;
    }

    if (msg.type === 'response_data') {
      const s = pendingStreams.get(msg.id);
      if (!s || !s.bodySent) return;
      const buf = Buffer.from(msg.data, 'base64');
      if (s.streaming) {
        try { s.res.write(buf); } catch (e) {}
        if (msg.last) { try { s.res.end(); } catch (e) {} pendingStreams.delete(msg.id); s.ended = true; }
      } else {
        s.bodyParts.push(buf);
        if (msg.last) {
          const fullBody = Buffer.concat(s.bodyParts);
          const headers = Object.assign({}, s.headers, { 'Content-Length': String(fullBody.length) });
          try { s.res.writeHead(s.status, headers); s.res.end(fullBody); } catch (e) {}
          pendingStreams.delete(msg.id);
          s.ended = true;
        }
      }
      return;
    }

    if (msg.type === 'response_done') {
      const s = pendingStreams.get(msg.id);
      if (s && s.bodySent) { try { s.res.end(); } catch (e) {} pendingStreams.delete(msg.id); s.ended = true; }
      return;
    }
  });

  ws.on('close', () => {
    if (deviceId) {
      devices.delete(deviceId);
      deviceNames.delete(deviceId);
      console.log('Device disconnected:', deviceId);
    }
    for (const [rid, s] of pendingStreams) {
      if (rid.startsWith(deviceId + '-') && !s.ended) {
        try { s.res.end(); } catch (_) {}
        s.ended = true;
        pendingStreams.delete(rid);
      }
    }
  });

  ws.on('error', () => {});
});

server.listen(PORT, HOST, () => {
  console.log(`Relay server running on ${HOST}:${PORT}`);
});
